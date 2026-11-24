package com.hyunchang.webapp.repository.paper;

import com.hyunchang.webapp.entity.paper.UsPaperSession;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UsPaperSessionRepository extends JpaRepository<UsPaperSession, Long> {
    List<UsPaperSession> findTop100ByOrderByIdDesc();

    List<UsPaperSession> findByStateIn(Collection<String> states);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UsPaperSession s where s.id=:id")
    Optional<UsPaperSession> lockById(@Param("id") Long id);
}
