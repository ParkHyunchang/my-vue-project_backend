package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.repository.DatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class DatingService {
    private final DatingRepository datingRepository;

    public DatingService(DatingRepository datingRepository) {
        this.datingRepository = datingRepository;
    }

    public List<Dating> findAll() {
        return datingRepository.findAll();
    }

    public Dating findById(Long id) {
        return datingRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dating memory not found"));
    }

    private void validateDating(Dating dating) {
        if (dating.getTitle() == null || dating.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("제목은 필수 입력값입니다.");
        }
        if (dating.getDate() == null) {
            throw new IllegalArgumentException("날짜는 필수 입력값입니다.");
        }
        if (dating.getDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
        }
        if (dating.getCategory() == null || dating.getCategory().trim().isEmpty()) {
            throw new IllegalArgumentException("카테고리는 필수 입력값입니다.");
        }
    }

    @Transactional
    public Dating create(Dating dating) {
        validateDating(dating);
        return datingRepository.save(dating);
    }

    @Transactional
    public Dating update(Long id, Dating dating) {
        validateDating(dating);
        Dating existingDating = findById(id);
        existingDating.setTitle(dating.getTitle());
        existingDating.setDate(dating.getDate());
        existingDating.setCategory(dating.getCategory());
        existingDating.setPartner(dating.getPartner());
        existingDating.setDescription(dating.getDescription());
        existingDating.setLocation(dating.getLocation());
        existingDating.setImage(dating.getImage());
        return datingRepository.save(existingDating);
    }

    @Transactional
    public void delete(Long id) {
        datingRepository.deleteById(id);
    }
}
