package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockHeatmapItemDto;
import com.hyunchang.webapp.dto.StockPriceDto;
import com.hyunchang.webapp.dto.StockSearchResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Yahoo Finance API 연동 서비스.
 * - 미국 시총 상위 종목 조회 (스크리너)
 * - 개별 종목 시세 조회 (v8 chart)
 * - 종목 검색 (영문)
 * - 히트맵용 개별 종목 조회
 * - 미국 종목 한글명 변환
 */
@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);

    /**
     * 미국 시총 Top10 후보 심볼 목록 (20개).
     * fetchAll()이 Yahoo Finance v8/chart로 각 종목의 실시간 시총을 조회한 뒤
     * 시총 내림차순으로 정렬하므로, 실제 표시되는 순위는 완전히 동적입니다.
     * 세 번째 원소(발행주식수)는 chart API가 marketCap을 반환하지 않을 때만 사용되는 보조값입니다.
     */
    public static final List<String[]> US_STOCKS_FALLBACK = List.of(
        new String[]{"AAPL",  "Apple",               "15200000000"},
        new String[]{"NVDA",  "NVIDIA",              "24500000000"},
        new String[]{"MSFT",  "Microsoft",           "7440000000"},
        new String[]{"AMZN",  "Amazon",              "10600000000"},
        new String[]{"GOOGL", "Alphabet (Google)",   "12200000000"},
        new String[]{"META",  "Meta",                "2540000000"},
        new String[]{"TSLA",  "Tesla",               "3210000000"},
        new String[]{"AVGO",  "Broadcom",            "4770000000"},
        new String[]{"BRK-B", "Berkshire Hathaway",  "2180000000"},
        new String[]{"LLY",   "Eli Lilly",           "950000000"},
        new String[]{"JPM",   "JPMorgan Chase",      "2880000000"},
        new String[]{"V",     "Visa",                "2050000000"},
        new String[]{"WMT",   "Walmart",             "8010000000"},
        new String[]{"XOM",   "ExxonMobil",          "3960000000"},
        new String[]{"ORCL",  "Oracle",              "2760000000"},
        new String[]{"MA",    "Mastercard",          "930000000"},
        new String[]{"COST",  "Costco",              "440000000"},
        new String[]{"NFLX",  "Netflix",             "420000000"},
        new String[]{"PLTR",  "Palantir",            "21400000000"},
        new String[]{"AMD",   "AMD",                 "1620000000"},
        new String[]{"UNH",   "UnitedHealth Group",  "930000000"},
        new String[]{"HD",    "Home Depot",          "990000000"},
        new String[]{"PG",    "Procter & Gamble",    "2360000000"},
        new String[]{"JNJ",   "Johnson & Johnson",   "2410000000"},
        new String[]{"ABBV",  "AbbVie",              "1770000000"}
    );

    // 미국 주요 종목 한글명 매핑
    public static final Map<String, String> US_STOCK_NAMES_KO = Map.ofEntries(
        Map.entry("AAPL",  "애플"),          Map.entry("NVDA",  "엔비디아"),
        Map.entry("MSFT",  "마이크로소프트"),  Map.entry("AMZN",  "아마존"),
        Map.entry("GOOGL", "알파벳(구글) A"), Map.entry("GOOG",  "알파벳(구글) C"),
        Map.entry("META",  "메타"),          Map.entry("TSLA",  "테슬라"),
        Map.entry("AVGO",  "브로드컴"),       Map.entry("BRK-B", "버크셔 해서웨이"),
        Map.entry("BRK-A", "버크셔 해서웨이 A"), Map.entry("LLY",  "일라이 릴리"),
        Map.entry("JPM",   "JP모건 체이스"),  Map.entry("V",     "비자"),
        Map.entry("MA",    "마스터카드"),     Map.entry("COST",  "코스트코"),
        Map.entry("HD",    "홈디포"),        Map.entry("ORCL",  "오라클"),
        Map.entry("WMT",   "월마트"),        Map.entry("BAC",   "뱅크 오브 아메리카"),
        Map.entry("XOM",   "엑손모빌"),      Map.entry("NFLX",  "넷플릭스"),
        Map.entry("AMD",   "AMD"),           Map.entry("INTC",  "인텔"),
        Map.entry("QCOM",  "퀄컴"),          Map.entry("MU",    "마이크론 테크놀로지"),
        Map.entry("TSM",   "TSMC"),          Map.entry("ASML",  "ASML"),
        Map.entry("PLTR",  "팔란티어"),      Map.entry("COIN",  "코인베이스"),
        Map.entry("MSTR",  "마이크로스트래티지"), Map.entry("SHOP", "쇼피파이"),
        Map.entry("PYPL",  "페이팔"),        Map.entry("DIS",   "디즈니"),
        Map.entry("UBER",  "우버"),          Map.entry("ABNB",  "에어비앤비"),
        Map.entry("CRM",   "세일즈포스"),    Map.entry("NOW",   "서비스나우"),
        Map.entry("SNOW",  "스노우플레이크"), Map.entry("PANW",  "팔로알토 네트웍스"),
        Map.entry("CRWD",  "크라우드스트라이크"), Map.entry("NET",  "클라우드플레어"),
        Map.entry("ARM",   "ARM 홀딩스"),    Map.entry("SMCI",  "슈퍼마이크로컴퓨터"),
        Map.entry("MRVL",  "마벨 테크놀로지"), Map.entry("ON",   "온세미컨덕터"),
        Map.entry("AMAT",  "어플라이드 머티리얼즈"), Map.entry("LRCX", "램리서치"),
        Map.entry("KLAC",  "KLA"),           Map.entry("TXN",   "텍사스 인스트루먼트"),
        Map.entry("MELI",  "메르카도리브레"), Map.entry("SE",    "씨 리미티드"),
        Map.entry("PDD",   "핀둬둬"),        Map.entry("BABA",  "알리바바"),
        Map.entry("JD",    "징둥닷컴"),      Map.entry("NIO",   "니오"),
        Map.entry("XPEV",  "샤오펑"),        Map.entry("LI",    "리오토"),
        Map.entry("RIVN",  "리비안"),        Map.entry("LCID",  "루시드 그룹"),
        Map.entry("GM",    "제너럴 모터스"), Map.entry("F",     "포드"),
        Map.entry("GS",    "골드만삭스"),    Map.entry("MS",    "모건 스탠리"),
        Map.entry("C",     "씨티그룹"),      Map.entry("WFC",   "웰스파고"),
        Map.entry("SCHW",  "찰스 슈왑"),     Map.entry("HOOD",  "로빈후드"),
        Map.entry("SQ",    "블록(스퀘어)"),  Map.entry("SOFI",  "소파이"),
        Map.entry("IONQ",  "아이온큐"),      Map.entry("RGTI",  "리게티 컴퓨팅"),
        Map.entry("SPY",   "S&P500 ETF"),    Map.entry("QQQ",   "나스닥100 ETF"),
        Map.entry("SOXL",  "반도체 3배 ETF"), Map.entry("TQQQ", "나스닥100 3배 ETF"),
        Map.entry("SQQQ",  "나스닥100 인버스 3배 ETF"), Map.entry("ARKK", "ARK 이노베이션 ETF"),
        Map.entry("VOO",   "뱅가드 S&P500 ETF"), Map.entry("VTI", "뱅가드 미국 전체시장 ETF"),
        Map.entry("UNH",   "유나이티드헬스"), Map.entry("PG",   "프록터 앤 갬블"),
        Map.entry("JNJ",   "존슨앤드존슨"),  Map.entry("ABBV", "애브비")
    );

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

    /** 미국 종목 심볼의 한글명 반환. 없으면 null. */
    public String resolveUsStockName(String symbol) {
        return US_STOCK_NAMES_KO.get(symbol == null ? null : symbol.toUpperCase());
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 메서드
    // ─────────────────────────────────────────────────────────────

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
