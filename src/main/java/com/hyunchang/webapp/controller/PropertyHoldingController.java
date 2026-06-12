package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.PropertyHolding;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.PropertyHoldingService;
import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Property", description = "보유 부동산 API")
@RestController
@RequestMapping("/api/property")
public class PropertyHoldingController {

    private static final String MENU_PATH = "/realestate";

    private final PropertyHoldingService propertyHoldingService;
    private final MenuPermissionService menuPermissionService;

    public PropertyHoldingController(PropertyHoldingService propertyHoldingService,
                                     MenuPermissionService menuPermissionService) {
        this.propertyHoldingService = propertyHoldingService;
        this.menuPermissionService = menuPermissionService;
    }

    private boolean hasAccess() {
        return menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
    }

    @Operation(summary = "현재 사용자 보유 부동산 전체 조회")
    @GetMapping("/holdings")
    public ResponseEntity<?> getHoldings() {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();
        List<PropertyHolding> holdings = propertyHoldingService.getHoldings(userId);
        return ResponseEntity.ok(holdings);
    }

    public record PropertyRequest(
        String propertyType,     // APT | LAND (미지정 시 APT)
        String dealType,
        String name,
        String lawdCd,
        String sigungu,
        Double areaM2,
        Long purchasePrice,
        Long monthlyRent,
        String memo,
        String purchaseDate,     // ISO yyyy-MM-dd
        // ── 토지(LAND) 전용 ──
        String jimok,
        String useZone,
        String umdName,
        String jibun,
        String bdongCode,
        Long officialPricePerM2,
        Integer officialPriceYear
    ) {}

    @Operation(summary = "보유 부동산 추가 (아파트/토지)")
    @PostMapping("/holdings")
    public ResponseEntity<?> addHolding(@RequestBody PropertyRequest request) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();

        boolean isLand = "LAND".equalsIgnoreCase(request.propertyType());
        String propertyType = isLand ? "LAND" : "APT";

        if (request.name() == null || request.name().isBlank()
            || request.lawdCd() == null || request.lawdCd().length() != 5) {
            return ResponseEntity.badRequest().body("이름(단지명/토지명)과 지역은 필수입니다.");
        }
        if (isLand) {
            if (request.areaM2() == null || request.areaM2() <= 0) {
                return ResponseEntity.badRequest().body("토지는 면적이 필수입니다.");
            }
        } else if (request.dealType() == null || request.dealType().isBlank()) {
            return ResponseEntity.badRequest().body("거래유형은 필수입니다.");
        }

        // 토지는 거래유형 개념이 없어 SALE 로 고정
        String dealType = isLand ? "SALE" : request.dealType().toUpperCase();

        PropertyHolding created = propertyHoldingService.addHolding(
            userId, propertyType, dealType,
            request.name().trim(),
            request.lawdCd(),
            request.sigungu() != null ? request.sigungu().trim() : "",
            request.areaM2(),
            request.purchasePrice(),
            isLand ? null : request.monthlyRent(),
            request.memo() != null ? request.memo().trim() : null,
            toLocalDate(request.purchaseDate()),
            trimOrNull(request.jimok()),
            trimOrNull(request.useZone()),
            trimOrNull(request.umdName()),
            trimOrNull(request.jibun()),
            isLand ? trimOrNull(request.bdongCode()) : null,
            isLand ? request.officialPricePerM2() : null,
            isLand ? request.officialPriceYear() : null
        );
        return ResponseEntity.ok(created);
    }

    private String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Operation(summary = "보유 부동산 수정 (가격/월세/메모)")
    @PutMapping("/holdings/{id}")
    public ResponseEntity<?> updateHolding(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();

        Long purchasePrice = toLong(body.get("purchasePrice"));
        Long monthlyRent = toLong(body.get("monthlyRent"));
        String memo = body.get("memo") != null ? body.get("memo").toString() : null;
        java.time.LocalDate purchaseDate = toLocalDate(
            body.get("purchaseDate") != null ? body.get("purchaseDate").toString() : null);
        // 토지 속성 (아파트면 무시됨)
        String jimok = trimOrNull(body.get("jimok") != null ? body.get("jimok").toString() : null);
        String useZone = trimOrNull(body.get("useZone") != null ? body.get("useZone").toString() : null);
        Long officialPricePerM2 = toLong(body.get("officialPricePerM2"));
        Integer officialPriceYear = toInt(body.get("officialPriceYear"));

        PropertyHolding updated = propertyHoldingService.updateHolding(
            userId, id, purchasePrice, monthlyRent, memo, purchaseDate,
            jimok, useZone, officialPricePerM2, officialPriceYear);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "보유 부동산 삭제")
    @DeleteMapping("/holdings/{id}")
    public ResponseEntity<?> deleteHolding(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();
        propertyHoldingService.deleteHolding(userId, id);
        return ResponseEntity.ok(Map.of("message", "삭제되었습니다."));
    }

    private Long toLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        return null;
    }

    private Integer toInt(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        return null;
    }

    private java.time.LocalDate toLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
