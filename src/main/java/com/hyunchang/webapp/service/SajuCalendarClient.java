package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.util.TtlCache;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 한국천문연구원(KASI) 음양력 정보 API(data.go.kr, LrsrCldInfoService) 클라이언트.
 *
 * <p>양력 날짜를 넣으면(getLunCalInfo) 그날의 세차(년주)·월건(월주)·일진(일주) 간지를 공식 만세력 기준으로 돌려주고, 음력 날짜를 넣으면
 * (getSolCalInfo) 대응하는 양력 날짜와 간지를 돌려준다. 이 값은 절기(태양 황경) 기준으로 이미 확정된 것이라 서버가 절기 계산을 직접 구현할 필요가 없다.
 * 날짜별 간지는 불변이므로 장기 캐시한다.
 *
 * <p><b>윤달 한계</b>: 윤달 기간에는 API가 월건(lunWolgeon)을 빈 문자열로 돌려준다(윤달은 그 해의 절기 상 어느 월에 속하는지 이 API가 알려주지 않기
 * 때문). 이 클라이언트는 monthGanji=null로 그대로 전달하고, 실제 대체값 계산은 {@link SajuPaljaService}가 근접한 날짜의 월건을 빌려오는
 * 방식으로 근사 처리한다.
 */
@Service
public class SajuCalendarClient {

    private static final Logger log = LoggerFactory.getLogger(SajuCalendarClient.class);
    private static final String BASE_URL =
            "https://apis.data.go.kr/B090041/openapi/service/LrsrCldInfoService/";
    private static final String LUN_CAL_URL = BASE_URL + "getLunCalInfo"; // 양력 → 음력/간지
    private static final String SOL_CAL_URL = BASE_URL + "getSolCalInfo"; // 음력 → 양력/간지

    @Value("${app.saju.kasi-key:}")
    private String serviceKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // 날짜별 간지는 불변이므로 사실상 영구 캐시(10년), 실패 응답만 짧게 재시도 허용
    private final TtlCache<LocalDate, GanjiRaw> solarCache =
            new TtlCache<>(Duration.ofDays(3650), Duration.ofMinutes(10));
    private final TtlCache<String, LunarResolution> lunarCache =
            new TtlCache<>(Duration.ofDays(3650), Duration.ofMinutes(10));

    /** 년주(세차)·월주(월건)·일주(일진) 원본 간지 문자열 (예: "경오(庚午)"). monthGanji 는 윤달이면 null 일 수 있다. */
    public record GanjiRaw(String yearGanji, String monthGanji, String dayGanji) {}

    /** 음력 → 양력 변환 결과. */
    public record LunarResolution(LocalDate solarDate, GanjiRaw ganji) {}

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 양력 날짜 기준 간지 조회. */
    public GanjiRaw fetch(LocalDate date) {
        if (date == null) return null;
        TtlCache.Hit<GanjiRaw> hit = solarCache.lookup(date);
        if (hit != null) return hit.negative() ? null : hit.value();

        GanjiRaw result = fetchSolar(date);
        if (result != null) {
            solarCache.put(date, result);
        } else {
            solarCache.putNegative(date);
        }
        return result;
    }

    /** 음력 날짜(연/월/일, 윤달 여부) 기준 대응 양력 날짜 + 간지 조회. */
    public LunarResolution resolveLunar(
            int lunYear, int lunMonth, int lunDay, boolean isLeapMonth) {
        String key = lunYear + "-" + lunMonth + "-" + lunDay + "-" + isLeapMonth;
        TtlCache.Hit<LunarResolution> hit = lunarCache.lookup(key);
        if (hit != null) return hit.negative() ? null : hit.value();

        LunarResolution result = fetchLunar(lunYear, lunMonth, lunDay, isLeapMonth);
        if (result != null) {
            lunarCache.put(key, result);
        } else {
            lunarCache.putNegative(key);
        }
        return result;
    }

    private GanjiRaw fetchSolar(LocalDate date) {
        String url =
                LUN_CAL_URL
                        + "?"
                        + authQuery()
                        + "&solYear="
                        + date.getYear()
                        + "&solMonth="
                        + String.format("%02d", date.getMonthValue())
                        + "&solDay="
                        + String.format("%02d", date.getDayOfMonth())
                        + "&_type=json";
        JsonNode item = fetchSingleItem(url, "date=" + date);
        if (item == null) return null;
        return toGanjiRaw(item, "date=" + date);
    }

