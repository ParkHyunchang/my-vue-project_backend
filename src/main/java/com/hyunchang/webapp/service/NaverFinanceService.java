package com.hyunchang.webapp.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 네이버 금융 HTML 파싱 서비스.
 * - KOSPI/KOSDAQ 시가총액 순위 조회 (세션·로그인 불필요)
 * - 순위, 현재가, 전일비, 등락률, 시가총액, 거래량 포함
 *
 * 출처: https://finance.naver.com/sise/sise_market_sum.naver
 */
@Service
public class NaverFinanceService {

    private static final Logger log = LoggerFactory.getLogger(NaverFinanceService.class);

    private static final String NAVER_SISE_URL =
        "https://finance.naver.com/sise/sise_market_sum.naver?sosok=%s&page=1";

    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L; // 6시간

    // ── 테이블 컬럼 인덱스 (0부터 시작) ────────────────────────────
    // [0] 순위 | [1] 종목명 | [2] 현재가 | [3] 전일비 | [4] 등락률
    // [5] 액면가 | [6] 시가총액 | [7] 상장주식수 | [8] 외국인비율 | [9] 거래량
    private static final int COL_NAME     = 1;
    private static final int COL_PRICE    = 2;
    private static final int COL_CHANGE   = 3;
    private static final int COL_CHANGE_P = 4;
    private static final int COL_MKTCAP   = 6;
    private static final int COL_VOLUME   = 9;
    private static final int MIN_COLS     = 10;

    /** 네이버 금융에서 가져온 종목 데이터 */
    public record NaverStockData(
        String symbol,        // Yahoo Finance 형식: "005930.KS" / "196170.KQ"
        String name,          // 한글 종목명
        double price,         // 현재가 (원)
        double change,        // 전일비 (원, 부호 포함)
        double changePercent, // 등락률 (%, 부호 포함)
        long   marketCap,     // 시가총액 (원)
        long   volume         // 거래량
    ) {}

    // ── 캐시 ────────────────────────────────────────────────────
    private volatile List<NaverStockData> kospiCache      = null;
    private volatile long                 kospiCacheTime  = 0;
    private volatile List<NaverStockData> kosdaqCache     = null;
    private volatile long                 kosdaqCacheTime = 0;

    // ─────────────────────────────────────────────────────────────
    // 공개 메서드
    // ─────────────────────────────────────────────────────────────

    /**
     * KOSPI 시가총액 Top N 조회 (캐시 6h TTL).
     * 실패 시 마지막 성공 캐시 반환, 캐시도 없으면 빈 리스트.
     */
    public List<NaverStockData> getTopStocksKospiCached(int count) {
        long now = System.currentTimeMillis();
        if (kospiCache == null || (now - kospiCacheTime) > CACHE_TTL_MS) {
            List<NaverStockData> fresh = fetchTopStocks("0", count);
            if (!fresh.isEmpty()) {
                kospiCache     = fresh;
                kospiCacheTime = now;
            } else if (kospiCache != null) {
                log.warn("Naver KOSPI 조회 실패 — 이전 캐시 사용 ({}개)", kospiCache.size());
            }
        }
        if (kospiCache == null) return Collections.emptyList();
        return kospiCache.subList(0, Math.min(count, kospiCache.size()));
    }

