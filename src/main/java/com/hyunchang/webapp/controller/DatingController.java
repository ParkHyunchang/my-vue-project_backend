package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.service.DatingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dating")
public class DatingController {
    private final DatingService datingService;

    public DatingController(DatingService datingService) {
        this.datingService = datingService;
    }

    @GetMapping
    public List<Dating> findAll() {
        return datingService.findAll();
    }

    @GetMapping("/{id}")
    public Dating findById(@PathVariable Long id) {
        return datingService.findById(id);
    }

    @PostMapping
    public Dating create(@RequestBody Dating dating) {
        return datingService.create(dating);
    }

    @PutMapping("/{id}")
    public Dating update(@PathVariable Long id, @RequestBody Dating dating) {
        return datingService.update(id, dating);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        datingService.delete(id);
    }
}