    private LunarResolution fetchLunar(int lunYear, int lunMonth, int lunDay, boolean isLeapMonth) {
        String ctx = "lunar=" + lunYear + "-" + lunMonth + "-" + lunDay + " leap=" + isLeapMonth;
        String url =
                SOL_CAL_URL
                        + "?"
                        + authQuery()
                        + "&lunYear="
                        + lunYear
                        + "&lunMonth="
                        + String.format("%02d", lunMonth)
                        + "&lunDay="
                        + String.format("%02d", lunDay)
                        + "&_type=json";
        JsonNode body = fetchBody(url, ctx);
        if (body == null) return null;

        // getSolCalInfo 는 그 해에 동일 월 번호의 평달/윤달이 둘 다 있으면 item 을 배열로 2건 돌려준다.
        // lunLeapmonth 필드("평"/"윤")로 원하는 쪽을 직접 골라야 한다 — 파라미터로 필터링되지 않는다.
        JsonNode itemsNode = body.path("items").path("item");
        JsonNode item = pickLunarItem(itemsNode, isLeapMonth);
        if (item == null) {
            log.warn("[Saju] KASI 음력 변환 결과에서 {} 항목을 찾지 못했습니다 ({})", isLeapMonth ? "윤달" : "평달", ctx);
            return null;
        }

        int solYear = item.path("solYear").asInt(0);
        int solMonth = item.path("solMonth").asInt(0);
        int solDay = item.path("solDay").asInt(0);
        if (solYear == 0 || solMonth == 0 || solDay == 0) {
            log.warn("[Saju] KASI 음력 변환 응답에 양력 날짜가 없습니다 ({})", ctx);
            return null;
        }
        LocalDate solarDate;
        try {
            solarDate = LocalDate.of(solYear, solMonth, solDay);
        } catch (Exception e) {
            log.warn("[Saju] KASI 음력 변환 응답의 양력 날짜가 올바르지 않습니다 ({}): {}", ctx, e.getMessage());
            return null;
        }

        GanjiRaw ganji = toGanjiRaw(item, ctx);
        if (ganji == null) return null;
        return new LunarResolution(solarDate, ganji);
    }

    /** items.item 이 배열이면 원하는 평/윤 쪽을 고르고, 단일 객체면 그대로 사용한다(그 해엔 평달만 존재). */
    private JsonNode pickLunarItem(JsonNode itemsNode, boolean isLeapMonth) {
        String wantedFlag = isLeapMonth ? "윤" : "평";
        if (itemsNode.isArray()) {
            JsonNode fallback = null;
            Iterator<JsonNode> it = itemsNode.elements();
            while (it.hasNext()) {
                JsonNode candidate = it.next();
                if (fallback == null) fallback = candidate;
                if (wantedFlag.equals(candidate.path("lunLeapmonth").asText(""))) {
                    return candidate;
                }
            }
            // 윤달을 요청했는데 배열에 윤달 항목이 없으면(그 해엔 윤달이 없음) 실패로 처리한다.
            return isLeapMonth ? null : fallback;
        }
        if (itemsNode.isMissingNode() || itemsNode.isNull()) return null;
        // 단일 객체 — 윤달을 요청했는데 이 객체가 평달이면 그 해엔 윤달이 없다는 뜻이므로 실패 처리.
        if (isLeapMonth && !"윤".equals(itemsNode.path("lunLeapmonth").asText(""))) return null;
        return itemsNode;
    }

    /** 윤달이면 lunWolgeon(월건)이 빈 문자열로 오므로 null 로 정규화해 전달한다 — 대체 처리는 SajuPaljaService 담당. */
    private GanjiRaw toGanjiRaw(JsonNode item, String ctx) {
        String yearGanji = nullIfBlank(item.path("lunSecha").asText(null));
        String monthGanji = nullIfBlank(item.path("lunWolgeon").asText(null));
        String dayGanji = nullIfBlank(item.path("lunIljin").asText(null));
        if (yearGanji == null || dayGanji == null) {
            log.warn("[Saju] KASI 응답에 년주/일주 간지 필드가 없습니다 ({})", ctx);
            return null;
        }
        return new GanjiRaw(yearGanji, monthGanji, dayGanji);
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String authQuery() {
        return "serviceKey=" + URLEncoder.encode(serviceKey.strip(), StandardCharsets.UTF_8);
    }

    /** items.item 노드 하나만 필요한 호출용 — 배열이면 첫 번째, 없으면 null. */
    private JsonNode fetchSingleItem(String url, String ctx) {
        JsonNode body = fetchBody(url, ctx);
        if (body == null) return null;
        JsonNode item = body.path("items").path("item");
        if (item.isArray()) {
            if (item.isEmpty()) return null;
            item = item.get(0);
        }
        if (item.isMissingNode() || item.isNull()) {
            log.warn("[Saju] KASI 응답에 item 이 없습니다 ({})", ctx);
            return null;
        }
        return item;
    }

    /** 공통 HTTP 호출 + header.resultCode 검사. 성공하면 response.body 노드를 반환. */
    private JsonNode fetchBody(String url, String ctx) {
        if (!isConfigured()) {
            log.warn("[Saju] KASI API 키가 설정되지 않았습니다 (app.saju.kasi-key)");
            return null;
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            if (response.statusCode() != 200 || responseBody == null || responseBody.isBlank()) {
                String head = responseBody == null ? "" : responseBody.strip();
                if (head.length() > 300) head = head.substring(0, 300);
                log.warn(
                        "[Saju] KASI 응답 비정상 (status={}, {}) body=[{}]",
                        response.statusCode(),
                        ctx,
                        head);
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode header = root.path("response").path("header");
            String resultCode = header.path("resultCode").asText("");
            if (!resultCode.isEmpty() && !"00".equals(resultCode)) {
                log.warn(
                        "[Saju] KASI 오류 응답 ({}, code={}, msg={})",
                        ctx,
                        resultCode,
                        header.path("resultMsg").asText(""));
                return null;
            }
            return root.path("response").path("body");
        } catch (Exception e) {
            log.warn("[Saju] KASI 조회 실패 ({}): {}", ctx, e.getMessage());
            return null;
        }
    }
}
