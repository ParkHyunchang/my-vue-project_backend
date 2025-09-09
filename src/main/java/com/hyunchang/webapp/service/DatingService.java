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
        
        // 이미지 업데이트 시 기존 이미지 파일은 보존
        // 새로운 이미지가 제공된 경우에만 업데이트
        if (dating.getImage() != null && !dating.getImage().trim().isEmpty()) {
            existingDating.setImage(dating.getImage());
        }
        // 이미지가 빈 문자열로 제공된 경우에도 기존 이미지는 유지
        // (이미지 삭제를 원하는 경우 명시적으로 처리해야 함)
        
        return datingRepository.save(existingDating);
    }

    @Transactional
    public void delete(Long id) {
        // 데이터베이스에서만 레코드 삭제
        // 이미지 파일은 서버에 보존됨 (실수로 삭제되는 것을 방지)
        datingRepository.deleteById(id);
    }
}
