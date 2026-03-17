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
import org.springframework.http.MediaType;
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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
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

    // KRX(한국거래소) Open API — KOSPI/KOSDAQ 시총 순위 (무료, 인증 불필요)
    private static final String KRX_API_URL =
        "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";
    private static final DateTimeFormatter KRX_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd");

    // ─────────────────────────────────────────────────────────────
    // 국내 Top 10 — KRX 실패 시 비상 폴백 (실제 순위·시총은 Yahoo Finance 재정렬)
    // ─────────────────────────────────────────────────────────────
    private static final List<String[]> KR_STOCKS_FALLBACK = List.of(
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

    // ─────────────────────────────────────────────────────────────
    // 미국 Top 10 — Yahoo Finance 스크리너 실패 시 비상 폴백
    // 실제 순위·시총은 Yahoo Finance v8 chart 호출 후 재정렬
    // ─────────────────────────────────────────────────────────────
    private static final List<String[]> US_STOCKS_FALLBACK = List.of(
        new String[]{"AAPL",  "Apple",               "0"},
        new String[]{"NVDA",  "NVIDIA",              "0"},
        new String[]{"MSFT",  "Microsoft",           "0"},
        new String[]{"AMZN",  "Amazon",              "0"},
        new String[]{"GOOGL", "Alphabet (Google)",   "0"},
        new String[]{"META",  "Meta",                "0"},
        new String[]{"TSLA",  "Tesla",               "0"},
        new String[]{"AVGO",  "Broadcom",            "0"},
        new String[]{"BRK-B", "Berkshire Hathaway",  "0"},
        new String[]{"LLY",   "Eli Lilly",           "0"}
    );

    // ─────────────────────────────────────────────────────────────
    // 6시간 인메모리 캐시 + 전일 기준 순위 (하루 1회만 갱신)
    // ─────────────────────────────────────────────────────────────
    private final Map<String, List<StockQuoteDto>>  quoteCache  = new ConcurrentHashMap<>();
    private final Map<String, Long>                 cacheTimes  = new ConcurrentHashMap<>();
    // 순위 변동 기준점: 스케줄 갱신(KR 09:00 / US 23:30) 시에만 업데이트
    // → 하루 종일 동일한 기준으로 화살표 표시 (네이버증권·Bloomberg 동일 방식)
    private final Map<String, Map<String, Integer>> baseRanks   = new ConcurrentHashMap<>();
    // 포트폴리오 개별 종목 시세 캐시 (top10 외 종목용)
    private final Map<String, StockPriceDto>       singlePriceCache = new ConcurrentHashMap<>();
    private final Map<String, Long>                singlePriceTimes = new ConcurrentHashMap<>();
    // 6시간 캐시 — 25 calls/day 기준: KR(10) + US(10) = 20 calls/일 사용
    // 하루 최대 2회 갱신 가능 (오전/오후 1회씩)
    private static final long CACHE_TTL_MS      = 6 * 60 * 60 * 1000L; // 6시간
    private static final long NEWS_CACHE_TTL_MS = 20 * 60 * 1000L;     // 뉴스 캐시 20분

    // 뉴스 캐시 (KR/US 각각, 번역 포함)
    private volatile List<StockNewsDto> krNewsCache     = null;
    private volatile long               krNewsCacheTime = 0;
    private volatile List<StockNewsDto> usNewsCache     = null;
    private volatile long               usNewsCacheTime = 0;

    // ─────────────────────────────────────────────────────────────
    // 일일 API 호출 카운터 (자정 자동 초기화)
    // ─────────────────────────────────────────────────────────────
    private static final int DAILY_LIMIT = 25;

    private final AtomicInteger dailyCallCount = new AtomicInteger(0);
    private volatile LocalDate  counterDate    = LocalDate.now();

    // ─────────────────────────────────────────────────────────────
    // 국내 히트맵 섹터 매핑 — 심볼 → 섹터명
    // 종목명·심볼 목록은 KRX API로 동적 조회, 알 수 없는 종목은 "기타" 처리
    // ─────────────────────────────────────────────────────────────
    private static final Map<String, String> KR_SECTOR_MAP = Map.ofEntries(
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

    // ─────────────────────────────────────────────────────────────
    // KRX 실패 시 히트맵 폴백 — 심볼 → 한국명 (가격은 Yahoo Finance에서 실시간 조회)
    // ─────────────────────────────────────────────────────────────
    private static final Map<String, String> KR_HEATMAP_NAME_FALLBACK = Map.ofEntries(
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

    // ─────────────────────────────────────────────────────────────
    // 미국 주요 종목 한글명 — 국내 투자자가 많이 보유하는 종목 위주
    // ─────────────────────────────────────────────────────────────
    private static final Map<String, String> US_STOCK_NAMES_KO = Map.ofEntries(
        Map.entry("AAPL",   "애플"),
        Map.entry("NVDA",   "엔비디아"),
        Map.entry("MSFT",   "마이크로소프트"),
        Map.entry("AMZN",   "아마존"),
        Map.entry("GOOGL",  "알파벳(구글) A"),
        Map.entry("GOOG",   "알파벳(구글) C"),
        Map.entry("META",   "메타"),
        Map.entry("TSLA",   "테슬라"),
        Map.entry("AVGO",   "브로드컴"),
        Map.entry("BRK-B",  "버크셔 해서웨이"),
        Map.entry("BRK-A",  "버크셔 해서웨이 A"),
        Map.entry("LLY",    "일라이 릴리"),
        Map.entry("JPM",    "JP모건 체이스"),
        Map.entry("V",      "비자"),
        Map.entry("MA",     "마스터카드"),
        Map.entry("COST",   "코스트코"),
        Map.entry("HD",     "홈디포"),
        Map.entry("ORCL",   "오라클"),
        Map.entry("WMT",    "월마트"),
        Map.entry("BAC",    "뱅크 오브 아메리카"),
        Map.entry("XOM",    "엑손모빌"),
        Map.entry("NFLX",   "넷플릭스"),
        Map.entry("AMD",    "AMD"),
        Map.entry("INTC",   "인텔"),
        Map.entry("QCOM",   "퀄컴"),
        Map.entry("MU",     "마이크론 테크놀로지"),
        Map.entry("TSM",    "TSMC"),
        Map.entry("ASML",   "ASML"),
        Map.entry("PLTR",   "팔란티어"),
        Map.entry("COIN",   "코인베이스"),
        Map.entry("MSTR",   "마이크로스트래티지"),
        Map.entry("SHOP",   "쇼피파이"),
        Map.entry("PYPL",   "페이팔"),
        Map.entry("DIS",    "디즈니"),
        Map.entry("UBER",   "우버"),
        Map.entry("ABNB",   "에어비앤비"),
        Map.entry("CRM",    "세일즈포스"),
        Map.entry("NOW",    "서비스나우"),
        Map.entry("SNOW",   "스노우플레이크"),
        Map.entry("PANW",   "팔로알토 네트웍스"),
        Map.entry("CRWD",   "크라우드스트라이크"),
        Map.entry("NET",    "클라우드플레어"),
        Map.entry("ARM",    "ARM 홀딩스"),
        Map.entry("SMCI",   "슈퍼마이크로컴퓨터"),
        Map.entry("MRVL",   "마벨 테크놀로지"),
        Map.entry("ON",     "온세미컨덕터"),
        Map.entry("AMAT",   "어플라이드 머티리얼즈"),
        Map.entry("LRCX",   "램리서치"),
        Map.entry("KLAC",   "KLA"),
        Map.entry("TXN",    "텍사스 인스트루먼트"),
        Map.entry("MELI",   "메르카도리브레"),
        Map.entry("SE",     "씨 리미티드"),
        Map.entry("PDD",    "핀둬둬"),
        Map.entry("BABA",   "알리바바"),
        Map.entry("JD",     "징둥닷컴"),
        Map.entry("NIO",    "니오"),
        Map.entry("XPEV",   "샤오펑"),
        Map.entry("LI",     "리오토"),
        Map.entry("RIVN",   "리비안"),
        Map.entry("LCID",   "루시드 그룹"),
        Map.entry("GM",     "제너럴 모터스"),
        Map.entry("F",      "포드"),
        Map.entry("GS",     "골드만삭스"),
        Map.entry("MS",     "모건 스탠리"),
        Map.entry("C",      "씨티그룹"),
        Map.entry("WFC",    "웰스파고"),
        Map.entry("SCHW",   "찰스 슈왑"),
        Map.entry("HOOD",   "로빈후드"),
        Map.entry("SQ",     "블록(스퀘어)"),
        Map.entry("SOFI",   "소파이"),
        Map.entry("IONQ",   "아이온큐"),
        Map.entry("RGTI",   "리게티 컴퓨팅"),
        Map.entry("SPY",    "S&P500 ETF"),
        Map.entry("QQQ",    "나스닥100 ETF"),
        Map.entry("SOXL",   "반도체 3배 ETF"),
        Map.entry("TQQQ",   "나스닥100 3배 ETF"),
        Map.entry("SQQQ",   "나스닥100 인버스 3배 ETF"),
        Map.entry("ARKK",   "ARK 이노베이션 ETF"),
        Map.entry("VOO",    "뱅가드 S&P500 ETF"),
        Map.entry("VTI",    "뱅가드 미국 전체시장 ETF")
    );

    // KRX 종목 목록 캐시 (검색·히트맵 공용, 6시간 갱신)
    private volatile List<String[]> krxStocksCache    = null;
    private volatile long           krxStocksCacheTime = 0;

    // KR 전종목 한글명 룩업 캐시 (KOSPI + KOSDAQ, 6시간 갱신)
    private volatile Map<String, String> krNameLookup     = null;
    private volatile long                krNameLookupTime = 0;

    // 히트맵 캐시 (30분 스케줄 갱신 + Yahoo Finance 기반)
    private volatile List<StockHeatmapSectorDto> heatmapCache     = null;
    private volatile String                       heatmapUpdatedAt = null;
    private static final DateTimeFormatter HEATMAP_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");


    // 뉴스 RSS 소스 — {출처명, URL, 시장(KR|US)}
    private static final List<String[]> RSS_SOURCES = List.of(
        new String[]{"한국경제",   "https://www.hankyung.com/feed/finance",                                         "KR"},
        new String[]{"머니투데이", "https://news.mt.co.kr/newsRSS.php?cast=1&SECTION_CD=SC1",                       "KR"},
        new String[]{"연합뉴스",   "https://www.yna.co.kr/rss/economy.xml",                                        "KR"},
        new String[]{"Yahoo Finance", "https://finance.yahoo.com/rss/topfinstories",                               "US"},
        new String[]{"MarketWatch","https://feeds.marketwatch.com/marketwatch/marketpulse/",                       "US"},
        new String[]{"CNBC",      "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=15839069", "US"}
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

    /**
     * 국내 시총 Top 10 — KRX API로 당일 실제 순위 동적 조회
     * KRX 실패 시 하드코딩 목록으로 폴백
     */
    public List<StockQuoteDto> getTop10KR() {
        Long cachedAt = cacheTimes.get("KR");
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [KR]");
            return quoteCache.getOrDefault("KR", Collections.emptyList());
        }

        List<String[]> stocks = fetchKRXTopStocks("STK", 10);
        if (stocks.isEmpty()) {
            log.warn("KRX 조회 실패, 비상 폴백 사용");
            stocks = KR_STOCKS_FALLBACK;
        }

        List<StockQuoteDto> result = fetchAll("KR", stocks, "KRW");
        if (!result.isEmpty()) {
            quoteCache.put("KR", result);
            cacheTimes.put("KR", System.currentTimeMillis());
        }
        return result;
    }

    public List<StockQuoteDto> getTop10US() {
        Long cachedAt = cacheTimes.get("US");
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < CACHE_TTL_MS) {
            log.info("Stock 캐시 히트 [US]");
            return quoteCache.getOrDefault("US", Collections.emptyList());
        }
        List<String[]> stocks = fetchUSTopFromYahoo(15);
        if (stocks.isEmpty()) {
            log.warn("Yahoo Finance US 스크리너 실패, 비상 폴백 사용");
            stocks = US_STOCKS_FALLBACK;
        }
        List<StockQuoteDto> result = fetchAll("US", stocks, "USD");
        if (!result.isEmpty()) {
            quoteCache.put("US", result);
            cacheTimes.put("US", System.currentTimeMillis());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // 스케줄 자동 갱신 (KST 기준)
    // ─────────────────────────────────────────────────────────────
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void scheduledRefreshKR() {
        log.info("[스케줄] KR 시총 Top10 갱신 시작 (09:00 KST)");
        // 갱신 전 현재 순위를 기준점으로 저장 → 오늘 하루 순위 변동 비교 기준
        saveBaseRanks("KR");
        quoteCache.remove("KR");
        cacheTimes.remove("KR");
        getTop10KR();
        log.info("[스케줄] KR 시총 Top10 갱신 완료");
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

    /** 스케줄 갱신 직전에 현재 캐시 순위를 하루 기준점(baseRanks)으로 저장 */
    private void saveBaseRanks(String cacheKey) {
        List<StockQuoteDto> cached = quoteCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            Map<String, Integer> snapshot = new LinkedHashMap<>();
            cached.forEach(s -> snapshot.put(s.getSymbol(), s.getRank()));
            baseRanks.put(cacheKey, snapshot);
            log.info("[순위 기준점 저장] {} — {}개 종목", cacheKey, snapshot.size());
        }
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
        // 한글(CJK) 포함 여부 감지 → 로컬 국내 종목 목록에서 검색
        if (containsKorean(query)) {
            return searchKrLocal(query.trim());
        }
        // 영문/코드 → Yahoo Finance
        return searchYahoo(query.trim());
    }

    /** 한글 문자 포함 여부 */
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

    /** KRX 상위 100종목에서 한글 이름 부분 매칭 */
    private List<StockSearchResultDto> searchKrLocal(String query) {
        String lower = query.toLowerCase();
        List<StockSearchResultDto> results = new ArrayList<>();

        for (String[] s : getKrxStocksCached(100)) {
            if (s[1].contains(query) || s[0].toLowerCase().contains(lower)) {
                results.add(StockSearchResultDto.builder()
                    .symbol(s[0])
                    .name(s[1])
                    .exchange("KSC")
                    .type("EQUITY")
                    .market("KR")
                    .build());
            }
        }

        log.info("KRX 검색 [{}] → {}건", query, results.size());
        return results;
    }

    /** Yahoo Finance 검색 (영문/코드용) */
    private List<StockSearchResultDto> searchYahoo(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://query1.finance.yahoo.com/v1/finance/search"
                + "?q=" + encoded
                + "&lang=en-US&region=US&quotesCount=12&newsCount=0&listsCount=0";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            headers.set("Accept", "application/json, text/plain, */*");
            headers.set("Accept-Language", "en-US,en;q=0.9");
            headers.set("Referer", "https://finance.yahoo.com/");

            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode root   = objectMapper.readTree(resp.getBody());
            JsonNode quotes = root.path("quotes");

            List<StockSearchResultDto> results = new ArrayList<>();
            for (JsonNode q : quotes) {
                String type = q.path("quoteType").asText("");
                if (!"EQUITY".equals(type) && !"ETF".equals(type)) continue;

                String symbol    = q.path("symbol").asText("").trim();
                String longName  = q.path("longname").asText("").trim();
                String shortName = q.path("shortname").asText("").trim();
                String name      = longName.isEmpty() ? shortName : longName;
                String exchange  = q.path("exchange").asText("").trim();

                if (symbol.isEmpty() || name.isEmpty()) continue;

                String market = (symbol.endsWith(".KS") || symbol.endsWith(".KQ")
                    || "KSC".equals(exchange) || "KOE".equals(exchange)) ? "KR" : "US";

                // 한글명 변환 적용
                String resolvedName = resolveStockName(symbol);
                if (resolvedName != null && !resolvedName.isBlank()) name = resolvedName;

                results.add(StockSearchResultDto.builder()
                    .symbol(symbol)
                    .name(name)
                    .exchange(exchange)
                    .type(type)
                    .market(market)
                    .build());
            }
            log.info("Yahoo Finance 검색 [{}] → {}건", query, results.size());
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
     * KRX 상위 30종목을 Yahoo Finance v8 chart API로 시세 조회 후 섹터별 그룹화
     * KR_SECTOR_MAP에 없는 종목은 "기타" 섹터로 분류
     */
    private List<StockHeatmapSectorDto> fetchHeatmapFromYahoo() {
        List<String[]> krStocks = getKrxStocksCached(30);
        if (krStocks.isEmpty()) {
            log.warn("KRX 종목 조회 실패 — 히트맵 폴백(하드코딩 목록) 사용");
            krStocks = KR_HEATMAP_NAME_FALLBACK.entrySet().stream()
                .map(e -> new String[]{e.getKey(), e.getValue(), "0"})
                .toList();
        }

        ExecutorService exec = Executors.newFixedThreadPool(10);
        List<Future<StockHeatmapItemDto>> futures = krStocks.stream()
            .map(s -> exec.submit(() -> fetchSingleChartStock(s[0], s[1])))
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

        // 섹터별 그룹화 — KRX 반환 순서(시총 순) 유지
        Map<String, List<StockHeatmapItemDto>> bySector = new LinkedHashMap<>();
        for (String[] s : krStocks) {
            StockHeatmapItemDto item = resultMap.get(s[0]);
            if (item == null) continue;
            String sector = KR_SECTOR_MAP.getOrDefault(s[0], "기타");
            bySector.computeIfAbsent(sector, k -> new ArrayList<>()).add(item);
        }

        log.info("히트맵 수집 완료: {}개 섹터, {}개 종목",
            bySector.size(), bySector.values().stream().mapToLong(List::size).sum());

        return bySector.entrySet().stream()
            .map(e -> StockHeatmapSectorDto.builder()
                .sector(e.getKey()).stocks(e.getValue()).build())
            .toList();
    }

    private StockHeatmapItemDto fetchSingleChartStock(String symbol, String name) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
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
                .symbol(symbol)
                .name(name)
                .price(price)
                .changePercent(Math.round(changePct * 100.0) / 100.0)
                .marketCap(mktCap > 0 ? mktCap : 1)
                .build();

        } catch (Exception e) {
            log.warn("v8 chart 조회 실패 [{}]: {}", symbol, e.getMessage());
            return null;
        }
    }

    public List<StockNewsDto> getNews(String market) {
        long now = System.currentTimeMillis();

        if ("KR".equalsIgnoreCase(market)) {
            if (krNewsCache != null && (now - krNewsCacheTime) < NEWS_CACHE_TTL_MS) {
                log.info("뉴스 캐시 히트 [KR]");
                return krNewsCache;
            }
            List<StockNewsDto> fresh = fetchNewsByMarket("KR");
            krNewsCache     = fresh;
            krNewsCacheTime = now;
            return fresh;
        }

        if ("US".equalsIgnoreCase(market)) {
            if (usNewsCache != null && (now - usNewsCacheTime) < NEWS_CACHE_TTL_MS) {
                log.info("뉴스 캐시 히트 [US]");
                return usNewsCache;
            }
            List<StockNewsDto> fresh = fetchNewsByMarket("US");
            // 제목·요약 한글 번역
            List<StockNewsDto> translated = fresh.stream().map(n -> {
                String koTitle = translateToKorean(n.getTitle());
                String koDesc  = n.getDescription() != null ? translateToKorean(n.getDescription()) : null;
                return StockNewsDto.builder()
                        .title(koTitle).link(n.getLink())
                        .description(koDesc).pubDate(n.getPubDate())
                        .source(n.getSource()).market(n.getMarket())
                        .build();
            }).toList();
            usNewsCache     = translated;
            usNewsCacheTime = now;
            return translated;
        }

        // ALL — 두 시장 합산 (번역 포함)
        List<StockNewsDto> all = new ArrayList<>(getNews("KR"));
        all.addAll(getNews("US"));
        return all.stream().limit(30).toList();
    }

    private List<StockNewsDto> fetchNewsByMarket(String market) {
        List<StockNewsDto> all = new ArrayList<>();
        for (String[] src : RSS_SOURCES) {
            if (!src[2].equalsIgnoreCase(market)) continue;
            try { all.addAll(parseRss(src[1], src[0], src[2])); }
            catch (Exception e) { log.warn("RSS 파싱 실패 [{}]: {}", src[0], e.getMessage()); }
        }
        return all.stream().limit(30).toList();
    }

    private String translateToKorean(String text) {
        if (text == null || text.isBlank()) return text;
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://translate.googleapis.com/translate_a/single"
                + "?client=gtx&sl=en&tl=ko&dt=t&q=" + encoded;
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode parts = root.get(0);
            if (parts == null || !parts.isArray()) return text;
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.isArray() && !part.isEmpty()) sb.append(part.get(0).asText(""));
            }
            String result = sb.toString().trim();
            if (result.isEmpty()) return text;
            try {
                result = URLDecoder.decode(result, StandardCharsets.UTF_8);
            } catch (Exception ignore) { /* 디코딩 실패 시 원문 사용 */ }
            return result;
        } catch (Exception e) {
            log.warn("번역 실패: {}", e.getMessage());
            return text;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // KRX 종목 목록 캐시 (검색·히트맵 공용, 6시간 TTL)
    // ─────────────────────────────────────────────────────────────

    /**
     * KRX KOSPI 상위 종목 목록 (캐시). 처음 호출 또는 TTL 만료 시 KRX API 재조회.
     * @param count 필요한 종목 수 (최대 캐시 크기 이하)
     */
    private List<String[]> getKrxStocksCached(int count) {
        long now = System.currentTimeMillis();
        if (krxStocksCache == null || (now - krxStocksCacheTime) > CACHE_TTL_MS) {
            List<String[]> fresh = fetchKRXTopStocks("STK", 100);
            if (!fresh.isEmpty()) {
                krxStocksCache     = fresh;
                krxStocksCacheTime = now;
                log.info("KRX 종목 캐시 갱신: {}개", fresh.size());
            }
        }
        if (krxStocksCache == null) return Collections.emptyList();
        return krxStocksCache.subList(0, Math.min(count, krxStocksCache.size()));
    }

    // ─────────────────────────────────────────────────────────────
    // Yahoo Finance 스크리너 — 미국 시총 상위 종목 동적 조회
    // ─────────────────────────────────────────────────────────────

    /**
     * Yahoo Finance predefined screener "largest_companies" — 미국 시총 Top N
     * 실패 시 빈 리스트 반환 → 호출부에서 US_STOCKS_FALLBACK으로 대체
     */
    private List<String[]> fetchUSTopFromYahoo(int count) {
        try {
            String url = "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved"
                + "?count=" + count + "&scrIds=largest_companies&start=0";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            headers.set("Accept", "application/json, text/plain, */*");
            headers.set("Accept-Language", "en-US,en;q=0.9");
            headers.set("Referer", "https://finance.yahoo.com/");

            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode quotes = objectMapper.readTree(resp.getBody())
                .path("finance").path("result").get(0).path("quotes");

            if (!quotes.isArray() || quotes.size() == 0) return Collections.emptyList();

            List<String[]> result = new ArrayList<>();
            for (JsonNode q : quotes) {
                String symbol = q.path("symbol").asText("").trim();
                String name   = q.path("shortName").asText("").trim();
                if (name.isEmpty()) name = q.path("longName").asText("").trim();
                if (symbol.isEmpty() || name.isEmpty()) continue;
                result.add(new String[]{symbol, name, "0"});
            }
            log.info("Yahoo Finance US 스크리너 조회 → {}개", result.size());
            return result;

        } catch (Exception e) {
            log.warn("Yahoo Finance US 스크리너 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 한글 종목명 변환 — KR(.KS/.KQ): KRX 전종목, US: 주요 종목 매핑
    // ─────────────────────────────────────────────────────────────

    /**
     * 주어진 심볼의 한글명 반환.
     * - .KS/.KQ: KRX KOSPI+KOSDAQ 전종목 캐시 → 하드코딩 폴백
     * - US: US_STOCK_NAMES_KO 매핑
     * - 없으면 null 반환 (호출부에서 원래 이름 유지)
     */
    public String resolveStockName(String symbol) {
        if (symbol == null) return null;
        String upper = symbol.toUpperCase();

        if (upper.endsWith(".KS") || upper.endsWith(".KQ")) {
            buildKrNameLookupIfNeeded();
            if (krNameLookup != null) {
                String name = krNameLookup.get(upper);
                if (name != null) return name;
            }
            return KR_HEATMAP_NAME_FALLBACK.get(symbol);
        }
        return US_STOCK_NAMES_KO.get(upper);
    }

    private void buildKrNameLookupIfNeeded() {
        if (krNameLookup != null
                && (System.currentTimeMillis() - krNameLookupTime) < CACHE_TTL_MS) return;
        buildKrNameLookup();
    }

    private synchronized void buildKrNameLookup() {
        if (krNameLookup != null
                && (System.currentTimeMillis() - krNameLookupTime) < CACHE_TTL_MS) return;

        Map<String, String> map = new java.util.HashMap<>();

        // KOSPI 전종목 (시총 순, 최대 1000 → 실제 ~900개)
        List<String[]> kospi = fetchKRXTopStocks("STK", 1000);
        kospi.forEach(s -> map.put(s[0].toUpperCase(), s[1]));

        // KOSDAQ 전종목 (시총 순, 최대 1500 → 실제 ~1600개)
        List<String[]> kosdaq = fetchKRXTopStocks("KSQ", 1500);
        kosdaq.forEach(s -> map.put(s[0].toUpperCase(), s[1]));

        // 하드코딩 폴백도 병합 (KRX 실패 시 최소 보장)
        KR_HEATMAP_NAME_FALLBACK.forEach((k, v) -> map.putIfAbsent(k.toUpperCase(), v));

        if (!map.isEmpty()) {
            krNameLookup     = map;
            krNameLookupTime = System.currentTimeMillis();
            log.info("KR 종목명 룩업 맵 빌드 완료: KOSPI {}개, KOSDAQ {}개, 총 {}개",
                kospi.size(), kosdaq.size(), map.size());
        }
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
    // KRX(한국거래소) Open API — KOSPI 시총 순위 동적 조회
    // ─────────────────────────────────────────────────────────────

    /**
     * KRX에서 KOSPI 시총 상위 N개 종목을 조회합니다.
     * mktId: "STK"(KOSPI), "KSQ"(KOSDAQ)
     * 최근 7거래일을 순서대로 시도하며 데이터를 가져옵니다.
     */
    private List<String[]> fetchKRXTopStocks(String mktId, int limit) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (int back = 0; back <= 7; back++) {
            LocalDate tryDate = today.minusDays(back);
            DayOfWeek dow = tryDate.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) continue;

            String trdDd = tryDate.format(KRX_DATE_FMT);
            List<String[]> result = tryKRXFetch(mktId, trdDd, limit);
            if (!result.isEmpty()) {
                log.info("KRX {} 시총 순위 조회 성공 (기준일: {}), {}개 종목", mktId, trdDd, result.size());
                return result;
            }
        }
        log.warn("KRX {} 최근 7거래일 데이터 조회 실패", mktId);
        return Collections.emptyList();
    }

    private List<String[]> tryKRXFetch(String mktId, String trdDd, int limit) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Referer",
                "http://data.krx.co.kr/contents/MDC/STAT/standard/MDCSTAT01501.cmd");
            headers.set("Accept", "application/json, text/plain, */*");

            String body = "bld=dbms%2FMDC%2FSTAT%2Fstandard%2FMDCSTAT01501"
                + "&locale=ko_KR&mktId=" + mktId + "&trdDd=" + trdDd
                + "&share=1&money=1&csvxls_isNo=false";

            ResponseEntity<String> resp = restTemplate.exchange(
                KRX_API_URL, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

            JsonNode block = objectMapper.readTree(resp.getBody()).path("OutBlock_1");
            if (!block.isArray() || block.size() == 0) return Collections.emptyList();

            // KRX 응답은 시총 내림차순 정렬
            List<String[]> result = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, block.size()); i++) {
                JsonNode item = block.get(i);
                String code  = item.path("ISU_SRT_CD").asText("").trim();
                String name  = item.path("ISU_ABBRV").asText("").trim();
                // 발행주식수 (Yahoo Finance 시총이 없을 때 백업 계산용)
                String shares = item.path("LIST_SHRS").asText("0").replace(",", "");
                if (code.isEmpty() || name.isEmpty()) continue;
                // Yahoo Finance 심볼: 6자리코드.KS (KOSPI), .KQ (KOSDAQ)
                String yfSymbol = code + ("STK".equals(mktId) ? ".KS" : ".KQ");
                result.add(new String[]{yfSymbol, name, shares});
            }
            return result;

        } catch (Exception e) {
            log.warn("KRX API 조회 실패 [mktId={}, trdDd={}]: {}", mktId, trdDd, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Yahoo Finance v8 chart 병렬 호출 → 시가총액 정렬 → 순위 변동 계산
    // Alpha Vantage 쿼터 소모 없음, 10개 병렬 처리
    // ─────────────────────────────────────────────────────────────

    private List<StockQuoteDto> fetchAll(String cacheKey, List<String[]> stocks, String currency) {
        ExecutorService exec = Executors.newFixedThreadPool(10);
        List<Future<RawQuote>> futures = stocks.stream()
            .map(s -> exec.submit(() -> fetchSingleFromYahoo(
                s[0], s[1], Long.parseLong(s[2]), currency)))
            .toList();

        List<RawQuote> raws = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                RawQuote raw = futures.get(i).get(12, TimeUnit.SECONDS);
                if (raw != null) raws.add(raw);
            } catch (Exception e) {
                log.warn("Top10 종목 조회 실패 [{}]: {}", stocks.get(i)[0], e.getMessage());
            }
        }
        exec.shutdown();

        if (raws.isEmpty()) return Collections.emptyList();

        // 시가총액 내림차순 정렬
        raws.sort(Comparator.comparingLong(RawQuote::marketCap).reversed());

        // 전일 기준 순위(baseRanks)와 비교 — 스케줄 갱신 시에만 갱신되므로
        // 하루 종일 동일한 기준으로 순위 변동 화살표 표시 (전일 대비)
        Map<String, Integer> base     = baseRanks.getOrDefault(cacheKey, Collections.emptyMap());
        List<StockQuoteDto>  result   = new ArrayList<>();

        for (int idx = 0; idx < raws.size(); idx++) {
            RawQuote raw      = raws.get(idx);
            int      newRank  = idx + 1;
            Integer  baseRank = base.get(raw.symbol());
            // baseRank 없음 = 전일에 없던 종목(신규 진입) → 변동 없음으로 표시
            int rankChange    = (baseRank == null) ? 0 : (baseRank - newRank);

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

        return result;
    }

    // Yahoo Finance v8 chart 단일 종목 조회 (Top10용)
    private RawQuote fetchSingleFromYahoo(String symbol, String name, long shares, String currency) {
        try {
            // range=5d: 오늘 데이터가 없어도(휴장·주말) 최근 거래일 meta 반환 보장
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                + "?interval=1d&range=5d";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            headers.set("Accept", "application/json, text/plain, */*");
            headers.set("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8");
            headers.set("Referer", "https://finance.yahoo.com/");

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

            // v8 chart에서 시가총액 없으면 v7 quote API 시도
            if (mktCap == 0) mktCap = fetchMarketCapFromV7(symbol);
            // 그래도 0이면 현재가 × 발행주식수 (폴백)
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

    /** Yahoo Finance v7 quote API — 시가총액 보조 조회 */
    private long fetchMarketCapFromV7(String symbol) {
        try {
            String encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
            String url = "https://query2.finance.yahoo.com/v7/finance/quote?symbols=" + encoded
                + "&fields=marketCap";

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            headers.set("Accept", "application/json, text/plain, */*");
            headers.set("Referer", "https://finance.yahoo.com/");

            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            JsonNode result = objectMapper.readTree(resp.getBody())
                .path("quoteResponse").path("result");
            if (result.isArray() && result.size() > 0) {
                long cap = result.get(0).path("marketCap").asLong(0);
                if (cap > 0) log.debug("v7 시가총액 보조 조회 성공 [{}]: {}", symbol, cap);
                return cap;
            }
        } catch (Exception e) {
            log.debug("v7 시가총액 보조 조회 실패 [{}]: {}", symbol, e.getMessage());
        }
        return 0L;
    }

    // 내부 전달용 레코드
    private record RawQuote(
        String symbol, String name,
        double price, double change, double changePercent,
        long marketCap, long volume) {}

    // ─────────────────────────────────────────────────────────────
    // 뉴스 RSS 파싱
    // ─────────────────────────────────────────────────────────────

    private List<StockNewsDto> parseRss(String url, String sourceName, String market) throws Exception {
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
                .pubDate(pubDate).source(sourceName).market(market).build());
        }
        return news;
    }

    private String getNodeText(Element el, String tagName) {
        NodeList nodes = el.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }
}
