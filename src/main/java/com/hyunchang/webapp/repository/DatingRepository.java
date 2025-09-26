package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Dating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DatingRepository extends JpaRepository<Dating, Long> {
    @Query("SELECT d FROM Dating d ORDER BY " +
           "CASE WHEN d.dateType = 'single' THEN d.date " +
           "WHEN d.dateType = 'range' THEN d.startDate " +
           "ELSE d.date END DESC")
    List<Dating> findAll();
}
