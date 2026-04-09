package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.PortfolioSkill;
import com.hyunchang.webapp.service.PortfolioSkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/portfolio-skills")
public class PortfolioSkillController {

    private final PortfolioSkillService portfolioSkillService;

    public PortfolioSkillController(PortfolioSkillService portfolioSkillService) {
        this.portfolioSkillService = portfolioSkillService;
    }

    @GetMapping
    public List<PortfolioSkill> findAll() {
        return portfolioSkillService.findAll();
    }

    @GetMapping("/{id}")
    public PortfolioSkill findById(@PathVariable Long id) {
        return portfolioSkillService.findById(id);
    }

    @PostMapping
    public PortfolioSkill create(@RequestBody PortfolioSkill skill) {
        return portfolioSkillService.create(skill);
    }

    @PutMapping("/{id}")
    public PortfolioSkill update(@PathVariable Long id, @RequestBody PortfolioSkill skill) {
        return portfolioSkillService.update(id, skill);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        portfolioSkillService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
