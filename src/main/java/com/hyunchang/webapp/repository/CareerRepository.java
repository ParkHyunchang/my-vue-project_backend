package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Career;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CareerRepository extends JpaRepository<Career, Long> {
    List<Career> findAllByOrderBySortOrderAsc();
}
