package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.IsaHolding;
import com.hyunchang.webapp.entity.PortfolioAssetType;
import com.hyunchang.webapp.service.IsaHoldingService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.StockService;
import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Portfolio ISA", description = "ISA holdings API")
@RestController
@RequestMapping("/api/portfolio/isa")
public class IsaHoldingController {

    private static final String MENU_PATH = "/portfolio";

    private final IsaHoldingService isaHoldingService;
    private final MenuPermissionService menuPermissionService;
    private final StockService stockService;

    public IsaHoldingController(IsaHoldingService isaHoldingService,
                                MenuPermissionService menuPermissionService,
                                StockService stockService) {
        this.isaHoldingService = isaHoldingService;
        this.menuPermissionService = menuPermissionService;
        this.stockService = stockService;
    }

    private boolean hasAccess() {
        return menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied.");
    }

    @Operation(summary = "List ISA holdings")
    @GetMapping("/holdings")
    public ResponseEntity<?> getHoldings() {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();
        List<IsaHolding> holdings = isaHoldingService.getHoldings(userId);
        holdings.forEach(h -> {
            if (PortfolioAssetType.STOCK.name().equals(h.getAssetType())) {
                String kor = stockService.resolveStockName(h.getSymbol());
                if (kor != null && !kor.isBlank()) h.setName(kor);
            }
        });
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

    @Operation(summary = "Create ISA holding")
    @PostMapping("/holdings")
    public ResponseEntity<?> addHolding(@RequestBody HoldingRequest request) {
        if (!hasAccess()) return forbidden();
        PortfolioAssetType assetType = resolveAssetType(request.assetType());
        ResponseEntity<?> invalid = validateRequest(request, assetType);
        if (invalid != null) return invalid;

        IsaHolding created = isaHoldingService.addHolding(
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

    @Operation(summary = "Update ISA holding quantity and average price")
    @PutMapping("/holdings/{id}")
    public ResponseEntity<?> updateHolding(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();

        Long quantity = numberAsLong(body.get("quantity"));
        if (quantity == null || quantity <= 0) {
            return ResponseEntity.badRequest().body("Quantity must be greater than 0.");
        }

        Double avgPrice = numberAsDouble(body.get("avgPrice"));
        IsaHolding updated = isaHoldingService.updateHolding(SecurityUtils.getCurrentUserId(), id, quantity, avgPrice);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Update ISA core flag")
    @PutMapping("/holdings/{id}/core")
    public ResponseEntity<?> updateCore(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();

        Object coreObj = body.get("core");
        if (!(coreObj instanceof Boolean core)) {
            return ResponseEntity.badRequest().body("core must be true or false.");
        }

        IsaHolding updated = isaHoldingService.updateCore(SecurityUtils.getCurrentUserId(), id, core);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete ISA holding")
    @DeleteMapping("/holdings/{id}")
    public ResponseEntity<?> deleteHolding(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        isaHoldingService.deleteHolding(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok(Map.of("message", "Deleted."));
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
