package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.PortfolioSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PortfolioSkillRepository extends JpaRepository<PortfolioSkill, Long> {
    List<PortfolioSkill> findAllByOrderBySortOrderAsc();
}
