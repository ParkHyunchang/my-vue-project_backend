package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.History;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HistoryRepository extends JpaRepository<History, Long> {
    @Query(
            "SELECT h FROM History h ORDER BY "
                    + "CASE WHEN h.dateType = 'single' THEN h.date "
                    + "WHEN h.dateType = 'range' THEN h.startDate "
                    + "ELSE h.date END DESC")
    List<History> findAll();
}
