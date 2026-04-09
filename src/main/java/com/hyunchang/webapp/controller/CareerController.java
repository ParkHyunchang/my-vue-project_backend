package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Career;
import com.hyunchang.webapp.service.CareerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/career")
public class CareerController {

    private final CareerService careerService;

    public CareerController(CareerService careerService) {
        this.careerService = careerService;
    }

    @GetMapping
    public List<Career> findAll() {
        return careerService.findAll();
    }

    @GetMapping("/{id}")
    public Career findById(@PathVariable Long id) {
        return careerService.findById(id);
    }

    @PostMapping
    public Career create(@RequestBody Career career) {
        return careerService.create(career);
    }

    @PutMapping("/{id}")
    public Career update(@PathVariable Long id, @RequestBody Career career) {
        return careerService.update(id, career);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        careerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
