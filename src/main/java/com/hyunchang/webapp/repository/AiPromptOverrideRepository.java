package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.AiPromptOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiPromptOverrideRepository extends JpaRepository<AiPromptOverride, Long> {
    Optional<AiPromptOverride> findByPromptKey(String promptKey);
    void deleteByPromptKey(String promptKey);
    boolean existsByPromptKey(String promptKey);
}
