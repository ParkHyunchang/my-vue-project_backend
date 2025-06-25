package com.example.todoapi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TodoRepository extends JpaRepository<Todo, Long> {
    
    // Find todos by completion status
    List<Todo> findByCompleted(boolean completed);
    
    // Find todos by title containing (case-insensitive search)
    @Query("SELECT t FROM Todo t WHERE LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Todo> findByTitleContainingIgnoreCase(@Param("keyword") String keyword);
    
    // Find todos by title containing and completion status
    @Query("SELECT t FROM Todo t WHERE LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%')) AND t.completed = :completed")
    List<Todo> findByTitleContainingIgnoreCaseAndCompleted(@Param("keyword") String keyword, @Param("completed") boolean completed);
    
    // Count todos by completion status
    long countByCompleted(boolean completed);
} 