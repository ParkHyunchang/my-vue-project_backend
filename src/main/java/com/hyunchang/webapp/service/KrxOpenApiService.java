package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.util.TtlCache;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * KRX 공식 Open API 서비스. - POST https://data-dbg.krx.co.kr/svc/apis/sto/stk_bydd_trd (KOSPI 일별매매정보) -
 * POST https://data-dbg.krx.co.kr/svc/apis/sto/ksq_bydd_trd (KOSDAQ 일별매매정보) - 인증: AUTH_KEY 헤더,
 * 파라미터: {"basDd":"YYYYMMDD"} - 전체 종목을 한 번에 가져와 6시간 TTL 캐시에 보관. 순위/가격 조회 모두 캐시에서 제공.
 */
@Service
public class KrxOpenApiService {

    private static final Logger log = LoggerFactory.getLogger(KrxOpenApiService.class);

    private static final String KOSPI_URL = "https://data-dbg.krx.co.kr/svc/apis/sto/stk_bydd_trd";
    private static final String KOSDAQ_URL = "https://data-dbg.krx.co.kr/svc/apis/sto/ksq_bydd_trd";
    private static final String ETF_URL = "https://data-dbg.krx.co.kr/svc/apis/etp/etf_bydd_trd";
    private static final String ETN_URL = "https://data-dbg.krx.co.kr/svc/apis/etp/etn_bydd_trd";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long SWING_SCREEN_CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final int SWING_LOOKBACK_DAYS = 20;
    private static final int SWING_HISTORY_FETCH_DAYS = 35;
    private static final double SWING_MIN_CHANGE_PCT = 3.0;
    private static final double SWING_MIN_VOLUME_RATIO = 3.0;
    // 콜드 캐시 시 일자별 히스토리 수집 동시 실행 수 — 다른 외부 API 병렬 풀(NEWS_POOL 등)과
    // 동일하게 8로 맞춰 KRX 쪽 부하를 과하게 늘리지 않는다.
    private static final int HISTORY_FETCH_PARALLELISM = 8;
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long FAIL_COOLDOWN_MS = 10 * 60 * 1000L; // 연속 실패 시 재시도 쿨다운

