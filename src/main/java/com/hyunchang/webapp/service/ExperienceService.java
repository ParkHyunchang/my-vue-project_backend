package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.ExperiencePublicResponse;
import com.hyunchang.webapp.entity.Experience;
import com.hyunchang.webapp.repository.ExperienceRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperienceService {

    private final ExperienceRepository experienceRepository;

    public ExperienceService(ExperienceRepository experienceRepository) {
        this.experienceRepository = experienceRepository;
    }

    public List<Experience> findAll() {
        return experienceRepository.findAllByOrderBySortOrderAsc();
    }

    /** 공개 홈 화면용 조회 — 노출 필드를 공개 전용 DTO로 제한한다. */
    public List<ExperiencePublicResponse> findAllPublic() {
        return findAll().stream().map(ExperiencePublicResponse::from).toList();
    }

    public Experience findById(Long id) {
        return experienceRepository
                .findById(id)
                .orElseThrow(() -> new RuntimeException("Experience not found: " + id));
    }

    @Transactional
    public Experience create(Experience experience) {
        return experienceRepository.save(experience);
    }

    @Transactional
    public Experience update(Long id, Experience experience) {
        Experience existing = findById(id);
        existing.setTitle(experience.getTitle());
        existing.setSubtitle(experience.getSubtitle());
        existing.setDescription(experience.getDescription());
        existing.setPeriod(experience.getPeriod());
        existing.setSortOrder(experience.getSortOrder());
        return experienceRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        Experience experience = findById(id);
        Integer deletedOrder = experience.getSortOrder();
        experienceRepository.deleteById(id);
        if (deletedOrder != null) {
            experienceRepository.decrementSortOrderAfter(deletedOrder);
        }
    }
}
