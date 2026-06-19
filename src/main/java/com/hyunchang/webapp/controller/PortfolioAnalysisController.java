package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.PortfolioAnalysisService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/portfolio/analyze")
public class PortfolioAnalysisController {
    // /stock 메뉴 권한 있어야 호출 가능 (보유 종목·시총 데이터 접근 권한과 동일)
    private static final String MENU_PATH = "/stock";

    private final PortfolioAnalysisService portfolioAnalysisService;
    private final MenuPermissionService menuPermissionService;

    public PortfolioAnalysisController(PortfolioAnalysisService portfolioAnalysisService,
                                       MenuPermissionService menuPermissionService) {
        this.portfolioAnalysisService = portfolioAnalysisService;
        this.menuPermissionService = menuPermissionService;
    }

    @PostMapping
    public ResponseEntity<?> analyze(@RequestBody(required = false) Map<String, Object> requestBody) {
        if (!menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
        }
        return ResponseEntity.ok(portfolioAnalysisService.analyze(requestBody));
    }
}
