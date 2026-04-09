package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Career;
import com.hyunchang.webapp.repository.CareerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CareerService {

    private final CareerRepository careerRepository;

    public CareerService(CareerRepository careerRepository) {
        this.careerRepository = careerRepository;
    }

    public List<Career> findAll() {
        return careerRepository.findAllByOrderBySortOrderAsc();
    }

    public Career findById(Long id) {
        return careerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Career not found: " + id));
    }

    @Transactional
    public Career create(Career career) {
        return careerRepository.save(career);
    }

    @Transactional
    public Career update(Long id, Career career) {
        Career existing = findById(id);
        existing.setIcon(career.getIcon());
        existing.setCompany(career.getCompany());
        existing.setPeriod(career.getPeriod());
        existing.setBadge(career.getBadge());
        existing.setRoleDesc(career.getRoleDesc());
        existing.setProjects(career.getProjects());
        existing.setTags(career.getTags());
        existing.setSortOrder(career.getSortOrder());
        return careerRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        Career career = findById(id);
        Integer deletedOrder = career.getSortOrder();
        careerRepository.deleteById(id);
        if (deletedOrder != null) {
            careerRepository.decrementSortOrderAfter(deletedOrder);
        }
    }
}
