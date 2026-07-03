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
import java.io.IOException;
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

    private static final long TOP10_CACHE_TTL_MS    = 2 * 60 * 1000L;      // 2분 (Top10 실시간 갱신)
    private static final long PORTFOLIO_PRICE_TTL_MS = 2 * 60 * 1000L;      // 2분 (포트폴리오 개별 시세)
    private static final long QUOTE_FAIL_TTL_MS = 60 * 1000L;
    private static final String BASE_RANKS_FILE = "data/base-ranks.json";
    private static final String TOP10_SNAPSHOTS_FILE = "data/top10-snapshots.json";
    private static final int US_TOP10_DISCOVERY_SIZE = 100;
    private static final int  DAILY_LIMIT  = 25; // Alpha Vantage 일일 한도 (표시용)
    private static final DateTimeFormatter HEATMAP_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── 시세 캐시 ────────────────────────────────────────────────
    private final Map<String, List<StockQuoteDto>>  quoteCache       = new ConcurrentHashMap<>();
    private final Map<String, Long>                 cacheTimes       = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> baseRanks        = new ConcurrentHashMap<>();
    private final Map<String, Top10Snapshot>        top10Snapshots   = new ConcurrentHashMap<>();
    private final Map<String, StockPriceDto>        singlePriceCache = new ConcurrentHashMap<>();
    private final Map<String, Long>                 singlePriceTimes = new ConcurrentHashMap<>();

    // ── 히트맵 캐시 ──────────────────────────────────────────────
    private final Map<String, Long>                 quoteFailTimes   = new ConcurrentHashMap<>();

    private volatile List<StockHeatmapSectorDto> heatmapCache     = null;
    private volatile String                       heatmapUpdatedAt = null;

    // ── Alpha Vantage 일일 API 카운터 (현재는 Yahoo Finance 사용으로 표시 전용) ──
    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private volatile LocalDate  counterDate    = LocalDate.now();

    private final KrxService           krxService;
    private final KrxOpenApiService    krxApiService;
    private final YahooFinanceService  yahooService;
    private final StockNewsService     newsService;
    private final NaverFinanceService  naverService;
    private final ObjectMapper         objectMapper;

    private record Top10Snapshot(
        String market,
        String source,
        String fetchedAt,
        List<StockQuoteDto> quotes
    ) {}

    public StockService(KrxService krxService,
                        KrxOpenApiService krxApiService,
                        YahooFinanceService yahooService,
                        StockNewsService newsService,
                        NaverFinanceService naverService,
                        ObjectMapper objectMapper) {
        this.krxService    = krxService;
        this.krxApiService = krxApiService;
        this.yahooService  = yahooService;
        this.newsService   = newsService;
        this.naverService  = naverService;
        this.objectMapper  = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 API
    // ─────────────────────────────────────────────────────────────

    /** 국내 시총 Top 10 (네이버 금융 실시간 순위 + 시세) */
    public List<StockQuoteDto> getTop10KR() {
        Long cachedAt = cacheTimes.get("KR");
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < TOP10_CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [KR]");
            return quoteCache.getOrDefault("KR", Collections.emptyList());
        }
        List<NaverFinanceService.NaverStockData> naverStocks = naverService.getTopStocksKospiCached(10);
        List<StockQuoteDto> result;
        if (!naverStocks.isEmpty()) {
            result = buildKrQuotes("KR", naverStocks, "KRW");
        } else {
            log.warn("Naver Finance KOSPI 조회 실패 및 캐시 없음 — 비상 폴백(하드코딩 + Yahoo) 사용");
            result = fetchAll("KR", krxService.getKrStocksFallback(), "KRW");
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
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < TOP10_CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [KOSDAQ]");
            return quoteCache.getOrDefault("KOSDAQ", Collections.emptyList());
        }
        List<NaverFinanceService.NaverStockData> naverStocks = naverService.getTopStocksKosdaqCached(10);
        List<StockQuoteDto> result;
        if (!naverStocks.isEmpty()) {
            result = buildKrQuotes("KOSDAQ", naverStocks, "KRW");
        } else {
            log.warn("Naver Finance KOSDAQ 조회 실패 및 캐시 없음 — 비상 폴백(하드코딩 + Yahoo) 사용");
            result = fetchAll("KOSDAQ", krxService.getKqStocksFallback(), "KRW");
        }
        if (!result.isEmpty()) {
            quoteCache.put("KOSDAQ", result);
            cacheTimes.put("KOSDAQ", System.currentTimeMillis());
        }
        return result;
    }

    /**
     * 미국 시총 Top 10.
     * 1순위: Yahoo Finance screener API — 미국 상장 전체에서 시총 상위 동적 조회 (ADR 포함, 하드코딩 없음)
     * 2순위: 마지막 성공 스냅샷 (외부 API 실패 시 신규 상장 반영 실패보다 오래된 성공 데이터 우선)
     * 3순위: 정적 후보 풀 기반 부트스트랩 (스냅샷도 없는 최초 실행 시 최후 안전망)
     */
    public List<StockQuoteDto> getTop10US() {
        Long cachedAt = cacheTimes.get("US");
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < TOP10_CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [US]");
            return quoteCache.getOrDefault("US", Collections.emptyList());
        }

        List<YahooFinanceService.RawQuote> raws =
            yahooService.fetchTopMarketCapUs(US_TOP10_DISCOVERY_SIZE);
        if (!raws.isEmpty()) {
            List<StockQuoteDto> result = buildQuoteDtos("US", raws, "USD");
            if (result.size() > 10) result = new ArrayList<>(result.subList(0, 10));
            saveSuccessfulTop10Snapshot("US", result, "yahoo-screener");
            quoteCache.put("US", result);
            cacheTimes.put("US", System.currentTimeMillis());
            log.info("미국 시총 Top10 조회 완료 [yahoo-screener]: {}개 종목 (전체시장 동적 조회)", result.size());
            return result;
        }

        List<StockQuoteDto> snapshot = latestTop10SnapshotQuotes("US");
        if (!snapshot.isEmpty()) {
            quoteCache.put("US", snapshot);
            cacheTimes.put("US", System.currentTimeMillis());
            log.warn("Yahoo screener 실패 — 마지막 성공 스냅샷 반환 [US]: {}개 종목", snapshot.size());
            return snapshot;
        }

        log.warn("Yahoo screener 실패 및 스냅샷 없음 — 최초 실행 부트스트랩용 정적 후보 풀 사용");
        List<StockQuoteDto> bootstrap = fetchBootstrapUsTop10();
        if (!bootstrap.isEmpty()) {
            quoteCache.put("US", bootstrap);
            cacheTimes.put("US", System.currentTimeMillis());
        }
        return bootstrap;
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
     * 1) 개별 캐시(2분) → 2) Yahoo Finance(실시간) → 3) KRX Open API 폴백(.KS/.KQ 전일 종가)
     *
     * Top10 캐시는 시총 순위 표시용(6시간 TTL, KRX 전일 종가 기반)이므로
     * 포트폴리오 실시간 시세에는 사용하지 않습니다.
     */
    public StockPriceDto getQuote(String symbol, String market) {
        if (symbol == null || symbol.isBlank()) return null;
        String requestedSymbol = symbol.trim();
        String currency = "KR".equalsIgnoreCase(market) ? "KRW" : "USD";
        long now = System.currentTimeMillis();

        // 1) 개별 캐시 (포트폴리오용: 2분 TTL)
        Long cachedAt = singlePriceTimes.get(requestedSymbol);
        if (cachedAt != null && (now - cachedAt) < PORTFOLIO_PRICE_TTL_MS) {
            StockPriceDto cached = singlePriceCache.get(requestedSymbol);
            if (cached != null) return cached;
        }

        Long failedAt = quoteFailTimes.get(requestedSymbol);
        if (failedAt != null && (now - failedAt) < QUOTE_FAIL_TTL_MS) {
            return null;
        }
        if (failedAt != null) quoteFailTimes.remove(requestedSymbol);

        // 2) Yahoo Finance (KR/US 공통 — 실시간 인트라데이)
        List<String> candidates = quoteCandidates(requestedSymbol, market);
        for (String candidate : candidates) {
            StockPriceDto dto = yahooService.fetchQuote(candidate, currency);
            if (dto != null) {
                StockPriceDto normalized = withRequestedSymbol(dto, requestedSymbol, currency);
                cacheQuote(requestedSymbol, normalized);
                return normalized;
            }
        }

        // 3) Yahoo 실패 시 KRX Open API 폴백 (.KS/.KQ 전일 종가)
        for (String candidate : candidates) {
            if (hasKrSuffix(candidate)) {
                NaverFinanceService.NaverStockData krx = krxApiService.getKrPrice(candidate);
                if (krx != null) {
                    StockPriceDto fallback = StockPriceDto.builder()
                        .symbol(requestedSymbol).price(krx.price())
                        .change(krx.change()).changePercent(krx.changePercent())
                        .currency("KRW").build();
                    cacheQuote(requestedSymbol, fallback);
                    return fallback;
                }
            }
        }

        quoteFailTimes.put(requestedSymbol, now);
        return null;
    }

    private List<String> quoteCandidates(String symbol, String market) {
        String upperSym = symbol.toUpperCase(Locale.ROOT);
        if ("KR".equalsIgnoreCase(market)) {
            if (hasKrSuffix(upperSym)) return List.of(upperSym);
            return List.of(upperSym + ".KS", upperSym + ".KQ");
        }
        return List.of(symbol);
    }

    private boolean hasKrSuffix(String symbol) {
        if (symbol == null) return false;
        String upperSym = symbol.toUpperCase(Locale.ROOT);
        return upperSym.endsWith(".KS") || upperSym.endsWith(".KQ");
    }

    private StockPriceDto withRequestedSymbol(StockPriceDto dto, String symbol, String currency) {
        return StockPriceDto.builder()
            .symbol(symbol)
            .price(dto.getPrice())
            .change(dto.getChange())
            .changePercent(dto.getChangePercent())
            .currency(dto.getCurrency() != null ? dto.getCurrency() : currency)
            .build();
    }

    private void cacheQuote(String symbol, StockPriceDto dto) {
        singlePriceCache.put(symbol, dto);
        singlePriceTimes.put(symbol, System.currentTimeMillis());
        quoteFailTimes.remove(symbol);
    }

    /**
     * 종목 검색 — 입력 언어/형식 무관하게 동일하게 동작.
     * 1) KR 로컬 (KRX OpenAPI 2500+종목) + US 로컬 (한글명/영문명/티커) 통합 검색
     * 2) 로컬 결과가 0건이고 검색어에 한글이 없으면 Yahoo Finance API 폴백
     *    (한글 검색어는 Yahoo가 인식하지 못하므로 호출하지 않음)
     */
    public List<StockSearchResultDto> searchStocks(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return Collections.emptyList();

        // symbol 기준 dedupe — KR 결과를 먼저 넣어 한국 사용자에게 익숙한 순서로 노출
        Map<String, StockSearchResultDto> merged = new LinkedHashMap<>();
        for (StockSearchResultDto r : krxService.searchKrLocal(q))      merged.putIfAbsent(r.getSymbol(), r);
        for (StockSearchResultDto r : yahooService.searchUsLocal(q))    merged.putIfAbsent(r.getSymbol(), r);

        // 로컬에서 잡히면 Yahoo API 호출 생략 (속도/외부 의존도↓). 비어 있고 한글이 없을 때만 폴백.
        if (merged.isEmpty() && !containsKorean(q)) {
            for (StockSearchResultDto r : yahooService.searchYahoo(q)) {
                if ("KR".equals(r.getMarket())) {
                    String koName = krxService.resolveKrStockName(r.getSymbol());
                    if (koName != null && !koName.isBlank()) {
                        r = StockSearchResultDto.builder()
                            .symbol(r.getSymbol()).name(koName)
                            .exchange(r.getExchange()).type(r.getType()).market(r.getMarket())
                            .build();
                    }
                }
                merged.putIfAbsent(r.getSymbol(), r);
            }
        }
        return new ArrayList<>(merged.values());
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
    public List<StockNewsDto> getNews(String market, boolean force) {
        return newsService.getNews(market, force);
    }

    /** 미국 종목 ticker → 영문 검색어 맵 (뉴스 필터링용) */
    public Map<String, String> getUsEnNames() {
        return yahooService.getUsEnNames();
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
        quoteCache.remove("KR");
        cacheTimes.remove("KR");
        getTop10KR();
        saveBaseRanks("KR"); // 새 데이터를 캐시에 넣은 뒤 저장 (다음 실행 때 기준점으로 사용)
        log.info("[스케줄] KR 시총 Top10 갱신 완료");
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void scheduledRefreshKOSDAQ() {
        log.info("[스케줄] KOSDAQ 시총 Top10 갱신 시작 (09:00 KST)");
        quoteCache.remove("KOSDAQ");
        cacheTimes.remove("KOSDAQ");
        getTop10KOSDAQ();
        saveBaseRanks("KOSDAQ");
        log.info("[스케줄] KOSDAQ 시총 Top10 갱신 완료");
    }

    @Scheduled(cron = "0 30 23 * * *", zone = "Asia/Seoul")
    public void scheduledRefreshUS() {
        log.info("[스케줄] US 시총 Top10 갱신 시작 (23:30 KST)");
        quoteCache.remove("US");
        cacheTimes.remove("US");
        getTop10US();
        saveBaseRanks("US");
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

    /** RawQuote 목록을 시총 내림차순 정렬 후 순위 변동 포함 StockQuoteDto 목록으로 변환 */
    private List<StockQuoteDto> buildQuoteDtos(String cacheKey,
            List<YahooFinanceService.RawQuote> raws, String currency) {
        if (raws.isEmpty()) return Collections.emptyList();

        List<YahooFinanceService.RawQuote> sorted = new ArrayList<>(raws);
        sorted.sort(Comparator.comparingLong(YahooFinanceService.RawQuote::marketCap).reversed());

        Map<String, Integer> base   = baseRanks.getOrDefault(cacheKey, Collections.emptyMap());
        List<StockQuoteDto>  result = new ArrayList<>();

        for (int idx = 0; idx < sorted.size(); idx++) {
            YahooFinanceService.RawQuote raw     = sorted.get(idx);
            int                          newRank  = idx + 1;
            Integer                      baseRank = base.get(raw.symbol());
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

    /** KRX 시총 순 종목을 Yahoo Finance v8/chart로 병렬 조회 (v7/quote 실패 시 폴백) */
    private List<StockQuoteDto> fetchAll(String cacheKey, List<String[]> stocks, String currency) {
        boolean isKr = "KRW".equals(currency);
        ExecutorService exec = Executors.newFixedThreadPool(10);
        List<Future<YahooFinanceService.RawQuote>> futures = stocks.stream()
            .map(s -> exec.submit(() -> yahooService.fetchSingle(
                s[0], s[1], Long.parseLong(s[2]), currency)))
            .toList();

        List<YahooFinanceService.RawQuote> raws = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                YahooFinanceService.RawQuote raw = futures.get(i).get(12, TimeUnit.SECONDS);
                if (raw == null) continue;
                // KR 종목은 Yahoo v8/chart가 marketCap을 비워 보내는 일이 흔하고
                // fallback JSON의 shares도 0이라 0이 그대로 흘러가면 정렬이 깨집니다.
                // 마지막 성공한 KRX Open API 캐시에서 시총을 보강합니다.
                if (isKr && raw.marketCap() == 0) {
                    long krxCap = krxApiService.getKrMarketCap(raw.symbol());
                    if (krxCap > 0) {
                        log.info("KR 시총 KRX 캐시 보강 [{}]: {}원 (Yahoo v8/chart 0 → KRX)", raw.symbol(), krxCap);
                        raw = new YahooFinanceService.RawQuote(
                            raw.symbol(), raw.name(),
                            raw.price(), raw.change(), raw.changePercent(),
                            krxCap, raw.volume()
                        );
                    }
                }
                raws.add(raw);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.warn("Top10 종목 조회 실패 [{}]: {}", stocks.get(i)[0], e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        exec.shutdown();

        return buildQuoteDtos(cacheKey, raws, currency);
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

    private List<StockQuoteDto> fetchBootstrapUsTop10() {
        List<YahooFinanceService.RawQuote> raws = yahooService.fetchBulkQuotes(yahooService.getUsStocksFallback());
        List<StockQuoteDto> result;
        if (!raws.isEmpty()) {
            result = buildQuoteDtos("US", raws, "USD");
        } else {
            result = fetchAll("US", yahooService.getUsStocksFallback(), "USD");
        }
        return result.size() > 10 ? new ArrayList<>(result.subList(0, 10)) : result;
    }

    private List<StockQuoteDto> latestTop10SnapshotQuotes(String market) {
        Top10Snapshot snapshot = top10Snapshots.get(market);
        if (snapshot == null || snapshot.quotes() == null || snapshot.quotes().isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(snapshot.quotes());
    }

    private synchronized void saveSuccessfulTop10Snapshot(
            String market, List<StockQuoteDto> quotes, String source) {
        if (quotes == null || quotes.isEmpty()) return;

        List<StockQuoteDto> top10 = quotes.size() > 10
            ? new ArrayList<>(quotes.subList(0, 10))
            : new ArrayList<>(quotes);
        Top10Snapshot previous = top10Snapshots.get(market);
        logTop10Changes(market, previous != null ? previous.quotes() : Collections.emptyList(), top10);

        top10Snapshots.put(market, new Top10Snapshot(
            market,
            source,
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            top10
        ));
        persistTop10Snapshots();
    }

    private void logTop10Changes(String market, List<StockQuoteDto> previous, List<StockQuoteDto> current) {
        List<String> prevSymbols = symbolsByRank(previous);
        List<String> currSymbols = symbolsByRank(current);
        if (prevSymbols.isEmpty()) {
            log.info("[Top10 스냅샷] {} 신규 저장: {}", market, currSymbols);
            return;
        }
        if (prevSymbols.equals(currSymbols)) {
            log.info("[Top10 스냅샷] {} 구성/순위 변동 없음", market);
            return;
        }

        Set<String> prevSet = new LinkedHashSet<>(prevSymbols);
        Set<String> currSet = new LinkedHashSet<>(currSymbols);
        List<String> entered = currSymbols.stream().filter(s -> !prevSet.contains(s)).toList();
        List<String> exited = prevSymbols.stream().filter(s -> !currSet.contains(s)).toList();
        log.info("[Top10 스냅샷] {} 변경 감지 - 진입: {}, 제외: {}, 새 순서: {}",
            market, entered, exited, currSymbols);
    }

    private List<String> symbolsByRank(List<StockQuoteDto> quotes) {
        if (quotes == null || quotes.isEmpty()) return Collections.emptyList();
        return quotes.stream()
            .sorted(Comparator.comparingInt(StockQuoteDto::getRank))
            .map(StockQuoteDto::getSymbol)
            .toList();
    }

    private synchronized void refreshHeatmapCache() {
        List<StockHeatmapSectorDto> result = fetchHeatmapFromYahoo();
        if (!result.isEmpty()) {
            heatmapCache     = result;
            heatmapUpdatedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(HEATMAP_FMT);
        }
    }

    /** KRX 상위 30종목 히트맵. KRX 데이터 우선, 실패 시 Yahoo Finance 폴백. */
    private List<StockHeatmapSectorDto> fetchHeatmapFromYahoo() {
        List<NaverFinanceService.NaverStockData> krxStocks = naverService.getTopStocksKospiCached(50);
        if (!krxStocks.isEmpty()) {
            return buildHeatmapFromKrx(krxStocks);
        }
        log.warn("KRX 데이터 없음 — 히트맵 Yahoo Finance 폴백 사용");
        return buildHeatmapFromYahoo();
    }

    /** KRX NaverStockData → 섹터별 StockHeatmapSectorDto 변환 */
    private List<StockHeatmapSectorDto> buildHeatmapFromKrx(
            List<NaverFinanceService.NaverStockData> krxStocks) {
        List<NaverFinanceService.NaverStockData> top30 =
            krxStocks.subList(0, Math.min(30, krxStocks.size()));

        Map<String, List<StockHeatmapItemDto>> bySector = new LinkedHashMap<>();
        for (NaverFinanceService.NaverStockData s : top30) {
            StockHeatmapItemDto item = StockHeatmapItemDto.builder()
                .symbol(s.symbol()).name(s.name()).price(s.price())
                .changePercent(s.changePercent())
                .marketCap(s.marketCap() > 0 ? s.marketCap() : 1)
                .build();
            String sector = krxService.getKrSectorMap().getOrDefault(s.symbol(), "기타");
            bySector.computeIfAbsent(sector, k -> new ArrayList<>()).add(item);
        }

        log.info("히트맵 수집 완료 (KRX): {}개 섹터, {}개 종목",
            bySector.size(), bySector.values().stream().mapToLong(List::size).sum());

        return bySector.entrySet().stream()
            .map(e -> StockHeatmapSectorDto.builder()
                .sector(e.getKey()).stocks(e.getValue()).build())
            .toList();
    }

    /** Yahoo Finance 30개 병렬 조회 (KRX 실패 시 폴백) */
    private List<StockHeatmapSectorDto> buildHeatmapFromYahoo() {
        List<String[]> krStocks = krxService.getTopStocksCached(30);
        if (krStocks.isEmpty()) {
            log.warn("KRX 종목 목록 없음 — 히트맵 하드코딩 목록 사용");
            krStocks = krxService.getKrHeatmapNameFallback().entrySet().stream()
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
                if (item == null) continue;
                // Yahoo v8/chart는 한국 종목 marketCap을 거의 안 채워줌 → KRX 캐시로 보강.
                // 보강 후에도 0이면 트리맵 면적 계산이 불가능하므로 최후 1로 강제.
                if (item.getMarketCap() <= 0) {
                    long krxCap = krxApiService.getKrMarketCap(item.getSymbol());
                    if (krxCap > 0) log.info("히트맵 시총 KRX 캐시 보강 [{}]: {}원", item.getSymbol(), krxCap);
                    item.setMarketCap(krxCap > 0 ? krxCap : 1);
                }
                resultMap.put(krStocks.get(i)[0], item);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.warn("히트맵 종목 조회 실패 [{}]: {}", krStocks.get(i)[0], e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        exec.shutdown();

        Map<String, List<StockHeatmapItemDto>> bySector = new LinkedHashMap<>();
        for (String[] s : krStocks) {
            StockHeatmapItemDto item = resultMap.get(s[0]);
            if (item == null) continue;
            String sector = krxService.getKrSectorMap().getOrDefault(s[0], "기타");
            bySector.computeIfAbsent(sector, k -> new ArrayList<>()).add(item);
        }

        log.info("히트맵 수집 완료 (Yahoo 폴백): {}개 섹터, {}개 종목",
            bySector.size(), bySector.values().stream().mapToLong(List::size).sum());

        return bySector.entrySet().stream()
            .map(e -> StockHeatmapSectorDto.builder()
                .sector(e.getKey()).stocks(e.getValue()).build())
            .toList();
    }

    @PostConstruct
    public void loadBaseRanksFromFile() {
        File file = new File(BASE_RANKS_FILE);
        if (file.exists()) {
            try {
                Map<String, Map<String, Integer>> loaded = objectMapper.readValue(
                    file, new TypeReference<>() {});
                baseRanks.putAll(loaded);
                log.info("[순위 기준점 로드] 파일에서 {}개 마켓 복원 ({})", loaded.size(), BASE_RANKS_FILE);
                return;
            } catch (IOException e) {
                log.warn("[순위 기준점 로드] 파일 읽기 실패, 초기 데이터로 기준점 생성: {}", e.getMessage());
            }
        }
        // 파일이 없거나 읽기 실패 시 — 초기 데이터를 가져와 기준점 저장
        log.info("[순위 기준점 초기화] 파일 없음 — KR/KOSDAQ/US 초기 데이터 로드 후 기준점 저장");
        try {
            getTop10KR();     saveBaseRanks("KR");
            getTop10KOSDAQ(); saveBaseRanks("KOSDAQ");
            getTop10US();     saveBaseRanks("US");
            log.info("[순위 기준점 초기화] 완료 — 다음 스케줄 갱신 시 순위 변동 표시 가능");
        } catch (Exception e) {
            log.warn("[순위 기준점 초기화] 실패: {}", e.getMessage());
        }
    }

    @PostConstruct
    public void loadTop10SnapshotsFromFile() {
        File file = new File(TOP10_SNAPSHOTS_FILE);
        if (!file.exists()) {
            log.info("[Top10 스냅샷 로드] 파일 없음 ({})", TOP10_SNAPSHOTS_FILE);
            return;
        }
        try {
            Map<String, Top10Snapshot> loaded = objectMapper.readValue(
                file, new TypeReference<>() {});
            top10Snapshots.putAll(loaded);
            log.info("[Top10 스냅샷 로드] {}개 마켓 복원 ({})", loaded.size(), TOP10_SNAPSHOTS_FILE);
        } catch (IOException e) {
            log.warn("[Top10 스냅샷 로드] 파일 읽기 실패: {}", e.getMessage());
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
        } catch (IOException e) {
            log.warn("[순위 기준점 파일 저장] 실패: {}", e.getMessage());
        }
    }

    private void persistTop10Snapshots() {
        try {
            File file = new File(TOP10_SNAPSHOTS_FILE);
            file.getParentFile().mkdirs();
            objectMapper.writeValue(file, top10Snapshots);
            log.info("[Top10 스냅샷 파일 저장] {}", TOP10_SNAPSHOTS_FILE);
        } catch (IOException e) {
            log.warn("[Top10 스냅샷 파일 저장] 실패: {}", e.getMessage());
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
