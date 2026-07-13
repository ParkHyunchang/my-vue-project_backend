package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.service.news.NewsPromptFormatter;
import com.hyunchang.webapp.service.news.RssClient;
import com.hyunchang.webapp.util.TtlCache;
import jakarta.annotation.PreDestroy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 종목별 뉴스 검색.
 *
 * <p>AI 분석에서 시장 단위 RSS 의 키워드 필터링만으로는 무관 뉴스가 섞이는 문제가 있어, 종목명·티커로 직접 검색하는 소스들을 병렬 호출하고 합쳐서 반환한다.
 *
 * <p>소스: - KR: Google News RSS (종목명/투자 키워드/주요 경제지 검색) - US: Yahoo Finance RSS (ticker) +
 * AlphaVantage News & Sentiment + Google News RSS (영어 회사명/티커/주요 금융매체 검색)
 *
 * <p>종목별 결과는 10분 캐시한다 — 포트폴리오 진단·재진단·후보 갱신이 연달아 같은 종목을 재수집하며 매번 수 초를 쓰는 것을 방지 (뉴스 헤드라인 특성상 10분 내
 * 변동은 무시 가능).
 */
@Service
public class StockSymbolNewsService {
    private static final Logger log = LoggerFactory.getLogger(StockSymbolNewsService.class);

