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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
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
     * 미국 시총 Top10 폴백용 후보 심볼 목록 — src/main/resources/stock/us-stocks-fallback.json 에서 로드.
     * 1순위 screener API (fetchTopMarketCapUs)가 차단/실패하는 경우에만 사용되는 안전망이며,
     * v7/quote 또는 v8/chart로 시총을 조회한 뒤 내림차순 정렬해 상위를 추출합니다.
     * shares 값은 Yahoo가 marketCap=0을 반환할 때만 가격×주식수 계산용 폴백으로 사용됩니다.
     */
    private List<String[]> usStocksFallback = Collections.emptyList();

    /** 미국 주요 종목 한글명 매핑 — src/main/resources/stock/us-names-ko.json 에서 로드 */
    private Map<String, String> usStockNamesKo = Collections.emptyMap();

    /** 미국 주요 ETF 목록 (검색 풀용) — src/main/resources/stock/us-etf.json 에서 로드. [symbol, 한글표시명, 영문검색어] */
    private List<String[]> usEtfList = Collections.emptyList();

    /** 미국 주요 종목 영문 검색어 매핑 (뉴스 필터링용) — src/main/resources/stock/us-en-names.json 에서 로드 */
    private Map<String, String> usEnNames = Collections.emptyMap();

    /** 시세 데이터 내부 전달용 레코드 (StockService에서 순위 계산에 사용) */
    public record RawQuote(
        String symbol, String name,
        double price, double change, double changePercent,
        long marketCap, long volume) {}

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // ── Yahoo Finance screener API 인증 토큰 캐시 (30분 TTL) ─────────
    private static final long CRUMB_TTL_MS = 30 * 60 * 1000L;
    private volatile String cachedCrumb        = null;
    private volatile String cachedCookieHeader = null;
    private volatile long   crumbCachedAt      = 0;
    private final Object crumbLock = new Object();

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

        usEtfList = loadEtfList("stock/us-etf.json");
        log.info("us-etf.json 로드 완료: {}개 ETF", usEtfList.size());

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

    // ── 미국 종목 로컬 검색 인덱스 ────────────────────────────────
    /** 검색 인덱스 1행: symbol + 표시명 + (한글명, 영문토큰, 영문 정식명) 검색 키 + 타입(EQUITY/ETF) */
    private record UsSearchEntry(
        String symbol, String displayName,
        String koName, String enTokens, String enFullName, String type) {}

    private volatile List<UsSearchEntry> usSearchIndex = null;

    private void buildUsSearchIndexIfNeeded() {
        if (usSearchIndex != null) return;
        synchronized (this) {
            if (usSearchIndex != null) return;
            // us-stocks-fallback.json → symbol → 영문 정식명 룩업
            Map<String, String> enFullMap = new HashMap<>();
            for (String[] s : usStocksFallback) {
                if (s.length >= 2 && s[0] != null && s[1] != null) {
                    enFullMap.put(s[0].toUpperCase(), s[1]);
                }
            }
            // 세 소스에 등장하는 모든 심볼 합집합
            Set<String> symbols = new LinkedHashSet<>();
            usStockNamesKo.keySet().forEach(k -> symbols.add(k.toUpperCase()));
            usEnNames.keySet().forEach(k -> symbols.add(k.toUpperCase()));
            symbols.addAll(enFullMap.keySet());

            List<UsSearchEntry> list = new ArrayList<>(symbols.size());
            for (String sym : symbols) {
                String ko       = usStockNamesKo.get(sym);
                String enTokens = usEnNames.get(sym);
                String enFull   = enFullMap.get(sym);
                String display  = (ko != null && !ko.isBlank()) ? ko
                                : (enFull != null && !enFull.isBlank()) ? enFull
                                : sym;
                list.add(new UsSearchEntry(sym, display, ko, enTokens, enFull, "EQUITY"));
            }
            // 주요 미국 ETF 추가 (us-etf.json) — 주식 인덱스와 동일하게 한글/영문/티커 검색 지원
            int etfCount = 0;
            for (String[] etf : usEtfList) {
                String sym = etf[0].toUpperCase();
                if (symbols.contains(sym)) continue; // 주식 소스와 심볼 중복 시 스킵
                String ko = (etf[1] == null || etf[1].isBlank()) ? null : etf[1];
                String en = (etf[2] == null || etf[2].isBlank()) ? null : etf[2];
                String display = ko != null ? ko : sym;
                list.add(new UsSearchEntry(sym, display, ko, en, null, "ETF"));
                etfCount++;
            }
            usSearchIndex = list;
            log.info("US 종목 검색 인덱스 빌드 완료: {}개 (주식 {} + ETF {})",
                list.size(), list.size() - etfCount, etfCount);
        }
    }

    /**
     * 미국 종목 로컬 검색 — 한글명("테슬라"), 영문명("Tesla"/"tesla"), 티커("TSLA") 모두 매칭.
     * us-names-ko.json + us-en-names.json + us-stocks-fallback.json 의 합집합 인덱스에서
     * 부분일치(contains) 검색을 수행합니다. Yahoo API에 의존하지 않아 빠르고 안정적입니다.
     */
    public List<StockSearchResultDto> searchUsLocal(String query) {
        buildUsSearchIndexIfNeeded();
        if (usSearchIndex == null || usSearchIndex.isEmpty()) return Collections.emptyList();

        String q     = query.trim();
        String lower = q.toLowerCase();
        if (q.isEmpty()) return Collections.emptyList();

        List<StockSearchResultDto> results = new ArrayList<>();
        for (UsSearchEntry e : usSearchIndex) {
            boolean hit =
                   e.symbol.toLowerCase().contains(lower)
                || (e.koName     != null && e.koName.contains(q))
                || (e.enTokens   != null && e.enTokens.toLowerCase().contains(lower))
                || (e.enFullName != null && e.enFullName.toLowerCase().contains(lower));
            if (hit) {
                results.add(StockSearchResultDto.builder()
                    .symbol(e.symbol).name(e.displayName)
                    .exchange("").type(e.type).market("US").build());
            }
        }
        log.info("US 로컬 검색 [{}] → {}건", q, results.size());
        return results;
    }

    // ─────────────────────────────────────────────────────────────
    // 동적 시총 상위 조회 (Yahoo Finance screener API)
    // ─────────────────────────────────────────────────────────────

    /**
     * Yahoo Finance screener API로 미국 상장 시총 상위 종목을 동적으로 조회합니다.
     * region=us 필터로 NYSE/NASDAQ 상장 종목(ADR 포함)을 시가총액 내림차순 정렬해 반환합니다.
     * 하드코딩 후보 풀이 없으므로 시총 변동/신규 상위 진입 종목까지 그대로 반영됩니다.
     * 실패 시 빈 리스트 반환 — 호출자가 폴백을 처리합니다.
     */
    public List<RawQuote> fetchTopMarketCapUs(int count) {
        if (!refreshYahooAuth()) return Collections.emptyList();
        try {
            String body = String.format(java.util.Locale.US, """
                {
                  "size": %d,
                  "offset": 0,
                  "sortField": "intradaymarketcap",
                  "sortType": "desc",
                  "quoteType": "EQUITY",
                  "topOperator": "AND",
                  "query": {
                    "operator": "AND",
                    "operands": [
                      {"operator": "EQ", "operands": ["region", "us"]}
                    ]
                  },
                  "userId": "",
                  "userIdType": "guid"
                }""", count);

            String url = "https://query2.finance.yahoo.com/v1/finance/screener?crumb="
                + URLEncoder.encode(cachedCrumb, StandardCharsets.UTF_8)
                + "&lang=en-US&region=US";

            HttpHeaders headers = buildBrowserHeaders("https://finance.yahoo.com/");
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept-Language", "en-US,en;q=0.9");
            headers.set(HttpHeaders.COOKIE, cachedCookieHeader);

            // String을 body로 넘기면 MappingJackson2HttpMessageConverter가 JSON 문자열로 다시 감싸
            // ("\"{...}\"") 전송해서 Yahoo가 401로 거부함. byte[]로 넘기면 ByteArrayHttpMessageConverter가
            // 원본 그대로 송신.
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(bodyBytes, headers), String.class);

            JsonNode quotes = objectMapper.readTree(resp.getBody())
                .path("finance").path("result").path(0).path("quotes");

            List<RawQuote> raws = new ArrayList<>();
            for (JsonNode q : quotes) {
                String symbol = q.path("symbol").asText("").trim();
                if (symbol.isEmpty()) continue;

                String longName  = q.path("longName").asText("").trim();
                String shortName = q.path("shortName").asText("").trim();
                String name = !longName.isEmpty() ? longName
                            : !shortName.isEmpty() ? shortName : symbol;
                String koName = resolveUsStockName(symbol);
                if (koName != null && !koName.isBlank()) name = koName;

                double price     = readNumber(q.path("regularMarketPrice"));
                double change    = readNumber(q.path("regularMarketChange"));
                double changePct = readNumber(q.path("regularMarketChangePercent"));
                long   mktCap    = (long) readNumber(q.path("marketCap"));
                long   volume    = (long) readNumber(q.path("regularMarketVolume"));

                if (price == 0 || mktCap == 0) continue;

                raws.add(new RawQuote(symbol, name, price,
                    Math.round(change * 100.0) / 100.0,
                    Math.round(changePct * 100.0) / 100.0,
                    mktCap, volume));
            }
            log.info("Yahoo screener 동적 조회 완료: {}개 종목 (시총 내림차순, ADR 포함)", raws.size());
            return raws;

        } catch (RestClientException | IOException e) {
            log.warn("Yahoo screener 조회 실패: {}", e.getMessage());
            if (e instanceof HttpStatusCodeException hsce
                    && hsce.getStatusCode().value() == 401) {
                synchronized (crumbLock) { cachedCrumb = null; } // crumb 만료 — 재발급 유도
            }
            return Collections.emptyList();
        }
    }

    /**
     * Yahoo Finance screener API 호출에 필요한 cookie + crumb 토큰을 갱신합니다.
     * 30분 TTL 캐싱으로 매 요청마다 토큰 재발급 비용을 피합니다.
     */
    private boolean refreshYahooAuth() {
        synchronized (crumbLock) {
            long now = System.currentTimeMillis();
            if (cachedCrumb != null && (now - crumbCachedAt) < CRUMB_TTL_MS) return true;

            try {
                // 1) 세션 쿠키 획득.
                //    fc.yahoo.com이 A3 쿠키를 가장 빠르게 발급해주지만 응답이 404로 떨어지므로
                //    HttpStatusCodeException에서도 응답 헤더에서 Set-Cookie를 추출해야 함.
                HttpHeaders cookieHeaders = buildBrowserHeaders("https://finance.yahoo.com/");
                List<String> setCookies = null;
                try {
                    ResponseEntity<String> fcResp = restTemplate.exchange(
                        "https://fc.yahoo.com/", HttpMethod.GET,
                        new HttpEntity<>(cookieHeaders), String.class);
                    setCookies = fcResp.getHeaders().get(HttpHeaders.SET_COOKIE);
                } catch (HttpStatusCodeException e) {
                    HttpHeaders errHeaders = e.getResponseHeaders();
                    setCookies = (errHeaders != null) ? errHeaders.get(HttpHeaders.SET_COOKIE) : null;
                } catch (RestClientException ignored) {
                    // 네트워크 차단 등으로 fc.yahoo.com 자체가 실패한 경우 — finance.yahoo.com 폴백
                }
                if (setCookies == null || setCookies.isEmpty()) {
                    try {
                        ResponseEntity<String> mainResp = restTemplate.exchange(
                            "https://finance.yahoo.com/", HttpMethod.GET,
                            new HttpEntity<>(cookieHeaders), String.class);
                        setCookies = mainResp.getHeaders().get(HttpHeaders.SET_COOKIE);
                    } catch (HttpStatusCodeException e) {
                        setCookies = e.getResponseHeaders() != null
                            ? e.getResponseHeaders().get(HttpHeaders.SET_COOKIE) : null;
                    }
                }
                if (setCookies == null || setCookies.isEmpty()) {
                    log.warn("Yahoo 인증 쿠키 획득 실패");
                    return false;
                }
                String cookieHeader = setCookies.stream()
                    .map(c -> c.split(";", 2)[0])
                    .collect(Collectors.joining("; "));

                // 2) crumb 토큰 발급 — query2 호스트만 A3 쿠키 기반 발급을 받아주고,
                //    query1은 "Invalid Cookie"로 거부함. (Yahoo 내부 정책 변경)
                HttpHeaders crumbHeaders = buildBrowserHeaders("https://finance.yahoo.com/");
                crumbHeaders.set(HttpHeaders.COOKIE, cookieHeader);
                ResponseEntity<String> crumbResp = restTemplate.exchange(
                    "https://query2.finance.yahoo.com/v1/test/getcrumb",
                    HttpMethod.GET, new HttpEntity<>(crumbHeaders), String.class);
                String crumb = crumbResp.getBody();
                if (crumb == null || crumb.isBlank()) {
                    log.warn("Yahoo crumb 응답 비어있음");
                    return false;
                }
                cachedCrumb        = crumb.trim();
                cachedCookieHeader = cookieHeader;
                crumbCachedAt      = now;
                log.info("Yahoo 인증 토큰 갱신 완료");
                return true;

            } catch (RestClientException e) {
                log.warn("Yahoo 인증 토큰 갱신 실패: {}", e.getMessage());
                return false;
            }
        }
    }

    /** Yahoo 응답의 숫자 필드 — formatted=true(객체 {raw, fmt}) / false(스칼라) 모두 처리. */
    private double readNumber(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return 0;
        if (node.isObject()) return node.path("raw").asDouble(0);
        return node.asDouble(0);
    }

    // ─────────────────────────────────────────────────────────────
    // 여러 종목 일괄 시세 조회 (v7/quote — 시총 정확도 높음)
    // ─────────────────────────────────────────────────────────────

    /**
     * Yahoo Finance v7/quote API로 여러 종목을 한 번에 조회합니다.
     * v8/chart 개별 호출 대비 시가총액 데이터가 정확하며 API 호출 횟수가 1회로 줄어듭니다.
     * 2025년부터 Yahoo가 v7/quote에도 cookie + crumb 인증을 요구하기 시작하여 인증 토큰을 갱신해 사용합니다.
     * 실패 시 빈 리스트를 반환하고 호출자가 폴백을 처리합니다.
     */
    public List<RawQuote> fetchBulkQuotes(List<String[]> stocks) {
        if (!refreshYahooAuth()) return Collections.emptyList();

        StringJoiner sj = new StringJoiner(",");
        Map<String, String[]> stockMap = new HashMap<>();
        for (String[] s : stocks) {
            sj.add(s[0]);
            stockMap.put(s[0].toUpperCase(), s);
        }

        try {
            String url = "https://query2.finance.yahoo.com/v7/finance/quote?symbols="
                + sj
                + "&lang=en-US&region=US&crumb="
                + URLEncoder.encode(cachedCrumb, StandardCharsets.UTF_8);

            HttpHeaders headers = buildBrowserHeaders("https://finance.yahoo.com/");
            headers.set("Accept-Language", "en-US,en;q=0.9");
            headers.set(HttpHeaders.COOKIE, cachedCookieHeader);
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

        } catch (RestClientException | IOException | NumberFormatException e) {
            log.warn("Yahoo Finance v7/quote 일괄 조회 실패: {}", e.getMessage());
            if (e instanceof HttpStatusCodeException hsce
                    && hsce.getStatusCode().value() == 401) {
                synchronized (crumbLock) { cachedCrumb = null; } // crumb 만료 — 재발급 유도
            }
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

            // 한국 종목은 Yahoo v8/chart가 marketCap을 비워 보내는 경우가 흔합니다.
            // 0을 그대로 반환하면 호출자(StockService)가 KRX Open API 캐시로 보강합니다.
            return StockHeatmapItemDto.builder()
                .symbol(symbol).name(name).price(price)
                .changePercent(Math.round(changePct * 100.0) / 100.0)
                .marketCap(mktCap)
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

        } catch (RestClientException | IOException e) {
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
        } catch (IOException e) {
            log.error("JSON 로드 실패 [{}]: {}", resourcePath, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** us-etf.json 로드: [{symbol, ko, en}, ...] → String[]{symbol, ko, en} */
    private List<String[]> loadEtfList(String resourcePath) {
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
                    m.getOrDefault("ko", ""),
                    m.getOrDefault("en", "")
                })
                .filter(a -> a[0] != null && !a[0].isBlank())
                .collect(Collectors.toList());
        } catch (IOException e) {
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
        } catch (IOException e) {
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
