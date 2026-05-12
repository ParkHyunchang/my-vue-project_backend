package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Todo;
import com.hyunchang.webapp.entity.todo.TodoHistory;
import com.hyunchang.webapp.repository.TodoRepository;
import com.hyunchang.webapp.repository.todo.TodoHistoryRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class TodoService {
    private static final Logger log = LoggerFactory.getLogger(TodoService.class);

    private final TodoRepository todoRepository;
    private final TodoHistoryRepository todoHistoryRepository;

    public TodoService(TodoRepository todoRepository, TodoHistoryRepository todoHistoryRepository) {
        this.todoRepository = todoRepository;
        this.todoHistoryRepository = todoHistoryRepository;
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

        TodoHistory history = new TodoHistory();
        history.setAction("CREATE");
        history.setTodoId(savedTodo.getId());
        history.setTodoTitle(savedTodo.getTitle());
        history.setCreatedDt(LocalDateTime.now());
        todoHistoryRepository.save(history);

        log.info("[TODO] user={}({}), CREATE id={} title={} done={}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            savedTodo.getId(), savedTodo.getTitle(), savedTodo.getDone());
        return savedTodo;
    }

    @Transactional
    public Todo update(Long id, Todo todo) {
        Todo existingTodo = findById(id);

        String oldTitle = existingTodo.getTitle();
        Boolean oldDone = existingTodo.getDone();
        String oldDescription = existingTodo.getDescription();

        Boolean newDone = todo.getDone() != null ? todo.getDone() : false;
        existingTodo.setTitle(todo.getTitle());
        existingTodo.setDone(newDone);
        existingTodo.setDescription(todo.getDescription());

        Todo updatedTodo = todoRepository.save(existingTodo);

        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(oldTitle, updatedTodo.getTitle())) {
            diffs.add(String.format("title '%s'→'%s'", oldTitle, updatedTodo.getTitle()));
        }
        if (!Objects.equals(oldDone, updatedTodo.getDone())) {
            diffs.add(String.format("done %s→%s", oldDone, updatedTodo.getDone()));
        }
        if (!Objects.equals(oldDescription, updatedTodo.getDescription())) {
            diffs.add("description (변경됨)");
        }

        TodoHistory history = new TodoHistory();
        history.setAction("UPDATE");
        history.setTodoId(updatedTodo.getId());
        history.setTodoTitle(updatedTodo.getTitle());
        history.setCreatedDt(LocalDateTime.now());
        todoHistoryRepository.save(history);

        log.info("[TODO] user={}({}), UPDATE id={} {}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            updatedTodo.getId(),
            diffs.isEmpty() ? "(변경 없음)" : String.join(", ", diffs));
        return updatedTodo;
    }

    @Transactional
    public void delete(Long id) {
        Todo todo = findById(id);

        TodoHistory history = new TodoHistory();
        history.setAction("DELETE");
        history.setTodoId(todo.getId());
        history.setTodoTitle(todo.getTitle());
        history.setCreatedDt(LocalDateTime.now());
        todoHistoryRepository.save(history);

        todoRepository.deleteById(id);

        log.info("[TODO] user={}({}), DELETE id={} title={} done={}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            todo.getId(), todo.getTitle(), todo.getDone());
    }
}
