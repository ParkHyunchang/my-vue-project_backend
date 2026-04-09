package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Experience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExperienceRepository extends JpaRepository<Experience, Long> {
    List<Experience> findAllByOrderBySortOrderAsc();

    @Modifying
    @Query("UPDATE Experience e SET e.sortOrder = e.sortOrder - 1 WHERE e.sortOrder > :sortOrder")
    void decrementSortOrderAfter(@Param("sortOrder") Integer sortOrder);
}
