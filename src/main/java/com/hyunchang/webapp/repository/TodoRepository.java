package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Todo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TodoRepository extends JpaRepository<Todo, Long> {
} 