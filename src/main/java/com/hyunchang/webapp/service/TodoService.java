package com.hyunchang.webapp.service;

import com.hyunchang.webapp.domain.Todo;
import com.hyunchang.webapp.domain.History;
import com.hyunchang.webapp.repository.TodoRepository;
import com.hyunchang.webapp.repository.HistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TodoService {
    private final TodoRepository todoRepository;
    private final HistoryRepository historyRepository;

    public TodoService(TodoRepository todoRepository, HistoryRepository historyRepository) {
        this.todoRepository = todoRepository;
        this.historyRepository = historyRepository;
    }

    public List<Todo> findAll() {
        return todoRepository.findAll();
    }

    public Page<Todo> findAll(Pageable pageable) {
        return todoRepository.findAll(pageable);
    }

    public Todo findById(Long id) {
        return todoRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Todo not found"));
    }

    @Transactional
    public Todo create(Todo todo) {
        if (todo.getDone() == null) {
            todo.setDone(false);
        }
        
        Todo savedTodo = todoRepository.save(todo);
        
        History history = new History();
        history.setAction("CREATE");
        history.setTodoId(savedTodo.getId());
        history.setTodoTitle(savedTodo.getTitle());
        history.setCreatedAt(LocalDateTime.now());
        historyRepository.save(history);
        
        return savedTodo;
    }

    @Transactional
    public Todo update(Long id, Todo todo) {
        Todo existingTodo = findById(id);
        existingTodo.setTitle(todo.getTitle());
        existingTodo.setDone(todo.getDone() != null ? todo.getDone() : false);
        existingTodo.setDescription(todo.getDescription());
        
        Todo updatedTodo = todoRepository.save(existingTodo);
        
        History history = new History();
        history.setAction("UPDATE");
        history.setTodoId(updatedTodo.getId());
        history.setTodoTitle(updatedTodo.getTitle());
        history.setCreatedAt(LocalDateTime.now());
        historyRepository.save(history);
        
        return updatedTodo;
    }

    @Transactional
    public void delete(Long id) {
        Todo todo = findById(id);
        
        History history = new History();
        history.setAction("DELETE");
        history.setTodoId(todo.getId());
        history.setTodoTitle(todo.getTitle());
        history.setCreatedAt(LocalDateTime.now());
        historyRepository.save(history);
        
        todoRepository.deleteById(id);
    }
} 