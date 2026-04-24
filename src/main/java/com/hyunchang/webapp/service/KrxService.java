package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.StockSearchResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * KRX(한국거래소) Open API 연동 서비스.
 * - KOSPI/KOSDAQ 시총 순위 조회
 * - 국내 종목 한글명 조회 및 캐시
 * - 한글 검색어로 종목 검색
 */
@Service
public class KrxService {

    private static final Logger log = LoggerFactory.getLogger(KrxService.class);

    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L; // 6시간

    // ── KRX 실패 시 비상 폴백 목록 ────────────────────────────────
    public static final List<String[]> KQ_STOCKS_FALLBACK = List.of(
        new String[]{"196170.KQ", "알테오젠",        "0"},
        new String[]{"028300.KQ", "HLB",             "0"},
        new String[]{"247540.KQ", "에코프로비엠",    "0"},
        new String[]{"086520.KQ", "에코프로",        "0"},
        new String[]{"141080.KQ", "리가켐바이오",    "0"},
        new String[]{"214150.KQ", "클래시스",        "0"},
        new String[]{"357780.KQ", "솔브레인",        "0"},
        new String[]{"277810.KQ", "레인보우로보틱스","0"},
        new String[]{"000250.KQ", "삼천당제약",      "0"},
        new String[]{"214450.KQ", "파마리서치",      "0"}
    );

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
    private volatile List<String[]>       krxStocksCache    = null;  // KOSPI (검색·히트맵용, 6h TTL)
    private volatile long                 krxStocksCacheTime = 0;
    private volatile Map<String, String>  krNameLookup      = null;
    private volatile long                 krNameLookupTime  = 0;

    private final NaverFinanceService naverService;
    private final KrxOpenApiService   krxApiService;

    public KrxService(NaverFinanceService naverService, KrxOpenApiService krxApiService) {
        this.naverService  = naverService;
        this.krxApiService = krxApiService;
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 메서드
    // ─────────────────────────────────────────────────────────────

    /**
     * KOSPI 종목 목록 캐시 조회 (검색·히트맵 공용, 6시간 TTL).
     * 네이버 금융에서 실시간으로 가져오며, 실패 시 이전 캐시 → 빈 리스트.
     */
    public List<String[]> getTopStocksCached(int count) {
        long now = System.currentTimeMillis();
        if (krxStocksCache == null || (now - krxStocksCacheTime) > CACHE_TTL_MS) {
            List<String[]> fresh = naverService.getTopStocksKospiCached(50).stream()
                .map(s -> new String[]{s.symbol(), s.name(), "0"})
                .collect(Collectors.toList());
            if (!fresh.isEmpty()) {
                krxStocksCache     = fresh;
                krxStocksCacheTime = now;
                log.info("종목 캐시 갱신 (Naver 출처): {}개", fresh.size());
            }
        }
        if (krxStocksCache == null) return Collections.emptyList();
        return krxStocksCache.subList(0, Math.min(count, krxStocksCache.size()));
    }

    /**
     * 한글 검색어로 KRX 종목 검색.
     * krNameLookup (KRX OpenAPI 전체 + 네이버 상위 100 + 폴백)을 검색 풀로 사용합니다.
     */
    public List<StockSearchResultDto> searchKrLocal(String query) {
        buildKrNameLookupIfNeeded();
        if (krNameLookup == null || krNameLookup.isEmpty()) return Collections.emptyList();

        String lower = query.toLowerCase();
        List<StockSearchResultDto> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : krNameLookup.entrySet()) {
            String sym  = entry.getKey();   // e.g., "005930.KS"
            String name = entry.getValue(); // e.g., "삼성전자"
            if (name.contains(query) || sym.toLowerCase().contains(lower)) {
                String exchange = sym.endsWith(".KQ") ? "KOE" : "KSC";
                results.add(StockSearchResultDto.builder()
                    .symbol(sym).name(name).exchange(exchange).type("EQUITY").market("KR").build());
            }
        }
        log.info("KRX 검색 [{}] → {}건", query, results.size());
        return results;
    }

    /**
     * KR 심볼(.KS/.KQ)의 한글명 반환. 없으면 null.
     * 1) KRX 공식 OpenAPI 전체 사전(ISU_ABBRV, 2500+개)
     * 2) 네이버 기반 상위 100개 + 하드코딩 폴백 룩업
     * 3) 히트맵용 하드코딩 폴백
     * 4) 네이버 금융 개별 종목 페이지 스크래핑 (우선주·소형주 등 최후 폴백)
     */
    public String resolveKrStockName(String symbol) {
        if (symbol == null) return null;

        String krxName = krxApiService.getKrName(symbol);
        if (krxName != null) return krxName;

        buildKrNameLookupIfNeeded();
        if (krNameLookup != null) {
            String name = krNameLookup.get(symbol.toUpperCase());
            if (name != null) return name;
        }
        String fallback = KR_HEATMAP_NAME_FALLBACK.get(symbol);
        if (fallback != null) return fallback;

        return naverService.resolveStockNameByCode(symbol);
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
        // KRX 공식 OpenAPI 전체 KOSPI/KOSDAQ 종목명 (2500+개, 최우선)
        krxApiService.getTopKospiStocks(Integer.MAX_VALUE)
            .forEach(s -> map.put(s.symbol().toUpperCase(), s.name()));
        krxApiService.getTopKosdaqStocks(Integer.MAX_VALUE)
            .forEach(s -> map.put(s.symbol().toUpperCase(), s.name()));
        // 네이버 금융에서 KOSPI/KOSDAQ 상위 50개씩 (KRX API 미동작 시 폴백 경로)
        naverService.getTopStocksKospiCached(50)
            .forEach(s -> map.putIfAbsent(s.symbol().toUpperCase(), s.name()));
        naverService.getTopStocksKosdaqCached(50)
            .forEach(s -> map.putIfAbsent(s.symbol().toUpperCase(), s.name()));
        // 네이버/KRX 모두 실패 시 최종 폴백 (29개 하드코딩)
        KR_HEATMAP_NAME_FALLBACK.forEach((k, v) -> map.putIfAbsent(k.toUpperCase(), v));
        krNameLookup     = map;
        krNameLookupTime = System.currentTimeMillis();
        log.info("KR 종목명 룩업 맵 빌드 완료 (KRX+Naver+폴백): 총 {}개", map.size());
    }

}
