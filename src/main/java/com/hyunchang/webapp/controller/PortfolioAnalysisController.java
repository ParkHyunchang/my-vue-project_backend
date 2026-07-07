package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.service.PortfolioAnalysisService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio/analyze")
public class PortfolioAnalysisController {
    // /stock 메뉴 권한 있어야 호출 가능 (보유 종목·시총 데이터 접근 권한과 동일)
    private static final String MENU_PATH = "/stock";

    private final PortfolioAnalysisService portfolioAnalysisService;
    private final MenuAccessGuard menuAccessGuard;

    public PortfolioAnalysisController(
            PortfolioAnalysisService portfolioAnalysisService, MenuAccessGuard menuAccessGuard) {
        this.portfolioAnalysisService = portfolioAnalysisService;
        this.menuAccessGuard = menuAccessGuard;
    }

    @PostMapping
    public ResponseEntity<?> analyze(
            @RequestBody(required = false) Map<String, Object> requestBody) {
        if (!menuAccessGuard.hasAccess(MENU_PATH)) return menuAccessGuard.forbidden();
        return ResponseEntity.ok(portfolioAnalysisService.analyze(requestBody));
    }
}
