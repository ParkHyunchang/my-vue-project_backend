package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.KiwoomAccountOverviewResponse;
import com.hyunchang.webapp.service.KiwoomAccountOverviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kiwoom/accounts")
@PreAuthorize("hasRole('ADMIN')")
public class KiwoomAccountOverviewController {
    private final KiwoomAccountOverviewService service;

    public KiwoomAccountOverviewController(KiwoomAccountOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ResponseEntity<KiwoomAccountOverviewResponse> overview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview());
    }
}
