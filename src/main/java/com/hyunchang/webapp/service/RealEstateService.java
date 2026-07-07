package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.dto.LandDealDto;
import com.hyunchang.webapp.dto.LandFiltersDto;
import com.hyunchang.webapp.dto.LandQuoteDto;
import com.hyunchang.webapp.dto.PropertyQuoteDto;
import com.hyunchang.webapp.dto.RealEstateDealDto;
import com.hyunchang.webapp.dto.RegionDto;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 부동산 실거래가 오케스트레이션 서비스. - 법정동 시군구 코드 검색 (resources/realestate/kr-lawd-codes.json) - 거래유형(매매/전세/월세)별
 * 실거래 검색 (RealEstateApiService 위임 + 최근 N개월 합산)
 */
@Service
public class RealEstateService {

    private static final Logger log = LoggerFactory.getLogger(RealEstateService.class);
    private static final String LAWD_RESOURCE = "realestate/kr-lawd-codes.json";
    private static final DateTimeFormatter YMD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final RealEstateApiService apiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<RegionDto> regions = Collections.emptyList();

    public RealEstateService(RealEstateApiService apiService) {
        this.apiService = apiService;
    }

    @PostConstruct
    void loadRegions() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(LAWD_RESOURCE)) {
            if (is == null) {
                log.warn("법정동 코드 리소스를 찾을 수 없습니다: {}", LAWD_RESOURCE);
                return;
            }
            List<RegionDto> list = objectMapper.readValue(is, new TypeReference<>() {});
            for (RegionDto r : list) {
                r.setName(r.getSido() + " " + r.getSigungu());
            }
            regions = Collections.unmodifiableList(list);
            log.info("법정동 시군구 코드 로드: {}개", regions.size());
        } catch (Exception e) {
            log.error("법정동 코드 로드 실패: {}", e.getMessage());
        }
    }

    /** 해당 지역+거래유형 최근 12개월 실거래에 등장한 단지명 목록 (가나다순, 중복 제거). */
    public List<String> getApartments(String lawdCd, String dealType) {
        return search(lawdCd, dealType, 12).stream()
                .map(RealEstateDealDto::getAptName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /** 해당 지역+거래유형+단지의 전용면적 목록 (오름차순, 중복 제거). */
    public List<Double> getAreas(String lawdCd, String dealType, String aptName) {
        if (aptName == null || aptName.isBlank()) return Collections.emptyList();
        String target = normalize(aptName);
        return search(lawdCd, dealType, 12).stream()
                .filter(d -> normalize(d.getAptName()).equals(target))
                .map(RealEstateDealDto::getAreaM2)
                .filter(a -> a > 0)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /** 시군구 코드로 지역 정보 조회 (없으면 null). */
    public RegionDto getRegion(String lawdCd) {
        if (lawdCd == null) return null;
        return regions.stream().filter(r -> r.getCode().equals(lawdCd)).findFirst().orElse(null);
    }

    /** 지역 검색 (시도/시군구 부분 일치). 빈 검색어면 전체 반환. */
    public List<RegionDto> searchRegions(String query) {
        if (query == null || query.isBlank()) return regions;
        String q = query.trim().toLowerCase();
        return regions.stream()
                .filter(
                        r ->
                                r.getName().toLowerCase().contains(q)
                                        || r.getSigungu().toLowerCase().contains(q))
                .limit(30)
                .collect(Collectors.toList());
    }

    public boolean isConfigured() {
        return apiService.isConfigured();
    }

    /**
     * 거래유형별 실거래 검색.
     *
     * @param lawdCd 시군구 코드 5자리
     * @param dealType SALE | JEONSE | MONTHLY
     * @param months 조회할 최근 개월 수 (1~12)
     */
    public List<RealEstateDealDto> search(String lawdCd, String dealType, int months) {
        if (lawdCd == null || lawdCd.length() != 5) return Collections.emptyList();
        int safeMonths = Math.max(1, Math.min(months, 12));
        boolean isSale = "SALE".equalsIgnoreCase(dealType);
        String sigungu =
                regions.stream()
                        .filter(r -> r.getCode().equals(lawdCd))
                        .map(RegionDto::getSigungu)
                        .findFirst()
                        .orElse("");

        List<RealEstateDealDto> all = new ArrayList<>();
        LocalDate cursor = LocalDate.now();
        for (int i = 0; i < safeMonths; i++) {
            String ymd = cursor.format(YMD_FMT);
            List<RealEstateDealDto> monthDeals =
                    isSale ? apiService.getTrades(lawdCd, ymd) : apiService.getRents(lawdCd, ymd);
            all.addAll(monthDeals);
            cursor = cursor.minusMonths(1);
        }

        // 전월세는 전세/월세로 한 번 더 필터 (API가 둘을 함께 반환)
        return all.stream()
                .filter(d -> isSale || dealType.equalsIgnoreCase(d.getDealType()))
                .peek(d -> d.setSigungu(sigungu))
                .sorted(Comparator.comparing(RealEstateDealDto::getDealDate).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 보유 부동산 추정 시세 — 같은 시군구·단지명·전용면적(±3㎡)의 최근 1년 실거래로 추정.
     *
     * @param dealType SALE | JEONSE | MONTHLY
     */
    public PropertyQuoteDto getQuote(
            String lawdCd, String dealType, String aptName, Double areaM2) {
        if (lawdCd == null || aptName == null || aptName.isBlank()) {
            return PropertyQuoteDto.builder().found(false).build();
        }
        List<RealEstateDealDto> deals = search(lawdCd, dealType, 12); // 최신순 정렬됨
        String target = normalize(aptName);

        List<RealEstateDealDto> matched =
                deals.stream()
                        .filter(
                                d -> {
                                    String dn = normalize(d.getAptName());
                                    boolean nameMatch =
                                            dn.equals(target)
                                                    || dn.contains(target)
                                                    || target.contains(dn);
                                    boolean areaMatch =
                                            areaM2 == null
                                                    || areaM2 <= 0
                                                    || Math.abs(d.getAreaM2() - areaM2) <= 3.0;
                                    return nameMatch && areaMatch;
                                })
                        .collect(Collectors.toList());

        if (matched.isEmpty()) {
            return PropertyQuoteDto.builder().found(false).matchCount(0).build();
        }

        boolean isSale = "SALE".equalsIgnoreCase(dealType);
        RealEstateDealDto recent = matched.get(0); // 이미 거래일 내림차순
        long recentPrice = isSale ? recent.getDealAmount() : recent.getDeposit();
        long avg =
                Math.round(
                        matched.stream()
                                .mapToLong(d -> isSale ? d.getDealAmount() : d.getDeposit())
                                .average()
                                .orElse(0));

        return PropertyQuoteDto.builder()
                .found(true)
                .matchCount(matched.size())
                .recentDate(recent.getDealDate())
                .recentPrice(recentPrice)
                .recentMonthlyRent(isSale ? 0 : recent.getMonthlyRent())
                .avgPrice(avg)
                .build();
    }

    // ── 토지(LAND) ─────────────────────────────────────────────────

    /** 토지 매매 실거래 검색 — 최근 N개월 합산, 거래일 내림차순. */
    public List<LandDealDto> searchLand(String lawdCd, int months) {
        if (lawdCd == null || lawdCd.length() != 5) return Collections.emptyList();
        int safeMonths = Math.max(1, Math.min(months, 12));
        String sigungu =
                regions.stream()
                        .filter(r -> r.getCode().equals(lawdCd))
                        .map(RegionDto::getSigungu)
                        .findFirst()
                        .orElse("");

        List<LandDealDto> all = new ArrayList<>();
        LocalDate cursor = LocalDate.now();
        for (int i = 0; i < safeMonths; i++) {
            all.addAll(apiService.getLandTrades(lawdCd, cursor.format(YMD_FMT)));
            cursor = cursor.minusMonths(1);
        }
        return all.stream()
                .peek(d -> d.setSigungu(sigungu))
                .sorted(Comparator.comparing(LandDealDto::getDealDate).reversed())
                .collect(Collectors.toList());
    }

    /** 토지 검색 결과의 지목·용도지역 목록 (최근 12개월, 가나다순, 중복 제거). */
    public LandFiltersDto getLandFilters(String lawdCd) {
        List<LandDealDto> deals = searchLand(lawdCd, 12);
        List<String> jimoks =
                deals.stream()
                        .map(LandDealDto::getJimok)
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList());
        List<String> useZones =
                deals.stream()
                        .map(LandDealDto::getUseZone)
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList());
        return LandFiltersDto.builder().jimoks(jimoks).useZones(useZones).build();
    }

    /** 보유 토지 추정 시세 — 같은 시군구·지목·용도지역 거래의 단가(원/㎡) min/avg/max 기반. 지목/용도지역이 비면 해당 조건은 매칭에서 생략한다. */
    public LandQuoteDto getLandQuote(String lawdCd, String jimok, String useZone, Double areaM2) {
        if (lawdCd == null || lawdCd.length() != 5) {
            return LandQuoteDto.builder().found(false).build();
        }
        List<LandDealDto> deals = searchLand(lawdCd, 12); // 최신순 정렬됨
        String targetJimok = normalize(jimok);
        String targetZone = normalize(useZone);

        List<LandDealDto> matched =
                deals.stream()
                        .filter(d -> d.getPricePerM2() > 0)
                        .filter(
                                d ->
                                        targetJimok.isEmpty()
                                                || normalize(d.getJimok()).equals(targetJimok))
                        .filter(
                                d ->
                                        targetZone.isEmpty()
                                                || normalize(d.getUseZone()).equals(targetZone))
                        .collect(Collectors.toList());

        if (matched.isEmpty()) {
            return LandQuoteDto.builder().found(false).matchCount(0).build();
        }

        long[] unitPrices = matched.stream().mapToLong(LandDealDto::getPricePerM2).toArray();
        long avgUnit = Math.round(Arrays.stream(unitPrices).average().orElse(0));
        long minUnit = Arrays.stream(unitPrices).min().orElse(0);
        long maxUnit = Arrays.stream(unitPrices).max().orElse(0);
        long estimate =
                (areaM2 != null && areaM2 > 0)
                        ? Math.round(avgUnit * areaM2 / 10000.0) // 원 → 만원
                        : 0;

        return LandQuoteDto.builder()
                .found(true)
                .matchCount(matched.size())
                .pricePerM2Min(minUnit)
                .pricePerM2Avg(avgUnit)
                .pricePerM2Max(maxUnit)
                .recentDate(matched.get(0).getDealDate())
                .estimate(estimate)
                .build();
    }

    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase();
    }
}
