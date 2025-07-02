package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.domain.History;
import com.hyunchang.webapp.service.HistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/histories")
public class HistoryController {
    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public List<History> findAll() {
        return historyService.findAll();
    }

    @GetMapping("/{id}")
    public History findById(@PathVariable Long id) {
        return historyService.findById(id);
    }

    @PostMapping
    public History create(@RequestBody History history) {
        return historyService.create(history);
    }

    @PutMapping("/{id}")
    public History update(@PathVariable Long id, @RequestBody History history) {
        return historyService.update(id, history);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        historyService.delete(id);
    }
} 