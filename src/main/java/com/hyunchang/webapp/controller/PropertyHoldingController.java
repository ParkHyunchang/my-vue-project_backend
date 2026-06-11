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
        String dealType,
        String name,
        String lawdCd,
        String sigungu,
        Double areaM2,
        Long purchasePrice,
        Long monthlyRent,
        String memo,
        String purchaseDate   // ISO yyyy-MM-dd
    ) {}

    @Operation(summary = "보유 부동산 추가")
    @PostMapping("/holdings")
    public ResponseEntity<?> addHolding(@RequestBody PropertyRequest request) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();

        if (request.name() == null || request.name().isBlank()
            || request.lawdCd() == null || request.lawdCd().length() != 5
            || request.dealType() == null || request.dealType().isBlank()) {
            return ResponseEntity.badRequest().body("단지명, 지역, 거래유형은 필수입니다.");
        }

        PropertyHolding created = propertyHoldingService.addHolding(
            userId,
            request.dealType().toUpperCase(),
            request.name().trim(),
            request.lawdCd(),
            request.sigungu() != null ? request.sigungu().trim() : "",
            request.areaM2(),
            request.purchasePrice(),
            request.monthlyRent(),
            request.memo() != null ? request.memo().trim() : null,
            toLocalDate(request.purchaseDate())
        );
        return ResponseEntity.ok(created);
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

        PropertyHolding updated = propertyHoldingService.updateHolding(
            userId, id, purchasePrice, monthlyRent, memo, purchaseDate);
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

    private java.time.LocalDate toLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
