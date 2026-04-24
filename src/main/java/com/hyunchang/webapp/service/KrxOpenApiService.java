package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * KRX 공식 Open API 서비스.
 * - POST https://data-dbg.krx.co.kr/svc/apis/sto/stk_bydd_trd (KOSPI 일별매매정보)
 * - POST https://data-dbg.krx.co.kr/svc/apis/sto/ksq_bydd_trd (KOSDAQ 일별매매정보)
 * - 인증: AUTH_KEY 헤더, 파라미터: {"basDd":"YYYYMMDD"}
 * - 전체 종목을 한 번에 가져와 6시간 TTL 캐시에 보관. 순위/가격 조회 모두 캐시에서 제공.
 */
@Service
public class KrxOpenApiService {

    private static final Logger log = LoggerFactory.getLogger(KrxOpenApiService.class);

    private static final String KOSPI_URL  = "https://data-dbg.krx.co.kr/svc/apis/sto/stk_bydd_trd";
    private static final String KOSDAQ_URL = "https://data-dbg.krx.co.kr/svc/apis/sto/ksq_bydd_trd";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long CACHE_TTL_MS      = 6 * 60 * 60 * 1000L;
    private static final long FAIL_COOLDOWN_MS  = 10 * 60 * 1000L; // 연속 실패 시 재시도 쿨다운

    @Value("${KRX_API_KEY:}")
    private String apiKey;

    private final HttpClient   httpClient   = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── KOSPI 전체 캐시 (symbol.toUpperCase() → NaverStockData) ──
    private volatile Map<String, NaverFinanceService.NaverStockData> kospiMap  = Collections.emptyMap();
    private volatile long kospiMapTime     = 0;
    private volatile long kospiMapFailTime = 0;

    // ── KOSDAQ 전체 캐시 ──
    private volatile Map<String, NaverFinanceService.NaverStockData> kosdaqMap = Collections.emptyMap();
    private volatile long kosdaqMapTime     = 0;
    private volatile long kosdaqMapFailTime = 0;

    // ─────────────────────────────────────────────────────────────
    // 공개 메서드
    // ─────────────────────────────────────────────────────────────

    /** KOSPI 시가총액 상위 count개 (캐시 기반, 시총 내림차순) */
    public List<NaverFinanceService.NaverStockData> getTopKospiStocks(int count) {
        return topN(getOrLoadKospiMap(), count);
    }

    /** KOSDAQ 시가총액 상위 count개 (캐시 기반, 시총 내림차순) */
    public List<NaverFinanceService.NaverStockData> getTopKosdaqStocks(int count) {
        return topN(getOrLoadKosdaqMap(), count);
    }

    /**
     * KR 종목 시세 직접 조회.
     * .KS → KOSPI 캐시, .KQ → KOSDAQ 캐시에서 반환. 없거나 가격 0이면 null.
     * 거래 없는 날 우선주 등은 캐시에는 있으나 price==0 이므로 시세로는 null 반환.
     */
    public NaverFinanceService.NaverStockData getKrPrice(String symbol) {
        NaverFinanceService.NaverStockData data = lookup(symbol);
        if (data == null || data.price() == 0) return null;
        return data;
    }

    /**
     * KR 종목 한글명 직접 조회 (가격 무관).
     * 거래 없는 날 우선주도 이름은 반환되도록 price 필터링을 하지 않습니다.
     */
    public String getKrName(String symbol) {
        NaverFinanceService.NaverStockData data = lookup(symbol);
        if (data == null) return null;
        String name = data.name();
        return (name != null && !name.isBlank()) ? name : null;
    }

