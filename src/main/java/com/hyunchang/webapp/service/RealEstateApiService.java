package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.LandDealDto;
import com.hyunchang.webapp.dto.RealEstateDealDto;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 국토교통부 아파트 실거래가 공개 API 서비스 (data.go.kr). - 매매: GET
 * /1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev - 전월세: GET
 * /1613000/RTMSDataSvcAptRent/getRTMSDataSvcAptRent - 파라미터: serviceKey, LAWD_CD(시군구 5자리),
 * DEAL_YMD(YYYYMM), pageNo, numOfRows - 응답: XML (header/resultCode + body/items/item) - (LAWD_CD,
 * DEAL_YMD)별로 6시간 TTL 캐시. 월세금액 0이면 전세, 양수면 월세로 분류.
 */
@Service
public class RealEstateApiService {

    private static final Logger log = LoggerFactory.getLogger(RealEstateApiService.class);

    private static final String TRADE_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev";
    private static final String RENT_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcAptRent/getRTMSDataSvcAptRent";
    private static final String LAND_TRADE_URL =
            "https://apis.data.go.kr/1613000/RTMSDataSvcLandTrade/getRTMSDataSvcLandTrade";

    private static final int NUM_OF_ROWS = 1000;
    private static final int MAX_PAGES = 5; // 시군구·월별 최대 5000건까지 수집
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    @Value("${app.realestate.service-key:}")
    private String serviceKey;

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // 캐시: "trade|11680|202406" → CacheEntry
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    // 토지 캐시: "land|11680|202406" → LandCacheEntry
    private final Map<String, LandCacheEntry> landCache = new ConcurrentHashMap<>();

    private record CacheEntry(long time, List<RealEstateDealDto> data) {}

    private record LandCacheEntry(long time, List<LandDealDto> data) {}

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 아파트 매매 실거래 조회 (dealType = SALE). */
    public List<RealEstateDealDto> getTrades(String lawdCd, String dealYmd) {
        return getCached("trade", TRADE_URL, lawdCd, dealYmd);
    }

    /** 아파트 전월세 실거래 조회 (dealType = JEONSE | MONTHLY, 월세금액으로 분류). */
    public List<RealEstateDealDto> getRents(String lawdCd, String dealYmd) {
        return getCached("rent", RENT_URL, lawdCd, dealYmd);
    }

    /** 토지 매매 실거래 조회. (시군구·월별 6시간 TTL 캐시) */
    public List<LandDealDto> getLandTrades(String lawdCd, String dealYmd) {
        if (!isConfigured()) {
            log.warn("REALESTATE_API_KEY 미설정 — 토지 실거래가 조회 비활성");
            return Collections.emptyList();
        }
        String key = "land|" + lawdCd + "|" + dealYmd;
        LandCacheEntry hit = landCache.get(key);
        if (hit != null && (System.currentTimeMillis() - hit.time()) < CACHE_TTL_MS) {
            return hit.data();
        }
        List<LandDealDto> result = fetchAllLandPages(lawdCd, dealYmd);
        landCache.put(key, new LandCacheEntry(System.currentTimeMillis(), result));
        return result;
    }

    // ─────────────────────────────────────────────────────────────

    private List<RealEstateDealDto> getCached(
            String kind, String url, String lawdCd, String dealYmd) {
        if (!isConfigured()) {
            log.warn("REALESTATE_API_KEY 미설정 — 부동산 실거래가 조회 비활성");
            return Collections.emptyList();
        }
        String key = kind + "|" + lawdCd + "|" + dealYmd;
        CacheEntry hit = cache.get(key);
        if (hit != null && (System.currentTimeMillis() - hit.time()) < CACHE_TTL_MS) {
            return hit.data();
        }
        List<RealEstateDealDto> result = fetchAllPages(url, lawdCd, dealYmd, "rent".equals(kind));
        // 빈 결과여도(거래 없음) 캐시에 담아 반복 호출 방지
        cache.put(key, new CacheEntry(System.currentTimeMillis(), result));
        return result;
    }

