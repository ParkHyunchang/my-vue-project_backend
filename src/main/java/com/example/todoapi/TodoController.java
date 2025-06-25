package com.example.todoapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/todos")
@CrossOrigin(origins = "*") // Allow CORS for frontend
public class TodoController {
    
    @Autowired
    private TodoService todoService;
    
    // GET /todos - Get all todos
    @GetMapping
    public ResponseEntity<List<Todo>> getAllTodos(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        List<Todo> todos;
        
        if (q != null && !q.trim().isEmpty()) {
            // Search todos
            todos = todoService.searchTodos(q);
        } else {
            // Get all todos
            todos = todoService.getAllTodos();
        }
        
        // Add total count header for pagination
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Total-Count", String.valueOf(todoService.countAllTodos()));
        
        return ResponseEntity.ok().headers(headers).body(todos);
    }
    
    // GET /todos/{id} - Get todo by id
    @GetMapping("/{id}")
    public ResponseEntity<Todo> getTodoById(@PathVariable Long id) {
        Optional<Todo> todo = todoService.getTodoById(id);
        return todo.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    // POST /todos - Create new todo
    @PostMapping
    public ResponseEntity<Todo> createTodo(@RequestBody Todo todo) {
        Todo createdTodo = todoService.createTodo(todo);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdTodo);
    }
    
    // PUT /todos/{id} - Update todo
    @PutMapping("/{id}")
    public ResponseEntity<Todo> updateTodo(@PathVariable Long id, @RequestBody Todo todoDetails) {
        Optional<Todo> updatedTodo = todoService.updateTodo(id, todoDetails);
        return updatedTodo.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    // DELETE /todos/{id} - Delete todo
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTodo(@PathVariable Long id) {
        boolean deleted = todoService.deleteTodo(id);
        if (deleted) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    // GET /todos/search?q={keyword} - Search todos
    @GetMapping("/search")
    public ResponseEntity<List<Todo>> searchTodos(@RequestParam String q) {
        List<Todo> todos = todoService.searchTodos(q);
        return ResponseEntity.ok(todos);
    }
    
    // GET /todos/completed/{status} - Get todos by completion status
    @GetMapping("/completed/{status}")
    public ResponseEntity<List<Todo>> getTodosByStatus(@PathVariable boolean status) {
        List<Todo> todos = todoService.getTodosByStatus(status);
        return ResponseEntity.ok(todos);
    }
} 