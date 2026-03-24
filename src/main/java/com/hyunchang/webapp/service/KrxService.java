package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockSearchResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * KRX(한국거래소) Open API 연동 서비스.
 * - KOSPI/KOSDAQ 시총 순위 조회
 * - 국내 종목 한글명 조회 및 캐시
 * - 한글 검색어로 종목 검색
 */
@Service
public class KrxService {

    private static final Logger log = LoggerFactory.getLogger(KrxService.class);

    private static final String KRX_API_URL =
        "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";
    private static final DateTimeFormatter KRX_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L; // 6시간

    // ── KRX 실패 시 비상 폴백 목록 ────────────────────────────────
    public static final List<String[]> KR_STOCKS_FALLBACK = List.of(
        new String[]{"005930.KS", "삼성전자",        "0"},
        new String[]{"000660.KS", "SK하이닉스",      "0"},
        new String[]{"373220.KS", "LG에너지솔루션",  "0"},
        new String[]{"207940.KS", "삼성바이오로직스","0"},
        new String[]{"005380.KS", "현대자동차",      "0"},
        new String[]{"000270.KS", "기아",            "0"},
        new String[]{"005490.KS", "POSCO홀딩스",     "0"},
        new String[]{"006400.KS", "삼성SDI",         "0"},
        new String[]{"035420.KS", "NAVER",           "0"},
        new String[]{"105560.KS", "KB금융",          "0"}
    );

    // ── 히트맵 섹터 매핑 — 심볼 → 섹터명 ─────────────────────────
    public static final Map<String, String> KR_SECTOR_MAP = Map.ofEntries(
        Map.entry("005930.KS", "반도체/IT"),
        Map.entry("000660.KS", "반도체/IT"),
        Map.entry("006400.KS", "반도체/IT"),
        Map.entry("066570.KS", "반도체/IT"),
        Map.entry("009150.KS", "반도체/IT"),
        Map.entry("207940.KS", "바이오"),
        Map.entry("068270.KS", "바이오"),
        Map.entry("128940.KS", "바이오"),
        Map.entry("000100.KS", "바이오"),
        Map.entry("005380.KS", "자동차"),
        Map.entry("000270.KS", "자동차"),
        Map.entry("012330.KS", "자동차"),
        Map.entry("373220.KS", "에너지/소재"),
        Map.entry("005490.KS", "에너지/소재"),
        Map.entry("051910.KS", "에너지/소재"),
        Map.entry("096770.KS", "에너지/소재"),
        Map.entry("105560.KS", "금융"),
        Map.entry("055550.KS", "금융"),
        Map.entry("086790.KS", "금융"),
        Map.entry("000810.KS", "금융"),
        Map.entry("035420.KS", "인터넷/플랫폼"),
        Map.entry("035720.KS", "인터넷/플랫폼"),
        Map.entry("259960.KS", "인터넷/플랫폼"),
        Map.entry("017670.KS", "통신"),
        Map.entry("030200.KS", "통신"),
        Map.entry("028260.KS", "유통/소비"),
        Map.entry("004170.KS", "유통/소비"),
        Map.entry("139480.KS", "유통/소비"),
        Map.entry("015760.KS", "유틸리티")
    );

    // ── KRX 실패 시 히트맵 폴백 한글명 매핑 ──────────────────────
    public static final Map<String, String> KR_HEATMAP_NAME_FALLBACK = Map.ofEntries(
        Map.entry("005930.KS", "삼성전자"),
        Map.entry("000660.KS", "SK하이닉스"),
        Map.entry("006400.KS", "삼성SDI"),
        Map.entry("066570.KS", "LG전자"),
        Map.entry("009150.KS", "삼성전기"),
        Map.entry("207940.KS", "삼성바이오로직스"),
        Map.entry("068270.KS", "셀트리온"),
        Map.entry("128940.KS", "한미약품"),
        Map.entry("000100.KS", "유한양행"),
        Map.entry("005380.KS", "현대자동차"),
        Map.entry("000270.KS", "기아"),
        Map.entry("012330.KS", "현대모비스"),
        Map.entry("373220.KS", "LG에너지솔루션"),
        Map.entry("005490.KS", "POSCO홀딩스"),
        Map.entry("051910.KS", "LG화학"),
        Map.entry("096770.KS", "SK이노베이션"),
        Map.entry("105560.KS", "KB금융"),
        Map.entry("055550.KS", "신한지주"),
        Map.entry("086790.KS", "하나금융지주"),
        Map.entry("000810.KS", "삼성화재"),
        Map.entry("035420.KS", "NAVER"),
        Map.entry("035720.KS", "카카오"),
        Map.entry("259960.KS", "크래프톤"),
        Map.entry("017670.KS", "SK텔레콤"),
        Map.entry("030200.KS", "KT"),
        Map.entry("028260.KS", "삼성물산"),
        Map.entry("004170.KS", "신세계"),
        Map.entry("139480.KS", "이마트"),
        Map.entry("015760.KS", "한국전력")
    );

    // ── 캐시 ────────────────────────────────────────────────────
    private volatile List<String[]>       krxStocksCache    = null;
    private volatile long                 krxStocksCacheTime = 0;
    private volatile Map<String, String>  krNameLookup      = null;
    private volatile long                 krNameLookupTime  = 0;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public KrxService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper  = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 메서드
    // ─────────────────────────────────────────────────────────────

