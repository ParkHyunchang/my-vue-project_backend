package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Todo;
import com.hyunchang.webapp.service.TodoService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/todos")
public class TodoController {
    private static final String MENU_PATH = "/todos";

    private final TodoService todoService;
    private final MenuPermissionService menuPermissionService;

    public TodoController(TodoService todoService, MenuPermissionService menuPermissionService) {
        this.todoService = todoService;
        this.menuPermissionService = menuPermissionService;
    }

    private boolean hasAccess() {
        return menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
    }

    @GetMapping
    public ResponseEntity<?> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "created") String sort,
            @RequestParam(defaultValue = "desc") String dir) {
        if (!hasAccess()) return forbidden();

        Page<Todo> todoPage = todoService.search(q, status, priority, category, sort, dir, page, size);

        return ResponseEntity
            .ok()
            .header("X-Total-Count", String.valueOf(todoPage.getTotalElements()))
            .body(todoPage.getContent());
    }

    @GetMapping("/categories")
    public ResponseEntity<?> categories() {
        if (!hasAccess()) return forbidden();
        List<String> list = todoService.findCategories();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(todoService.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Todo todo) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(todoService.create(todo));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Todo todo) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(todoService.update(id, todo));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        todoService.delete(id);
        return ResponseEntity.ok().body("삭제되었습니다.");
    }

    @DeleteMapping("/completed")
    public ResponseEntity<?> deleteAllCompleted() {
        if (!hasAccess()) return forbidden();
        int deleted = todoService.deleteAllCompleted();
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

}
