package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.UsStrategyDeploymentRequests.*;
import com.hyunchang.webapp.dto.UsStrategyDeploymentResponse;
import com.hyunchang.webapp.service.strategybridge.UsStrategyDeploymentService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/kiwoom/us/strategy-deployments")
@PreAuthorize("hasRole('ADMIN')")
public class UsStrategyDeploymentController {
    private final UsStrategyDeploymentService service;

    public UsStrategyDeploymentController(UsStrategyDeploymentService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public UsStrategyDeploymentResponse status() {
        return service.status();
    }

    @PostMapping("/imports")
    public UsStrategyDeploymentResponse importCandidate(@Valid @RequestBody ImportRequest request) {
        return service.importCandidate(request);
    }

    @PostMapping("/approve")
    public UsStrategyDeploymentResponse approve(@Valid @RequestBody ApproveRequest request) {
        return service.approve(request);
    }

    @PostMapping("/revoke")
    public UsStrategyDeploymentResponse revoke(@Valid @RequestBody RevokeRequest request) {
        return service.revoke(request);
    }
}
