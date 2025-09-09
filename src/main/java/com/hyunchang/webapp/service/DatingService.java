package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.repository.DatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

@Service
public class DatingService {
    private final DatingRepository datingRepository;
    private static final String UPLOAD_DIR = getUploadDirectory();
    
    private static String getUploadDirectory() {
        // Docker 환경에서는 /app/uploads/images/ 사용, 로컬에서는 상대 경로 사용
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            // Linux/Unix 환경 (Docker 컨테이너)
            return "/app/uploads/images/";
        } else {
            // Windows 환경 (로컬 개발)
            return System.getProperty("user.dir") + "/uploads/images/";
        }
    }

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
        
        // 이미지 업데이트 처리
        if (dating.getImage() != null && !dating.getImage().trim().isEmpty()) {
            existingDating.setImage(dating.getImage());
        } else {
            existingDating.setImage(null);
        }
        
        // 다중 이미지 업데이트 처리
        if (dating.getImages() != null && !dating.getImages().trim().isEmpty()) {
            existingDating.setImages(dating.getImages());
        } else {
            existingDating.setImages(null);
        }
        
        return datingRepository.save(existingDating);
    }

    @Transactional
    public void delete(Long id) {
        Dating existingDating = findById(id);
        
        // 이미지 파일들 삭제
        deleteImageFiles(existingDating);
        
        // 데이터베이스에서 레코드 삭제
        datingRepository.deleteById(id);
    }
    
    private void deleteImageFiles(Dating dating) {
        try {
            // 단일 이미지 파일 삭제
            if (dating.getImage() != null && !dating.getImage().trim().isEmpty()) {
                deleteImageFile(dating.getImage());
            }
            
            // 다중 이미지 파일들 삭제
            if (dating.getImages() != null && !dating.getImages().trim().isEmpty()) {
                // JSON 문자열을 파싱하여 각 이미지 파일 삭제
                String[] imagePaths = dating.getImages().replace("[", "").replace("]", "").replace("\"", "").split(",");
                for (String imagePath : imagePaths) {
                    if (imagePath != null && !imagePath.trim().isEmpty()) {
                        deleteImageFile(imagePath.trim());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("이미지 파일 삭제 중 오류 발생: " + e.getMessage());
            // 파일 삭제 실패해도 데이터베이스 삭제는 계속 진행
        }
    }
    
    public void deleteImageFile(String imagePath) {
        try {
            if (imagePath != null && !imagePath.trim().isEmpty()) {
                // URL에서 파일명만 추출
                String fileName = imagePath.substring(imagePath.lastIndexOf("/") + 1);
                Path filePath = Paths.get(UPLOAD_DIR + fileName);
                
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                    System.out.println("이미지 파일 삭제됨: " + filePath.toString());
                } else {
                    System.out.println("삭제할 이미지 파일이 존재하지 않음: " + filePath.toString());
                }
            }
        } catch (IOException e) {
            System.err.println("이미지 파일 삭제 실패: " + imagePath + " - " + e.getMessage());
        }
    }
}
