package com.hyunchang.webapp.repository.paper;

import com.hyunchang.webapp.entity.paper.UsPaperEquity;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface UsPaperEquityRepository extends JpaRepository<UsPaperEquity, Long> {
    List<UsPaperEquity> findTop100BySessionIdOrderByIdDesc(Long sessionId);
}