    private static final int FETCH_TIMEOUT_SEC = 6;
    private static final int PER_SOURCE_LIMIT = 6;
    private static final int TOTAL_NEWS_LIMIT = 24;
    private static final int DESC_LIMIT = 400;
    private static final DateTimeFormatter RFC_822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);

    // RSS/HTTP IO 대기가 대부분이라 스레드를 넉넉히 — 보유 13종목 × 소스 4~6개가 한 번에 몰린다
    private static final ExecutorService POOL = Executors.newFixedThreadPool(16);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RssClient rssClient;
    private final String alphaVantageKey;

    // AlphaVantage 무료 한도(25/일, 초당 1회 burst) 메시지를 한 번 받으면 6시간 동안 호출 자체를 skip.
    // 4종목 동시 호출 시 모든 요청이 한도 메시지를 받고 25/일 카운터만 까먹는 문제를 방지.
    // 6시간 = 하루 최대 4번 시도(0/6/12/18시 분산) → 25/일 한도 안에서 자연스럽게 갱신.
    // alphaVantageLock 으로 진짜 HTTP 호출을 직렬화해서 동시 4개 thread 가 모두 한도 메시지 받는 것 방지.
    private static final Duration ALPHAVANTAGE_BACKOFF = Duration.ofHours(6);
    private volatile Instant alphaVantageBlockedUntil = null;
    private final Object alphaVantageLock = new Object();

    // 종목별 뉴스 결과 캐시 — negative 캐시는 쓰지 않는다 (전 소스 일시 실패를 10분간 고정하지 않기 위해)
    private final TtlCache<String, List<StockNewsDto>> symbolNewsCache =
            new TtlCache<>(Duration.ofMinutes(10), Duration.ofMinutes(1));

    // AlphaVantage 응답 재사용 — 무료 25회/일 한도를 후보 12종목 갱신에 다 태우지 않도록
    // 성공 응답을 6시간 재사용하고, 한도 차단 중에는 티커별 마지막 성공 응답으로 대체한다.
    // 감성 점수는 AV 가 유일한 소스라서, 차단 시 빈 값 대신 stale 데이터가 US 후보 신호를 유지시킨다.
    private final TtlCache<String, List<StockNewsDto>> avFreshCache =
            new TtlCache<>(Duration.ofHours(6), Duration.ofMinutes(1));
    private final Map<String, List<StockNewsDto>> avLastGood = new ConcurrentHashMap<>();

    public StockSymbolNewsService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            RssClient rssClient,
            @Value("${alphavantage.api-key:}") String alphaVantageKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.rssClient = rssClient;
        this.alphaVantageKey = alphaVantageKey;
    }

    @PreDestroy
    public void destroy() {
        POOL.shutdown();
    }

    /**
     * 종목 하나에 대한 직접 검색 뉴스 합산.
     *
     * @param symbol 티커 (.KS / .KQ 포함 가능)
     * @param market "KR" | "US"
     * @param name 한글 종목명 (KR 검색용)
     * @param enName 영문 회사명 (US Google News 보조용, 없으면 null)
     */
    public List<StockNewsDto> fetchForSymbol(
            String symbol, String market, String name, String enName) {
        String mkt = market != null ? market.toUpperCase(Locale.ROOT) : "KR";
        String cacheKey = mkt + "|" + (symbol == null ? "" : symbol.toUpperCase(Locale.ROOT));
        TtlCache.Hit<List<StockNewsDto>> cached = symbolNewsCache.lookup(cacheKey);
        if (cached != null && !cached.negative()) return cached.value();

        List<CompletableFuture<List<StockNewsDto>>> tasks = new ArrayList<>();

        if ("KR".equals(mkt)) {
            for (NewsQuery q : buildKrNewsQueries(symbol, name)) {
                tasks.add(
                        asyncSafe(
                                () ->
                                        fetchGoogleNews(
                                                q.query(), "ko", "KR", "ko-KR", q.sourceLabel())));
            }
        } else {
            String ticker = symbol == null ? "" : symbol.split("\\.")[0].toUpperCase(Locale.ROOT);
            if (notBlank(ticker)) {
                tasks.add(asyncSafe(() -> fetchYahooFinance(ticker)));
                tasks.add(asyncSafe(() -> fetchAlphaVantage(ticker)));
                tasks.add(
                        asyncSafe(
                                () ->
                                        fetchGoogleNews(
                                                ticker
                                                        + " (stock OR shares OR earnings OR analyst)",
                                                "en",
                                                "US",
                                                "en-US",
                                                "Google News · Ticker")));
            }
            if (notBlank(enName)) {
                tasks.add(
                        asyncSafe(
                                () ->
                                        fetchGoogleNews(
                                                enName,
                                                "en",
                                                "US",
                                                "en-US",
                                                "Google News · Company")));
                tasks.add(
                        asyncSafe(
                                () ->
                                        fetchGoogleNews(
                                                enName
                                                        + " (site:reuters.com OR site:cnbc.com OR site:marketwatch.com OR site:barrons.com OR site:fool.com)",
                                                "en",
                                                "US",
                                                "en-US",
                                                "Google News · Financial Press")));
            }
        }

        List<StockNewsDto> all = new ArrayList<>();
        for (CompletableFuture<List<StockNewsDto>> f : tasks) {
            try {
                all.addAll(f.get(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                log.warn("[SymbolNews] 소스 타임아웃");
                f.cancel(true);
            } catch (Exception e) {
                log.warn("[SymbolNews] 소스 실패: {}", summarize(e));
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        List<StockNewsDto> deduped = dedupe(all);
        deduped.sort(
                (a, b) ->
                        Long.compare(
                                RssClient.parsePubDateEpoch(b.getPubDate()),
                                RssClient.parsePubDateEpoch(a.getPubDate())));
        List<StockNewsDto> result = deduped.stream().limit(TOTAL_NEWS_LIMIT).toList();
        // 빈 결과는 캐시하지 않는다 — 전 소스 일시 실패였다면 다음 호출에서 바로 재시도
        if (!result.isEmpty()) symbolNewsCache.put(cacheKey, result);
        return result;
    }

    private record NewsQuery(String query, String sourceLabel) {}

    private List<NewsQuery> buildKrNewsQueries(String symbol, String name) {
        Map<String, String> queries = new LinkedHashMap<>();
        if (notBlank(name)) {
            addQuery(queries, name, "Google News · 종목");
            addQuery(queries, name + " (주가 OR 실적 OR 투자 OR 공시 OR 배당 OR 분배금)", "Google News · 투자키워드");
            addQuery(
                    queries,
                    name
                            + " (site:hankyung.com OR site:mk.co.kr OR site:yna.co.kr OR site:sedaily.com OR site:edaily.co.kr OR site:news.mt.co.kr)",
                    "Google News · 주요경제지");

            String simplified = simplifyKrEtfName(name);
            if (notBlank(simplified) && !simplified.equals(name)) {
                addQuery(queries, simplified + " ETF", "Google News · ETF키워드");
                addQuery(
                        queries,
                        simplified + " (상장지수펀드 OR ETF OR 분배금 OR 운용보수)",
                        "Google News · ETF상품");
            }
        }

        String shortCode = symbol == null ? "" : symbol.split("\\.")[0].trim();
        if (notBlank(shortCode) && notBlank(name)) {
            addQuery(queries, shortCode + " " + name, "Google News · 코드");
        }

        return queries.entrySet().stream()
                .limit(6)
                .map(e -> new NewsQuery(e.getKey(), e.getValue()))
                .toList();
    }

    private void addQuery(Map<String, String> queries, String query, String sourceLabel) {
        if (!notBlank(query)) return;
        String normalized = query.replaceAll("\\s+", " ").trim();
        if (!normalized.isBlank()) queries.putIfAbsent(normalized, sourceLabel);
    }

    private String simplifyKrEtfName(String name) {
        if (!notBlank(name)) return "";
        String simplified =
                name.trim()
                        .replaceFirst(
                                "^(KODEX|TIGER|SOL|ACE|KBSTAR|RISE|PLUS|HANARO|ARIRANG|TIMEFOLIO|WON|KoAct)\\s*",
                                "")
                        .replaceAll("([가-힣])([A-Za-z0-9])", "$1 $2")
                        .replaceAll("([A-Za-z0-9])([가-힣])", "$1 $2")
                        .replaceAll("\\s+", " ")
                        .trim();
        return simplified;
    }

    // ─────────────────────────────────────────────────────────────
    // Google News RSS 검색
    // https://news.google.com/rss/search?q={query}&hl=ko-KR&gl=KR&ceid=KR:ko

    private List<StockNewsDto> fetchGoogleNews(
            String query, String langPrefix, String gl, String hl, String sourceLabel) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url =
                "https://news.google.com/rss/search?q="
                        + encoded
                        + "&hl="
                        + hl
                        + "&gl="
                        + gl
                        + "&ceid="
                        + gl
                        + ":"
                        + langPrefix;
        return parseRss(url, sourceLabel, gl);
    }

    // ─────────────────────────────────────────────────────────────
    // Yahoo Finance RSS (US ticker 전용)
    // https://feeds.finance.yahoo.com/rss/2.0/headline?s=NVDA&region=US&lang=en-US

    private List<StockNewsDto> fetchYahooFinance(String ticker) {
        String url =
                "https://feeds.finance.yahoo.com/rss/2.0/headline?s="
                        + URLEncoder.encode(ticker, StandardCharsets.UTF_8)
                        + "&region=US&lang=en-US";
        return parseRss(url, "Yahoo Finance", "US");
    }

    // ─────────────────────────────────────────────────────────────
    // AlphaVantage News & Sentiment (US 전용, 무료 25회/일)
    // https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers={ticker}&limit=10&apikey={key}

    private List<StockNewsDto> fetchAlphaVantage(String ticker) {
        if (alphaVantageKey == null || alphaVantageKey.isBlank()) {
            log.debug("[SymbolNews/AV] api-key 없음 — skip");
            return List.of();
        }
        String key = ticker.toUpperCase(Locale.ROOT);
        // 6시간 안에 받은 성공 응답은 재사용 — 무료 한도(25회/일)를 아낀다
        TtlCache.Hit<List<StockNewsDto>> fresh = avFreshCache.lookup(key);
        if (fresh != null && !fresh.negative()) return fresh.value();
        // 빠른 경로: 한도 차단 중에는 호출 대신 마지막 성공 응답으로 대체 (lock 진입 없이 즉시 반환)
        Instant blockedUntil = alphaVantageBlockedUntil;
        if (blockedUntil != null && Instant.now().isBefore(blockedUntil)) {
            log.debug("[SymbolNews/AV] 한도 차단 중 (until {}) — 마지막 성공 응답 사용", blockedUntil);
            return avLastGood.getOrDefault(key, List.of());
        }
        // 실제 HTTP 호출은 직렬화 — 동시 진입한 thread 들이 모두 한도 메시지 받는 race 방지
        synchronized (alphaVantageLock) {
            // double-check: 다른 thread 가 먼저 backoff 등록 또는 캐시를 채웠을 수 있음
            Instant b = alphaVantageBlockedUntil;
            if (b != null && Instant.now().isBefore(b)) {
                return avLastGood.getOrDefault(key, List.of());
            }
            TtlCache.Hit<List<StockNewsDto>> refetched = avFreshCache.lookup(key);
            if (refetched != null && !refetched.negative()) return refetched.value();

            List<StockNewsDto> out = doFetchAlphaVantage(ticker);
            if (out == null) {
                // 한도 메시지·호출 실패 — 캐시하지 않고 마지막 성공 응답으로 대체
                return avLastGood.getOrDefault(key, List.of());
            }
            avFreshCache.put(key, out);
            if (!out.isEmpty()) avLastGood.put(key, out);
            return out;
        }
    }

    /** 성공 시 뉴스 리스트(없으면 빈 리스트), 한도 메시지·호출 실패 시 null (캐시 금지 신호). */
    private List<StockNewsDto> doFetchAlphaVantage(String ticker) {
        String url =
                "https://www.alphavantage.co/query"
                        + "?function=NEWS_SENTIMENT"
                        + "&tickers="
                        + URLEncoder.encode(ticker, StandardCharsets.UTF_8)
                        + "&limit=10"
                        + "&apikey="
                        + alphaVantageKey;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            ResponseEntity<String> resp =
                    restTemplate.exchange(
                            url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = resp.getBody();
            if (body == null || body.isBlank()) return null;

            JsonNode root = objectMapper.readTree(body);
            // 한도 초과 또는 정보 메시지면 feed 가 비어있음 — 이후 호출은 backoff 동안 skip
            if (root.has("Note") || root.has("Information")) {
                String msg =
                        root.has("Note")
                                ? root.path("Note").asText()
                                : root.path("Information").asText();
                alphaVantageBlockedUntil = Instant.now().plus(ALPHAVANTAGE_BACKOFF);
                log.info(
                        "[SymbolNews/AV] {} — {}분 동안 차단 (until {})",
                        msg,
                        ALPHAVANTAGE_BACKOFF.toMinutes(),
                        alphaVantageBlockedUntil);
                return null;
            }
            JsonNode feed = root.path("feed");
            if (!feed.isArray()) return null;

            List<StockNewsDto> out = new ArrayList<>();
            for (JsonNode item : feed) {
                String title = item.path("title").asText("").trim();
                String link = item.path("url").asText("").trim();
                if (title.isBlank() || link.isBlank()) continue;

                String summary = item.path("summary").asText("").trim();
                if (summary.length() > DESC_LIMIT) summary = summary.substring(0, DESC_LIMIT) + "…";

                String source = item.path("source").asText("AlphaVantage");
                String pubDate = formatAvDate(item.path("time_published").asText(""));
                SentimentScore sentiment = sentimentForTicker(item, ticker);

                out.add(
                        StockNewsDto.builder()
                                .title(title)
                                .originalTitle(title)
                                .link(link)
                                .description(summary)
                                .originalDescription(summary)
                                .pubDate(pubDate)
                                .source("AlphaVantage · " + source)
                                .market("US")
                                .sentimentScore(sentiment.score())
                                .relevanceScore(sentiment.relevance())
                                .build());
                if (out.size() >= 10) break;
            }
            return out;
        } catch (Exception e) {
            log.warn("[SymbolNews/AV] 호출 실패: {}", summarize(e));
            return null;
        }
    }

    /** "20260515T123456" → "Thu, 15 May 2026 12:34:56 GMT" */
    private SentimentScore sentimentForTicker(JsonNode item, String ticker) {
        for (JsonNode score : item.path("ticker_sentiment")) {
            if (ticker.equalsIgnoreCase(score.path("ticker").asText(""))) {
                return new SentimentScore(
                        numberOrNull(score.path("ticker_sentiment_score").asText()),
                        numberOrNull(score.path("relevance_score").asText()));
            }
        }
        return new SentimentScore(null, numberOrNull(item.path("relevance_score").asText()));
    }

    private Double numberOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record SentimentScore(Double score, Double relevance) {}

    private String formatAvDate(String raw) {
        if (raw == null || raw.length() < 8) return "";
        try {
            int year = Integer.parseInt(raw.substring(0, 4));
            int month = Integer.parseInt(raw.substring(4, 6));
            int day = Integer.parseInt(raw.substring(6, 8));
            int hour = raw.length() >= 11 ? Integer.parseInt(raw.substring(9, 11)) : 0;
            int min = raw.length() >= 13 ? Integer.parseInt(raw.substring(11, 13)) : 0;
            int sec = raw.length() >= 15 ? Integer.parseInt(raw.substring(13, 15)) : 0;
            return ZonedDateTime.of(year, month, day, hour, min, sec, 0, ZoneOffset.UTC)
                    .format(RFC_822);
        } catch (Exception e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 공용 RSS 파서

    private List<StockNewsDto> parseRss(String url, String sourceName, String market) {
        try {
            List<RssClient.RssItem> items = rssClient.fetchItems(url);
            List<StockNewsDto> news = new ArrayList<>();
            for (RssClient.RssItem item : items) {
                if (news.size() >= PER_SOURCE_LIMIT) break;
                String title = item.title();
                String link = item.link();
                String desc = item.description();
                String pubDate = item.pubDate();
                String itemSource = item.itemSource();
                if (title.isBlank() || link.isBlank()) continue;

                desc = RssClient.stripHtml(desc).trim();
                if (desc.length() > DESC_LIMIT) desc = desc.substring(0, DESC_LIMIT) + "…";

                news.add(
                        StockNewsDto.builder()
                                .title(title)
                                .link(link)
                                .description(desc)
                                .pubDate(pubDate)
                                .source(RssClient.mergeSource(sourceName, itemSource))
                                .market(market)
                                .build());
            }
            return news;
        } catch (Exception e) {
            log.warn("[SymbolNews/{}] RSS 파싱 실패: {}", sourceName, summarize(e));
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 유틸

    private List<StockNewsDto> dedupe(List<StockNewsDto> input) {
        Map<String, StockNewsDto> seen = new LinkedHashMap<>();
        for (StockNewsDto n : input) {
            if (n == null) continue;
            String key = NewsPromptFormatter.dedupeKey(n);
            if (key.isBlank()) continue;
            seen.putIfAbsent(key, n);
        }
        return new ArrayList<>(seen.values());
    }

    private CompletableFuture<List<StockNewsDto>> asyncSafe(Supplier<List<StockNewsDto>> task) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return task.get();
                    } catch (Exception e) {
                        log.warn("[SymbolNews] task 실패: {}", summarize(e));
                        return List.of();
                    }
                },
                POOL);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String summarize(Throwable t) {
        if (t == null) return "(null)";
        String msg = t.getMessage();
        String type = t.getClass().getSimpleName();
        if (msg == null || msg.isBlank()) return type;
        msg = msg.replaceAll("\\s+", " ").trim();
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return type + ": " + msg;
    }
}
