package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Dating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DatingRepository extends JpaRepository<Dating, Long> {
    @Query("SELECT d FROM Dating d ORDER BY d.date DESC")
    List<Dating> findAll();
}
