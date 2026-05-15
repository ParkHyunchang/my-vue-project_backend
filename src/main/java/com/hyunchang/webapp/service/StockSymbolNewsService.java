package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockNewsDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 종목별 뉴스 검색.
 *
 * AI 분석에서 시장 단위 RSS 의 키워드 필터링만으로는 무관 뉴스가 섞이는 문제가 있어,
 * 종목명·티커로 직접 검색하는 소스들을 병렬 호출하고 합쳐서 반환한다.
 *
 * 소스:
 *   - KR: Google News RSS (종목명 검색)
 *   - US: Yahoo Finance RSS (ticker) + AlphaVantage News & Sentiment + Google News RSS (영어 회사명)
 *
 * 캐싱은 하지 않는다 — StockAnalysisService 의 "매번 최신" 정책 일관성 유지.
 */
@Service
public class StockSymbolNewsService {
    private static final Logger log = LoggerFactory.getLogger(StockSymbolNewsService.class);

    private static final int  FETCH_TIMEOUT_SEC = 6;
    private static final int  PER_SOURCE_LIMIT  = 8;
    private static final int  DESC_LIMIT        = 400;
    private static final DateTimeFormatter RFC_822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String alphaVantageKey;

    public StockSymbolNewsService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${alphavantage.api-key:}") String alphaVantageKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.alphaVantageKey = alphaVantageKey;
    }

    @PreDestroy
    public void destroy() {
        POOL.shutdown();
    }

    /**
     * 종목 하나에 대한 직접 검색 뉴스 합산.
     * @param symbol  티커 (.KS / .KQ 포함 가능)
     * @param market  "KR" | "US"
     * @param name    한글 종목명 (KR 검색용)
     * @param enName  영문 회사명 (US Google News 보조용, 없으면 null)
     */
    public List<StockNewsDto> fetchForSymbol(String symbol, String market, String name, String enName) {
        String mkt = market != null ? market.toUpperCase(Locale.ROOT) : "KR";
        List<CompletableFuture<List<StockNewsDto>>> tasks = new ArrayList<>();

        if ("KR".equals(mkt)) {
            if (notBlank(name)) {
                tasks.add(asyncSafe(() -> fetchGoogleNews(name, "ko", "KR", "ko-KR")));
            }
        } else {
            String ticker = symbol == null ? "" : symbol.split("\\.")[0].toUpperCase(Locale.ROOT);
            if (notBlank(ticker)) {
                tasks.add(asyncSafe(() -> fetchYahooFinance(ticker)));
                tasks.add(asyncSafe(() -> fetchAlphaVantage(ticker)));
            }
            if (notBlank(enName)) {
                tasks.add(asyncSafe(() -> fetchGoogleNews(enName, "en", "US", "en-US")));
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

        return dedupe(all);
    }

    // ─────────────────────────────────────────────────────────────
    // Google News RSS 검색
    // https://news.google.com/rss/search?q={query}&hl=ko-KR&gl=KR&ceid=KR:ko

    private List<StockNewsDto> fetchGoogleNews(String query, String langPrefix, String gl, String hl) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://news.google.com/rss/search?q=" + encoded
                + "&hl=" + hl + "&gl=" + gl + "&ceid=" + gl + ":" + langPrefix;
        return parseRss(url, "Google News", gl);
    }

    // ─────────────────────────────────────────────────────────────
    // Yahoo Finance RSS (US ticker 전용)
    // https://finance.yahoo.com/rss/headline?s=NVDA

    private List<StockNewsDto> fetchYahooFinance(String ticker) {
        String url = "https://finance.yahoo.com/rss/headline?s="
                + URLEncoder.encode(ticker, StandardCharsets.UTF_8);
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
        String url = "https://www.alphavantage.co/query"
                + "?function=NEWS_SENTIMENT"
                + "&tickers=" + URLEncoder.encode(ticker, StandardCharsets.UTF_8)
                + "&limit=10"
                + "&apikey=" + alphaVantageKey;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = resp.getBody();
            if (body == null || body.isBlank()) return List.of();

            JsonNode root = objectMapper.readTree(body);
            // 한도 초과 또는 정보 메시지면 feed 가 비어있음
            if (root.has("Note") || root.has("Information")) {
                log.info("[SymbolNews/AV] {}",
                        root.has("Note") ? root.path("Note").asText() : root.path("Information").asText());
                return List.of();
            }
            JsonNode feed = root.path("feed");
            if (!feed.isArray()) return List.of();

            List<StockNewsDto> out = new ArrayList<>();
            for (JsonNode item : feed) {
                String title = item.path("title").asText("").trim();
                String link  = item.path("url").asText("").trim();
                if (title.isBlank() || link.isBlank()) continue;

                String summary = item.path("summary").asText("").trim();
                if (summary.length() > DESC_LIMIT) summary = summary.substring(0, DESC_LIMIT) + "…";

                String source = item.path("source").asText("AlphaVantage");
                String pubDate = formatAvDate(item.path("time_published").asText(""));

                out.add(StockNewsDto.builder()
                        .title(title)
                        .originalTitle(title)
                        .link(link)
                        .description(summary)
                        .originalDescription(summary)
                        .pubDate(pubDate)
                        .source("AlphaVantage · " + source)
                        .market("US")
                        .build());
                if (out.size() >= 10) break;
            }
            return out;
        } catch (Exception e) {
            log.warn("[SymbolNews/AV] 호출 실패: {}", summarize(e));
            return List.of();
        }
    }

    /** "20260515T123456" → "Thu, 15 May 2026 12:34:56 GMT" */
    private String formatAvDate(String raw) {
        if (raw == null || raw.length() < 8) return "";
        try {
            int year  = Integer.parseInt(raw.substring(0, 4));
            int month = Integer.parseInt(raw.substring(4, 6));
            int day   = Integer.parseInt(raw.substring(6, 8));
            int hour  = raw.length() >= 11 ? Integer.parseInt(raw.substring(9, 11)) : 0;
            int min   = raw.length() >= 13 ? Integer.parseInt(raw.substring(11, 13)) : 0;
            int sec   = raw.length() >= 15 ? Integer.parseInt(raw.substring(13, 15)) : 0;
            return ZonedDateTime.of(year, month, day, hour, min, sec, 0, ZoneOffset.UTC).format(RFC_822);
        } catch (Exception e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 공용 RSS 파서

    private List<StockNewsDto> parseRss(String url, String sourceName, String market) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (compatible; RSS Reader/1.0)");
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String xml = resp.getBody();
            if (xml == null || xml.isBlank()) return List.of();

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            NodeList items = doc.getElementsByTagName("item");
            List<StockNewsDto> news = new ArrayList<>();
            for (int i = 0; i < items.getLength() && news.size() < PER_SOURCE_LIMIT; i++) {
                if (items.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                Element el = (Element) items.item(i);
                String title   = textOf(el, "title");
                String link    = textOf(el, "link");
                String desc    = textOf(el, "description");
                String pubDate = textOf(el, "pubDate");
                if (title.isBlank() || link.isBlank()) continue;

                desc = desc.replaceAll("<[^>]*>", "").trim();
                if (desc.length() > DESC_LIMIT) desc = desc.substring(0, DESC_LIMIT) + "…";

                news.add(StockNewsDto.builder()
                        .title(title)
                        .link(link)
                        .description(desc)
                        .pubDate(pubDate)
                        .source(sourceName)
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

    private String textOf(Element el, String tag) {
        NodeList nodes = el.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        String txt = nodes.item(0).getTextContent();
        return txt == null ? "" : txt.trim();
    }

    private List<StockNewsDto> dedupe(List<StockNewsDto> input) {
        Map<String, StockNewsDto> seen = new LinkedHashMap<>();
        for (StockNewsDto n : input) {
            if (n == null) continue;
            String key = dedupeKey(n);
            if (key.isBlank()) continue;
            seen.putIfAbsent(key, n);
        }
        return new ArrayList<>(seen.values());
    }

    private String dedupeKey(StockNewsDto n) {
        if (notBlank(n.getLink())) return n.getLink().trim().toLowerCase(Locale.ROOT);
        if (notBlank(n.getTitle())) return n.getTitle().trim().toLowerCase(Locale.ROOT);
        return "";
    }

    private CompletableFuture<List<StockNewsDto>> asyncSafe(Supplier<List<StockNewsDto>> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.get(); } catch (Exception e) {
                log.warn("[SymbolNews] task 실패: {}", summarize(e));
                return List.of();
            }
        }, POOL);
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

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
