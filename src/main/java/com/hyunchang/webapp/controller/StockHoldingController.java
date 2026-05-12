package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.StockHolding;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.StockHoldingService;
import com.hyunchang.webapp.service.StockService;
import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Portfolio", description = "내 잔고(포트폴리오) API")
@RestController
@RequestMapping("/api/portfolio")
public class StockHoldingController {

    private static final String MENU_PATH = "/portfolio";

    private final StockHoldingService stockHoldingService;
    private final MenuPermissionService menuPermissionService;
    private final StockService stockService;

    public StockHoldingController(StockHoldingService stockHoldingService,
                                  MenuPermissionService menuPermissionService,
                                  StockService stockService) {
        this.stockHoldingService = stockHoldingService;
        this.menuPermissionService = menuPermissionService;
        this.stockService = stockService;
    }

    private boolean hasAccess() {
        return menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
    }

    @Operation(summary = "현재 사용자 보유 종목 전체 조회")
    @GetMapping("/holdings")
    public ResponseEntity<?> getHoldings() {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();
        List<StockHolding> holdings = stockHoldingService.getHoldings(userId);
        // 한글명 자동 변환 (DB에 영문명이 저장된 기존 데이터 포함)
        holdings.forEach(h -> {
            String kor = stockService.resolveStockName(h.getSymbol());
            if (kor != null && !kor.isBlank()) h.setName(kor);
        });
        return ResponseEntity.ok(holdings);
    }

    public record HoldingRequest(
        String market,
        String name,
        String symbol,
        Long quantity,
        Double avgPrice
    ) {}

    @Operation(summary = "보유 종목 추가")
    @PostMapping("/holdings")
    public ResponseEntity<?> addHolding(@RequestBody HoldingRequest request) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();

        if (request.quantity() == null || request.quantity() <= 0) {
            return ResponseEntity.badRequest().body("보유수량은 1 이상이어야 합니다.");
        }
        if (request.market() == null || request.market().isBlank()
            || request.name() == null || request.name().isBlank()
            || request.symbol() == null || request.symbol().isBlank()) {
            return ResponseEntity.badRequest().body("시장, 종목명, 심볼은 필수입니다.");
        }

        StockHolding created = stockHoldingService.addHolding(
            userId,
            request.market().toUpperCase(),
            request.name().trim(),
            request.symbol().trim().toUpperCase(),
            request.quantity(),
            request.avgPrice()
        );
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "보유 종목 수정 (수량/평단가)")
    @PutMapping("/holdings/{id}")
    public ResponseEntity<?> updateHolding(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();

        Object qtyObj = body.get("quantity");
        Long quantity = null;
        if (qtyObj instanceof Number n) {
            quantity = n.longValue();
        }
        if (quantity == null || quantity <= 0) {
            return ResponseEntity.badRequest().body("보유수량은 1 이상이어야 합니다.");
        }

        Object avgObj = body.get("avgPrice");
        Double avgPrice = null;
        if (avgObj instanceof Number n) {
            avgPrice = n.doubleValue();
        }

        StockHolding updated = stockHoldingService.updateHolding(userId, id, quantity, avgPrice);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "보유 종목 삭제")
    @DeleteMapping("/holdings/{id}")
    public ResponseEntity<?> deleteHolding(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();
        stockHoldingService.deleteHolding(userId, id);
        return ResponseEntity.ok(Map.of("message", "삭제되었습니다."));
    }
}
