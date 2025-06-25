package com.example.todoapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TodoService {
    
    @Autowired
    private TodoRepository todoRepository;
    
    // Get all todos
    public List<Todo> getAllTodos() {
        return todoRepository.findAll();
    }
    
    // Get todos with pagination
    public Page<Todo> getTodosWithPagination(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return todoRepository.findAll(pageable);
    }
    
    // Get todo by id
    public Optional<Todo> getTodoById(Long id) {
        return todoRepository.findById(id);
    }
    
    // Create new todo
    public Todo createTodo(Todo todo) {
        return todoRepository.save(todo);
    }
    
    // Update todo
    public Optional<Todo> updateTodo(Long id, Todo todoDetails) {
        return todoRepository.findById(id)
                .map(todo -> {
                    todo.setTitle(todoDetails.getTitle());
                    todo.setCompleted(todoDetails.isCompleted());
                    return todoRepository.save(todo);
                });
    }
    
    // Delete todo
    public boolean deleteTodo(Long id) {
        if (todoRepository.existsById(id)) {
            todoRepository.deleteById(id);
            return true;
        }
        return false;
    }
    
    // Search todos by keyword
    public List<Todo> searchTodos(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return todoRepository.findAll();
        }
        return todoRepository.findByTitleContainingIgnoreCase(keyword.trim());
    }
    
    // Search todos by keyword with pagination
    public Page<Todo> searchTodosWithPagination(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        if (keyword == null || keyword.trim().isEmpty()) {
            return todoRepository.findAll(pageable);
        }
        // For pagination with search, we need to implement custom logic
        // For now, we'll return all matching results
        List<Todo> results = todoRepository.findByTitleContainingIgnoreCase(keyword.trim());
        return Page.empty(pageable); // Placeholder - would need custom implementation
    }
    
    // Get todos by completion status
    public List<Todo> getTodosByStatus(boolean completed) {
        return todoRepository.findByCompleted(completed);
    }
    
    // Count todos by completion status
    public long countTodosByStatus(boolean completed) {
        return todoRepository.countByCompleted(completed);
    }
    
    // Count all todos
    public long countAllTodos() {
        return todoRepository.count();
    }
} 