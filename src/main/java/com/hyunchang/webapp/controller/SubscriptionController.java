package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.entity.Subscription;
import com.hyunchang.webapp.service.SubscriptionService;
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

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {
    private static final String MENU_PATH = "/subscription";

    private final SubscriptionService subscriptionService;
    private final MenuAccessGuard menuAccessGuard;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  MenuAccessGuard menuAccessGuard) {
        this.subscriptionService = subscriptionService;
        this.menuAccessGuard = menuAccessGuard;
    }

    private boolean hasAccess() {
        return menuAccessGuard.hasAccess(MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return menuAccessGuard.forbidden();
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
        return ApiResponses.deleted();
    }
}
