package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.LandDealDto;
import com.hyunchang.webapp.dto.LandFiltersDto;
import com.hyunchang.webapp.dto.LandQuoteDto;
import com.hyunchang.webapp.dto.OfficialPriceDto;
import com.hyunchang.webapp.dto.UmdDto;
import com.hyunchang.webapp.dto.PropertyQuoteDto;
import com.hyunchang.webapp.dto.RealEstateAnalysisResponse;
import com.hyunchang.webapp.dto.RealEstateDealDto;
import com.hyunchang.webapp.dto.RealEstateNewsDto;
import com.hyunchang.webapp.dto.RegionDto;
import com.hyunchang.webapp.service.LandPriceService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.RealEstateAnalysisService;
import com.hyunchang.webapp.service.RealEstateNewsService;
import com.hyunchang.webapp.service.RealEstateService;
import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "RealEstate", description = "부동산 실거래가 API (국토교통부)")
@RestController
@RequestMapping("/api/realestate")
public class RealEstateController {

    private static final String MENU_PATH = "/realestate";

    private final RealEstateService realEstateService;
    private final RealEstateNewsService realEstateNewsService;
    private final RealEstateAnalysisService realEstateAnalysisService;
    private final LandPriceService landPriceService;
    private final MenuPermissionService menuPermissionService;

    public RealEstateController(RealEstateService realEstateService,
                                RealEstateNewsService realEstateNewsService,
                                RealEstateAnalysisService realEstateAnalysisService,
                                LandPriceService landPriceService,
                                MenuPermissionService menuPermissionService) {
        this.realEstateService = realEstateService;
        this.realEstateNewsService = realEstateNewsService;
        this.realEstateAnalysisService = realEstateAnalysisService;
        this.landPriceService = landPriceService;
        this.menuPermissionService = menuPermissionService;
    }

    @Operation(summary = "지역(법정동 시군구) 검색 — q 미입력 시 전체 목록")
    @GetMapping("/regions")
    public ResponseEntity<List<RegionDto>> searchRegions(
            @RequestParam(required = false, defaultValue = "") String q) {
        return ResponseEntity.ok(realEstateService.searchRegions(q));
    }