    private NaverFinanceService.NaverStockData lookup(String symbol) {
        if (symbol == null) return null;
        String upper = symbol.toUpperCase();
        if (upper.endsWith(".KS")) return getOrLoadKospiMap().get(upper);
        if (upper.endsWith(".KQ")) return getOrLoadKosdaqMap().get(upper);
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // 캐시 로드 (Double-checked locking)
    // ─────────────────────────────────────────────────────────────

    private Map<String, NaverFinanceService.NaverStockData> getOrLoadKospiMap() {
        if (!isStale(kospiMapTime)) return kospiMap;
        if (inFailCooldown(kospiMapFailTime)) return kospiMap;
        synchronized (this) {
            if (!isStale(kospiMapTime)) return kospiMap;
            if (inFailCooldown(kospiMapFailTime)) return kospiMap;
            List<NaverFinanceService.NaverStockData> all = fetchAllFromKrx(KOSPI_URL, ".KS");
            if (!all.isEmpty()) {
                kospiMap         = toMap(all);
                kospiMapTime     = System.currentTimeMillis();
                kospiMapFailTime = 0;
                log.info("KRX KOSPI 캐시 갱신: {}개 종목", kospiMap.size());
            } else {
                kospiMapFailTime = System.currentTimeMillis();
                log.warn("KRX KOSPI 로드 실패 — {}분 쿨다운 적용", FAIL_COOLDOWN_MS / 60_000);
            }
        }
        return kospiMap;
    }

    private Map<String, NaverFinanceService.NaverStockData> getOrLoadKosdaqMap() {
        if (!isStale(kosdaqMapTime)) return kosdaqMap;
        if (inFailCooldown(kosdaqMapFailTime)) return kosdaqMap;
        synchronized (this) {
            if (!isStale(kosdaqMapTime)) return kosdaqMap;
            if (inFailCooldown(kosdaqMapFailTime)) return kosdaqMap;
            List<NaverFinanceService.NaverStockData> all = fetchAllFromKrx(KOSDAQ_URL, ".KQ");
            if (!all.isEmpty()) {
                kosdaqMap         = toMap(all);
                kosdaqMapTime     = System.currentTimeMillis();
                kosdaqMapFailTime = 0;
                log.info("KRX KOSDAQ 캐시 갱신: {}개 종목", kosdaqMap.size());
            } else {
                kosdaqMapFailTime = System.currentTimeMillis();
                log.warn("KRX KOSDAQ 로드 실패 — {}분 쿨다운 적용", FAIL_COOLDOWN_MS / 60_000);
            }
        }
        return kosdaqMap;
    }

    private boolean inFailCooldown(long failTime) {
        return failTime > 0 && (System.currentTimeMillis() - failTime) < FAIL_COOLDOWN_MS;
    }

    private boolean isStale(long cacheTime) {
        return cacheTime == 0 || (System.currentTimeMillis() - cacheTime) > CACHE_TTL_MS;
    }

    private Map<String, NaverFinanceService.NaverStockData> toMap(
            List<NaverFinanceService.NaverStockData> list) {
        Map<String, NaverFinanceService.NaverStockData> m = new HashMap<>(list.size() * 2);
        list.forEach(s -> m.put(s.symbol().toUpperCase(), s));
        return Collections.unmodifiableMap(m);
    }

    private List<NaverFinanceService.NaverStockData> topN(
            Map<String, NaverFinanceService.NaverStockData> map, int count) {
        if (map.isEmpty()) return Collections.emptyList();
        return map.values().stream()
            .sorted(Comparator.comparingLong(NaverFinanceService.NaverStockData::marketCap).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────
    // KRX API 호출
    // ─────────────────────────────────────────────────────────────

    private List<NaverFinanceService.NaverStockData> fetchAllFromKrx(String url, String suffix) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("여기에")) {
            log.warn("KRX_API_KEY 미설정 — Naver 폴백으로 전환");
            return Collections.emptyList();
        }
        for (LocalDate date : getRecentBusinessDays(7)) {
            try {
                List<NaverFinanceService.NaverStockData> result = callKrxApi(url, date.format(DATE_FMT), suffix);
                if (!result.isEmpty()) {
                    log.info("KRX API {} 조회 성공 (기준일 {}): {}개 종목", suffix, date, result.size());
                    return result;
                }
            } catch (Exception e) {
                log.debug("KRX API 조회 실패 (기준일 {}): {}", date, e.getMessage());
            }
        }
        log.warn("KRX API {} 최근 7 영업일 모두 빈 결과", suffix);
        return Collections.emptyList();
    }

    private List<NaverFinanceService.NaverStockData> callKrxApi(String url, String basDd, String suffix)
            throws Exception {
        String body = "{\"basDd\":\"" + basDd + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("AUTH_KEY", apiKey.strip())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.debug("KRX API HTTP {} (basDd={}): {}", response.statusCode(), basDd,
                response.body().substring(0, Math.min(200, response.body().length())));
            return Collections.emptyList();
        }

        return parseResponse(response.body(), suffix);
    }

