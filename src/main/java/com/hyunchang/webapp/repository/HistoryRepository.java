package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.domain.History;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoryRepository extends JpaRepository<History, Long> {
} 