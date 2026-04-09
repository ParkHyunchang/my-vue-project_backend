package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Diary;
import com.hyunchang.webapp.service.DiaryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/diary")
public class DiaryController {

    private final DiaryService diaryService;

    public DiaryController(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    @GetMapping
    public List<Diary> findAll() {
        return diaryService.findAll();
    }

    @GetMapping("/date/{date}")
    public ResponseEntity<?> findByDate(@PathVariable String date) {
        Diary diary = diaryService.findByDate(LocalDate.parse(date));
        if (diary == null) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok(diary);
    }

    @PostMapping
    public ResponseEntity<Diary> save(@RequestBody Map<String, String> body) {
        LocalDate date = LocalDate.parse(body.get("diaryDate"));
        String content = body.get("content");
        return ResponseEntity.ok(diaryService.save(date, content));
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<Diary> analyze(@PathVariable Long id) {
        return ResponseEntity.ok(diaryService.analyze(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        diaryService.delete(id);
        return ResponseEntity.ok().build();
    }
}