    @Operation(summary = "아파트 실거래 검색 — dealType: SALE(매매) | JEONSE(전세) | MONTHLY(월세)")
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String lawdCd,
            @RequestParam(defaultValue = "SALE") String dealType,
            @RequestParam(defaultValue = "3") int months) {
        if (!realEstateService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "부동산 실거래가 API 키(REALESTATE_API_KEY)가 설정되지 않았습니다."));
        }
        List<RealEstateDealDto> deals = realEstateService.search(lawdCd, dealType, months);
        return ResponseEntity.ok(deals);
    }

    @Operation(summary = "지역+거래유형 단지명 목록 (보유 등록 자동완성용)")
    @GetMapping("/apartments")
    public ResponseEntity<List<String>> getApartments(
            @RequestParam String lawdCd,
            @RequestParam(defaultValue = "SALE") String dealType) {
        return ResponseEntity.ok(realEstateService.getApartments(lawdCd, dealType));
    }

    @Operation(summary = "단지의 전용면적 목록 (보유 등록 자동완성용)")
    @GetMapping("/areas")
    public ResponseEntity<List<Double>> getAreas(
            @RequestParam String lawdCd,
            @RequestParam(defaultValue = "SALE") String dealType,
            @RequestParam String aptName) {
        return ResponseEntity.ok(realEstateService.getAreas(lawdCd, dealType, aptName));
    }

    @Operation(summary = "보유 부동산 추정 시세 — 같은 시군구·단지명·전용면적의 최근 실거래가 기반")
    @GetMapping("/quote")
    public ResponseEntity<?> getQuote(
            @RequestParam String lawdCd,
            @RequestParam(defaultValue = "SALE") String dealType,
            @RequestParam String aptName,
            @RequestParam(required = false) Double areaM2) {
        if (!realEstateService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "부동산 실거래가 API 키(REALESTATE_API_KEY)가 설정되지 않았습니다."));
        }
        PropertyQuoteDto quote = realEstateService.getQuote(lawdCd, dealType, aptName, areaM2);
        return ResponseEntity.ok(quote);
    }

    // ── 토지(LAND) ─────────────────────────────────────────────────

    @Operation(summary = "토지 매매 실거래 검색")
    @GetMapping("/land/search")
    public ResponseEntity<?> searchLand(
            @RequestParam String lawdCd,
            @RequestParam(defaultValue = "3") int months) {
        if (!realEstateService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "부동산 실거래가 API 키(REALESTATE_API_KEY)가 설정되지 않았습니다."));
        }
        List<LandDealDto> deals = realEstateService.searchLand(lawdCd, months);
        return ResponseEntity.ok(deals);
    }

    @Operation(summary = "토지 검색 결과의 지목·용도지역 목록 (필터/등록 자동완성용)")
    @GetMapping("/land/filters")
    public ResponseEntity<LandFiltersDto> getLandFilters(@RequestParam String lawdCd) {
        return ResponseEntity.ok(realEstateService.getLandFilters(lawdCd));
    }

    @Operation(summary = "보유 토지 추정 시세 — 같은 시군구·지목·용도지역 실거래 단가(원/㎡) 기반")
    @GetMapping("/land/quote")
    public ResponseEntity<?> getLandQuote(
            @RequestParam String lawdCd,
            @RequestParam(required = false) String jimok,
            @RequestParam(required = false) String useZone,
            @RequestParam(required = false) Double areaM2) {
        if (!realEstateService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "부동산 실거래가 API 키(REALESTATE_API_KEY)가 설정되지 않았습니다."));
        }
        LandQuoteDto quote = realEstateService.getLandQuote(lawdCd, jimok, useZone, areaM2);
        return ResponseEntity.ok(quote);
    }

    @Operation(summary = "시군구 하위 읍면동(법정동 10자리) 목록 — 토지 공시지가 조회용")
    @GetMapping("/land/umds")
    public ResponseEntity<List<UmdDto>> getUmds(@RequestParam String lawdCd) {
        return ResponseEntity.ok(landPriceService.getUmds(lawdCd));
    }

    @Operation(summary = "개별공시지가 조회 — 법정동코드(10)+지번(산구분·본번·부번)으로 PNU 조립 후 NSDI 조회")
    @GetMapping("/land/official-price")
    public ResponseEntity<?> getOfficialPrice(
            @RequestParam String bdongCode,
            @RequestParam(defaultValue = "false") boolean mountain,
            @RequestParam(defaultValue = "0") int bun,
            @RequestParam(defaultValue = "0") int ji,
            @RequestParam(required = false) Integer year) {
        if (!landPriceService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "공시지가 API 키가 설정되지 않았습니다."));
        }
        OfficialPriceDto price = landPriceService.getOfficialPrice(bdongCode, mountain, bun, ji, year);
        return ResponseEntity.ok(price);
    }

    @Operation(summary = "부동산 뉴스 (국내 부동산 RSS) — force=true 시 캐시 무효화")
    @GetMapping("/news")
    public ResponseEntity<List<RealEstateNewsDto>> getNews(
            @RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.ok(realEstateNewsService.getNews(force));
    }

    public record AnalyzeRequest(String lawdCd, String dealType) {}

    @Operation(summary = "AI 지역 시황 분석 — 검색한 시군구+거래유형 실거래/뉴스 기반")
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody AnalyzeRequest request) {
        if (!menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
        }
        if (request == null || request.lawdCd() == null || request.lawdCd().isBlank()) {
            return ResponseEntity.badRequest().body("lawdCd 가 필요합니다.");
        }
        if (!realEstateService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "부동산 실거래가 API 키(REALESTATE_API_KEY)가 설정되지 않았습니다."));
        }
        RealEstateAnalysisResponse res = realEstateAnalysisService.analyze(request.lawdCd(), request.dealType());
        return ResponseEntity.ok(res);
    }

    public record LandAnalyzeRequest(String lawdCd) {}

    @Operation(summary = "AI 토지 지역 시황 분석 — 검색한 시군구 토지 실거래 단가/지목/용도지역 기반")
    @PostMapping("/land/analyze")
    public ResponseEntity<?> analyzeLand(@RequestBody LandAnalyzeRequest request) {
        if (!menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
        }
        if (request == null || request.lawdCd() == null || request.lawdCd().isBlank()) {
            return ResponseEntity.badRequest().body("lawdCd 가 필요합니다.");
        }
        if (!realEstateService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "부동산 실거래가 API 키(REALESTATE_API_KEY)가 설정되지 않았습니다."));
        }
        return ResponseEntity.ok(realEstateAnalysisService.analyzeLand(request.lawdCd()));
    }

    @Operation(summary = "부동산 실거래가 API 설정 여부")
    @GetMapping("/config")
    public ResponseEntity<Map<String, Boolean>> getConfig() {
        return ResponseEntity.ok(Map.of("configured", realEstateService.isConfigured()));
    }
}
