package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.History;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface HistoryRepository extends JpaRepository<History, Long> {
    @Query("SELECT h FROM History h ORDER BY h.date DESC")
    List<History> findAll();
} 