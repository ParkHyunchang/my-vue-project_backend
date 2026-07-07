package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.entity.StockHolding;
import com.hyunchang.webapp.service.StockHoldingService;
import com.hyunchang.webapp.service.StockService;
import com.hyunchang.webapp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Portfolio", description = "내 잔고(포트폴리오) API")
@RestController
@RequestMapping("/api/portfolio")
public class StockHoldingController {

    private static final String MENU_PATH = "/portfolio";

    private final StockHoldingService stockHoldingService;
    private final MenuAccessGuard menuAccessGuard;
    private final StockService stockService;

    public StockHoldingController(
            StockHoldingService stockHoldingService,
            MenuAccessGuard menuAccessGuard,
            StockService stockService) {
        this.stockHoldingService = stockHoldingService;
        this.menuAccessGuard = menuAccessGuard;
        this.stockService = stockService;
    }

    private boolean hasAccess() {
        return menuAccessGuard.hasAccess(MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return menuAccessGuard.forbidden();
    }

    @Operation(summary = "현재 사용자 보유 종목 전체 조회")
    @GetMapping("/holdings")
    public ResponseEntity<?> getHoldings() {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();
        List<StockHolding> holdings = stockHoldingService.getHoldings(userId);
        // 한글명 자동 변환 (DB에 영문명이 저장된 기존 데이터 포함)
        holdings.forEach(
                h -> {
                    String kor = stockService.resolveStockName(h.getSymbol());
                    if (kor != null && !kor.isBlank()) h.setName(kor);
                });
        // ※ [STOCK/HOLDING] VIEW 로그는 프론트엔드의 logAudit 호출로 이관 (탭 클릭 시점 1회만 기록)
        return ResponseEntity.ok(holdings);
    }

    public record HoldingRequest(
            String market, String name, String symbol, Long quantity, Double avgPrice) {}

    @Operation(summary = "보유 종목 추가")
    @PostMapping("/holdings")
    public ResponseEntity<?> addHolding(@RequestBody HoldingRequest request) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();

        if (request.quantity() == null || request.quantity() <= 0) {
            return ResponseEntity.badRequest().body("보유수량은 1 이상이어야 합니다.");
        }
        if (request.market() == null
                || request.market().isBlank()
                || request.name() == null
                || request.name().isBlank()
                || request.symbol() == null
                || request.symbol().isBlank()) {
            return ResponseEntity.badRequest().body("시장, 종목명, 심볼은 필수입니다.");
        }

        StockHolding created =
                stockHoldingService.addHolding(
                        userId,
                        request.market().toUpperCase(),
                        request.name().trim(),
                        request.symbol().trim().toUpperCase(),
                        request.quantity(),
                        request.avgPrice());
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "보유 종목 수정 (수량/평단가)")
    @PutMapping("/holdings/{id}")
    public ResponseEntity<?> updateHolding(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
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

    @Operation(summary = "보유 종목 코어 여부 토글 (코어=장기 적립 / 위성=단타)")
    @PutMapping("/holdings/{id}/core")
    public ResponseEntity<?> updateCore(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();

        Object coreObj = body.get("core");
        if (!(coreObj instanceof Boolean core)) {
            return ResponseEntity.badRequest().body("core 값(true/false)이 필요합니다.");
        }

        StockHolding updated = stockHoldingService.updateCore(userId, id, core);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "보유 종목 삭제")
    @DeleteMapping("/holdings/{id}")
    public ResponseEntity<?> deleteHolding(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        String userId = SecurityUtils.getCurrentUserId();
        stockHoldingService.deleteHolding(userId, id);
        return ApiResponses.deletedMessage();
    }
}
