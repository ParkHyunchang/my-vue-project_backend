package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Experience;
import com.hyunchang.webapp.service.ExperienceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/experience")
public class ExperienceController {

    private final ExperienceService experienceService;

    public ExperienceController(ExperienceService experienceService) {
        this.experienceService = experienceService;
    }

    @GetMapping
    public List<Experience> findAll() {
        return experienceService.findAll();
    }

    @GetMapping("/{id}")
    public Experience findById(@PathVariable Long id) {
        return experienceService.findById(id);
    }

    @PostMapping
    public Experience create(@RequestBody Experience experience) {
        return experienceService.create(experience);
    }

    @PutMapping("/{id}")
    public Experience update(@PathVariable Long id, @RequestBody Experience experience) {
        return experienceService.update(id, experience);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        experienceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
