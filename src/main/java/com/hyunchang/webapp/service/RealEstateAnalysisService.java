package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.*;
import com.hyunchang.webapp.service.ai.AiProviderChain;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
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
    private final AiPromptService aiPromptService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RealEstateAnalysisService(RealEstateService realEstateService,
                                     RealEstateNewsService realEstateNewsService,
                                     AiProviderChain aiProviderChain,
                                     AiPromptService aiPromptService) {
        this.realEstateService = realEstateService;
        this.realEstateNewsService = realEstateNewsService;
        this.aiProviderChain = aiProviderChain;
        this.aiPromptService = aiPromptService;
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

    // ── 토지(LAND) AI 시황 ─────────────────────────────────────────

    public LandAnalysisResponse analyzeLand(String lawdCd) {
        RegionDto region = realEstateService.getRegion(lawdCd);
        String sigungu = region != null ? region.getSigungu() : lawdCd;
        String regionName = region != null ? region.getName() : lawdCd;

        List<LandDealDto> deals = safe(
            () -> realEstateService.searchLand(lawdCd, ANALYSIS_MONTHS), List.of());

        if (deals.isEmpty()) {
            return LandAnalysisResponse.builder()
                .noData(true)
                .stats(LandStatsDto.builder()
                    .sigungu(sigungu).totalCount(0)
                    .jimokBuckets(List.of()).zoneBuckets(List.of()).build())
                .build();
        }

        LandStatsDto stats = computeLandStats(deals, sigungu);

        List<RealEstateNewsDto> news = safe(() -> realEstateNewsService.getNews(false), List.of());
        List<RealEstateNewsDto> topNews = news.stream().limit(5).collect(Collectors.toList());

        String prompt = buildLandPrompt(regionName, stats, deals, topNews);
        AiProviderChain.ChainResult chain = aiProviderChain.analyze(prompt);

        if (!chain.success()) {
            return LandAnalysisResponse.builder()
                .blocked(true).stats(stats)
                .retryAt(chain.retryAt()).providersStatus(chain.providersStatus()).build();
        }

        return LandAnalysisResponse.builder()
            .blocked(false).providerName(chain.providerName()).model(chain.model())
            .analyzedAt(Instant.now()).stats(stats)
            .result(parseResult(chain.text())).sources(topNews)
            .providersStatus(chain.providersStatus()).build();
    }

    private LandStatsDto computeLandStats(List<LandDealDto> deals, String sigungu) {
        long[] units = deals.stream().mapToLong(LandDealDto::getPricePerM2).filter(v -> v > 0).toArray();
        long avg = units.length == 0 ? 0 : Math.round(Arrays.stream(units).average().orElse(0));
        long min = units.length == 0 ? 0 : Arrays.stream(units).min().orElse(0);
        long max = units.length == 0 ? 0 : Arrays.stream(units).max().orElse(0);

        // 추세: 거래일 오름차순 정렬 후 전반부/후반부 평균 단가 비교
        List<LandDealDto> asc = deals.stream()
            .filter(d -> d.getPricePerM2() > 0)
            .sorted(Comparator.comparing(LandDealDto::getDealDate))
            .collect(Collectors.toList());
        double trendPct = 0;
        if (asc.size() >= 4) {
            int mid = asc.size() / 2;
            double prior = unitAvg(asc.subList(0, mid));
            double recent = unitAvg(asc.subList(mid, asc.size()));
            if (prior > 0) trendPct = (recent - prior) / prior * 100.0;
        }
        trendPct = Math.round(trendPct * 10.0) / 10.0;
        String trendLabel = trendPct > 1.5 ? "상승" : trendPct < -1.5 ? "하락" : "보합";

        return LandStatsDto.builder()
            .sigungu(sigungu).totalCount(deals.size())
            .avgPricePerM2(avg).minPricePerM2(min).maxPricePerM2(max)
            .trendPct(trendPct).trendLabel(trendLabel)
            .jimokBuckets(bucketBy(deals, LandDealDto::getJimok))
            .zoneBuckets(bucketBy(deals, LandDealDto::getUseZone))
            .build();
    }

    private double unitAvg(List<LandDealDto> ds) {
        return ds.stream().mapToLong(LandDealDto::getPricePerM2).filter(v -> v > 0).average().orElse(0);
    }

    /** 지목/용도지역별 평균 단가 집계 (단가 높은 순). */
    private List<LandStatsDto.Bucket> bucketBy(List<LandDealDto> deals, Function<LandDealDto, String> key) {
        Map<String, List<LandDealDto>> grouped = deals.stream()
            .filter(d -> d.getPricePerM2() > 0 && key.apply(d) != null && !key.apply(d).isBlank())
            .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
            .map(e -> LandStatsDto.Bucket.builder()
                .label(e.getKey()).count(e.getValue().size())
                .avgPricePerM2(Math.round(unitAvg(e.getValue()))).build())
            .sorted(Comparator.comparingLong(LandStatsDto.Bucket::getAvgPricePerM2).reversed())
            .collect(Collectors.toList());
    }

    private String buildLandPrompt(String regionName, LandStatsDto stats,
                                   List<LandDealDto> deals, List<RealEstateNewsDto> news) {
        StringBuilder jimokBlock = new StringBuilder();
        for (LandStatsDto.Bucket b : stats.getJimokBuckets()) {
            jimokBlock.append("· ").append(b.getLabel()).append(": ")
                .append(b.getCount()).append("건, 평균 ").append(unit(b.getAvgPricePerM2())).append("\n");
        }
        StringBuilder zoneBlock = new StringBuilder();
        for (LandStatsDto.Bucket b : stats.getZoneBuckets()) {
            zoneBlock.append("· ").append(b.getLabel()).append(": ")
                .append(b.getCount()).append("건, 평균 ").append(unit(b.getAvgPricePerM2())).append("\n");
        }
        StringBuilder dealBlock = new StringBuilder();
        int i = 1;
        for (LandDealDto d : deals.stream().limit(10).collect(Collectors.toList())) {
            dealBlock.append(i++).append(". ")
                .append(nullSafe(d.getDong())).append(" ")
                .append(nullSafe(d.getJimok())).append(" / ").append(nullSafe(d.getUseZone()))
                .append(" — ").append(d.getAreaM2()).append("㎡, ").append(money(d.getDealAmount()))
                .append(" (단가 ").append(unit(d.getPricePerM2())).append(", ")
                .append(d.getDealDate()).append(")\n");
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
            newsInstruction = "아래 뉴스는 일반 부동산 시황 참고용입니다. 이 지역 토지와 직접 관련될 때만 신중히 인용하세요.";
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("지역", nullSafe(regionName));
        vars.put("분석개월수", String.valueOf(ANALYSIS_MONTHS));
        vars.put("거래건수", String.valueOf(stats.getTotalCount()));
        vars.put("평균단가", unit(stats.getAvgPricePerM2()));
        vars.put("최저단가", unit(stats.getMinPricePerM2()));
        vars.put("최고단가", unit(stats.getMaxPricePerM2()));
        vars.put("추세", stats.getTrendLabel());
        vars.put("추세율", String.format(Locale.US, "%.1f", stats.getTrendPct()));
        vars.put("용도지역별단가", zoneBlock.toString().strip());
        vars.put("지목별단가", jimokBlock.toString().strip());
        vars.put("대표거래", dealBlock.toString().strip());
        vars.put("뉴스지침", newsInstruction);
        vars.put("뉴스목록", newsBlock.toString().strip());
        return aiPromptService.render(AiPromptCatalog.REALESTATE_LAND, vars);
    }

    /** 단가(원/㎡) → "1,234,000원/㎡ (약 41만원/평)". */
    private String unit(long perM2) {
        if (perM2 <= 0) return "-";
        long perPyeong = Math.round(perM2 * 3.3058 / 10000.0);
        return String.format(Locale.KOREA, "%,d원/㎡ (약 %,d만원/평)", perM2, perPyeong);
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

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("지역", nullSafe(regionName));
        vars.put("거래유형", dealLabel);
        vars.put("분석개월수", String.valueOf(ANALYSIS_MONTHS));
        vars.put("거래건수", String.valueOf(stats.getTotalCount()));
        vars.put("평균가", money(stats.getAvgPrice()));
        vars.put("최저가", money(stats.getMinPrice()));
        vars.put("최고가", money(stats.getMaxPrice()));
        vars.put("추세", stats.getTrendLabel());
        vars.put("추세율", String.format(Locale.US, "%.1f", stats.getTrendPct()));
        vars.put("평형별시세", bucketBlock.toString().strip());
        vars.put("가격항목", priceLabel);
        vars.put("대표거래", dealBlock.toString().strip());
        vars.put("뉴스지침", newsInstruction);
        vars.put("뉴스목록", newsBlock.toString().strip());
        return aiPromptService.render(AiPromptCatalog.REALESTATE_TRADE, vars);
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
