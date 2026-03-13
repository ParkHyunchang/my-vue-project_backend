package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockHeatmapItemDto;
import com.hyunchang.webapp.dto.StockHeatmapResponseDto;
import com.hyunchang.webapp.dto.StockHeatmapSectorDto;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.dto.StockQuotaDto;
import com.hyunchang.webapp.dto.StockQuoteDto;
import com.hyunchang.webapp.dto.StockSearchResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    @Value("${alphavantage.api-key:}")
    private String apiKey;

    private static final String AV_URL =
        "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=%s&apikey=%s";

    // ─────────────────────────────────────────────────────────────
    // 국내 Top 10 종목 정의
    // symbol[0]=Alpha Vantage 심볼, symbol[1]=한국명, symbol[2]=발행주식수(2026 기준)
    // 발행주식수는 연 1~2회 수준으로 거의 변하지 않음
    // ─────────────────────────────────────────────────────────────
    private static final List<String[]> KR_STOCKS = List.of(
        new String[]{"005930.KRX", "삼성전자",           "5969782550"},
        new String[]{"000660.KRX", "SK하이닉스",          "728002365"},
        new String[]{"373220.KRX", "LG에너지솔루션",       "234000000"},
        new String[]{"207940.KRX", "삼성바이오로직스",      "71174000"},
        new String[]{"005380.KRX", "현대자동차",           "213668187"},
        new String[]{"000270.KRX", "기아",                "404533515"},
        new String[]{"005490.KRX", "POSCO홀딩스",          "87186835"},
        new String[]{"006400.KRX", "삼성SDI",              "68764530"},
        new String[]{"035420.KRX", "NAVER",              "164263395"},
        new String[]{"105560.KRX", "KB금융",              "418593000"}
    );

    // ─────────────────────────────────────────────────────────────
    // 미국 Top 10 종목 정의
    // ─────────────────────────────────────────────────────────────
    private static final List<String[]> US_STOCKS = List.of(
        new String[]{"AAPL",  "Apple",               "15204137000"},
        new String[]{"NVDA",  "NVIDIA",              "24440000000"},
        new String[]{"MSFT",  "Microsoft",            "7437000000"},
        new String[]{"AMZN",  "Amazon",              "10595000000"},
        new String[]{"GOOGL", "Alphabet (Google)",   "12117000000"},
        new String[]{"META",  "Meta",                 "2574000000"},
        new String[]{"TSLA",  "Tesla",                "3194000000"},
        new String[]{"AVGO",  "Broadcom",             "4667000000"},
        new String[]{"BRK.B", "Berkshire Hathaway",  "2155000000"},
        new String[]{"LLY",   "Eli Lilly",             "951000000"}
    );

    // ─────────────────────────────────────────────────────────────
    // 6시간 인메모리 캐시 + 이전 순위 기억
    // ─────────────────────────────────────────────────────────────
    private final Map<String, List<StockQuoteDto>> quoteCache      = new ConcurrentHashMap<>();
    private final Map<String, Long>                cacheTimes      = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> prevRanks      = new ConcurrentHashMap<>();
    // 포트폴리오 개별 종목 시세 캐시 (top10 외 종목용)
    private final Map<String, StockPriceDto>       singlePriceCache = new ConcurrentHashMap<>();
    private final Map<String, Long>                singlePriceTimes = new ConcurrentHashMap<>();
    // 6시간 캐시 — 25 calls/day 기준: KR(10) + US(10) = 20 calls/일 사용
    // 하루 최대 2회 갱신 가능 (오전/오후 1회씩)
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L; // 6시간

    // ─────────────────────────────────────────────────────────────
    // 일일 API 호출 카운터 (자정 자동 초기화)
    // ─────────────────────────────────────────────────────────────
    private static final int DAILY_LIMIT = 25;

    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private volatile LocalDate  counterDate    = LocalDate.now();

    // ─────────────────────────────────────────────────────────────
    // 국내 히트맵용 섹터별 종목 (Yahoo Finance .KS 심볼, API 쿼터 소모 없음)
    // ─────────────────────────────────────────────────────────────
    private record HeatmapStock(String symbol, String name, String sector) {}

    private static final List<HeatmapStock> KR_HEATMAP_STOCKS = List.of(
        new HeatmapStock("005930.KS", "삼성전자",        "반도체/IT"),
        new HeatmapStock("000660.KS", "SK하이닉스",      "반도체/IT"),
        new HeatmapStock("006400.KS", "삼성SDI",         "반도체/IT"),
        new HeatmapStock("066570.KS", "LG전자",          "반도체/IT"),
        new HeatmapStock("009150.KS", "삼성전기",         "반도체/IT"),
        new HeatmapStock("207940.KS", "삼성바이오로직스", "바이오"),
        new HeatmapStock("068270.KS", "셀트리온",         "바이오"),
        new HeatmapStock("128940.KS", "한미약품",         "바이오"),
        new HeatmapStock("000100.KS", "유한양행",         "바이오"),
        new HeatmapStock("005380.KS", "현대자동차",       "자동차"),
        new HeatmapStock("000270.KS", "기아",             "자동차"),
        new HeatmapStock("012330.KS", "현대모비스",       "자동차"),
        new HeatmapStock("373220.KS", "LG에너지솔루션",   "에너지/소재"),
        new HeatmapStock("005490.KS", "POSCO홀딩스",      "에너지/소재"),
        new HeatmapStock("051910.KS", "LG화학",           "에너지/소재"),
        new HeatmapStock("096770.KS", "SK이노베이션",     "에너지/소재"),
        new HeatmapStock("105560.KS", "KB금융",           "금융"),
        new HeatmapStock("055550.KS", "신한지주",         "금융"),
        new HeatmapStock("086790.KS", "하나금융지주",     "금융"),
        new HeatmapStock("000810.KS", "삼성화재",         "금융"),
        new HeatmapStock("035420.KS", "NAVER",            "인터넷/플랫폼"),
        new HeatmapStock("035720.KS", "카카오",           "인터넷/플랫폼"),
        new HeatmapStock("259960.KS", "크래프톤",         "인터넷/플랫폼"),
        new HeatmapStock("017670.KS", "SK텔레콤",         "통신"),
        new HeatmapStock("030200.KS", "KT",               "통신"),
        new HeatmapStock("028260.KS", "삼성물산",         "유통/소비"),
        new HeatmapStock("004170.KS", "신세계",           "유통/소비"),
        new HeatmapStock("139480.KS", "이마트",           "유통/소비"),
        new HeatmapStock("015760.KS", "한국전력",         "유틸리티")
    );

    // 히트맵 캐시 (30분 스케줄 갱신 + Yahoo Finance 기반)
    private volatile List<StockHeatmapSectorDto> heatmapCache     = null;
    private volatile String                       heatmapUpdatedAt = null;
    private static final DateTimeFormatter HEATMAP_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");


    // 뉴스 RSS 소스
    private static final List<String[]> RSS_SOURCES = List.of(
        new String[]{"한국경제", "https://www.hankyung.com/feed/finance"},
        new String[]{"매일경제", "https://www.mk.co.kr/rss/30000001/"},
        new String[]{"연합뉴스", "https://www.yna.co.kr/rss/economy.xml"}
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper  objectMapper;

    public StockService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper  = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 메서드
    // ─────────────────────────────────────────────────────────────

    public List<StockQuoteDto> getTop10KR() {
        return getCachedOrFetch("KR", KR_STOCKS, "KRW");
    }

    public List<StockQuoteDto> getTop10US() {
        return getCachedOrFetch("US", US_STOCKS, "USD");
    }

    // ─────────────────────────────────────────────────────────────
    // 스케줄 자동 갱신 (KST 기준)
    //   - KR: 09:00 한국 장 개장 직전 (10 calls)
    //   - US: 23:30 미국 장 개장 직전 (10 calls)
    //   합계 20 calls/day → 25 한도 내 유지
    // ─────────────────────────────────────────────────────────────
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void scheduledRefreshKR() {
        log.info("[스케줄] KR 시총 Top10 갱신 시작 (09:00 KST)");
        quoteCache.remove("KR");
        cacheTimes.remove("KR");
        getCachedOrFetch("KR", KR_STOCKS, "KRW");
        log.info("[스케줄] KR 시총 Top10 갱신 완료");
    }

    @Scheduled(cron = "0 30 23 * * *", zone = "Asia/Seoul")
    public void scheduledRefreshUS() {
        log.info("[스케줄] US 시총 Top10 갱신 시작 (23:30 KST)");
        quoteCache.remove("US");
        cacheTimes.remove("US");
        getCachedOrFetch("US", US_STOCKS, "USD");
        log.info("[스케줄] US 시총 Top10 갱신 완료");
    }

    // ─────────────────────────────────────────────────────────────
    // 쿼타 조회
    // ─────────────────────────────────────────────────────────────
    public StockQuotaDto getQuota() {
        resetCounterIfNewDay();
        int used      = dailyCallCount.get();
        int remaining = Math.max(0, DAILY_LIMIT - used);

        LocalDateTime now      = LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        long minutesLeft = Duration.between(now, midnight).toMinutes();
        String resetInfo = String.format("자정(00:00) 초기화까지 %d시간 %d분 남음",
            minutesLeft / 60, minutesLeft % 60);

        return StockQuotaDto.builder()
            .dailyLimit(DAILY_LIMIT)
            .callsUsed(used)
            .callsRemaining(remaining)
            .nextKrRefresh("매일 09:00 KST (한국 장 개장 직전)")
            .nextUsRefresh("매일 23:30 KST (미국 장 개장 직전)")
            .resetInfo(resetInfo)
            .build();
    }

    private void resetCounterIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(counterDate)) {
            dailyCallCount.set(0);
            counterDate = today;
            log.info("일일 API 카운터 초기화 (날짜 변경: {})", today);
        }
    }

    private void incrementDailyCalls(int count) {
        resetCounterIfNewDay();
        int newVal = dailyCallCount.addAndGet(count);
        log.info("Alpha Vantage API 호출: +{} (오늘 누적: {}/{})", count, newVal, DAILY_LIMIT);
    }

    /**
     * 포트폴리오 개별 종목 시세 조회
     * 1) top10 캐시에 있으면 API 호출 없이 반환
     * 2) 개별 캐시에 있으면 반환
     * 3) Yahoo Finance v8 chart API 호출 (쿼터 소모 없음, 6시간 캐시)
     */
    public StockPriceDto getQuote(String symbol, String market) {
        String cacheKey = market.toUpperCase();
        String currency = "KR".equalsIgnoreCase(market) ? "KRW" : "USD";

        // top10 캐시 우선 확인 (추가 API 호출 없음)
        List<StockQuoteDto> top10 = quoteCache.get(cacheKey);
        if (top10 != null) {
            for (StockQuoteDto q : top10) {
                if (q.getSymbol().equalsIgnoreCase(symbol)) {
                    return StockPriceDto.builder()
                        .symbol(q.getSymbol())
                        .price(q.getPrice())
                        .change(q.getChange())
                        .changePercent(q.getChangePercent())
                        .currency(q.getCurrency())
                        .build();
                }
            }
        }

        // 개별 캐시 확인
        Long cachedAt = singlePriceTimes.get(symbol);
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
            StockPriceDto cached = singlePriceCache.get(symbol);
            if (cached != null) return cached;
        }

        // Yahoo Finance v8 chart API 호출 (쿼터 소모 없음)
        StockPriceDto dto = fetchQuoteFromYahooChart(symbol, currency);
        if (dto == null) return null;

        singlePriceCache.put(symbol, dto);
        singlePriceTimes.put(symbol, System.currentTimeMillis());
        return dto;
    }

    private StockPriceDto fetchQuoteFromYahooChart(String symbol, String currency) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?interval=1d&range=1d";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Accept", "application/json");

            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode meta = objectMapper.readTree(resp.getBody())
                .path("chart").path("result").get(0).path("meta");

            double price     = meta.path("regularMarketPrice").asDouble(0);
            double prevClose = meta.path("chartPreviousClose").asDouble(0);
            if (prevClose == 0) prevClose = meta.path("previousClose").asDouble(0);
            double change    = price - prevClose;
            double changePct = (prevClose > 0) ? change / prevClose * 100.0 : 0;

            if (price == 0) return null;

            return StockPriceDto.builder()
                .symbol(symbol)
                .price(price)
                .change(Math.round(change * 100.0) / 100.0)
                .changePercent(Math.round(changePct * 100.0) / 100.0)
                .currency(currency)
                .build();

        } catch (Exception e) {
            log.warn("Yahoo Finance v8 chart 시세 조회 실패 [{}]: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Yahoo Finance 비공식 검색 API를 통해 종목 검색 (시세 API 쿼터 소모 없음)
     * KR/US 구분 없이 전체 거래소 종목을 커버
     */
    public List<StockSearchResultDto> searchStocks(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://query1.finance.yahoo.com/v1/finance/search"
                + "?q=" + encoded
                + "&lang=ko-KR&region=KR&quotesCount=12&newsCount=0&listsCount=0";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "application/json");

            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root   = objectMapper.readTree(resp.getBody());
            JsonNode quotes = root.path("quotes");

            List<StockSearchResultDto> results = new ArrayList<>();
            for (JsonNode q : quotes) {
                String type = q.path("quoteType").asText("");
                if (!"EQUITY".equals(type) && !"ETF".equals(type)) continue;

                String symbol   = q.path("symbol").asText("").trim();
                String longName  = q.path("longname").asText("").trim();
                String shortName = q.path("shortname").asText("").trim();
                String name     = longName.isEmpty() ? shortName : longName;
                String exchange = q.path("exchange").asText("").trim();

                if (symbol.isEmpty() || name.isEmpty()) continue;

                // KR 거래소 자동 감지
                String market = (symbol.endsWith(".KS") || symbol.endsWith(".KQ")
                    || "KSC".equals(exchange) || "KOE".equals(exchange)) ? "KR" : "US";

                results.add(StockSearchResultDto.builder()
                    .symbol(symbol)
                    .name(name)
                    .exchange(exchange)
                    .type(type)
                    .market(market)
                    .build());
            }
            return results;

        } catch (Exception e) {
            log.warn("Yahoo Finance 종목 검색 실패 [{}]: {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 국내 주식 히트맵 데이터 (Yahoo Finance 배치 조회 — Alpha Vantage 쿼터 소모 없음)
     * 매 30분 자동 갱신 + 기준 시각 반환
     */
    public StockHeatmapResponseDto getHeatmapKR() {
        if (heatmapCache == null) {
            refreshHeatmapCache();
        }
        return StockHeatmapResponseDto.builder()
            .updatedAt(heatmapUpdatedAt)
            .sectors(heatmapCache != null ? heatmapCache : Collections.emptyList())
            .build();
    }

    @Scheduled(cron = "0 0/30 * * * *", zone = "Asia/Seoul")
    public void scheduledRefreshHeatmap() {
        log.info("[스케줄] 국내 히트맵 자동 갱신 시작");
        refreshHeatmapCache();
        log.info("[스케줄] 국내 히트맵 자동 갱신 완료 ({})", heatmapUpdatedAt);
    }

    private synchronized void refreshHeatmapCache() {
        List<StockHeatmapSectorDto> result = fetchHeatmapFromYahoo();
        if (!result.isEmpty()) {
            heatmapCache     = result;
            heatmapUpdatedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(HEATMAP_FMT);
        }
    }

    /**
     * Yahoo Finance v8 chart API (개별 종목, 인증 불필요)
     * v7 quote API가 401로 막혀 v8 chart로 대체
     * 29개 종목을 10개씩 병렬 처리
     */
    private List<StockHeatmapSectorDto> fetchHeatmapFromYahoo() {
        ExecutorService exec = Executors.newFixedThreadPool(10);
        List<Future<StockHeatmapItemDto>> futures = KR_HEATMAP_STOCKS.stream()
            .map(hs -> exec.submit(() -> fetchSingleChartStock(hs)))
            .toList();

        Map<String, StockHeatmapItemDto> resultMap = new HashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                StockHeatmapItemDto item = futures.get(i).get(10, TimeUnit.SECONDS);
                if (item != null) resultMap.put(KR_HEATMAP_STOCKS.get(i).symbol(), item);
            } catch (Exception e) {
                log.warn("히트맵 종목 조회 실패 [{}]: {}", KR_HEATMAP_STOCKS.get(i).symbol(), e.getMessage());
            }
        }
        exec.shutdown();

        // 섹터별 그룹화 (정의 순서 유지)
        Map<String, List<StockHeatmapItemDto>> bySector = new LinkedHashMap<>();
        for (HeatmapStock hs : KR_HEATMAP_STOCKS) {
            StockHeatmapItemDto item = resultMap.get(hs.symbol());
            if (item != null) bySector.computeIfAbsent(hs.sector(), k -> new ArrayList<>()).add(item);
        }

        log.info("히트맵 수집 완료: {}개 섹터, {}개 종목",
            bySector.size(), bySector.values().stream().mapToLong(List::size).sum());

        return bySector.entrySet().stream()
            .map(e -> StockHeatmapSectorDto.builder()
                .sector(e.getKey()).stocks(e.getValue()).build())
            .toList();
    }

    private StockHeatmapItemDto fetchSingleChartStock(HeatmapStock hs) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + hs.symbol()
                + "?interval=1d&range=1d&region=KR&lang=ko-KR";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Accept", "application/json");

            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode meta = objectMapper.readTree(resp.getBody())
                .path("chart").path("result").get(0).path("meta");

            double price     = meta.path("regularMarketPrice").asDouble(0);
            double prevClose = meta.path("chartPreviousClose").asDouble(0);
            if (prevClose == 0) prevClose = meta.path("previousClose").asDouble(0);
            double changePct = (prevClose > 0) ? (price - prevClose) / prevClose * 100.0 : 0;
            long   mktCap    = meta.path("marketCap").asLong(0);

            if (price == 0) return null;

            return StockHeatmapItemDto.builder()
                .symbol(hs.symbol())
                .name(hs.name())
                .price(price)
                .changePercent(Math.round(changePct * 100.0) / 100.0)
                .marketCap(mktCap > 0 ? mktCap : 1)
                .build();

        } catch (Exception e) {
            log.warn("v8 chart 조회 실패 [{}]: {}", hs.symbol(), e.getMessage());
            return null;
        }
    }

    public List<StockNewsDto> getNews() {
        List<StockNewsDto> all = new ArrayList<>();
        for (String[] src : RSS_SOURCES) {
            try { all.addAll(parseRss(src[1], src[0])); }
            catch (Exception e) { log.warn("RSS 파싱 실패 [{}]: {}", src[0], e.getMessage()); }
        }
        return all.stream().limit(30).toList();
    }

    // ─────────────────────────────────────────────────────────────
    // 캐시 처리
    // ─────────────────────────────────────────────────────────────

    private List<StockQuoteDto> getCachedOrFetch(String key, List<String[]> stocks, String currency) {
        Long cachedAt = cacheTimes.get(key);
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [{}]", key);
            return quoteCache.getOrDefault(key, Collections.emptyList());
        }
        List<StockQuoteDto> result = fetchAll(key, stocks, currency);
        if (!result.isEmpty()) {
            quoteCache.put(key, result);
            cacheTimes.put(key, System.currentTimeMillis());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Alpha Vantage 병렬 호출 → 실시간 시가총액 계산 → 순위 정렬 → 변동 계산
    // 5 calls/min 한도 준수: 5개 배치 → 15초 대기 → 5개
    // ─────────────────────────────────────────────────────────────

    private List<StockQuoteDto> fetchAll(String cacheKey, List<String[]> stocks, String currency) {
        // 1단계: Alpha Vantage에서 실시간 가격 병렬 조회
        List<RawQuote> raws = new ArrayList<>();
        int batchSize = 5;

        for (int i = 0; i < stocks.size(); i += batchSize) {
            List<String[]> batch = stocks.subList(i, Math.min(i + batchSize, stocks.size()));
            ExecutorService exec = Executors.newFixedThreadPool(batch.size());
            List<Future<RawQuote>> futures = new ArrayList<>();

            for (String[] stock : batch) {
                futures.add(exec.submit(() -> fetchSingleRaw(stock[0], stock[1],
                    Long.parseLong(stock[2]), currency)));
            }
            int batchSuccessCount = 0;
            for (Future<RawQuote> f : futures) {
                try {
                    RawQuote raw = f.get(12, TimeUnit.SECONDS);
                    if (raw != null) { raws.add(raw); batchSuccessCount++; }
                } catch (Exception e) {
                    log.warn("종목 조회 실패: {}", e.getMessage());
                }
            }
            exec.shutdown();
            incrementDailyCalls(batchSuccessCount);

            if (i + batchSize < stocks.size()) {
                try { Thread.sleep(15_000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        if (raws.isEmpty()) return Collections.emptyList();

        // 2단계: 실시간 시가총액 = 현재가 × 발행주식수 → 내림차순 정렬
        raws.sort(Comparator.comparingLong(RawQuote::marketCap).reversed());

        // 3단계: 이전 순위와 비교하여 rankChange 계산
        Map<String, Integer> oldRanks = prevRanks.getOrDefault(cacheKey, Collections.emptyMap());
        Map<String, Integer> newRanks = new LinkedHashMap<>();
        List<StockQuoteDto> result    = new ArrayList<>();

        for (int idx = 0; idx < raws.size(); idx++) {
            RawQuote raw      = raws.get(idx);
            int      newRank  = idx + 1;
            Integer  oldRank  = oldRanks.get(raw.symbol());
            // 첫 호출이면 변동 없음(0), 이후부터 이전 순위 - 현재 순위 (양수 = 상승)
            int rankChange    = (oldRank == null) ? 0 : (oldRank - newRank);

            newRanks.put(raw.symbol(), newRank);
            result.add(StockQuoteDto.builder()
                .rank(newRank)
                .rankChange(rankChange)
                .symbol(raw.symbol())
                .name(raw.name())
                .price(raw.price())
                .change(raw.change())
                .changePercent(raw.changePercent())
                .marketCap(raw.marketCap())
                .currency(currency)
                .volume(raw.volume())
                .build());
        }

        // 4단계: 이전 순위 갱신
        prevRanks.put(cacheKey, newRanks);
        return result;
    }

    // 단일 종목 조회 (raw 데이터)
    private RawQuote fetchSingleRaw(String symbol, String name, long shares, String currency) {
        try {
            String url  = String.format(AV_URL, symbol, apiKey);
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());

            if (root.has("Note") || root.has("Information")) {
                log.warn("Alpha Vantage API 한도 초과 [{}]", symbol);
                return null;
            }

            JsonNode q = root.path("Global Quote");
            if (q.isMissingNode() || q.isEmpty()) {
                log.warn("Alpha Vantage 빈 응답 [{}]", symbol);
                return null;
            }

            double price   = q.path("05. price").asDouble(0);
            double change  = q.path("09. change").asDouble(0);
            String pctStr  = q.path("10. change percent").asText("0%")
                               .replace("%", "").trim();
            double pct     = pctStr.isEmpty() ? 0 : Double.parseDouble(pctStr);
            long   volume  = q.path("06. volume").asLong(0);

            // 실시간 시가총액 = 현재가 × 발행주식수
            long marketCap = (long)(price * shares);

            return new RawQuote(symbol, name, price, change, pct, marketCap, volume);

        } catch (Exception e) {
            log.warn("Alpha Vantage 호출 실패 [{}]: {}", symbol, e.getMessage());
            return null;
        }
    }

    // 내부 전달용 레코드
    private record RawQuote(
        String symbol, String name,
        double price, double change, double changePercent,
        long marketCap, long volume) {}

    // ─────────────────────────────────────────────────────────────
    // 뉴스 RSS 파싱
    // ─────────────────────────────────────────────────────────────

    private List<StockNewsDto> parseRss(String url, String sourceName) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (compatible; RSS Reader/1.0)");
        ResponseEntity<String> resp = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        String xml = resp.getBody();
        if (xml == null || xml.isBlank()) return Collections.emptyList();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        NodeList items = doc.getElementsByTagName("item");
        List<StockNewsDto> news = new ArrayList<>();
        int limit = Math.min(items.getLength(), 10);

        for (int i = 0; i < limit; i++) {
            if (items.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element el = (Element) items.item(i);

            String title   = getNodeText(el, "title");
            String link    = getNodeText(el, "link");
            String desc    = getNodeText(el, "description");
            String pubDate = getNodeText(el, "pubDate");

            if (title.isBlank()) continue;
            desc = desc.replaceAll("<[^>]*>", "").trim();
            if (desc.length() > 120) desc = desc.substring(0, 120) + "…";

            news.add(StockNewsDto.builder()
                .title(title).link(link).description(desc)
                .pubDate(pubDate).source(sourceName).build());
        }
        return news;
    }

    private String getNodeText(Element el, String tagName) {
        NodeList nodes = el.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }
}
