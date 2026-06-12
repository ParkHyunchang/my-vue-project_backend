package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.OfficialPriceDto;
import com.hyunchang.webapp.dto.UmdDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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
import java.util.stream.Collectors;

/**
 * 개별공시지가 조회 서비스 (NSDI 국가공간정보 개별공시지가 API, data.go.kr 1611000).
 *
 * - 법정동코드(10자리) 리소스(realestate/kr-bdong-codes.json)를 메모리에 로드해
 *   시군구(5자리)별 읍면동 목록을 제공한다.
 * - 읍면동 법정동코드 + 지번(산구분·본번·부번)으로 PNU(19자리)를 만들어 공시지가를 조회한다.
 *   PNU = 법정동코드(10) + 산구분(1: 일반=1/산=2) + 본번(4) + 부번(4).
 * - 인증키는 RealEstateApiService 와 동일한 app.realestate.service-key 를 재사용한다
 *   (data.go.kr 인증키는 계정 단위 — NSDI 개별공시지가 서비스 활용신청만 별도 필요).
 */
@Service
public class LandPriceService {

    private static final Logger log = LoggerFactory.getLogger(LandPriceService.class);
    private static final String BDONG_RESOURCE = "realestate/kr-bdong-codes.json";
    private static final String PRICE_URL =
        "https://apis.data.go.kr/1611000/nsdi/IndvdLandPriceService/attr/getIndvdLandPriceAttr";

    @Value("${app.realestate.service-key:}")
    private String serviceKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // 시군구코드(5) → 읍면동 목록
    private Map<String, List<UmdDto>> umdsBySigungu = Collections.emptyMap();

    // 리소스 압축 형식: {"n": 읍면동명, "c": 법정동코드10}
    private record BdongRow(String n, String c) {}

    @PostConstruct
    void loadBdongCodes() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(BDONG_RESOURCE)) {
            if (is == null) {
                log.warn("법정동(읍면동) 코드 리소스를 찾을 수 없습니다: {}", BDONG_RESOURCE);
                return;
            }
            List<BdongRow> rows = objectMapper.readValue(is, new TypeReference<>() {});
            umdsBySigungu = rows.stream()
                .filter(r -> r.c() != null && r.c().length() == 10)
                .map(r -> new UmdDto(r.n(), r.c()))
                .collect(Collectors.groupingBy(u -> u.getCode().substring(0, 5)));
            log.info("법정동(읍면동) 코드 로드: {}개 (시군구 {}곳)", rows.size(), umdsBySigungu.size());
        } catch (Exception e) {
            log.error("법정동(읍면동) 코드 로드 실패: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 시군구(5자리) 하위 읍면동 목록 (법정동코드 오름차순). */
    public List<UmdDto> getUmds(String lawdCd) {
        if (lawdCd == null || lawdCd.length() != 5) return Collections.emptyList();
        return umdsBySigungu.getOrDefault(lawdCd, Collections.emptyList());
    }

    /** PNU(19자리) 조립. */
    public String buildPnu(String bdongCode, boolean mountain, int bun, int ji) {
        if (bdongCode == null || bdongCode.length() != 10) return null;
        return bdongCode
            + (mountain ? "2" : "1")
            + String.format("%04d", Math.max(0, bun))
            + String.format("%04d", Math.max(0, ji));
    }

    /**
     * 개별공시지가 조회. year 가 null 이면 응답 중 가장 최근 연도를 사용한다.
     */
    public OfficialPriceDto getOfficialPrice(String bdongCode, boolean mountain, int bun, int ji, Integer year) {
        if (!isConfigured()) {
            return OfficialPriceDto.builder().found(false)
                .message("공시지가 API 키가 설정되지 않았습니다.").build();
        }
        String pnu = buildPnu(bdongCode, mountain, bun, ji);
        if (pnu == null) {
            return OfficialPriceDto.builder().found(false)
                .message("법정동코드(10자리)가 올바르지 않습니다.").build();
        }
        try {
            String url = PRICE_URL
                + "?serviceKey=" + serviceKey.strip()
                + "&pnu=" + pnu
                + "&format=json"
                + "&numOfRows=100"
                + (year != null ? "&stdrYear=" + year : "");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            if (response.statusCode() != 200 || body == null || body.isBlank() || body.stripLeading().startsWith("<")) {
                String head = body == null ? "" : body.strip();
                if (head.length() > 300) head = head.substring(0, 300);
                log.warn("NSDI 공시지가 응답 비정상 (status={}, pnu={}) body=[{}]", response.statusCode(), pnu, head);
                return OfficialPriceDto.builder().found(false).pnu(pnu)
                    .message("공시지가를 조회하지 못했습니다. (응답: " + head + ")").build();
            }

            JsonNode root = objectMapper.readTree(body);
            List<JsonNode> records = new ArrayList<>();
            collectPriceRecords(root, records);

            JsonNode best = null;
            int bestYear = -1;
            for (JsonNode r : records) {
                long price = asLong(r.get("pblntfPclnd"));
                if (price <= 0) continue;
                int yr = (int) asLong(r.get("stdrYear"));
                if (yr > bestYear) { bestYear = yr; best = r; }
            }
            if (best == null) {
                return OfficialPriceDto.builder().found(false).pnu(pnu)
                    .message("해당 필지의 공시지가 데이터가 없습니다.").build();
            }
            return OfficialPriceDto.builder()
                .found(true).pnu(pnu)
                .pricePerM2(asLong(best.get("pblntfPclnd")))
                .year(bestYear)
                .build();
        } catch (Exception e) {
            log.warn("NSDI 공시지가 조회 실패 (pnu={}): {}", pnu, e.getMessage());
            return OfficialPriceDto.builder().found(false).pnu(pnu)
                .message("공시지가 조회 중 오류가 발생했습니다.").build();
        }
    }

    /** 응답 트리에서 pblntfPclnd(공시지가)를 가진 레코드를 재귀적으로 수집 (래퍼 키 변동 대비). */
    private void collectPriceRecords(JsonNode node, List<JsonNode> out) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.has("pblntfPclnd")) out.add(node);
            node.forEach(child -> collectPriceRecords(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectPriceRecords(child, out));
        }
    }

    private long asLong(JsonNode n) {
        if (n == null || n.isNull()) return 0;
        String s = n.asText("").replace(",", "").trim();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) {
            try { return Math.round(Double.parseDouble(s)); } catch (NumberFormatException e2) { return 0; }
        }
    }
}
