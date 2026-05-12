package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Todo;
import com.hyunchang.webapp.service.TodoService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(defaultValue = "5") int size) {
        if (!hasAccess()) return forbidden();

        Pageable pageable = PageRequest.of(page, size);
        Page<Todo> todoPage = todoService.findAll(pageable);

        return ResponseEntity
            .ok()
            .header("X-Total-Count", String.valueOf(todoPage.getTotalElements()))
            .body(todoPage.getContent());
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

}
