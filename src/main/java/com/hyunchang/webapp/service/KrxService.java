package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockSearchResultDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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

    // ── JSON 리소스에서 로드되는 폴백/설정 데이터 ──────────────────
    private List<String[]>       krStocksFallback       = Collections.emptyList();
    private List<String[]>       kqStocksFallback       = Collections.emptyList();
    private Map<String, String>  krSectorMap            = Collections.emptyMap();
    private Map<String, String>  krHeatmapNameFallback  = Collections.emptyMap();

    // ── 캐시 ────────────────────────────────────────────────────
    private volatile List<String[]>       krxStocksCache    = null;  // KOSPI (검색·히트맵용, 6h TTL)
    private volatile long                 krxStocksCacheTime = 0;
    private volatile Map<String, String>  krNameLookup      = null;
    private volatile long                 krNameLookupTime  = 0;

    private final NaverFinanceService naverService;
    private final KrxOpenApiService   krxApiService;
    private final ObjectMapper        objectMapper;

    public KrxService(NaverFinanceService naverService,
                      KrxOpenApiService krxApiService,
                      ObjectMapper objectMapper) {
        this.naverService  = naverService;
        this.krxApiService = krxApiService;
        this.objectMapper  = objectMapper;
    }

    @PostConstruct
    private void loadConfig() {
        krStocksFallback = loadFallbackList("stock/kr-stocks-fallback.json");
        log.info("kr-stocks-fallback.json 로드 완료: {}개 종목", krStocksFallback.size());

        kqStocksFallback = loadFallbackList("stock/kq-stocks-fallback.json");
        log.info("kq-stocks-fallback.json 로드 완료: {}개 종목", kqStocksFallback.size());

        krSectorMap = loadStringMap("stock/kr-sector-map.json");
        log.info("kr-sector-map.json 로드 완료: {}개 항목", krSectorMap.size());

        krHeatmapNameFallback = loadStringMap("stock/kr-heatmap-names.json");
        log.info("kr-heatmap-names.json 로드 완료: {}개 항목", krHeatmapNameFallback.size());
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 데이터 접근자
    // ─────────────────────────────────────────────────────────────

    public List<String[]> getKrStocksFallback() { return krStocksFallback; }

    public List<String[]> getKqStocksFallback() { return kqStocksFallback; }

    public Map<String, String> getKrSectorMap() { return krSectorMap; }

    public Map<String, String> getKrHeatmapNameFallback() { return krHeatmapNameFallback; }

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
        String fallback = krHeatmapNameFallback.get(symbol);
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
        // 네이버/KRX 모두 실패 시 최종 폴백 (JSON에서 로드된 항목)
        krHeatmapNameFallback.forEach((k, v) -> map.putIfAbsent(k.toUpperCase(), v));
        krNameLookup     = map;
        krNameLookupTime = System.currentTimeMillis();
        log.info("KR 종목명 룩업 맵 빌드 완료 (KRX+Naver+폴백): 총 {}개", map.size());
    }

    private List<String[]> loadFallbackList(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("리소스 파일 없음: {}", resourcePath);
                return Collections.emptyList();
            }
            List<Map<String, String>> list = objectMapper.readValue(is,
                new TypeReference<>() {});
            return list.stream()
                .map(m -> new String[]{
                    m.get("symbol"),
                    m.getOrDefault("name", m.get("symbol")),
                    m.getOrDefault("shares", "0")
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("JSON 로드 실패 [{}]: {}", resourcePath, e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, String> loadStringMap(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("리소스 파일 없음: {}", resourcePath);
                return Collections.emptyMap();
            }
            Map<String, String> map = objectMapper.readValue(is, new TypeReference<>() {});
            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            log.error("JSON 로드 실패 [{}]: {}", resourcePath, e.getMessage());
            return Collections.emptyMap();
        }
    }

}
