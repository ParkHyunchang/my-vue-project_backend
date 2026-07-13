package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.dto.TodoRequest;
import com.hyunchang.webapp.entity.Todo;
import com.hyunchang.webapp.service.TodoService;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/todos")
public class TodoController {
    private static final String MENU_PATH = "/todos";

    private final TodoService todoService;
    private final MenuAccessGuard menuAccessGuard;

    public TodoController(TodoService todoService, MenuAccessGuard menuAccessGuard) {
        this.todoService = todoService;
        this.menuAccessGuard = menuAccessGuard;
    }

    private boolean hasAccess() {
        return menuAccessGuard.hasAccess(MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return menuAccessGuard.forbidden();
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

        Page<Todo> todoPage =
                todoService.search(q, status, priority, category, sort, dir, page, size);

        return ResponseEntity.ok()
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
    public ResponseEntity<?> create(@RequestBody TodoRequest request) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(todoService.create(toEntity(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody TodoRequest request) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(todoService.update(id, toEntity(request)));
    }

    private Todo toEntity(TodoRequest request) {
        Todo todo = new Todo();
        todo.setTitle(request.title());
        todo.setDescription(request.description());
        todo.setDone(request.done());
        todo.setPriority(request.priority());
        todo.setDueDate(request.dueDate());
        todo.setCategory(request.category());
        return todo;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        todoService.delete(id);
        return ApiResponses.deleted();
    }

    @DeleteMapping("/completed")
    public ResponseEntity<?> deleteAllCompleted() {
        if (!hasAccess()) return forbidden();
        int deleted = todoService.deleteAllCompleted();
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }
}
