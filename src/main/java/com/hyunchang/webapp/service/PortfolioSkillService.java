package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.PortfolioSkillPublicResponse;
import com.hyunchang.webapp.entity.PortfolioSkill;
import com.hyunchang.webapp.repository.PortfolioSkillRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioSkillService {

    private final PortfolioSkillRepository portfolioSkillRepository;

    public PortfolioSkillService(PortfolioSkillRepository portfolioSkillRepository) {
        this.portfolioSkillRepository = portfolioSkillRepository;
    }

    public List<PortfolioSkill> findAll() {
        return portfolioSkillRepository.findAllByOrderBySortOrderAsc();
    }

    /** 공개 홈 화면용 조회 — 노출 필드를 공개 전용 DTO로 제한한다. */
    public List<PortfolioSkillPublicResponse> findAllPublic() {
        return findAll().stream().map(PortfolioSkillPublicResponse::from).toList();
    }

    public PortfolioSkill findById(Long id) {
        return portfolioSkillRepository
                .findById(id)
                .orElseThrow(() -> new RuntimeException("PortfolioSkill not found: " + id));
    }

    @Transactional
    public PortfolioSkill create(PortfolioSkill skill) {
        return portfolioSkillRepository.save(skill);
    }

    @Transactional
    public PortfolioSkill update(Long id, PortfolioSkill skill) {
        PortfolioSkill existing = findById(id);
        existing.setCssClass(skill.getCssClass());
        existing.setTitle(skill.getTitle());
        existing.setDescriptions(skill.getDescriptions());
        existing.setSortOrder(skill.getSortOrder());
        return portfolioSkillRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        PortfolioSkill skill = findById(id);
        Integer deletedOrder = skill.getSortOrder();
        portfolioSkillRepository.deleteById(id);
        if (deletedOrder != null) {
            portfolioSkillRepository.decrementSortOrderAfter(deletedOrder);
        }
    }
}
