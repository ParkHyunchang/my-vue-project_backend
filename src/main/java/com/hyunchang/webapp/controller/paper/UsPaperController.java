package com.hyunchang.webapp.controller.paper;

import com.hyunchang.webapp.dto.paper.UsPaperDtos;
import com.hyunchang.webapp.service.paper.UsPaperService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/us-paper/sessions")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "us-paper.enabled", havingValue = "true")
public class UsPaperController {
    private final UsPaperService service;

    public UsPaperController(UsPaperService service) {
        this.service = service;
    }

    @GetMapping
    public List<UsPaperDtos.Status> list() {
        return service.list();
    }

    @PostMapping
    public UsPaperDtos.Status create(@Valid @RequestBody UsPaperDtos.CreateRequest request) {
        return service.create(request.initialCashUsd());
    }

    @GetMapping("/{id}")
    public UsPaperDtos.Status get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/start")
    public UsPaperDtos.Status start(@PathVariable Long id) {
        return service.start(id);
    }

    @PostMapping("/{id}/stop")
    public UsPaperDtos.Status stop(@PathVariable Long id) {
        return service.stop(id);
    }

    @PostMapping("/{id}/poll")
    public UsPaperDtos.Status poll(@PathVariable Long id) {
        return service.poll(id);
    }
}
