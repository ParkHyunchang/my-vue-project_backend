package com.hyunchang.webapp.repository.todo;

import com.hyunchang.webapp.entity.todo.TodoHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TodoHistoryRepository extends JpaRepository<TodoHistory, Long> {
} 