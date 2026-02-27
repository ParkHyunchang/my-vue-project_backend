package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Todo;
import com.hyunchang.webapp.service.TodoService;
import com.hyunchang.webapp.service.MenuCrudPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/todos")
public class TodoController {
    private final TodoService todoService;
    private final MenuCrudPermissionService menuCrudPermissionService;

    public TodoController(TodoService todoService, MenuCrudPermissionService menuCrudPermissionService) {
        this.todoService = todoService;
        this.menuCrudPermissionService = menuCrudPermissionService;
    }

    @GetMapping
    public ResponseEntity<?> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canRead(roleName, "/todos")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("조회 권한이 없습니다.");
        }
        
        Pageable pageable = PageRequest.of(page, size);
        Page<Todo> todoPage = todoService.findAll(pageable);
        
        return ResponseEntity
            .ok()
            .header("X-Total-Count", String.valueOf(todoPage.getTotalElements()))
            .body(todoPage.getContent());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canRead(roleName, "/todos")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("조회 권한이 없습니다.");
        }
        return ResponseEntity.ok(todoService.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Todo todo) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canCreate(roleName, "/todos")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("생성 권한이 없습니다.");
        }
        return ResponseEntity.ok(todoService.create(todo));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Todo todo) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canUpdate(roleName, "/todos")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("수정 권한이 없습니다.");
        }
        return ResponseEntity.ok(todoService.update(id, todo));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canDelete(roleName, "/todos")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("삭제 권한이 없습니다.");
        }
        todoService.delete(id);
        return ResponseEntity.ok().body("삭제되었습니다.");
    }
    
} 