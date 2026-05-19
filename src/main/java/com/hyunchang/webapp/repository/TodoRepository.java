package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Todo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    @Query("""
        SELECT t FROM Todo t
        WHERE (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%'))
                          OR LOWER(COALESCE(t.description, '')) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:status IS NULL
               OR (:status = 'done' AND t.done = true)
               OR (:status = 'active' AND (t.done = false OR t.done IS NULL)))
          AND (:priority IS NULL OR t.priority = :priority)
          AND (:category IS NULL OR t.category = :category)
    """)
    Page<Todo> search(@Param("q") String q,
                      @Param("status") String status,
                      @Param("priority") Integer priority,
                      @Param("category") String category,
                      Pageable pageable);

    @Query("SELECT DISTINCT t.category FROM Todo t WHERE t.category IS NOT NULL AND t.category <> '' ORDER BY t.category ASC")
    List<String> findDistinctCategories();

    long countByDoneTrue();

    @Modifying
    @Query("DELETE FROM Todo t WHERE t.done = true")
    int deleteAllByDoneTrue();
}
