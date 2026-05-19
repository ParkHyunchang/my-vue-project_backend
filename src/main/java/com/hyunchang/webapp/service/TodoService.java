package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Todo;
import com.hyunchang.webapp.entity.todo.TodoHistory;
import com.hyunchang.webapp.repository.TodoRepository;
import com.hyunchang.webapp.repository.todo.TodoHistoryRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    public Page<Todo> search(String q, String status, Integer priority, String category,
                             String sort, String dir, int page, int size) {
        String trimmedQ = (q == null || q.isBlank()) ? null : q.trim();
        String normalizedStatus = (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) ? null : status;
        String normalizedCategory = (category == null || category.isBlank()) ? null : category;
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sortObj = switch (sort == null ? "" : sort.toLowerCase()) {
            case "due", "duedate" -> Sort.by(direction, "dueDate").and(Sort.by(Sort.Direction.DESC, "id"));
            case "priority" -> Sort.by(direction, "priority").and(Sort.by(Sort.Direction.DESC, "id"));
            case "title" -> Sort.by(direction, "title").and(Sort.by(Sort.Direction.DESC, "id"));
            default -> Sort.by(direction, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        };
        Pageable pageable = PageRequest.of(page, size, sortObj);
        return todoRepository.search(trimmedQ, normalizedStatus, priority, normalizedCategory, pageable);
    }

    public List<String> findCategories() {
        return todoRepository.findDistinctCategories();
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
        if (todo.getPriority() == null) {
            todo.setPriority(2); // 보통
        }
        normalizeCategory(todo);

        Todo savedTodo = todoRepository.save(todo);

        TodoHistory history = new TodoHistory();
        history.setAction("CREATE");
        history.setTodoId(savedTodo.getId());
        history.setTodoTitle(savedTodo.getTitle());
        history.setCreatedDt(LocalDateTime.now());
        todoHistoryRepository.save(history);

        log.info("[TODO] user={}({}), CREATE id={} title={} done={} priority={} due={} category={}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            savedTodo.getId(), savedTodo.getTitle(), savedTodo.getDone(),
            savedTodo.getPriority(), savedTodo.getDueDate(), savedTodo.getCategory());
        return savedTodo;
    }

    @Transactional
    public Todo update(Long id, Todo todo) {
        Todo existingTodo = findById(id);

        String oldTitle = existingTodo.getTitle();
        Boolean oldDone = existingTodo.getDone();
        String oldDescription = existingTodo.getDescription();
        Integer oldPriority = existingTodo.getPriority();
        Object oldDue = existingTodo.getDueDate();
        String oldCategory = existingTodo.getCategory();

        Boolean newDone = todo.getDone() != null ? todo.getDone() : false;
        Integer newPriority = todo.getPriority() != null ? todo.getPriority() : 2;
        existingTodo.setTitle(todo.getTitle());
        existingTodo.setDone(newDone);
        existingTodo.setDescription(todo.getDescription());
        existingTodo.setPriority(newPriority);
        existingTodo.setDueDate(todo.getDueDate());
        existingTodo.setCategory(todo.getCategory());
        normalizeCategory(existingTodo);

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
        if (!Objects.equals(oldPriority, updatedTodo.getPriority())) {
            diffs.add(String.format("priority %s→%s", oldPriority, updatedTodo.getPriority()));
        }
        if (!Objects.equals(oldDue, updatedTodo.getDueDate())) {
            diffs.add(String.format("due %s→%s", oldDue, updatedTodo.getDueDate()));
        }
        if (!Objects.equals(oldCategory, updatedTodo.getCategory())) {
            diffs.add(String.format("category '%s'→'%s'", oldCategory, updatedTodo.getCategory()));
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
    public int deleteAllCompleted() {
        long count = todoRepository.countByDoneTrue();
        if (count == 0) {
            log.info("[TODO] user={}({}), BULK_DELETE_COMPLETED count=0 (no-op)",
                SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName());
            return 0;
        }

        int deleted = todoRepository.deleteAllByDoneTrue();

        TodoHistory history = new TodoHistory();
        history.setAction("BULK_DELETE_COMPLETED");
        history.setTodoTitle(String.format("완료 항목 %d개 일괄 삭제", deleted));
        history.setCreatedDt(LocalDateTime.now());
        todoHistoryRepository.save(history);

        log.info("[TODO] user={}({}), BULK_DELETE_COMPLETED count={}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(), deleted);
        return deleted;
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

    private void normalizeCategory(Todo todo) {
        if (todo.getCategory() != null) {
            String trimmed = todo.getCategory().trim();
            todo.setCategory(trimmed.isEmpty() ? null : trimmed);
        }
    }
}
