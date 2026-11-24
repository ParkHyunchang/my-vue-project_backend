package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.dto.PortfolioAccountDtos.*;
import com.hyunchang.webapp.service.PortfolioAccountService;
import com.hyunchang.webapp.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/portfolio/accounts")
public class PortfolioAccountController {
    private final PortfolioAccountService service;
    private final MenuAccessGuard access;

    public PortfolioAccountController(PortfolioAccountService service, MenuAccessGuard access) {
        this.service = service;
        this.access = access;
    }

    private boolean allowed() {
        return access.hasAccess("/portfolio");
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (!allowed()) return access.forbidden();
        return ResponseEntity.ok(service.list(SecurityUtils.getCurrentUserId()));
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody AccountRequest request) {
        if (!allowed()) return access.forbidden();
        return ResponseEntity.ok(service.create(SecurityUtils.getCurrentUserId(), request));
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> update(
            @PathVariable String key, @Valid @RequestBody AccountRequest request) {
        if (!allowed()) return access.forbidden();
        return ResponseEntity.ok(service.update(SecurityUtils.getCurrentUserId(), key, request));
    }

    @GetMapping("/{key}/holdings")
    public ResponseEntity<?> holdings(@PathVariable String key) {
        if (!allowed()) return access.forbidden();
        return ResponseEntity.ok(service.holdings(SecurityUtils.getCurrentUserId(), key));
    }

    @PostMapping("/{key}/holdings")
    public ResponseEntity<?> add(
            @PathVariable String key, @Valid @RequestBody HoldingRequest request) {
        if (!allowed()) return access.forbidden();
        return ResponseEntity.ok(service.add(SecurityUtils.getCurrentUserId(), key, request));
    }

    @PutMapping("/{key}/holdings/{id}")
    public ResponseEntity<?> updateHolding(
            @PathVariable String key,
            @PathVariable Long id,
            @Valid @RequestBody HoldingUpdate request) {
        if (!allowed()) return access.forbidden();
        return ResponseEntity.ok(
                service.updateHolding(SecurityUtils.getCurrentUserId(), key, id, request));
    }

    @PutMapping("/{key}/holdings/{id}/core")
    public ResponseEntity<?> core(
            @PathVariable String key,
            @PathVariable Long id,
            @Valid @RequestBody CoreUpdate request) {
        if (!allowed()) return access.forbidden();
        return ResponseEntity.ok(
                service.core(SecurityUtils.getCurrentUserId(), key, id, request.core()));
    }

    @DeleteMapping("/{key}/holdings/{id}")
    public ResponseEntity<?> delete(@PathVariable String key, @PathVariable Long id) {
        if (!allowed()) return access.forbidden();
        service.delete(SecurityUtils.getCurrentUserId(), key, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> duplicate() {
        return ResponseEntity.status(409).body("중복 등록 또는 동시 변경입니다. 새로고침 후 확인해 주세요.");
    }
}
