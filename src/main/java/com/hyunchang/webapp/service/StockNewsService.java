package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.StockNewsDto;
import com.hyunchang.webapp.service.news.RssClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.client.RestClientException;

/**
 * 주식 뉴스 서비스.
 * - 국내/해외 RSS 피드 수집 및 파싱
 * - 해외 뉴스 한글 자동 번역 (Google Translate 비공식 API)
 * - 20분 인메모리 캐시 (동시성: synchronized 블록으로 중복 fetch 방지)
 */
@Service
public class StockNewsService {

    private static final Logger log = LoggerFactory.getLogger(StockNewsService.class);
    private static final long NEWS_CACHE_TTL_MS    = 20 * 60 * 1000L; // 20분
    private static final int  PER_SOURCE_LIMIT    = 7;               // 소스당 최대 기사 수 (주식 관련만)
    private static final int  TOTAL_LIMIT          = 50;              // 시장당 최대 기사 수
    private static final int  FETCH_TIMEOUT_SEC    = 8;              // 소스별 타임아웃
    private static final int  MAX_ARTICLE_AGE_DAYS = 7;              // 7일 이상 된 기사 제외

    // #5 공유 스레드풀 — 요청마다 새 풀 생성하지 않음
    private static final ExecutorService SHARED_POOL = Executors.newFixedThreadPool(10);

    // ── 주식 관련성 필터 키워드 ─────────────────────────────
    // 판별 로직: ① 제목 제외패턴 → ② 제목 키워드 1개↑ 즉시통과 → ③ 설명 키워드 2개↑ 통과
    private static final Set<String> KR_STOCK_KEYWORDS = Set.of(
        // 시장/지수
        "주가", "주식", "증시", "코스피", "코스닥", "코넥스", "나스닥", "다우", "s&p",
        // 거래행위
        "매수", "매도", "거래량", "상한가", "하한가", "공매도",
        "기관 매수", "기관 순매", "기관투자", "외국인 순매", "외국인 매수", "외국인 매도",
        // 상품/구조
        "종목", "공모주", "상장", "etf", "펀드", "배당", "채권", "선물", "옵션",
        "자사주", "공시", "주주", "시가총액", "시총", "ipo", "기업공개",
        // 실적/지표
        "영업이익", "순이익", "실적", "per", "pbr", "roe",
        // 거시경제 (투자에 직결)
        "금리", "환율", "증권"
        // ※ 제거: "공모"(공모 여부=범죄), "기관"(정부기관 오탐), "장외"(모호), "투자자"(범용)
    );

    private static final Set<String> US_STOCK_KEYWORDS = Set.of(
        // 핵심 주식 용어
        "stock", "stocks", "shares", "share price", "stock market", "stock price",
        "nasdaq", "nyse", "dow jones", "s&p 500", "s&p500",
        // 거래/수익
        "earnings", "dividend", "ipo", "equity", "wall street",
        "market cap", "short selling", "buyback", "trading volume",
        // 상품
        "etf", "mutual fund", "hedge fund", "bond yield", "treasury yield",
        // 거시 (투자에 직결)
        "federal reserve", "interest rate", "inflation rate",
        // 시장 방향성
        "bull market", "bear market", "market rally", "market crash",
        "market correction", "sell-off", "selloff"
        // ※ 제거: "trading"(무역협정 오탐), "dow"(Dow Chemical), "correction"(정정기사)
        //         "recession"(정치), "inflation"(일반경제), "portfolio"(개인금융)
    );

    // 뉴스 종합·브리핑 형식 제목 패턴 — 여러 분야 혼합이므로 무조건 제외
    private static final Set<String> KR_EXCLUDE_TITLE = Set.of(
        "헤드라인", "뉴스브리핑", "뉴스 브리핑", "오늘의 뉴스", "이 시각 뉴스",
        "주요 뉴스", "뉴스 요약", "아침 뉴스", "저녁 뉴스"
    );

