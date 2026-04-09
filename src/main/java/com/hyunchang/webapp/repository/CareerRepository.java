package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Career;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CareerRepository extends JpaRepository<Career, Long> {
    List<Career> findAllByOrderBySortOrderAsc();

    @Modifying
    @Query("UPDATE Career c SET c.sortOrder = c.sortOrder - 1 WHERE c.sortOrder > :sortOrder")
    void decrementSortOrderAfter(@Param("sortOrder") Integer sortOrder);
}
