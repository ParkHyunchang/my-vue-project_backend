package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.AiPromptOverride;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiPromptOverrideRepository extends JpaRepository<AiPromptOverride, Long> {
    Optional<AiPromptOverride> findByPromptKey(String promptKey);

    void deleteByPromptKey(String promptKey);

    boolean existsByPromptKey(String promptKey);
}
