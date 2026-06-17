package com.hyunchang.webapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 여행 '다녀온 곳' 위치 검색용 지오코딩 프록시 (OSM Nominatim).
 *
 * 브라우저에서 Nominatim을 직접 호출하지 않고 백엔드가 대신 호출한다:
 *   - Nominatim 이용정책이 요구하는 식별 가능한 User-Agent를 서버에서 명시
 *   - 프론트는 same-origin(/api/travel/geocode) 호출 → CSP에 외부 도메인 개방 불필요
 *   - 동일 검색어는 짧게 캐시해 외부 호출 빈도를 낮춤(정책상 권장)
 *
 * 응답 JSON(배열)을 그대로 통과시켜 프론트가 display_name/lat/lon을 사용한다.
 */
@Service
public class TravelGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(TravelGeocodeService.class);

    private static final String SEARCH_URL = "https://nominatim.openstreetmap.org/search";
    // Nominatim 정책: 식별 가능한 User-Agent 필수 (앱명 + 연락처)
    private static final String USER_AGENT = "HyunchangHome-Travel/1.0 (https://hyunchang.synology.me; gusckd1988@gmail.com)";
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // 30분
    private static final String EMPTY_JSON = "[]";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // "오사카" → CacheEntry(time, json)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(long time, String json) {}

    /** 지명 검색 → Nominatim 응답 JSON(배열) 문자열. 실패 시 "[]". */
    public String search(String query) {
        if (query == null || query.isBlank()) return EMPTY_JSON;
        String q = query.trim();

        CacheEntry hit = cache.get(q.toLowerCase());
        if (hit != null && (System.currentTimeMillis() - hit.time()) < CACHE_TTL_MS) {
            return hit.json();
        }

        try {
            String url = SEARCH_URL
                + "?format=json&limit=5&accept-language=ko&q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[TRAVEL/GEOCODE] Nominatim HTTP {} (q={})", response.statusCode(), q);
                return EMPTY_JSON;
            }
            String body = response.body();
            String json = (body == null || body.isBlank()) ? EMPTY_JSON : body;
            cache.put(q.toLowerCase(), new CacheEntry(System.currentTimeMillis(), json));
            return json;
        } catch (Exception e) {
            log.warn("[TRAVEL/GEOCODE] 검색 실패 (q={}): {}", q, e.getMessage());
            return EMPTY_JSON;
        }
    }
}