    /**
     * KOSPI(STK) 또는 KOSDAQ(KSQ) 시총 상위 N개 종목 조회.
     * 최근 7거래일을 순서대로 시도하며 데이터를 가져옵니다.
     */
    public List<String[]> getTopStocks(String mktId, int limit) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (int back = 0; back <= 7; back++) {
            LocalDate tryDate = today.minusDays(back);
            DayOfWeek dow = tryDate.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) continue;
            String trdDd = tryDate.format(KRX_DATE_FMT);
            List<String[]> result = tryFetch(mktId, trdDd, limit);
            if (!result.isEmpty()) {
                log.info("KRX {} 시총 순위 조회 성공 (기준일: {}), {}개 종목", mktId, trdDd, result.size());
                return result;
            }
        }
        log.warn("KRX {} 최근 7거래일 데이터 조회 실패", mktId);
        return Collections.emptyList();
    }

    /** KRX 종목 목록 캐시 조회 (검색·히트맵 공용, 6시간 TTL) */
    public List<String[]> getTopStocksCached(int count) {
        long now = System.currentTimeMillis();
        if (krxStocksCache == null || (now - krxStocksCacheTime) > CACHE_TTL_MS) {
            List<String[]> fresh = getTopStocks("STK", 100);
            if (!fresh.isEmpty()) {
                krxStocksCache     = fresh;
                krxStocksCacheTime = now;
                log.info("KRX 종목 캐시 갱신: {}개", fresh.size());
            }
        }
        if (krxStocksCache == null) return Collections.emptyList();
        return krxStocksCache.subList(0, Math.min(count, krxStocksCache.size()));
    }

    /** 한글 검색어로 KRX 종목 검색 */
    public List<StockSearchResultDto> searchKrLocal(String query) {
        String lower = query.toLowerCase();
        List<StockSearchResultDto> results = new ArrayList<>();
        for (String[] s : getTopStocksCached(100)) {
            if (s[1].contains(query) || s[0].toLowerCase().contains(lower)) {
                results.add(StockSearchResultDto.builder()
                    .symbol(s[0]).name(s[1]).exchange("KSC").type("EQUITY").market("KR").build());
            }
        }
        log.info("KRX 검색 [{}] → {}건", query, results.size());
        return results;
    }

    /** KR 심볼(.KS/.KQ)의 한글명 반환. 없으면 null. */
    public String resolveKrStockName(String symbol) {
        buildKrNameLookupIfNeeded();
        if (krNameLookup != null) {
            String name = krNameLookup.get(symbol.toUpperCase());
            if (name != null) return name;
        }
        return KR_HEATMAP_NAME_FALLBACK.get(symbol);
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 메서드
    // ─────────────────────────────────────────────────────────────

    private void buildKrNameLookupIfNeeded() {
        if (krNameLookup != null
                && (System.currentTimeMillis() - krNameLookupTime) < CACHE_TTL_MS) return;
        buildKrNameLookup();
    }

    private synchronized void buildKrNameLookup() {
        if (krNameLookup != null
                && (System.currentTimeMillis() - krNameLookupTime) < CACHE_TTL_MS) return;
        Map<String, String> map = new HashMap<>();
        List<String[]> kospi  = getTopStocks("STK", 1000);
        List<String[]> kosdaq = getTopStocks("KSQ", 1500);
        kospi.forEach(s  -> map.put(s[0].toUpperCase(), s[1]));
        kosdaq.forEach(s -> map.put(s[0].toUpperCase(), s[1]));
        KR_HEATMAP_NAME_FALLBACK.forEach((k, v) -> map.putIfAbsent(k.toUpperCase(), v));
        if (!map.isEmpty()) {
            krNameLookup     = map;
            krNameLookupTime = System.currentTimeMillis();
            log.info("KR 종목명 룩업 맵 빌드 완료: KOSPI {}개, KOSDAQ {}개, 총 {}개",
                kospi.size(), kosdaq.size(), map.size());
        }
    }

    private List<String[]> tryFetch(String mktId, String trdDd, int limit) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Referer",
                "http://data.krx.co.kr/contents/MDC/STAT/standard/MDCSTAT01501.cmd");
            headers.set("Accept", "application/json, text/plain, */*");

            String body = "bld=dbms%2FMDC%2FSTAT%2Fstandard%2FMDCSTAT01501"
                + "&locale=ko_KR&mktId=" + mktId + "&trdDd=" + trdDd
                + "&share=1&money=1&csvxls_isNo=false";

            ResponseEntity<String> resp = restTemplate.exchange(
                KRX_API_URL, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

            JsonNode block = objectMapper.readTree(resp.getBody()).path("OutBlock_1");
            if (!block.isArray() || block.size() == 0) return Collections.emptyList();

            List<String[]> result = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, block.size()); i++) {
                JsonNode item  = block.get(i);
                String code    = item.path("ISU_SRT_CD").asText("").trim();
                String name    = item.path("ISU_ABBRV").asText("").trim();
                String shares  = item.path("LIST_SHRS").asText("0").replace(",", "");
                if (code.isEmpty() || name.isEmpty()) continue;
                String yfSymbol = code + ("STK".equals(mktId) ? ".KS" : ".KQ");
                result.add(new String[]{yfSymbol, name, shares});
            }
            return result;

        } catch (Exception e) {
            log.warn("KRX API 조회 실패 [mktId={}, trdDd={}]: {}", mktId, trdDd, e.getMessage());
            return Collections.emptyList();
        }
    }
}
