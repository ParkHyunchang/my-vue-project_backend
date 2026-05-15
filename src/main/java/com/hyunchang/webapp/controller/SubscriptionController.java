package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Subscription;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.SubscriptionService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {
    private static final String MENU_PATH = "/subscription";

    private final SubscriptionService subscriptionService;
    private final MenuPermissionService menuPermissionService;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  MenuPermissionService menuPermissionService) {
        this.subscriptionService = subscriptionService;
        this.menuPermissionService = menuPermissionService;
    }

    private boolean hasAccess() {
        return menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
    }

    @GetMapping
    public ResponseEntity<?> findAll() {
        if (!hasAccess()) return forbidden();
        List<Subscription> list = subscriptionService.findAll();
        return ResponseEntity.ok(list);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Subscription subscription) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(subscriptionService.create(subscription));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Subscription subscription) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(subscriptionService.update(id, subscription));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        subscriptionService.delete(id);
        return ResponseEntity.ok().body("삭제되었습니다.");
    }
}
