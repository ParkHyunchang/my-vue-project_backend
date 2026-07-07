package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.OfficialPriceDto;
import com.hyunchang.webapp.dto.UmdDto;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 * 개별공시지가 조회 서비스 (VWorld 디지털트윈국토 데이터 API, api.vworld.kr/ned/data).
 *
 * <p>- 법정동코드(10자리) 리소스(realestate/kr-bdong-codes.json)를 메모리에 로드해 시군구(5자리)별 읍면동 목록을 제공한다. - 읍면동
 * 법정동코드 + 지번(산구분·본번·부번)으로 PNU(19자리)를 만들어 공시지가를 조회한다. PNU = 법정동코드(10) + 산구분(1: 일반=1/산=2) + 본번(4) +
 * 부번(4). - 인증키는 VWorld 키 app.realestate.land-key(REALLAND_API_KEY)를 사용한다. VWorld 는 키 발급 시 등록한 서비스
 * URL/도메인을 요청과 대조하므로 app.realestate.land-domain(VWORLD_DOMAIN)에 등록 도메인을 넣어 함께 보낸다. - 응답 XML: {@code
 * <response><fields><field><pblntfPclnd>(원/㎡)·<stdrYear>...</field></fields></response>}.
 */
@Service
public class LandPriceService {

    private static final Logger log = LoggerFactory.getLogger(LandPriceService.class);
    private static final String BDONG_RESOURCE = "realestate/kr-bdong-codes.json";
    private static final String PRICE_URL = "https://api.vworld.kr/ned/data/getIndvdLandPriceAttr";

    @Value("${app.realestate.land-key:}")
    private String serviceKey;

    // VWorld 키 발급 시 등록한 서비스 URL/도메인 (도메인 검증용)
    @Value("${app.realestate.land-domain:}")
    private String domain;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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
            umdsBySigungu =
                    rows.stream()
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

    // VWorld 가 데이터 없을 때 최근 몇 개 연도까지 거슬러 조회할지
    private static final int YEAR_LOOKBACK = 4;

    /**
     * 개별공시지가 조회. year 가 null 이면 최근 연도부터 거슬러 올라가며 데이터가 있는 첫 연도를 사용한다.
     *
     * <p>stdrYear(기준연도)는 VWorld getIndvdLandPriceAttr 의 필수 파라미터다.
     */
    public OfficialPriceDto getOfficialPrice(
            String bdongCode, boolean mountain, int bun, int ji, Integer year) {
        if (!isConfigured()) {
            return OfficialPriceDto.builder()
                    .found(false)
                    .message("공시지가 API 키가 설정되지 않았습니다.")
                    .build();
        }
        String pnu = buildPnu(bdongCode, mountain, bun, ji);
        if (pnu == null) {
            return OfficialPriceDto.builder()
                    .found(false)
                    .message("법정동코드(10자리)가 올바르지 않습니다.")
                    .build();
        }

        if (year != null) {
            return fetchForYear(pnu, year);
        }
        // 연도 미지정: 올해(공시 전일 수 있음)부터 최근 연도로 거슬러 첫 데이터 채택
        int current = java.time.Year.now().getValue();
        OfficialPriceDto last = null;
        for (int y = current; y > current - YEAR_LOOKBACK; y--) {
            OfficialPriceDto dto = fetchForYear(pnu, y);
            if (dto.isFound()) return dto;
            last = dto;
        }
        return last != null
                ? last
                : OfficialPriceDto.builder()
                        .found(false)
                        .pnu(pnu)
                        .message("해당 필지의 공시지가 데이터가 없습니다.")
                        .build();
    }

    /** 특정 기준연도(stdrYear)로 공시지가 1회 조회. */
    private OfficialPriceDto fetchForYear(String pnu, int year) {
        try {
            String url =
                    PRICE_URL
                            + "?key="
                            + serviceKey.strip()
                            + "&pnu="
                            + pnu
                            + "&stdrYear="
                            + year
                            + "&format=xml"
                            + "&numOfRows=10"
                            + "&pageNo=1"
                            + (domain != null && !domain.isBlank()
                                    ? "&domain=" + domain.strip()
                                    : "");

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            if (response.statusCode() != 200 || body == null || body.isBlank()) {
                String head = body == null ? "" : body.strip();
                if (head.length() > 300) head = head.substring(0, 300);
                log.warn(
                        "VWorld 공시지가 응답 비정상 (status={}, pnu={}, year={}) body=[{}]",
                        response.statusCode(),
                        pnu,
                        year,
                        head);
                return OfficialPriceDto.builder()
                        .found(false)
                        .pnu(pnu)
                        .message("공시지가를 조회하지 못했습니다.")
                        .build();
            }

            // 응답 XML:
            // <response><fields><field><pblntfPclnd>(원/㎡)·<stdrYear>...</field>...</fields></response>
            // 같은 필지·연도가 갱신일자별로 여러 건 올 수 있어, 가격>0 중 최신 연도를 채택.
            Document doc = parseXml(body);
            NodeList fields = doc.getElementsByTagName("field");
            long bestPrice = 0;
            int bestYear = -1;
            for (int i = 0; i < fields.getLength(); i++) {
                Element f = (Element) fields.item(i);
                long price = asLong(text(f, "pblntfPclnd"));
                if (price <= 0) continue;
                int yr = (int) asLong(text(f, "stdrYear"));
                if (yr > bestYear) {
                    bestYear = yr;
                    bestPrice = price;
                }
            }
            if (bestYear < 0) {
                // 결함 응답이면 메시지를 함께 남김 (정상이면 데이터 없음)
                String err = text(doc.getDocumentElement(), "text");
                if (!err.isBlank()) {
                    log.warn("VWorld 공시지가 오류 (pnu={}, year={}): {}", pnu, year, err);
                }
                return OfficialPriceDto.builder()
                        .found(false)
                        .pnu(pnu)
                        .message(err.isBlank() ? "해당 필지의 공시지가 데이터가 없습니다." : "공시지가 조회 오류: " + err)
                        .build();
            }
            return OfficialPriceDto.builder()
                    .found(true)
                    .pnu(pnu)
                    .pricePerM2(bestPrice)
                    .year(bestYear)
                    .build();
        } catch (Exception e) {
            log.warn("VWorld 공시지가 조회 실패 (pnu={}, year={}): {}", pnu, year, e.getMessage());
            return OfficialPriceDto.builder()
                    .found(false)
                    .pnu(pnu)
                    .message("공시지가 조회 중 오류가 발생했습니다.")
                    .build();
        }
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /** 부모 엘리먼트에서 첫 번째 자식 태그의 텍스트 (없으면 ""). */
    private String text(Element parent, String tag) {
        if (parent == null) return "";
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0 || nl.item(0).getTextContent() == null) return "";
        return nl.item(0).getTextContent().trim();
    }

    private long asLong(String s) {
        if (s == null) return 0;
        String clean = s.replace(",", "").trim();
        if (clean.isEmpty()) return 0;
        try {
            return Long.parseLong(clean);
        } catch (NumberFormatException e) {
            try {
                return Math.round(Double.parseDouble(clean));
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }
}
