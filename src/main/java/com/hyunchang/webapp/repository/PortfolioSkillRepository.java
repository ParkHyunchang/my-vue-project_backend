package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.PortfolioSkill;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PortfolioSkillRepository extends JpaRepository<PortfolioSkill, Long> {
    List<PortfolioSkill> findAllByOrderBySortOrderAsc();

    @Modifying
    @Query(
            "UPDATE PortfolioSkill p SET p.sortOrder = p.sortOrder - 1 WHERE p.sortOrder > :sortOrder")
    void decrementSortOrderAfter(@Param("sortOrder") Integer sortOrder);
}
