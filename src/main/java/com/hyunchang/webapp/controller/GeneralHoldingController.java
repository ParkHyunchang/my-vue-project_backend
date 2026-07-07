package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.dto.GeneralHoldingResponse;
import com.hyunchang.webapp.entity.PortfolioAssetType;
import com.hyunchang.webapp.service.GeneralHoldingService;
import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Portfolio General", description = "신한투자증권 종합계좌 holdings API")
@RestController
@RequestMapping("/api/portfolio/general")
public class GeneralHoldingController {

    private static final String MENU_PATH = "/portfolio";

    private final GeneralHoldingService generalHoldingService;
    private final MenuAccessGuard menuAccessGuard;

    public GeneralHoldingController(GeneralHoldingService generalHoldingService,
                                    MenuAccessGuard menuAccessGuard) {
        this.generalHoldingService = generalHoldingService;
        this.menuAccessGuard = menuAccessGuard;
    }

    private boolean hasAccess() {
        return menuAccessGuard.hasAccess(MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return menuAccessGuard.forbidden();
    }

    @Operation(summary = "List General holdings")
    @GetMapping("/holdings")
    public ResponseEntity<?> getHoldings() {
        if (!hasAccess()) return forbidden();
        List<GeneralHoldingResponse> holdings = generalHoldingService.getHoldings(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(holdings);
    }

    public record HoldingRequest(
        String assetType,
        String market,
        String name,
        String symbol,
        Long quantity,
        Double avgPrice
    ) {}

    @Operation(summary = "Create General holding")
    @PostMapping("/holdings")
    public ResponseEntity<?> addHolding(@RequestBody HoldingRequest request) {
        if (!hasAccess()) return forbidden();
        PortfolioAssetType assetType = resolveAssetType(request.assetType());
        ResponseEntity<?> invalid = validateRequest(request, assetType);
        if (invalid != null) return invalid;

        GeneralHoldingResponse created = generalHoldingService.addHolding(
            SecurityUtils.getCurrentUserId(),
            assetType,
            normalizedMarket(request.market(), assetType),
            request.name().trim(),
            request.symbol().trim().toUpperCase(),
            request.quantity(),
            request.avgPrice()
        );
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "Update General holding quantity and average price")
    @PutMapping("/holdings/{id}")
    public ResponseEntity<?> updateHolding(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();

        Long quantity = numberAsLong(body.get("quantity"));
        if (quantity == null || quantity <= 0) {
            return ResponseEntity.badRequest().body("Quantity must be greater than 0.");
        }

        Double avgPrice = numberAsDouble(body.get("avgPrice"));
        GeneralHoldingResponse updated = generalHoldingService.updateHolding(SecurityUtils.getCurrentUserId(), id, quantity, avgPrice);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Update General core flag")
    @PutMapping("/holdings/{id}/core")
    public ResponseEntity<?> updateCore(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();

        Object coreObj = body.get("core");
        if (!(coreObj instanceof Boolean core)) {
            return ResponseEntity.badRequest().body("core must be true or false.");
        }

        GeneralHoldingResponse updated = generalHoldingService.updateCore(SecurityUtils.getCurrentUserId(), id, core);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete General holding")
    @DeleteMapping("/holdings/{id}")
    public ResponseEntity<?> deleteHolding(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        generalHoldingService.deleteHolding(SecurityUtils.getCurrentUserId(), id);
        return ApiResponses.deletedMessage();
    }

    private PortfolioAssetType resolveAssetType(String raw) {
        try {
            return PortfolioAssetType.from(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("assetType must be STOCK or CASH.");
        }
    }

    private ResponseEntity<?> validateRequest(HoldingRequest request, PortfolioAssetType assetType) {
        if (request.quantity() == null || request.quantity() <= 0) {
            return ResponseEntity.badRequest().body("Quantity must be greater than 0.");
        }
        if (request.name() == null || request.name().isBlank()
            || request.symbol() == null || request.symbol().isBlank()) {
            return ResponseEntity.badRequest().body("Name and symbol are required.");
        }
        if (assetType == PortfolioAssetType.STOCK && (request.market() == null || request.market().isBlank())) {
            return ResponseEntity.badRequest().body("Market is required.");
        }
        return null;
    }

    private String normalizedMarket(String market, PortfolioAssetType assetType) {
        return assetType == PortfolioAssetType.CASH ? "KR" : market.trim().toUpperCase();
    }

    private Long numberAsLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private Double numberAsDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
