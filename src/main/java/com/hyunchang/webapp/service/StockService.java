package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockHeatmapItemDto;
import com.hyunchang.webapp.dto.StockHeatmapResponseDto;
import com.hyunchang.webapp.dto.StockHeatmapSectorDto;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.dto.StockQuotaDto;
import com.hyunchang.webapp.dto.StockQuoteDto;
import com.hyunchang.webapp.dto.StockSearchResultDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 주식 기능 오케스트레이터.
 * 실제 외부 API 호출은 KrxService / YahooFinanceService / StockNewsService 에 위임합니다.
 * 이 클래스는 캐싱, 순위 계산, 스케줄링, 쿼터 관리에만 집중합니다.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private static final long CACHE_TTL_MS     = 6 * 60 * 60 * 1000L; // 6시간
    private static final String BASE_RANKS_FILE = "data/base-ranks.json";
    private static final int  DAILY_LIMIT  = 25; // Alpha Vantage 일일 한도 (표시용)
    private static final DateTimeFormatter HEATMAP_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── 시세 캐시 ────────────────────────────────────────────────
    private final Map<String, List<StockQuoteDto>>  quoteCache       = new ConcurrentHashMap<>();
    private final Map<String, Long>                 cacheTimes       = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> baseRanks        = new ConcurrentHashMap<>();
    private final Map<String, StockPriceDto>        singlePriceCache = new ConcurrentHashMap<>();
    private final Map<String, Long>                 singlePriceTimes = new ConcurrentHashMap<>();

    // ── 히트맵 캐시 ──────────────────────────────────────────────
    private volatile List<StockHeatmapSectorDto> heatmapCache     = null;
    private volatile String                       heatmapUpdatedAt = null;

    // ── Alpha Vantage 일일 API 카운터 (현재는 Yahoo Finance 사용으로 표시 전용) ──
    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private volatile LocalDate  counterDate    = LocalDate.now();

    private final KrxService           krxService;
    private final YahooFinanceService  yahooService;
    private final StockNewsService     newsService;
    private final NaverFinanceService  naverService;
    private final ObjectMapper         objectMapper;

    public StockService(KrxService krxService,
                        YahooFinanceService yahooService,
                        StockNewsService newsService,
                        NaverFinanceService naverService,
                        ObjectMapper objectMapper) {
        this.krxService   = krxService;
        this.yahooService = yahooService;
        this.newsService  = newsService;
        this.naverService = naverService;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 API
    // ─────────────────────────────────────────────────────────────

    /** 국내 시총 Top 10 (네이버 금융 실시간 순위 + 시세) */
    public List<StockQuoteDto> getTop10KR() {
        Long cachedAt = cacheTimes.get("KR");
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [KR]");
            return quoteCache.getOrDefault("KR", Collections.emptyList());
        }
        List<NaverFinanceService.NaverStockData> naverStocks = naverService.getTopStocksKospiCached(10);
        List<StockQuoteDto> result;
        if (!naverStocks.isEmpty()) {
            result = buildKrQuotes("KR", naverStocks, "KRW");
        } else {
            log.warn("Naver Finance KOSPI 조회 실패 및 캐시 없음 — 비상 폴백(하드코딩 + Yahoo) 사용");
            result = fetchAll("KR", KrxService.KR_STOCKS_FALLBACK, "KRW");
        }
        if (!result.isEmpty()) {
            quoteCache.put("KR", result);
            cacheTimes.put("KR", System.currentTimeMillis());
        }
        return result;
    }

    /** 국내 시총 Top 10 (KOSDAQ, 네이버 금융 실시간 순위 + 시세) */
    public List<StockQuoteDto> getTop10KOSDAQ() {
        Long cachedAt = cacheTimes.get("KOSDAQ");
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [KOSDAQ]");
            return quoteCache.getOrDefault("KOSDAQ", Collections.emptyList());
        }
        List<NaverFinanceService.NaverStockData> naverStocks = naverService.getTopStocksKosdaqCached(10);
        List<StockQuoteDto> result;
        if (!naverStocks.isEmpty()) {
            result = buildKrQuotes("KOSDAQ", naverStocks, "KRW");
        } else {
            log.warn("Naver Finance KOSDAQ 조회 실패 및 캐시 없음 — 비상 폴백(하드코딩 + Yahoo) 사용");
            result = fetchAll("KOSDAQ", KrxService.KQ_STOCKS_FALLBACK, "KRW");
        }
        if (!result.isEmpty()) {
            quoteCache.put("KOSDAQ", result);
            cacheTimes.put("KOSDAQ", System.currentTimeMillis());
        }
        return result;
    }

    /** 미국 시총 Top 10 (Yahoo Finance 스크리너 실시간 순위) */
    public List<StockQuoteDto> getTop10US() {
        Long cachedAt = cacheTimes.get("US");
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [US]");
            return quoteCache.getOrDefault("US", Collections.emptyList());
        }
        List<String[]> stocks = YahooFinanceService.US_STOCKS_FALLBACK;
        List<StockQuoteDto> result = fetchAll("US", stocks, "USD");
        if (!result.isEmpty()) {
            log.info("미국 시총 Top10 조회 완료: 후보 {}개 중 상위 {}개 선별 (실시간 시총 정렬)",
                stocks.size(), result.size());
            quoteCache.put("US", result);
            cacheTimes.put("US", System.currentTimeMillis());
        }
        return result;
    }

    /** Alpha Vantage API 쿼터 현황 조회 (표시용) */
    public StockQuotaDto getQuota() {
        resetCounterIfNewDay();
        int used      = dailyCallCount.get();
        int remaining = Math.max(0, DAILY_LIMIT - used);

        LocalDateTime now      = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        long minutesLeft = Duration.between(now, midnight).toMinutes();
        String resetInfo = String.format("자정(00:00) 초기화까지 %d시간 %d분 남음",
            minutesLeft / 60, minutesLeft % 60);

        return StockQuotaDto.builder()
            .dailyLimit(DAILY_LIMIT).callsUsed(used).callsRemaining(remaining)
            .nextKrRefresh("매일 09:00 KST (한국 장 개장 직전)")
            .nextUsRefresh("매일 23:30 KST (미국 장 개장 직전)")
            .resetInfo(resetInfo)
            .build();
    }

    /**
     * 포트폴리오 개별 종목 시세.
     * 1) Top10 캐시 → 2) 개별 캐시 → 3) Yahoo Finance v8 chart 호출
     */
    public StockPriceDto getQuote(String symbol, String market) {
        String cacheKey = market.toUpperCase();
        String currency = "KR".equalsIgnoreCase(market) ? "KRW" : "USD";

        List<StockQuoteDto> top10 = quoteCache.get(cacheKey);
        if (top10 != null) {
            for (StockQuoteDto q : top10) {
                if (q.getSymbol().equalsIgnoreCase(symbol)) {
                    return StockPriceDto.builder()
                        .symbol(q.getSymbol()).price(q.getPrice())
                        .change(q.getChange()).changePercent(q.getChangePercent())
                        .currency(q.getCurrency()).build();
                }
            }
        }

        Long cachedAt = singlePriceTimes.get(symbol);
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
            StockPriceDto cached = singlePriceCache.get(symbol);
            if (cached != null) return cached;
        }

        StockPriceDto dto = yahooService.fetchQuote(symbol, currency);
        if (dto == null) return null;
        singlePriceCache.put(symbol, dto);
        singlePriceTimes.put(symbol, System.currentTimeMillis());
        return dto;
    }

    /**
     * 종목 검색. 한글 포함 시 KRX 로컬 검색, 영문/코드 시 Yahoo Finance 검색.
     */
    public List<StockSearchResultDto> searchStocks(String query) {
        if (containsKorean(query)) return krxService.searchKrLocal(query.trim());
        return yahooService.searchYahoo(query.trim());
    }

    /** 국내 주식 히트맵 데이터 (30분 자동 갱신) */
    public StockHeatmapResponseDto getHeatmapKR() {
        if (heatmapCache == null) refreshHeatmapCache();
        return StockHeatmapResponseDto.builder()
            .updatedAt(heatmapUpdatedAt)
            .sectors(heatmapCache != null ? heatmapCache : Collections.emptyList())
            .build();
    }

    /** 뉴스 조회 — StockNewsService에 위임 */
    public List<StockNewsDto> getNews(String market) {
        return newsService.getNews(market);
    }

    /** 종목 한글명 반환 (KR: KrxService, US: YahooFinanceService) */
    public String resolveStockName(String symbol) {
        if (symbol == null) return null;
        String upper = symbol.toUpperCase();
        if (upper.endsWith(".KS") || upper.endsWith(".KQ")) {
            return krxService.resolveKrStockName(symbol);
        }
        return yahooService.resolveUsStockName(upper);
    }

    // ─────────────────────────────────────────────────────────────
    // 스케줄 자동 갱신 (KST 기준)
    // ─────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void scheduledRefreshKR() {
        log.info("[스케줄] KR 시총 Top10 갱신 시작 (09:00 KST)");
        saveBaseRanks("KR");
        quoteCache.remove("KR");
        cacheTimes.remove("KR");
        getTop10KR();
        log.info("[스케줄] KR 시총 Top10 갱신 완료");
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void scheduledRefreshKOSDAQ() {
        log.info("[스케줄] KOSDAQ 시총 Top10 갱신 시작 (09:00 KST)");
        saveBaseRanks("KOSDAQ");
        quoteCache.remove("KOSDAQ");
        cacheTimes.remove("KOSDAQ");
        getTop10KOSDAQ();
        log.info("[스케줄] KOSDAQ 시총 Top10 갱신 완료");
    }

    @Scheduled(cron = "0 30 23 * * *", zone = "Asia/Seoul")
    public void scheduledRefreshUS() {
        log.info("[스케줄] US 시총 Top10 갱신 시작 (23:30 KST)");
        saveBaseRanks("US");
        quoteCache.remove("US");
        cacheTimes.remove("US");
        getTop10US();
        log.info("[스케줄] US 시총 Top10 갱신 완료");
    }

    @Scheduled(cron = "0 0/30 * * * *", zone = "Asia/Seoul")
    public void scheduledRefreshHeatmap() {
        log.info("[스케줄] 국내 히트맵 자동 갱신 시작");
        refreshHeatmapCache();
        log.info("[스케줄] 국내 히트맵 자동 갱신 완료 ({})", heatmapUpdatedAt);
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 메서드
    // ─────────────────────────────────────────────────────────────

    /** KRX 시총 순 종목을 Yahoo Finance에서 병렬 조회 후 시총 내림차순 정렬 + 순위 변동 계산 */
    private List<StockQuoteDto> fetchAll(String cacheKey, List<String[]> stocks, String currency) {
        ExecutorService exec = Executors.newFixedThreadPool(10);
        List<Future<YahooFinanceService.RawQuote>> futures = stocks.stream()
            .map(s -> exec.submit(() -> yahooService.fetchSingle(
                s[0], s[1], Long.parseLong(s[2]), currency)))
            .toList();

        List<YahooFinanceService.RawQuote> raws = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                YahooFinanceService.RawQuote raw = futures.get(i).get(12, TimeUnit.SECONDS);
                if (raw != null) raws.add(raw);
            } catch (Exception e) {
                log.warn("Top10 종목 조회 실패 [{}]: {}", stocks.get(i)[0], e.getMessage());
            }
        }
        exec.shutdown();

        if (raws.isEmpty()) return Collections.emptyList();

        raws.sort(Comparator.comparingLong(YahooFinanceService.RawQuote::marketCap).reversed());

        Map<String, Integer> base   = baseRanks.getOrDefault(cacheKey, Collections.emptyMap());
        List<StockQuoteDto>  result = new ArrayList<>();

        for (int idx = 0; idx < raws.size(); idx++) {
            YahooFinanceService.RawQuote raw      = raws.get(idx);
            int                          newRank   = idx + 1;
            Integer                      baseRank  = base.get(raw.symbol());
            int rankChange = (baseRank == null) ? 0 : (baseRank - newRank);

            result.add(StockQuoteDto.builder()
                .rank(newRank).rankChange(rankChange)
                .symbol(raw.symbol()).name(raw.name())
                .price(raw.price()).change(raw.change()).changePercent(raw.changePercent())
                .marketCap(raw.marketCap()).currency(currency).volume(raw.volume())
                .build());
        }
        return result;
    }

    /** 네이버 금융 데이터로 StockQuoteDto 목록 생성 (순위 변동 계산 포함) */
    private List<StockQuoteDto> buildKrQuotes(String cacheKey,
            List<NaverFinanceService.NaverStockData> stocks, String currency) {
        Map<String, Integer> base   = baseRanks.getOrDefault(cacheKey, Collections.emptyMap());
        List<StockQuoteDto>  result = new ArrayList<>();
        for (int idx = 0; idx < stocks.size(); idx++) {
            NaverFinanceService.NaverStockData s = stocks.get(idx);
            int     newRank    = idx + 1;
            Integer baseRank   = base.get(s.symbol());
            int     rankChange = (baseRank == null) ? 0 : (baseRank - newRank);
            result.add(StockQuoteDto.builder()
                .rank(newRank).rankChange(rankChange)
                .symbol(s.symbol()).name(s.name())
                .price(s.price()).change(s.change()).changePercent(s.changePercent())
                .marketCap(s.marketCap()).currency(currency).volume(s.volume())
                .build());
        }
        return result;
    }

    private synchronized void refreshHeatmapCache() {
        List<StockHeatmapSectorDto> result = fetchHeatmapFromYahoo();
        if (!result.isEmpty()) {
            heatmapCache     = result;
            heatmapUpdatedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(HEATMAP_FMT);
        }
    }

    /** KRX 상위 30종목을 Yahoo Finance로 조회 후 섹터별 그룹화 */
    private List<StockHeatmapSectorDto> fetchHeatmapFromYahoo() {
        List<String[]> krStocks = krxService.getTopStocksCached(30);
        if (krStocks.isEmpty()) {
            log.warn("KRX 종목 조회 실패 — 히트맵 폴백(하드코딩 목록) 사용");
            krStocks = KrxService.KR_HEATMAP_NAME_FALLBACK.entrySet().stream()
                .map(e -> new String[]{e.getKey(), e.getValue(), "0"})
                .toList();
        }

        ExecutorService exec = Executors.newFixedThreadPool(10);
        List<Future<StockHeatmapItemDto>> futures = krStocks.stream()
            .map(s -> exec.submit(() -> yahooService.fetchHeatmapItem(s[0], s[1])))
            .toList();

        Map<String, StockHeatmapItemDto> resultMap = new HashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                StockHeatmapItemDto item = futures.get(i).get(10, TimeUnit.SECONDS);
                if (item != null) resultMap.put(krStocks.get(i)[0], item);
            } catch (Exception e) {
                log.warn("히트맵 종목 조회 실패 [{}]: {}", krStocks.get(i)[0], e.getMessage());
            }
        }
        exec.shutdown();

        Map<String, List<StockHeatmapItemDto>> bySector = new LinkedHashMap<>();
        for (String[] s : krStocks) {
            StockHeatmapItemDto item = resultMap.get(s[0]);
            if (item == null) continue;
            String sector = KrxService.KR_SECTOR_MAP.getOrDefault(s[0], "기타");
            bySector.computeIfAbsent(sector, k -> new ArrayList<>()).add(item);
        }

        log.info("히트맵 수집 완료: {}개 섹터, {}개 종목",
            bySector.size(), bySector.values().stream().mapToLong(List::size).sum());

        return bySector.entrySet().stream()
            .map(e -> StockHeatmapSectorDto.builder()
                .sector(e.getKey()).stocks(e.getValue()).build())
            .toList();
    }

    @PostConstruct
    public void loadBaseRanksFromFile() {
        File file = new File(BASE_RANKS_FILE);
        if (!file.exists()) return;
        try {
            Map<String, Map<String, Integer>> loaded = objectMapper.readValue(
                file, new TypeReference<>() {});
            baseRanks.putAll(loaded);
            log.info("[순위 기준점 로드] 파일에서 {}개 마켓 복원 ({})", loaded.size(), BASE_RANKS_FILE);
        } catch (Exception e) {
            log.warn("[순위 기준점 로드] 파일 읽기 실패, 무시: {}", e.getMessage());
        }
    }

    private void saveBaseRanks(String cacheKey) {
        List<StockQuoteDto> cached = quoteCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            Map<String, Integer> snapshot = new LinkedHashMap<>();
            cached.forEach(s -> snapshot.put(s.getSymbol(), s.getRank()));
            baseRanks.put(cacheKey, snapshot);
            log.info("[순위 기준점 저장] {} — {}개 종목", cacheKey, snapshot.size());
            persistBaseRanks();
        }
    }

    private void persistBaseRanks() {
        try {
            File file = new File(BASE_RANKS_FILE);
            file.getParentFile().mkdirs();
            objectMapper.writeValue(file, baseRanks);
            log.info("[순위 기준점 파일 저장] {}", BASE_RANKS_FILE);
        } catch (Exception e) {
            log.warn("[순위 기준점 파일 저장] 실패: {}", e.getMessage());
        }
    }

    private void resetCounterIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(counterDate)) {
            dailyCallCount.set(0);
            counterDate = today;
            log.info("일일 API 카운터 초기화 (날짜 변경: {})", today);
        }
    }

    private boolean containsKorean(String s) {
        for (char c : s.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_SYLLABLES
             || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_JAMO
             || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }
}