    // 뉴스 RSS 소스 — {출처명, URL, 시장(KR|US)}
    // 제거됨(2026-05-12): 머니투데이/이데일리/서울경제/아시아경제 — RSS URL 만료·TLS·DNS 문제로 응답 불가
    // 제거됨(2026-05-15): AP News(DNS 해석 불가)·Investopedia(Cloudflare 403 차단) — 다른 소스로 충분히 보충됨
    private static final List<String[]> RSS_SOURCES = List.of(
        // ── 국내 (KR) ────────────────────────────────────────────────
        new String[]{"한국경제",   "https://www.hankyung.com/feed/finance",                                              "KR"},
        new String[]{"연합뉴스",   "https://www.yna.co.kr/rss/economy.xml",                                              "KR"},
        new String[]{"매일경제",   "https://www.mk.co.kr/rss/40300001/",                                                 "KR"},
        new String[]{"Google News KR 증시", googleNewsRss("증시 OR 코스피 OR 코스닥 OR 주가 OR 실적", "ko", "KR", "ko-KR"), "KR"},
        new String[]{"Google News KR 경제지", googleNewsRss("주식 OR 투자 OR 증권 site:hankyung.com OR site:mk.co.kr OR site:sedaily.com OR site:edaily.co.kr OR site:news.mt.co.kr", "ko", "KR", "ko-KR"), "KR"},
        // ── 해외 (US) ────────────────────────────────────────────────
        new String[]{"Yahoo Finance", "https://finance.yahoo.com/rss/topfinstories",                                     "US"},
        new String[]{"MarketWatch",   "https://feeds.marketwatch.com/marketwatch/topstories/",                          "US"},
        new String[]{"CNBC",          "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=15839069", "US"},
        new String[]{"Motley Fool",   "https://www.fool.com/feeds/index.aspx",                                           "US"},
        new String[]{"Google News US Markets", googleNewsRss("stocks OR earnings OR \"S&P 500\" OR Nasdaq", "en", "US", "en-US"), "US"},
        new String[]{"Google News US Financial Press", googleNewsRss("stock OR earnings site:reuters.com OR site:cnbc.com OR site:marketwatch.com OR site:barrons.com OR site:fool.com", "en", "US", "en-US"), "US"}
    );

    // #4 뉴스 캐시 + 동시성 보호 락
    private volatile List<StockNewsDto> krNewsCache     = null;
    private volatile long               krNewsCacheTime = 0;
    private volatile List<StockNewsDto> usNewsCache     = null;
    private volatile long               usNewsCacheTime = 0;

    private final Object krLock = new Object();
    private final Object usLock = new Object();

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RssClient rssClient;

    public StockNewsService(RestTemplate restTemplate, ObjectMapper objectMapper, RssClient rssClient) {
        this.restTemplate = restTemplate;
        this.objectMapper  = objectMapper;
        this.rssClient = rssClient;
    }

    @PreDestroy
    public void destroy() {
        SHARED_POOL.shutdown();
    }

