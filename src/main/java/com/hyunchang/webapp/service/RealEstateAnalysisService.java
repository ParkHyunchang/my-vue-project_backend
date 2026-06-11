package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.*;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 지역 시황 분석 — 검색(매수 검토) 시점에 도움을 주는 용도.
 *
 * 흐름:
 *   1. 검색한 시군구+거래유형의 최근 실거래 수집 → 결정적 통계 집계(평균/최저/최고/추세/평형별)
 *   2. 통계 + 대표 거래 + 부동산 뉴스로 프롬프트 조립
 *   3. AiProviderChain (Gemini → Groq → Cloudflare) 호출
 *   4. JSON 파싱 → 응답 조립 (AI 실패해도 통계는 항상 반환)
 */
@Service
public class RealEstateAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RealEstateAnalysisService.class);

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
    private static final Pattern FIRST_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final int ANALYSIS_MONTHS = 6;

    private final RealEstateService realEstateService;
    private final RealEstateNewsService realEstateNewsService;
    private final AiProviderChain aiProviderChain;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RealEstateAnalysisService(RealEstateService realEstateService,
                                     RealEstateNewsService realEstateNewsService,
                                     AiProviderChain aiProviderChain) {
        this.realEstateService = realEstateService;
        this.realEstateNewsService = realEstateNewsService;
        this.aiProviderChain = aiProviderChain;
    }

    public RealEstateAnalysisResponse analyze(String lawdCd, String dealType) {
        String dt = (dealType == null || dealType.isBlank()) ? "SALE" : dealType.toUpperCase(Locale.ROOT);
        RegionDto region = realEstateService.getRegion(lawdCd);
        String sigungu = region != null ? region.getSigungu() : lawdCd;
        String regionName = region != null ? region.getName() : lawdCd;

        List<RealEstateDealDto> deals = safe(
            () -> realEstateService.search(lawdCd, dt, ANALYSIS_MONTHS), List.of());

        if (deals.isEmpty()) {
            return RealEstateAnalysisResponse.builder()
                .noData(true)
                .stats(RealEstateStatsDto.builder()
                    .sigungu(sigungu).dealType(dt).totalCount(0)
                    .areaBuckets(List.of()).build())
                .build();
        }

        RealEstateStatsDto stats = computeStats(deals, dt, sigungu);

        List<RealEstateNewsDto> news = safe(() -> realEstateNewsService.getNews(false), List.of());
        List<RealEstateNewsDto> topNews = news.stream().limit(5).collect(Collectors.toList());

        String prompt = buildPrompt(regionName, dt, stats, deals, topNews);
        AiProviderChain.ChainResult chain = aiProviderChain.analyze(prompt);

        if (!chain.success()) {
            return RealEstateAnalysisResponse.builder()
                .blocked(true)
                .stats(stats)
                .retryAt(chain.retryAt())
                .providersStatus(chain.providersStatus())
                .build();
        }

        return RealEstateAnalysisResponse.builder()
            .blocked(false)
            .providerName(chain.providerName())
            .model(chain.model())
            .analyzedAt(Instant.now())
            .stats(stats)
            .result(parseResult(chain.text()))
            .sources(topNews)
            .providersStatus(chain.providersStatus())
            .build();
    }

    // ── 통계 집계 ──────────────────────────────────────────────────

    private RealEstateStatsDto computeStats(List<RealEstateDealDto> deals, String dealType, String sigungu) {
        boolean isSale = "SALE".equalsIgnoreCase(dealType);
        long[] prices = deals.stream()
            .mapToLong(d -> isSale ? d.getDealAmount() : d.getDeposit())
            .filter(v -> v > 0)
            .toArray();

        long avg = prices.length == 0 ? 0
            : Math.round(Arrays.stream(prices).average().orElse(0));
        long min = prices.length == 0 ? 0 : Arrays.stream(prices).min().orElse(0);
        long max = prices.length == 0 ? 0 : Arrays.stream(prices).max().orElse(0);

        long avgMonthly = 0;
        if ("MONTHLY".equalsIgnoreCase(dealType)) {
            avgMonthly = Math.round(deals.stream()
                .mapToLong(RealEstateDealDto::getMonthlyRent)
                .filter(v -> v > 0).average().orElse(0));
        }

        // 추세: 거래일 오름차순 정렬 후 전반부/후반부 평균 비교
        List<RealEstateDealDto> asc = deals.stream()
            .sorted(Comparator.comparing(RealEstateDealDto::getDealDate))
            .collect(Collectors.toList());
        double trendPct = 0;
        if (asc.size() >= 4) {
            int mid = asc.size() / 2;
            double priorAvg = avgOf(asc.subList(0, mid), isSale);
            double recentAvg = avgOf(asc.subList(mid, asc.size()), isSale);
            if (priorAvg > 0) trendPct = (recentAvg - priorAvg) / priorAvg * 100.0;
        }
        trendPct = Math.round(trendPct * 10.0) / 10.0;
        String trendLabel = trendPct > 1.5 ? "상승" : trendPct < -1.5 ? "하락" : "보합";

        // 평형별 (전용면적 10㎡ 구간) 집계
        Map<Integer, List<RealEstateDealDto>> byBucket = deals.stream()
            .filter(d -> d.getAreaM2() > 0)
            .collect(Collectors.groupingBy(d -> ((int) (d.getAreaM2() / 10)) * 10, TreeMap::new, Collectors.toList()));
        List<RealEstateStatsDto.AreaBucket> buckets = byBucket.entrySet().stream()
            .map(e -> RealEstateStatsDto.AreaBucket.builder()
                .label(e.getKey() + "~" + (e.getKey() + 10) + "㎡")
                .count(e.getValue().size())
                .avgPrice(Math.round(avgOf(e.getValue(), isSale)))
                .build())
            .collect(Collectors.toList());

        return RealEstateStatsDto.builder()
            .sigungu(sigungu).dealType(dealType).totalCount(deals.size())
            .avgPrice(avg).minPrice(min).maxPrice(max)
            .avgMonthlyRent(avgMonthly)
            .trendPct(trendPct).trendLabel(trendLabel)
            .areaBuckets(buckets)
            .build();
    }

    private double avgOf(List<RealEstateDealDto> deals, boolean isSale) {
        return deals.stream()
            .mapToLong(d -> isSale ? d.getDealAmount() : d.getDeposit())
            .filter(v -> v > 0).average().orElse(0);
    }

    // ── 프롬프트 ──────────────────────────────────────────────────

    private String buildPrompt(String regionName, String dealType, RealEstateStatsDto stats,
                               List<RealEstateDealDto> deals, List<RealEstateNewsDto> news) {
        String dealLabel = switch (dealType) {
            case "JEONSE" -> "전세";
            case "MONTHLY" -> "월세";
            default -> "매매";
        };
        String priceLabel = "매매".equals(dealLabel) ? "거래금액" : "보증금";

        StringBuilder bucketBlock = new StringBuilder();
        for (RealEstateStatsDto.AreaBucket b : stats.getAreaBuckets()) {
            bucketBlock.append("· ").append(b.getLabel())
                .append(": ").append(b.getCount()).append("건, 평균 ")
                .append(money(b.getAvgPrice())).append("\n");
        }

        StringBuilder dealBlock = new StringBuilder();
        int i = 1;
        for (RealEstateDealDto d : deals.stream().limit(10).collect(Collectors.toList())) {
            long amt = "매매".equals(dealLabel) ? d.getDealAmount() : d.getDeposit();
            dealBlock.append(i++).append(". ")
                .append(d.getAptName()).append(" ")
                .append(d.getAreaM2()).append("㎡ ")
                .append(d.getFloor()).append("층 — ")
                .append(money(amt));
            if ("월세".equals(dealLabel) && d.getMonthlyRent() > 0) {
                dealBlock.append(" / 월 ").append(d.getMonthlyRent()).append("만원");
            }
            dealBlock.append(" (").append(d.getDealDate()).append(")\n");
        }

        StringBuilder newsBlock = new StringBuilder();
        String newsInstruction;
        if (news.isEmpty()) {
            newsBlock.append("(수집된 부동산 뉴스 없음)");
            newsInstruction = "관련 뉴스가 없으니 거래 데이터만 근거로 분석하세요.";
        } else {
            int n = 1;
            for (RealEstateNewsDto a : news) {
                newsBlock.append(n++).append(". ").append(nullSafe(a.getTitle())).append("\n");
            }
            newsInstruction = "아래 뉴스는 일반 부동산 시황 참고용입니다. 이 지역과 직접 관련될 때만 신중히 인용하세요.";
        }

        return """
            당신은 한국 부동산 시장 분석가입니다. 아래 실거래 데이터와 뉴스만 근거로,
            이 지역을 매수/계약 검토하는 사람에게 도움이 되도록 간결하고 정확하게 분석하세요.
            추측이나 일반론은 쓰지 마세요. 투자 권유가 아닌 정보 정리 톤으로 작성하세요.
            응답은 반드시 아래 스키마의 JSON 객체 하나로만 출력하세요. 코드블록·해설을 포함하지 마세요.

            ── 분석 대상 ──
            지역: %s
            거래유형: %s
            최근 %d개월 집계: 총 %d건, 평균 %s, 최저 %s, 최고 %s
            가격 추세: %s (%.1f%%, 최근 절반 vs 이전 절반)

            ── 평형(전용면적)별 시세 ──
            %s
            ── 최근 대표 거래 (%s) ──
            %s
            ── 뉴스 사용 지침 ──
            %s
            ── 부동산 뉴스 ──
            %s
            ── 응답 스키마 ──
            {
              "trend": "상승" | "보합" | "하락",
              "headline": "한 줄 핵심 요약 (60자 이내, 한국어)",
              "priceLevel": "주력 평형 기준 현재 시세대 요약 (예: 전용 84㎡ 24~26억대)",
              "keywords": ["키워드1", "키워드2", "키워드3"],
              "watchPoints": ["매수 검토 시 주의점1", "주의점2"],
              "comment": "2~3문장 종합 코멘트"
            }
            """.formatted(
                nullSafe(regionName), dealLabel, ANALYSIS_MONTHS,
                stats.getTotalCount(), money(stats.getAvgPrice()),
                money(stats.getMinPrice()), money(stats.getMaxPrice()),
                stats.getTrendLabel(), stats.getTrendPct(),
                bucketBlock.toString().strip(),
                priceLabel,
                dealBlock.toString().strip(),
                newsInstruction,
                newsBlock.toString().strip());
    }

    // ── JSON 파싱 ──────────────────────────────────────────────────

    private RealEstateAnalysisResult parseResult(String text) {
        String json = extractJson(text);
        try {
            JsonNode root = objectMapper.readTree(json);
            return RealEstateAnalysisResult.builder()
                .trend(text(root, "trend", "보합"))
                .headline(text(root, "headline", ""))
                .priceLevel(text(root, "priceLevel", ""))
                .keywords(stringList(root.path("keywords")))
                .watchPoints(stringList(root.path("watchPoints")))
                .comment(text(root, "comment", ""))
                .build();
        } catch (Exception e) {
            log.warn("[AI/RealEstate] JSON 파싱 실패: {} (head: {})", e.getMessage(), truncate(text, 200));
            return RealEstateAnalysisResult.builder()
                .trend("보합").headline("").priceLevel("")
                .keywords(List.of()).watchPoints(List.of())
                .comment(truncate(text, 500))
                .build();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        String trimmed = text.trim();
        Matcher m = JSON_FENCE.matcher(trimmed);
        if (m.find()) return m.group(1);
        Matcher m2 = FIRST_OBJECT.matcher(trimmed);
        if (m2.find()) return m2.group();
        return trimmed;
    }

    // ── 유틸 ──────────────────────────────────────────────────────

    /** 만원 단위 → "23억 4,000만" 형태. */
    private String money(long manwon) {
        if (manwon <= 0) return "-";
        long eok = manwon / 10000;
        long rest = manwon % 10000;
        StringBuilder sb = new StringBuilder();
        if (eok > 0) sb.append(eok).append("억");
        if (rest > 0) sb.append(eok > 0 ? " " : "").append(String.format(Locale.KOREA, "%,d", rest)).append("만");
        return sb.length() == 0 ? manwon + "만" : sb.toString();
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? fallback : v.asText(fallback);
    }

    private List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        try {
            return objectMapper.convertValue(arr, new TypeReference<>() {});
        } catch (Exception e) {
            List<String> out = new ArrayList<>();
            arr.forEach(n -> out.add(n.asText()));
            return Collections.unmodifiableList(out);
        }
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private <T> T safe(java.util.function.Supplier<T> sup, T fallback) {
        try { return sup.get(); } catch (Exception e) {
            log.warn("[AI/RealEstate] 컨텍스트 수집 실패: {}", e.getMessage());
            return fallback;
        }
    }
}
