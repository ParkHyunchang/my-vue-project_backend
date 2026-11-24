package com.hyunchang.webapp.repository.paper;

import com.hyunchang.webapp.entity.paper.UsPaperFill;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface UsPaperFillRepository extends JpaRepository<UsPaperFill, Long> {
    List<UsPaperFill> findTop100BySessionIdOrderByIdDesc(Long sessionId);
}