    /**
     * 시장별 뉴스 조회. market: "KR" | "US" | 그 외(전체).
     * 캐시 TTL(20분) 이내면 캐시 반환, 만료 시 RSS 재수집.
     * #4: synchronized 블록으로 동시 요청 시 중복 fetch 방지.
     */
    public List<StockNewsDto> getNews(String market, boolean force) {
        long now = System.currentTimeMillis();

        if ("KR".equalsIgnoreCase(market)) {
            synchronized (krLock) {
                if (!force && krNewsCache != null && (now - krNewsCacheTime) < NEWS_CACHE_TTL_MS) {
                    log.info("뉴스 캐시 히트 [KR]");
                    return krNewsCache;
                }
                if (force) log.info("뉴스 캐시 강제 갱신 [KR]");
                List<StockNewsDto> fresh = fetchByMarket("KR");
                krNewsCache     = fresh;
                krNewsCacheTime = System.currentTimeMillis();
                return fresh;
            }
        }

        if ("US".equalsIgnoreCase(market)) {
            synchronized (usLock) {
                if (!force && usNewsCache != null && (now - usNewsCacheTime) < NEWS_CACHE_TTL_MS) {
                    log.info("뉴스 캐시 히트 [US]");
                    return usNewsCache;
                }
                if (force) log.info("뉴스 캐시 강제 갱신 [US]");
                List<StockNewsDto> fresh = fetchByMarket("US");
                // 번역을 SHARED_POOL에서 병렬 처리
                List<CompletableFuture<StockNewsDto>> futures = fresh.stream().map(n ->
                    CompletableFuture.supplyAsync(() -> {
                        String translatedDesc = n.getDescription() != null
                            ? translateToKorean(n.getDescription()) : null;
                        if (translatedDesc != null && translatedDesc.length() > 300) {
                            translatedDesc = translatedDesc.substring(0, 300) + "…";
                        }
                        return StockNewsDto.builder()
                            .title(translateToKorean(n.getTitle()))
                            .originalTitle(n.getTitle())
                            .link(n.getLink())
                            .description(translatedDesc)
                            .originalDescription(n.getDescription())
                            .pubDate(n.getPubDate())
                            .source(n.getSource())
                            .market(n.getMarket())
                            .imageUrl(n.getImageUrl())
                            .build();
                    }, SHARED_POOL)
                ).toList();
                List<StockNewsDto> translated = futures.stream().map(f -> {
                    try { return f.get(30, TimeUnit.SECONDS); }
                    catch (InterruptedException | ExecutionException | TimeoutException e) {
                        log.warn("번역 타임아웃: {}", e.getMessage());
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        return null;
                    }
                }).filter(Objects::nonNull).toList();
                usNewsCache     = translated;
                usNewsCacheTime = System.currentTimeMillis();
                return translated;
            }
        }

        // ALL — 두 시장 합산
        List<StockNewsDto> all = new ArrayList<>(getNews("KR", false));
        all.addAll(getNews("US", false));
        return all.stream().limit(30).toList();
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 메서드
    // ─────────────────────────────────────────────────────────────

    // #5 공유 스레드풀 사용 (요청마다 새 ExecutorService 생성/종료 제거)
    private List<StockNewsDto> fetchByMarket(String market) {
        List<String[]> sources = RSS_SOURCES.stream()
            .filter(src -> src[2].equalsIgnoreCase(market))
            .toList();

        List<Future<List<StockNewsDto>>> futures = sources.stream()
            .map(src -> SHARED_POOL.submit(() -> {
                try { return parseRss(src[1], src[0], src[2]); }
                catch (Exception e) {
                    log.warn("RSS 파싱 실패 [{}]: {}", src[0], summarize(e));
                    return Collections.<StockNewsDto>emptyList();
                }
            }))
            .toList();

        List<StockNewsDto> all = new ArrayList<>();
        for (Future<List<StockNewsDto>> f : futures) {
            try { all.addAll(f.get(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)); }
            catch (InterruptedException | ExecutionException | TimeoutException e) {
                log.warn("RSS 소스 타임아웃 또는 오류: {}", summarize(e));
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        // 최신 기사 우선 정렬
        all.sort(Comparator.comparingLong(this::parsePubDateEpoch).reversed());
        return all.stream().limit(TOTAL_LIMIT).toList();
    }

    private long parsePubDateEpoch(StockNewsDto news) {
        String pubDate = news.getPubDate();
        if (pubDate == null || pubDate.isBlank()) return 0L;
        return parsePubDateStringEpoch(pubDate);
    }

    private List<StockNewsDto> parseRss(String url, String sourceName, String market) throws Exception {
        List<RssClient.RssItem> items = rssClient.fetchItems(url);
        List<StockNewsDto> news = new ArrayList<>();

        long maxAgeSeconds = (long) MAX_ARTICLE_AGE_DAYS * 24 * 3600;
        long nowEpoch      = System.currentTimeMillis() / 1000;

        // #6 US 뉴스는 번역 품질 향상을 위해 더 긴 원문 허용 (600자), KR은 300자
        int descLimit = "US".equalsIgnoreCase(market) ? 600 : 300;

        // PER_SOURCE_LIMIT개의 주식 관련 기사를 채울 때까지 피드 전체를 순회
        for (RssClient.RssItem item : items) {
            if (news.size() >= PER_SOURCE_LIMIT) break;
            String  title   = normalizeText(item.title());
            String  link    = item.link();
            String  desc    = normalizeText(item.description());
            String  pubDate = item.pubDate();
            String  itemSource = item.itemSource();

            if (title.isBlank()) continue;

            // 오래된 기사 제외 (날짜 파싱 실패 시 통과)
            if (!pubDate.isBlank()) {
                long pubEpoch = parsePubDateStringEpoch(pubDate);
                if (pubEpoch > 0 && (nowEpoch - pubEpoch) > maxAgeSeconds) {
                    log.debug("오래된 기사 제외 [{}]: {}", sourceName, title);
                    continue;
                }
            }

            if (!isStockRelated(title, desc, market)) {
                log.debug("주식 무관 기사 제외 [{}]: {}", sourceName, title);
                continue;
            }

            // HTML 태그 제거 후 길이 제한 (#6 시장별 한도 다르게)
            desc = RssClient.stripHtml(desc).trim();
            if (desc.length() > descLimit) desc = desc.substring(0, descLimit) + "…";

            news.add(StockNewsDto.builder()
                .title(title).link(link).description(desc)
                .pubDate(pubDate).source(RssClient.mergeSource(sourceName, itemSource)).market(market)
                .imageUrl(item.imageUrl())
                .build());
        }
        return news;
    }

    private boolean isStockRelated(String title, String description, String market) {
        String titleLower = title.toLowerCase();
        String descLower  = (description == null ? "" : description).toLowerCase();
        Set<String> keywords = "KR".equalsIgnoreCase(market) ? KR_STOCK_KEYWORDS : US_STOCK_KEYWORDS;

        // ① KR 전용: 뉴스 종합·브리핑 형식 제목은 무조건 제외
        if ("KR".equalsIgnoreCase(market) &&
                KR_EXCLUDE_TITLE.stream().anyMatch(titleLower::contains)) {
            log.debug("뉴스 종합 형식 제외: {}", title);
            return false;
        }

        // ② 제목에 키워드 1개 이상 → 주식 기사로 즉시 확정
        if (keywords.stream().anyMatch(titleLower::contains)) return true;

        // ③ 제목에 없으면 설명에서 키워드 2개 이상 필요 (단일 우연 매칭 방지)
        long descMatches = keywords.stream().filter(descLower::contains).count();
        return descMatches >= 2;
    }

    private static String googleNewsRss(String query, String langPrefix, String gl, String hl) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return "https://news.google.com/rss/search?q=" + encoded
                + "&hl=" + hl + "&gl=" + gl + "&ceid=" + gl + ":" + langPrefix;
    }

    private long parsePubDateStringEpoch(String pubDate) {
        return RssClient.parsePubDateEpoch(pubDate);
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
            JsonNode parts = objectMapper.readTree(resp.getBody()).get(0);
            if (parts == null || !parts.isArray()) return text;
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.isArray() && !part.isEmpty()) sb.append(part.get(0).asText(""));
            }
            String result = sb.toString().trim();
            if (result.isEmpty()) return text;
            try { result = URLDecoder.decode(result, StandardCharsets.UTF_8); }
            catch (IllegalArgumentException ignore) {}
            return result;
        } catch (RestClientException | IOException e) {
            log.warn("번역 실패: {}", summarize(e));
            return text;
        }
    }

    // HTTP 4xx/5xx 응답이 HTML 본문 전체를 메시지에 담는 경우(특히 RestTemplate)
    // 로그 한 줄이 수십 KB로 폭주하는 것을 막기 위해 200자로 절단.
    private static String summarize(Throwable t) {
        if (t == null) return "(null)";
        String msg = t.getMessage();
        String type = t.getClass().getSimpleName();
        if (msg == null || msg.isBlank()) return type;
        msg = msg.replaceAll("\\s+", " ").trim();
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return type + ": " + msg;
    }

    // 곱슬 따옴표·HTML 엔티티 → 표준 문자 정규화 (구글 번역 "aa" 아티팩트 방지)
    private static String normalizeText(String text) {
        if (text == null) return "";
        return text
            .replace('‘', '\'').replace('’', '\'')   // 오른쪽/왼쪽 단따옴표
            .replace('“', '"') .replace('”', '"')    // 오른쪽/왼쪽 쌍따옴표
            .replace('–', '-') .replace('—', '-')    // 엔 대시 / 엠 대시
            .replace("&#8217;", "'").replace("&#8216;", "'")   // HTML 엔티티 단따옴표
            .replace("&#8220;", "\"").replace("&#8221;", "\"") // HTML 엔티티 쌍따옴표
            .replace("&#8211;", "-").replace("&#8212;", "-")   // HTML 엔티티 대시
            .replace("&rsquo;", "'").replace("&lsquo;", "'")
            .replace("&rdquo;", "\"").replace("&ldquo;", "\"")
            .replace("&ndash;", "-").replace("&mdash;", "-")
            .replace("&amp;", "&").replace("&nbsp;", " ");
    }

}