    /**
     * KOSDAQ 시가총액 Top N 조회 (캐시 6h TTL).
     * 실패 시 마지막 성공 캐시 반환, 캐시도 없으면 빈 리스트.
     */
    public List<NaverStockData> getTopStocksKosdaqCached(int count) {
        long now = System.currentTimeMillis();
        if (kosdaqCache == null || (now - kosdaqCacheTime) > CACHE_TTL_MS) {
            List<NaverStockData> fresh = fetchTopStocks("1", count);
            if (!fresh.isEmpty()) {
                kosdaqCache     = fresh;
                kosdaqCacheTime = now;
            } else if (kosdaqCache != null) {
                log.warn("Naver KOSDAQ 조회 실패 — 이전 캐시 사용 ({}개)", kosdaqCache.size());
            }
        }
        if (kosdaqCache == null) return Collections.emptyList();
        return kosdaqCache.subList(0, Math.min(count, kosdaqCache.size()));
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 메서드
    // ─────────────────────────────────────────────────────────────

    /**
     * 네이버 금융 시총 순위 페이지 HTML 파싱.
     * <p>
     * 네이버 금융 table.type_2 구조:
     * [0]순위 [1]종목명 [2]현재가 [3]전일비 [4]등락률 [5]액면가
     * [6]시가총액 [7]상장주식수 [8]외국인비율 [9]거래량 [10]PER [11]ROE
     */
    private List<NaverStockData> fetchTopStocks(String market, int count) {
        try {
            String url = String.format(NAVER_SISE_URL, market);
            Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                           "AppleWebKit/537.36 (KHTML, like Gecko) " +
                           "Chrome/122.0.0.0 Safari/537.36")
                .header("Referer", "https://finance.naver.com/")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .timeout(15_000)
                .get();

            Elements rows   = doc.select("table.type_2 tbody tr");
            String   suffix = "0".equals(market) ? ".KS" : ".KQ";
            List<NaverStockData> result = new ArrayList<>();

            for (Element row : rows) {
                Elements tds = row.select("td");
                if (tds.size() < MIN_COLS) continue; // 빈 구분 행 건너뜀

                // 종목명 셀에서 코드(6자리)와 이름 추출
                Element nameLink = tds.get(COL_NAME).selectFirst("a[href*=code=]");
                if (nameLink == null) continue;

                Matcher codeMatcher = Pattern.compile("code=(\\d{6})").matcher(nameLink.attr("href"));
                if (!codeMatcher.find()) continue;
                String code = codeMatcher.group(1);
                String name = nameLink.text().trim();
                if (name.isEmpty()) continue;

                double price     = parseNumber(tds.get(COL_PRICE).text());
                double changeAbs = parseNumber(tds.get(COL_CHANGE).text());
                double changePct = parseSignedPercent(tds.get(COL_CHANGE_P).text());
                long   marketCap = parseKoreanMarketCap(tds.get(COL_MKTCAP).text());
                long   volume    = (long) parseNumber(tds.get(COL_VOLUME).text());

                // 전일비 부호: 등락률 부호 기준으로 결정
                double change = (changePct < 0) ? -changeAbs : changeAbs;

                if (price == 0) continue;

                result.add(new NaverStockData(
                    code + suffix, name, price,
                    Math.round(change * 10.0) / 10.0,
                    Math.round(changePct * 100.0) / 100.0,
                    marketCap, volume
                ));

                if (result.size() >= count) break;
            }

            String marketName = "0".equals(market) ? "KOSPI" : "KOSDAQ";
            log.info("Naver Finance {} HTML 파싱 완료: {}개 종목", marketName, result.size());
            return result;

        } catch (Exception e) {
            String marketName = "0".equals(market) ? "KOSPI" : "KOSDAQ";
            log.warn("Naver Finance {} HTML 파싱 실패: {}", marketName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * "71,500" → 71500.0  /  빈 문자열·"N/A" → 0
     */
    private double parseNumber(String text) {
        if (text == null) return 0;
        String clean = text.replace(",", "").trim();
        if (clean.isEmpty() || clean.equals("N/A") || clean.equals("-")) return 0;
        try { return Double.parseDouble(clean); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * 등락률 파싱: "+0.70", "-0.70", "▲0.70", "▽0.70" 등 → ±double
     * 네이버 금융은 ▲/▽ 또는 +/- 기호로 방향을 표시합니다.
     */
    private double parseSignedPercent(String text) {
        if (text == null) return 0;
        String clean = text
            .replace(",", "")
            .replace("▲", "+").replace("△", "+")
            .replace("▽", "-").replace("▼", "-")
            .replace("%", "")
            .trim();
        if (clean.isEmpty() || clean.equals("0.00") || clean.equals("-")) return 0;
        try { return Double.parseDouble(clean); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * 시가총액 파싱.
     * 네이버 금융 테이블은 억원 단위 순수 숫자로 제공: "271,638" → 27,163,800,000,000L
     * 레거시 "조/억" 텍스트 형식도 지원: "424조 8,440억" → 424,844,000,000,000L
     */
    private long parseKoreanMarketCap(String text) {
        if (text == null || text.isBlank() || text.equals("-") || text.equals("N/A")) return 0;

        // "조/억" 텍스트 형식 처리
        boolean hasUnit = false;
        long total = 0;
        Matcher jo = Pattern.compile("([\\d,]+)조").matcher(text);
        if (jo.find()) {
            total += Long.parseLong(jo.group(1).replace(",", "")) * 1_000_000_000_000L;
            hasUnit = true;
        }
        Matcher ok = Pattern.compile("([\\d,]+)억").matcher(text);
        if (ok.find()) {
            total += Long.parseLong(ok.group(1).replace(",", "")) * 100_000_000L;
            hasUnit = true;
        }
        if (hasUnit) return total;

        // 단위 없는 순수 숫자: 억원 단위로 처리 (네이버 금융 기본 형식)
        try {
            return Long.parseLong(text.replace(",", "").trim()) * 100_000_000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
