package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    
    List<Expense> findByTypeOrderByCreatedAtDesc(String type);
    
    List<Expense> findByCategoryOrderByCreatedAtDesc(String category);
    
    @Query("SELECT e FROM Expense e WHERE e.createdAt BETWEEN :startDate AND :endDate ORDER BY e.createdAt DESC")
    List<Expense> findByDateRange(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
    
    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.type = :type")
    Long sumByType(@Param("type") String type);
    
    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.type = :type AND e.createdAt BETWEEN :startDate AND :endDate")
    Long sumByTypeAndDateRange(@Param("type") String type, @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
} 