    @Value("${KRX_API_KEY:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── KOSPI 전체 캐시 (symbol.toUpperCase() → NaverStockData) ──
    private volatile Map<String, NaverFinanceService.NaverStockData> kospiMap =
            Collections.emptyMap();
    private volatile long kospiMapTime = 0;
    private volatile long kospiMapFailTime = 0;

    // ── KOSDAQ 전체 캐시 ──
    private volatile Map<String, NaverFinanceService.NaverStockData> kosdaqMap =
            Collections.emptyMap();
    private volatile long kosdaqMapTime = 0;
    private volatile long kosdaqMapFailTime = 0;

    // ── ETF+ETN(ETP) 통합 캐시 (검색·이름·시세 룩업용) ──
    private volatile Map<String, NaverFinanceService.NaverStockData> etpMap =
            Collections.emptyMap();
    private volatile Set<String> etnSymbols = Collections.emptySet(); // ETP 중 ETN 심볼(타입 구분용)
    private volatile long etpMapTime = 0;
    private volatile long etpMapFailTime = 0;

    private volatile List<KrSwingCandidate> swingCandidatesCache = Collections.emptyList();
    private volatile long swingCandidatesCacheTime = 0;
    // 스윙 스크리닝 전용 락 — KOSPI/KOSDAQ/ETP 캐시 로드(synchronized(this))와 분리해서
    // 히스토리 수집이 오래 걸려도 앱 전체의 KR 시세 조회가 같이 막히지 않게 한다.
    private final Object swingScreenLock = new Object();
    // 일자별 전체 시장 세션 캐시 — 과거 거래일 데이터는 불변이므로 길게 보관하고,
    // 빈 응답(공휴일·데이터 미발행)은 짧은 negative 캐시로 두어 발행 후 자동으로 채워진다.
    // 덕분에 스크리닝 갱신 시 신규 발행된 1~2일치만 KRX API를 호출한다.
    private final TtlCache<LocalDate, Map<String, NaverFinanceService.NaverStockData>>
            swingSessionCache = new TtlCache<>(Duration.ofDays(30), Duration.ofMinutes(30));
    private static final ExecutorService HISTORY_POOL =
            Executors.newFixedThreadPool(HISTORY_FETCH_PARALLELISM);

    @PreDestroy
    void shutdownHistoryPool() {
        HISTORY_POOL.shutdown();
    }

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
     * 상장 ETF+ETN 전체 목록 (정렬 없음). 검색 풀(이름 룩업)용 — 시총 순위가 필요 없으므로 전량 반환합니다. 신규 상장 레버리지/단일종목 ETN 등도 KRX가
     * 제공하는 즉시 포함됩니다.
     */
    public List<NaverFinanceService.NaverStockData> getAllEtp() {
        return new ArrayList<>(getOrLoadEtpMap().values());
    }

    /**
     * 단기 스윙 후보용 KRX 1차 스크리닝입니다. 최근 종가 기준으로 당일 3% 이상 상승하고 당일 거래량이 직전 20거래일 평균의 3배 이상인 종목만 반환합니다. 계산은
     * 서버에서 끝내고 LLM에는 압축된 후보만 전달해 근거 없는 추천을 줄입니다.
     */
    public List<KrSwingCandidate> getShortSwingCandidates(int limit) {
        if (limit <= 0 || !hasApiKey()) return List.of();
        if (!isStale(swingCandidatesCacheTime, SWING_SCREEN_CACHE_TTL_MS)) {
            return swingCandidatesCache.stream().limit(limit).toList();
        }

        synchronized (swingScreenLock) {
            if (isStale(swingCandidatesCacheTime, SWING_SCREEN_CACHE_TTL_MS)) {
                swingCandidatesCache = collectShortSwingCandidates();
                swingCandidatesCacheTime = System.currentTimeMillis();
            }
        }
        return swingCandidatesCache.stream().limit(limit).toList();
    }

    private List<KrSwingCandidate> collectShortSwingCandidates() {
        // 날짜별로 배치 병렬 수집 — 콜드 캐시(재시작 직후·30일 경과)에서는 21거래일치를
        // 순차 호출하면 KRX 호출당 지연(수 초)이 그대로 누적돼 전체 수집이 수 분 걸린다.
        // 배치 크기만큼 동시에 쏘고, 필요한 세션 수를 채우면 다음 배치를 시작하지 않는다.
        Map<LocalDate, Map<String, NaverFinanceService.NaverStockData>> sessions =
                new LinkedHashMap<>();
        List<LocalDate> businessDays = getRecentBusinessDays(SWING_HISTORY_FETCH_DAYS);
        for (int start = 0;
                start < businessDays.size() && sessions.size() < SWING_LOOKBACK_DAYS + 1;
                start += HISTORY_FETCH_PARALLELISM) {
            List<LocalDate> batch =
                    businessDays.subList(
                            start,
                            Math.min(start + HISTORY_FETCH_PARALLELISM, businessDays.size()));
            Map<LocalDate, CompletableFuture<Map<String, NaverFinanceService.NaverStockData>>>
                    futures = new LinkedHashMap<>();
            for (LocalDate date : batch) {
                futures.put(
                        date,
                        CompletableFuture.supplyAsync(() -> loadSwingSession(date), HISTORY_POOL));
            }
            for (var entry : futures.entrySet()) {
                Map<String, NaverFinanceService.NaverStockData> combined;
                try {
                    combined = entry.getValue().join();
                } catch (Exception e) {
                    log.debug(
                            "KRX swing screening batch fetch failed for {}: {}",
                            entry.getKey(),
                            e.getMessage());
                    continue;
                }
                if (combined != null && !combined.isEmpty()) sessions.put(entry.getKey(), combined);
            }
        }

        if (sessions.size() < SWING_LOOKBACK_DAYS + 1) {
            log.warn(
                    "KRX swing screening skipped: only {} trading sessions available",
                    sessions.size());
            return List.of();
        }

        Map.Entry<LocalDate, Map<String, NaverFinanceService.NaverStockData>> latest =
                sessions.entrySet().iterator().next();
        List<Map<String, NaverFinanceService.NaverStockData>> history =
                sessions.entrySet().stream()
                        .skip(1)
                        .limit(SWING_LOOKBACK_DAYS)
                        .map(Map.Entry::getValue)
                        .toList();

        return latest.getValue().values().stream()
                .map(stock -> toSwingCandidate(stock, latest.getKey(), history))
                .filter(candidate -> candidate != null)
                .sorted(
                        Comparator.comparingDouble(KrSwingCandidate::volumeRatio)
                                .thenComparingDouble(KrSwingCandidate::changePercent)
                                .reversed())
                .toList();
    }

    /**
     * 스크리닝용 일자별 세션 로드 (KOSPI+KOSDAQ 병합). 캐시 우선이며, 빈 응답은 negative 캐시로 짧게 기억해 공휴일 반복 조회를 막는다. 네트워크
     * 오류는 캐시하지 않고 다음 갱신에서 재시도한다.
     */
    private Map<String, NaverFinanceService.NaverStockData> loadSwingSession(LocalDate date) {
        TtlCache.Hit<Map<String, NaverFinanceService.NaverStockData>> hit =
                swingSessionCache.lookup(date);
        if (hit != null) return hit.negative() ? null : hit.value();

        try {
            Map<String, NaverFinanceService.NaverStockData> combined = new HashMap<>();
            callKrxApi(KOSPI_URL, date.format(DATE_FMT), ".KS")
                    .forEach(s -> combined.put(s.symbol().toUpperCase(), s));
            callKrxApi(KOSDAQ_URL, date.format(DATE_FMT), ".KQ")
                    .forEach(s -> combined.put(s.symbol().toUpperCase(), s));
            if (combined.isEmpty()) {
                swingSessionCache.putNegative(date);
                return null;
            }
            Map<String, NaverFinanceService.NaverStockData> frozen =
                    Collections.unmodifiableMap(combined);
            swingSessionCache.put(date, frozen);
            return frozen;
        } catch (IOException | InterruptedException e) {
            log.debug("KRX swing screening history unavailable for {}: {}", date, e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private KrSwingCandidate toSwingCandidate(
            NaverFinanceService.NaverStockData stock,
            LocalDate asOf,
            List<Map<String, NaverFinanceService.NaverStockData>> history) {
        if (stock.price() <= 0
                || stock.volume() <= 0
                || stock.changePercent() < SWING_MIN_CHANGE_PCT) {
            return null;
        }
        List<Long> volumes =
                history.stream()
                        .map(day -> day.get(stock.symbol().toUpperCase()))
                        .filter(item -> item != null && item.volume() > 0)
                        .map(NaverFinanceService.NaverStockData::volume)
                        .toList();
        if (volumes.size() < SWING_LOOKBACK_DAYS) return null;

        double averageVolume = volumes.stream().mapToLong(Long::longValue).average().orElse(0);
        if (averageVolume <= 0) return null;
        double volumeRatio = stock.volume() / averageVolume;
        if (volumeRatio < SWING_MIN_VOLUME_RATIO) return null;

        return new KrSwingCandidate(
                stock.symbol(),
                stock.name(),
                stock.symbol().endsWith(".KQ") ? "KOSDAQ" : "KOSPI",
                stock.price(),
                stock.changePercent(),
                stock.volume(),
                Math.round(averageVolume),
                Math.round(volumeRatio * 100.0) / 100.0,
                asOf);
    }

    public record KrSwingCandidate(
            String symbol,
            String name,
            String market,
            double closePrice,
            double changePercent,
            long volume,
            long average20DayVolume,
            double volumeRatio,
            LocalDate asOf) {}

    /** 해당 심볼이 ETN인지 여부 (검색 결과 타입 라벨 구분용). ETP 캐시 로드 후 판정. */
    public boolean isEtn(String symbol) {
        if (symbol == null) return false;
        getOrLoadEtpMap();
        return etnSymbols.contains(symbol.toUpperCase());
    }

    /**
     * KR 종목 시세 직접 조회. .KS → KOSPI 캐시, .KQ → KOSDAQ 캐시에서 반환. 없거나 가격 0이면 null. 거래 없는 날 우선주 등은 캐시에는
     * 있으나 price==0 이므로 시세로는 null 반환.
     */
    public NaverFinanceService.NaverStockData getKrPrice(String symbol) {
        NaverFinanceService.NaverStockData data = lookup(symbol);
        if (data == null || data.price() == 0) return null;
        return data;
    }

    /**
     * KR 종목 시가총액 직접 조회 (원 단위). 캐시 미스 시 0. 가격 0 우선주에도 시총은 존재할 수 있으므로 price 필터링을 하지 않습니다. Yahoo
     * v8/chart 폴백이 한국 종목 marketCap을 비워 보내는 경우의 보강용.
     */
    public long getKrMarketCap(String symbol) {
        NaverFinanceService.NaverStockData data = lookup(symbol);
        return data != null ? data.marketCap() : 0;
    }

    /** KR 종목 한글명 직접 조회 (가격 무관). 거래 없는 날 우선주도 이름은 반환되도록 price 필터링을 하지 않습니다. */
    public String getKrName(String symbol) {
        NaverFinanceService.NaverStockData data = lookup(symbol);
        if (data == null) return null;
        String name = data.name();
        return (name != null && !name.isBlank()) ? name : null;
    }

    private NaverFinanceService.NaverStockData lookup(String symbol) {
        if (symbol == null) return null;
        String upper = symbol.toUpperCase();
        NaverFinanceService.NaverStockData data = null;
        if (upper.endsWith(".KS")) data = getOrLoadKospiMap().get(upper);
        else if (upper.endsWith(".KQ")) data = getOrLoadKosdaqMap().get(upper);
        // 주식 캐시에 없으면 ETF/ETN(ETP) 캐시에서 보강 — ETF/ETN도 .KS/.KQ 접미사를 씁니다.
        if (data == null) data = getOrLoadEtpMap().get(upper);
        return data;
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
                kospiMap = toMap(all);
                kospiMapTime = System.currentTimeMillis();
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
                kosdaqMap = toMap(all);
                kosdaqMapTime = System.currentTimeMillis();
                kosdaqMapFailTime = 0;
                log.info("KRX KOSDAQ 캐시 갱신: {}개 종목", kosdaqMap.size());
            } else {
                kosdaqMapFailTime = System.currentTimeMillis();
                log.warn("KRX KOSDAQ 로드 실패 — {}분 쿨다운 적용", FAIL_COOLDOWN_MS / 60_000);
            }
        }
        return kosdaqMap;
    }

    /**
     * ETF+ETN 통합 캐시 로드. 두 ETP 엔드포인트를 각각 호출해 병합합니다. ETF/ETN은 모두 유가증권시장 상장이라 Yahoo 접미사로 .KS를 부여합니다.
     */
    private Map<String, NaverFinanceService.NaverStockData> getOrLoadEtpMap() {
        if (!isStale(etpMapTime)) return etpMap;
        if (inFailCooldown(etpMapFailTime)) return etpMap;
        synchronized (this) {
            if (!isStale(etpMapTime)) return etpMap;
            if (inFailCooldown(etpMapFailTime)) return etpMap;
            List<NaverFinanceService.NaverStockData> etf = fetchAllFromKrx(ETF_URL, ".KS");
            List<NaverFinanceService.NaverStockData> etn = fetchAllFromKrx(ETN_URL, ".KS");
            List<NaverFinanceService.NaverStockData> all = new ArrayList<>(etf.size() + etn.size());
            all.addAll(etf);
            all.addAll(etn);
            if (!all.isEmpty()) {
                etpMap = toMap(all);
                etnSymbols =
                        etn.stream()
                                .map(s -> s.symbol().toUpperCase())
                                .collect(Collectors.toUnmodifiableSet());
                etpMapTime = System.currentTimeMillis();
                etpMapFailTime = 0;
                log.info(
                        "KRX ETF/ETN 캐시 갱신: ETF {}개 + ETN {}개 = {}개",
                        etf.size(),
                        etn.size(),
                        etpMap.size());
            } else {
                etpMapFailTime = System.currentTimeMillis();
                log.warn("KRX ETF/ETN 로드 실패 — {}분 쿨다운 적용", FAIL_COOLDOWN_MS / 60_000);
            }
        }
        return etpMap;
    }

    private boolean inFailCooldown(long failTime) {
        return failTime > 0 && (System.currentTimeMillis() - failTime) < FAIL_COOLDOWN_MS;
    }

    private boolean isStale(long cacheTime) {
        return cacheTime == 0 || (System.currentTimeMillis() - cacheTime) > CACHE_TTL_MS;
    }

    private boolean isStale(long cacheTime, long ttlMs) {
        return cacheTime == 0 || (System.currentTimeMillis() - cacheTime) > ttlMs;
    }

    private boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("여기에");
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
                .sorted(
                        Comparator.comparingLong(NaverFinanceService.NaverStockData::marketCap)
                                .reversed())
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
                List<NaverFinanceService.NaverStockData> result =
                        callKrxApi(url, date.format(DATE_FMT), suffix);
                if (!result.isEmpty()) {
                    log.info("KRX API {} 조회 성공 (기준일 {}): {}개 종목", suffix, date, result.size());
                    return result;
                }
            } catch (IOException | InterruptedException e) {
                log.debug("KRX API 조회 실패 (기준일 {}): {}", date, e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.warn("KRX API {} 최근 7 영업일 모두 빈 결과", suffix);
        return Collections.emptyList();
    }

    private List<NaverFinanceService.NaverStockData> callKrxApi(
            String url, String basDd, String suffix) throws IOException, InterruptedException {
        String body = "{\"basDd\":\"" + basDd + "\"}";

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("AUTH_KEY", apiKey.strip())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.debug(
                    "KRX API HTTP {} (basDd={}): {}",
                    response.statusCode(),
                    basDd,
                    response.body().substring(0, Math.min(200, response.body().length())));
            return Collections.emptyList();
        }

        return parseResponse(response.body(), suffix);
    }

    /**
     * KRX API 응답 파싱. OutBlock_1 배열의 모든 종목을 반환 (정렬 없음 — 호출자가 필요 시 정렬). 응답 필드: ISU_CD(6자리 단축코드),
     * ISU_NM(종목명), TDD_CLSPRC(종가), CMPPREVDD_PRC(전일대비, 부호 포함), FLUC_RT(등락률, 부호 포함),
     * ACC_TRDVOL(누적거래량), MKTCAP(시가총액, 원 단위).
     */
    private List<NaverFinanceService.NaverStockData> parseResponse(String json, String suffix)
            throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode items = root.path("OutBlock_1");

        if (!items.isArray() || items.isEmpty()) return Collections.emptyList();

        List<NaverFinanceService.NaverStockData> result = new ArrayList<>(items.size());

        for (JsonNode item : items) {
            String shortCode = item.path("ISU_CD").asText("").trim();
            if (shortCode.length() != 6) continue;

            String name = item.path("ISU_NM").asText("").trim();
            if (name.isEmpty()) continue;

            // 거래 없는 날의 우선주 등은 price==0 일 수 있으나, 이름 룩업용으로
            // 캐시에는 포함시킵니다. 시세 조회는 getKrPrice() 쪽에서 price==0 을 걸러냅니다.
            double price = parseDouble(item.path("TDD_CLSPRC").asText("0"));
            double change = parseDouble(item.path("CMPPREVDD_PRC").asText("0"));
            double changeRate = parseDouble(item.path("FLUC_RT").asText("0"));
            long volume = parseLong(item.path("ACC_TRDVOL").asText("0"));
            long mktcap = parseLong(item.path("MKTCAP").asText("0"));

            result.add(
                    new NaverFinanceService.NaverStockData(
                            shortCode + suffix,
                            name,
                            price,
                            Math.round(change * 10.0) / 10.0,
                            Math.round(changeRate * 100.0) / 100.0,
                            mktcap,
                            volume));
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
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(String text) {
        if (text == null) return 0;
        String clean = text.replace(",", "").trim();
        if (clean.isEmpty() || clean.equals("-")) return 0;
        try {
            return Long.parseLong(clean);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
