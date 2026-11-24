package com.hyunchang.webapp.repository.paper;

import com.hyunchang.webapp.entity.paper.UsPaperEvent;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface UsPaperEventRepository extends JpaRepository<UsPaperEvent, Long> {
    List<UsPaperEvent> findTop100BySessionIdOrderByIdDesc(Long sessionId);
}
