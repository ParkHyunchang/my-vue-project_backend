package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.dto.ManualRecordsCleanupDtos.CleanupRequest;
import com.hyunchang.webapp.service.ManualRecordsCleanupService;
import com.hyunchang.webapp.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/portfolio/manual-records")
@PreAuthorize("hasRole('ADMIN')")
public class ManualRecordsCleanupController {
    private final ManualRecordsCleanupService service;
    private final MenuAccessGuard access;

    public ManualRecordsCleanupController(
            ManualRecordsCleanupService service, MenuAccessGuard access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        if (!access.hasAccess("/portfolio")) return access.forbidden();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.summary(SecurityUtils.getCurrentUserId()));
    }

    @DeleteMapping
    public ResponseEntity<?> delete(@Valid @RequestBody CleanupRequest request) {
        if (!access.hasAccess("/portfolio")) return access.forbidden();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.delete(SecurityUtils.getCurrentUserId(), request.confirmation()));
    }
}