    /**
     * KRX API 응답 파싱.
     * OutBlock_1 배열의 모든 종목을 반환 (정렬 없음 — 호출자가 필요 시 정렬).
     * MKTCAP 단위: 백만원 → 원 변환 (× 1,000,000).
     * FLUC_TP_CD: "1"=상승, "2"=하락, "3"=보합.
     */
    private List<NaverFinanceService.NaverStockData> parseResponse(String json, String suffix) throws Exception {
        JsonNode root  = objectMapper.readTree(json);
        JsonNode items = root.path("OutBlock_1");

        if (!items.isArray() || items.isEmpty()) return Collections.emptyList();

        List<NaverFinanceService.NaverStockData> result = new ArrayList<>(items.size());

        for (JsonNode item : items) {
            String shortCode = item.path("ISU_SRT_CD").asText("").trim();
            if (shortCode.length() != 6) continue;

            String name = item.path("ISU_ABBRV").asText("").trim();
            if (name.isEmpty()) continue;

            // 거래 없는 날의 우선주 등은 price==0 일 수 있으나, 이름 룩업용으로
            // 캐시에는 포함시킵니다. 시세 조회는 getKrPrice() 쪽에서 price==0 을 걸러냅니다.
            double price = parseDouble(item.path("TDD_CLSPRC").asText("0"));

            double changeAbs  = parseDouble(item.path("CMPPREVDD_PRC").asText("0"));
            double changeRate = parseDouble(item.path("FLUC_RT").asText("0"));
            String flucTp     = item.path("FLUC_TP_CD").asText("3");

            double change = "2".equals(flucTp) ? -Math.abs(changeAbs) : Math.abs(changeAbs);
            if ("2".equals(flucTp) && changeRate > 0) changeRate = -changeRate;

            long volume = parseLong(item.path("ACC_TRDVOL").asText("0"));
            long mktcap = parseLong(item.path("MKTCAP").asText("0")) * 1_000_000L;

            result.add(new NaverFinanceService.NaverStockData(
                shortCode + suffix, name, price,
                Math.round(change * 10.0) / 10.0,
                Math.round(changeRate * 100.0) / 100.0,
                mktcap, volume
            ));
        }

        return result;
    }

    /** 오늘 포함 최근 n 영업일 (어제부터 역순). 공휴일 미처리 — 빈 응답 시 다음 날짜로 자동 순회. */
    private List<LocalDate> getRecentBusinessDays(int count) {
        List<LocalDate> days = new ArrayList<>(count);
        LocalDate date = LocalDate.now().minusDays(1);
        while (days.size() < count) {
            DayOfWeek dow = date.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) days.add(date);
            date = date.minusDays(1);
        }
        return days;
    }

    private double parseDouble(String text) {
        if (text == null) return 0;
        String clean = text.replace(",", "").trim();
        if (clean.isEmpty() || clean.equals("-")) return 0;
        try { return Double.parseDouble(clean); } catch (NumberFormatException e) { return 0; }
    }

    private long parseLong(String text) {
        if (text == null) return 0;
        String clean = text.replace(",", "").trim();
        if (clean.isEmpty() || clean.equals("-")) return 0;
        try { return Long.parseLong(clean); } catch (NumberFormatException e) { return 0; }
    }
}