    private List<RealEstateDealDto> fetchAllPages(
            String url, String lawdCd, String dealYmd, boolean isRent) {
        List<RealEstateDealDto> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            try {
                List<RealEstateDealDto> pageItems = fetchPage(url, lawdCd, dealYmd, page, isRent);
                all.addAll(pageItems);
                if (pageItems.size() < NUM_OF_ROWS) break; // 마지막 페이지
            } catch (Exception e) {
                log.warn(
                        "국토부 실거래가 조회 실패 (lawdCd={}, ymd={}, page={}): {}",
                        lawdCd,
                        dealYmd,
                        page,
                        e.getMessage());
                break;
            }
        }
        return all;
    }

    private List<RealEstateDealDto> fetchPage(
            String url, String lawdCd, String dealYmd, int page, boolean isRent) throws Exception {
        String fullUrl =
                url
                        + "?serviceKey="
                        + serviceKey.strip()
                        + "&LAWD_CD="
                        + lawdCd
                        + "&DEAL_YMD="
                        + dealYmd
                        + "&pageNo="
                        + page
                        + "&numOfRows="
                        + NUM_OF_ROWS;

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("국토부 API HTTP {} (lawdCd={}, ymd={})", response.statusCode(), lawdCd, dealYmd);
            return Collections.emptyList();
        }
        return parseItems(response.body(), isRent, lawdCd);
    }

    private List<RealEstateDealDto> parseItems(String xml, boolean isRent, String lawdCd)
            throws Exception {
        if (xml == null || xml.isBlank()) return Collections.emptyList();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        // resultCode 확인 (정상: 00 또는 000). 오류 시 메시지 로깅 후 빈 결과.
        String resultCode = text(doc.getElementsByTagName("resultCode"));
        if (!resultCode.isEmpty() && !resultCode.equals("00") && !resultCode.equals("000")) {
            String msg = text(doc.getElementsByTagName("resultMsg"));
            log.warn("국토부 API 오류 resultCode={} msg={} (lawdCd={})", resultCode, msg, lawdCd);
            return Collections.emptyList();
        }

        NodeList items = doc.getElementsByTagName("item");
        List<RealEstateDealDto> result = new ArrayList<>(items.getLength());

        for (int i = 0; i < items.getLength(); i++) {
            if (items.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element el = (Element) items.item(i);

            String aptNm = get(el, "aptNm");
            String umdNm = get(el, "umdNm");
            String jibun = get(el, "jibun");
            double area = parseDouble(get(el, "excluUseAr"));
            int floor = (int) parseLong(get(el, "floor"));
            int buildYear = (int) parseLong(get(el, "buildYear"));
            int year = (int) parseLong(get(el, "dealYear"));
            int month = (int) parseLong(get(el, "dealMonth"));
            int day = (int) parseLong(get(el, "dealDay"));

            if (aptNm.isBlank() || year == 0) continue;

            String dealDate = String.format("%04d-%02d-%02d", year, month, day);

            RealEstateDealDto.RealEstateDealDtoBuilder b =
                    RealEstateDealDto.builder()
                            .aptName(aptNm)
                            .dong(umdNm)
                            .jibun(jibun)
                            .areaM2(area)
                            .floor(floor)
                            .buildYear(buildYear)
                            .dealDate(dealDate);

            if (isRent) {
                long deposit = parseLong(get(el, "deposit"));
                long monthlyRent = parseLong(get(el, "monthlyRent"));
                b.deposit(deposit)
                        .monthlyRent(monthlyRent)
                        .dealType(monthlyRent > 0 ? "MONTHLY" : "JEONSE");
            } else {
                b.dealAmount(parseLong(get(el, "dealAmount"))).dealType("SALE");
            }
            result.add(b.build());
        }
        return result;
    }

    // ── 토지 ───────────────────────────────────────────────────────

    private List<LandDealDto> fetchAllLandPages(String lawdCd, String dealYmd) {
        List<LandDealDto> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            try {
                String fullUrl =
                        LAND_TRADE_URL
                                + "?serviceKey="
                                + serviceKey.strip()
                                + "&LAWD_CD="
                                + lawdCd
                                + "&DEAL_YMD="
                                + dealYmd
                                + "&pageNo="
                                + page
                                + "&numOfRows="
                                + NUM_OF_ROWS;

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(fullUrl))
                                .timeout(Duration.ofSeconds(15))
                                .GET()
                                .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.warn(
                            "국토부 토지 API HTTP {} (lawdCd={}, ymd={})",
                            response.statusCode(),
                            lawdCd,
                            dealYmd);
                    break;
                }
                List<LandDealDto> pageItems = parseLandItems(response.body(), lawdCd);
                all.addAll(pageItems);
                if (pageItems.size() < NUM_OF_ROWS) break; // 마지막 페이지
            } catch (Exception e) {
                log.warn(
                        "국토부 토지 실거래가 조회 실패 (lawdCd={}, ymd={}, page={}): {}",
                        lawdCd,
                        dealYmd,
                        page,
                        e.getMessage());
                break;
            }
        }
        return all;
    }

    private List<LandDealDto> parseLandItems(String xml, String lawdCd) throws Exception {
        if (xml == null || xml.isBlank()) return Collections.emptyList();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        String resultCode = text(doc.getElementsByTagName("resultCode"));
        if (!resultCode.isEmpty() && !resultCode.equals("00") && !resultCode.equals("000")) {
            String msg = text(doc.getElementsByTagName("resultMsg"));
            log.warn("국토부 토지 API 오류 resultCode={} msg={} (lawdCd={})", resultCode, msg, lawdCd);
            return Collections.emptyList();
        }

        NodeList items = doc.getElementsByTagName("item");
        List<LandDealDto> result = new ArrayList<>(items.getLength());

        for (int i = 0; i < items.getLength(); i++) {
            if (items.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element el = (Element) items.item(i);

            // 토지 매매 API 응답 태그 (라이브 응답 기준으로 확인됨):
            //   umdNm(법정동), jimok(지목), landUse(용도지역), dealArea(거래면적㎡),
            //   dealAmount(거래금액 만원), dealYear/Month/Day, shareDealingType(지분구분)
            String umdNm = get(el, "umdNm");
            String jimok = get(el, "jimok");
            String useZone = firstNonBlank(get(el, "landUse"), get(el, "landUseNm"), get(el, "지역"));
            double area = parseDouble(get(el, "dealArea"));
            long amount = parseLong(get(el, "dealAmount"));
            int year = (int) parseLong(get(el, "dealYear"));
            int month = (int) parseLong(get(el, "dealMonth"));
            int day = (int) parseLong(get(el, "dealDay"));
            String share = firstNonBlank(get(el, "shareDealingType"), get(el, "지분구분"));

            if (year == 0 || amount <= 0) continue;

            String dealDate = String.format("%04d-%02d-%02d", year, month, day);

            result.add(
                    LandDealDto.builder()
                            .dong(umdNm)
                            .jimok(jimok)
                            .useZone(useZone)
                            .areaM2(area)
                            .dealAmount(amount)
                            .dealDate(dealDate)
                            .sharePartition(share)
                            .build());
        }
        return result;
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    // ── XML/숫자 헬퍼 ──────────────────────────────────────────────

    private String get(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0 || nl.item(0).getTextContent() == null) return "";
        return nl.item(0).getTextContent().trim();
    }

    private String text(NodeList nl) {
        if (nl.getLength() == 0 || nl.item(0).getTextContent() == null) return "";
        return nl.item(0).getTextContent().trim();
    }

    private double parseDouble(String text) {
        if (text == null) return 0;
        String clean = text.replace(",", "").trim();
        if (clean.isEmpty()) return 0;
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(String text) {
        if (text == null) return 0;
        String clean = text.replace(",", "").trim();
        if (clean.isEmpty()) return 0;
        try {
            return Long.parseLong(clean);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
