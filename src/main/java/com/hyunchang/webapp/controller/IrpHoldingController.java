package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.entity.IrpHolding;
import com.hyunchang.webapp.entity.PortfolioAssetType;
import com.hyunchang.webapp.service.IrpHoldingService;
import com.hyunchang.webapp.service.StockService;
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

@Tag(name = "Portfolio IRP", description = "IRP holdings API")
@RestController
@RequestMapping("/api/portfolio/irp")
public class IrpHoldingController {

    private static final String MENU_PATH = "/portfolio";

    private final IrpHoldingService irpHoldingService;
    private final MenuAccessGuard menuAccessGuard;
    private final StockService stockService;

    public IrpHoldingController(IrpHoldingService irpHoldingService,
                                MenuAccessGuard menuAccessGuard,
                                StockService stockService) {
        this.irpHoldingService = irpHoldingService;
        this.menuAccessGuard = menuAccessGuard;
        this.stockService = stockService;
    }

    private boolean hasAccess() {
        return menuAccessGuard.hasAccess(MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return menuAccessGuard.forbidden();
    }

    @Operation(summary = "List IRP holdings")
    @GetMapping("/holdings")
    public ResponseEntity<?> getHoldings() {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();
        List<IrpHolding> holdings = irpHoldingService.getHoldings(userId);
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

    @Operation(summary = "Create IRP holding")
    @PostMapping("/holdings")
    public ResponseEntity<?> addHolding(@RequestBody HoldingRequest request) {
        if (!hasAccess()) return forbidden();
        PortfolioAssetType assetType = resolveAssetType(request.assetType());
        ResponseEntity<?> invalid = validateRequest(request, assetType);
        if (invalid != null) return invalid;

        IrpHolding created = irpHoldingService.addHolding(
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

    @Operation(summary = "Update IRP holding quantity and average price")
    @PutMapping("/holdings/{id}")
    public ResponseEntity<?> updateHolding(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();

        Long quantity = numberAsLong(body.get("quantity"));
        if (quantity == null || quantity <= 0) {
            return ResponseEntity.badRequest().body("Quantity must be greater than 0.");
        }

        Double avgPrice = numberAsDouble(body.get("avgPrice"));
        IrpHolding updated = irpHoldingService.updateHolding(SecurityUtils.getCurrentUserId(), id, quantity, avgPrice);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Update IRP core flag")
    @PutMapping("/holdings/{id}/core")
    public ResponseEntity<?> updateCore(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();

        Object coreObj = body.get("core");
        if (!(coreObj instanceof Boolean core)) {
            return ResponseEntity.badRequest().body("core must be true or false.");
        }

        IrpHolding updated = irpHoldingService.updateCore(SecurityUtils.getCurrentUserId(), id, core);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete IRP holding")
    @DeleteMapping("/holdings/{id}")
    public ResponseEntity<?> deleteHolding(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        irpHoldingService.deleteHolding(SecurityUtils.getCurrentUserId(), id);
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
