package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.domain.Todo;
import com.hyunchang.webapp.service.TodoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.util.List;

@RestController
@RequestMapping("/todos")
public class TodoController {
    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @PostConstruct
    public void init() {
        // 테스트용 초기 데이터 추가
        if (todoService.findAll().isEmpty()) {
            todoService.create(new Todo(null, "테스트 할일 1", "설명 1", false));
            todoService.create(new Todo(null, "테스트 할일 2", "설명 2", false));
        }
    }

    @GetMapping
    public ResponseEntity<List<Todo>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Todo> todoPage = todoService.findAll(pageable);
        
        return ResponseEntity
            .ok()
            .header("X-Total-Count", String.valueOf(todoPage.getTotalElements()))
            .body(todoPage.getContent());
    }

    @GetMapping("/{id}")
    public Todo findById(@PathVariable Long id) {
        return todoService.findById(id);
    }

    @PostMapping
    public Todo create(@RequestBody Todo todo) {
        return todoService.create(todo);
    }

    @PutMapping("/{id}")
    public Todo update(@PathVariable Long id, @RequestBody Todo todo) {
        return todoService.update(id, todo);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        todoService.delete(id);
    }
} 