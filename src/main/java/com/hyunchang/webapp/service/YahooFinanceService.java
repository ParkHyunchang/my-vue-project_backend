package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockHeatmapItemDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.dto.StockSearchResultDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Yahoo Finance API 연동 서비스.
 * - 미국 시총 상위 종목 조회 (스크리너)
 * - 개별 종목 시세 조회 (v8 chart)
 * - 종목 검색 (영문)
 * - 히트맵용 개별 종목 조회
 * - 미국 종목 한글명/영문명 변환
 */
@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);

    /**
     * 미국 시총 Top10 후보 심볼 목록 — src/main/resources/stock/us-stocks-fallback.json 에서 로드.
     * fetchAll()이 Yahoo Finance v8/chart로 각 종목의 실시간 시총을 조회한 뒤
     * 시총 내림차순으로 정렬하므로, 실제 표시되는 순위는 완전히 동적입니다.
     */
    private List<String[]> usStocksFallback = Collections.emptyList();

    /** 미국 주요 종목 한글명 매핑 — src/main/resources/stock/us-names-ko.json 에서 로드 */
    private Map<String, String> usStockNamesKo = Collections.emptyMap();

    /** 미국 주요 종목 영문 검색어 매핑 (뉴스 필터링용) — src/main/resources/stock/us-en-names.json 에서 로드 */
    private Map<String, String> usEnNames = Collections.emptyMap();

    /** 시세 데이터 내부 전달용 레코드 (StockService에서 순위 계산에 사용) */
    public record RawQuote(
        String symbol, String name,
        double price, double change, double changePercent,
        long marketCap, long volume) {}

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public YahooFinanceService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper  = objectMapper;
    }

    @PostConstruct
    private void loadConfig() {
        usStocksFallback = loadFallbackList("stock/us-stocks-fallback.json");
        log.info("us-stocks-fallback.json 로드 완료: {}개 종목", usStocksFallback.size());

        usStockNamesKo = loadStringMap("stock/us-names-ko.json");
        log.info("us-names-ko.json 로드 완료: {}개 항목", usStockNamesKo.size());

        usEnNames = loadStringMap("stock/us-en-names.json");
        log.info("us-en-names.json 로드 완료: {}개 항목", usEnNames.size());
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 데이터 접근자
    // ─────────────────────────────────────────────────────────────

    public List<String[]> getUsStocksFallback() { return usStocksFallback; }

    public Map<String, String> getUsEnNames() { return usEnNames; }

    /** 미국 종목 심볼의 한글명 반환. 없으면 null. */
    public String resolveUsStockName(String symbol) {
        if (symbol == null) return null;
        return usStockNamesKo.get(symbol.toUpperCase());
    }

    // ─────────────────────────────────────────────────────────────
    // 여러 종목 일괄 시세 조회 (v7/quote — 시총 정확도 높음)
    // ─────────────────────────────────────────────────────────────

    /**
     * Yahoo Finance v7/quote API로 여러 종목을 한 번에 조회합니다.
     * v8/chart 개별 호출 대비 시가총액 데이터가 정확하며 API 호출 횟수가 1회로 줄어듭니다.
     * 실패 시 빈 리스트를 반환하고 호출자가 폴백을 처리합니다.
     */
    public List<RawQuote> fetchBulkQuotes(List<String[]> stocks) {
        StringJoiner sj = new StringJoiner(",");
        Map<String, String[]> stockMap = new HashMap<>();
        for (String[] s : stocks) {
            sj.add(s[0]);
            stockMap.put(s[0].toUpperCase(), s);
        }

        try {
            String url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols="
                + sj
                + "&lang=en-US&region=US";

            HttpHeaders headers = buildBrowserHeaders("https://finance.yahoo.com/");
            headers.set("Accept-Language", "en-US,en;q=0.9");
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode results = objectMapper.readTree(resp.getBody())
                .path("quoteResponse").path("result");

            List<RawQuote> quotes = new ArrayList<>();
            for (JsonNode q : results) {
                String symbol = q.path("symbol").asText("").trim();
                if (symbol.isEmpty()) continue;

                String[] info   = stockMap.get(symbol.toUpperCase());
                String   koName = resolveUsStockName(symbol);
                String   name   = (koName != null && !koName.isBlank()) ? koName
                                : (info != null ? info[1] : symbol);

                double price     = q.path("regularMarketPrice").asDouble(0);
                double change    = q.path("regularMarketChange").asDouble(0);
                double changePct = q.path("regularMarketChangePercent").asDouble(0);
                long   mktCap    = q.path("marketCap").asLong(0);
                long   volume    = q.path("regularMarketVolume").asLong(0);

                if (mktCap == 0 && info != null && info.length > 2) {
                    long shares = Long.parseLong(info[2]);
                    if (shares > 0) mktCap = (long)(price * shares);
                }
                if (price == 0) continue;

                quotes.add(new RawQuote(symbol, name, price,
                    Math.round(change * 100.0) / 100.0,
                    Math.round(changePct * 100.0) / 100.0,
                    mktCap, volume));
            }

            log.info("Yahoo Finance v7/quote 일괄 조회 완료: {}개 종목", quotes.size());
            return quotes;

        } catch (Exception e) {
            log.warn("Yahoo Finance v7/quote 일괄 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 개별 종목 시세 조회 (Top10 병렬 처리용 폴백)
    // ─────────────────────────────────────────────────────────────

    /** 개별 종목의 Yahoo Finance v8 chart 시세 조회 (v7/quote 실패 시 폴백용). */
    public RawQuote fetchSingle(String symbol, String name, long shares, String currency) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?interval=1d&range=1d";

            HttpHeaders headers = buildBrowserHeaders("https://finance.yahoo.com/");
            headers.set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8");
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode meta = objectMapper.readTree(resp.getBody())
                .path("chart").path("result").get(0).path("meta");

            double price     = meta.path("regularMarketPrice").asDouble(0);
            double prevClose = meta.path("chartPreviousClose").asDouble(0);
            if (prevClose == 0) prevClose = meta.path("previousClose").asDouble(0);
            double change    = price - prevClose;
            double changePct = (prevClose > 0) ? change / prevClose * 100.0 : 0;
            long   volume    = meta.path("regularMarketVolume").asLong(0);
            long   mktCap    = meta.path("marketCap").asLong(0);

            if (mktCap == 0 && shares > 0) mktCap = (long)(price * shares);
            if (price == 0) return null;

            return new RawQuote(symbol, name, price,
                Math.round(change * 100.0) / 100.0,
                Math.round(changePct * 100.0) / 100.0,
                mktCap, volume);

        } catch (Exception e) {
            log.warn("Top10 Yahoo chart 조회 실패 [{}]: {}", symbol, e.getMessage());
            return null;
        }
    }

    /** 포트폴리오 개별 종목 시세 조회 (v8 chart). */
    public StockPriceDto fetchQuote(String symbol, String currency) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?interval=1d&range=1d";

            HttpHeaders headers = buildBrowserHeaders(null);
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
                .symbol(symbol).price(price)
                .change(Math.round(change * 100.0) / 100.0)
                .changePercent(Math.round(changePct * 100.0) / 100.0)
                .currency(currency)
                .build();

        } catch (Exception e) {
            log.warn("Yahoo Finance v8 chart 시세 조회 실패 [{}]: {}", symbol, e.getMessage());
            return null;
        }
    }

    /** 히트맵용 단일 종목 조회 (v8 chart, 국내주식 전용). */
    public StockHeatmapItemDto fetchHeatmapItem(String symbol, String name) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?interval=1d&range=1d&region=KR&lang=ko-KR";

            HttpHeaders headers = buildBrowserHeaders(null);
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
                .symbol(symbol).name(name).price(price)
                .changePercent(Math.round(changePct * 100.0) / 100.0)
                .marketCap(mktCap > 0 ? mktCap : 1)
                .build();

        } catch (Exception e) {
            log.warn("v8 chart 조회 실패 [{}]: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 종목 검색
    // ─────────────────────────────────────────────────────────────

    /** Yahoo Finance 검색 API (영문/코드용). */
    public List<StockSearchResultDto> searchYahoo(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://query1.finance.yahoo.com/v1/finance/search"
                + "?q=" + encoded + "&lang=en-US&region=US&quotesCount=12&newsCount=0&listsCount=0";

            HttpHeaders headers = buildBrowserHeaders("https://finance.yahoo.com/");
            headers.set("Accept-Language", "en-US,en;q=0.9");
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode quotes = objectMapper.readTree(resp.getBody()).path("quotes");
            List<StockSearchResultDto> results = new ArrayList<>();

            for (JsonNode q : quotes) {
                String type     = q.path("quoteType").asText("");
                if (!"EQUITY".equals(type) && !"ETF".equals(type)) continue;
                String symbol   = q.path("symbol").asText("").trim();
                String longName = q.path("longname").asText("").trim();
                String name     = longName.isEmpty() ? q.path("shortname").asText("").trim() : longName;
                String exchange = q.path("exchange").asText("").trim();
                if (symbol.isEmpty() || name.isEmpty()) continue;
                String market = (symbol.endsWith(".KS") || symbol.endsWith(".KQ")
                    || "KSC".equals(exchange) || "KOE".equals(exchange)) ? "KR" : "US";
                String koName = resolveUsStockName(symbol);
                if (koName != null && !koName.isBlank()) name = koName;
                results.add(StockSearchResultDto.builder()
                    .symbol(symbol).name(name).exchange(exchange).type(type).market(market).build());
            }
            log.info("Yahoo Finance 검색 [{}] → {}건", query, results.size());
            return results;

        } catch (Exception e) {
            log.warn("Yahoo Finance 종목 검색 실패 [{}]: {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 메서드
    // ─────────────────────────────────────────────────────────────

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
            Map<String, String> raw = objectMapper.readValue(is,
                new TypeReference<>() {});
            // 키를 대문자로 정규화
            Map<String, String> normalized = new HashMap<>();
            raw.forEach((k, v) -> normalized.put(k.toUpperCase(), v));
            return Collections.unmodifiableMap(normalized);
        } catch (Exception e) {
            log.error("JSON 로드 실패 [{}]: {}", resourcePath, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private HttpHeaders buildBrowserHeaders(String referer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        headers.set("Accept", "application/json, text/plain, */*");
        if (referer != null) headers.set("Referer", referer);
        return headers;
    }
}
