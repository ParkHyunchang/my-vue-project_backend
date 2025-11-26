package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.repository.DatingRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String UPLOAD_DIR = getUploadDirectory();
    
    private static String getUploadDirectory() {
        // Docker 환경에서는 실제 NAS 경로 사용, 로컬에서는 상대 경로 사용
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            // Linux/Unix 환경 (Docker 컨테이너) - 실제 NAS 경로
            return "/volume1/docker/my-vue-project_backend/uploads/images/dating/";
        } else {
            // Windows 환경 (로컬 개발)
            return System.getProperty("user.dir") + "/uploads/images/dating/";
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
        
        // 날짜 타입에 따른 검증
        if (dating.getDateType() == null || dating.getDateType().trim().isEmpty()) {
            throw new IllegalArgumentException("날짜 타입은 필수 입력값입니다.");
        }
        
        if ("single".equals(dating.getDateType())) {
            // 단일 날짜 검증
            if (dating.getDate() == null) {
                throw new IllegalArgumentException("날짜는 필수 입력값입니다.");
            }
            if (dating.getDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
        } else if ("range".equals(dating.getDateType())) {
            // 기간 날짜 검증
            if (dating.getStartDate() == null) {
                throw new IllegalArgumentException("시작일은 필수 입력값입니다.");
            }
            if (dating.getEndDate() == null) {
                throw new IllegalArgumentException("종료일은 필수 입력값입니다.");
            }
            if (dating.getStartDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
            if (dating.getEndDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
            if (dating.getStartDate().isAfter(dating.getEndDate())) {
                throw new IllegalArgumentException("시작일은 종료일보다 이전이어야 합니다.");
            }
        } else {
            throw new IllegalArgumentException("올바른 날짜 타입을 선택해주세요.");
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
        existingDating.setDateType(dating.getDateType());
        existingDating.setStartDate(dating.getStartDate());
        existingDating.setEndDate(dating.getEndDate());
        existingDating.setCategory(dating.getCategory());
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
        Dating existingDating = datingRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("이미 삭제된 추억입니다."));
        
        // 이미지 파일들 삭제
        deleteImageFiles(existingDating);
        
        // 데이터베이스에서 레코드 삭제
        try {
            datingRepository.delete(existingDating);
        } catch (ObjectOptimisticLockingFailureException | EmptyResultDataAccessException e) {
            System.out.println("이미 삭제된 추억 삭제 시도 - id: " + id + ", message: " + e.getMessage());
            throw new EntityNotFoundException("이미 삭제된 추억입니다.");
        }
    }
    
    private void deleteImageFiles(Dating dating) {
        try {
            // 단일 이미지 파일 삭제
            if (dating.getImage() != null && !dating.getImage().trim().isEmpty()) {
                deleteImageFile(dating.getImage());
            }
            
            // 다중 이미지 파일들 삭제
            if (dating.getImages() != null && !dating.getImages().trim().isEmpty()) {
                boolean parsed = false;
                try {
                    JsonNode node = objectMapper.readTree(dating.getImages());
                    if (node.isArray()) {
                        parsed = true;
                        ArrayNode arrayNode = (ArrayNode) node;
                        for (JsonNode item : arrayNode) {
                            String mediaPath = extractMediaPath(item);
                            if (mediaPath != null && !mediaPath.trim().isEmpty()) {
                                deleteImageFile(mediaPath.trim());
                            }
                        }
                    }
                } catch (Exception parseException) {
                    parsed = false;
                }

                if (!parsed) {
                    String[] imagePaths = dating.getImages()
                            .replace("[", "")
                            .replace("]", "")
                            .replace("\"", "")
                            .split(",");
                    for (String imagePath : imagePaths) {
                        if (imagePath != null && !imagePath.trim().isEmpty()) {
                            deleteImageFile(imagePath.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("미디어 파일 삭제 중 오류 발생: " + e.getMessage());
            // 파일 삭제 실패해도 데이터베이스 삭제는 계속 진행
        }
    }
    
    private String extractMediaPath(JsonNode node) {
        if (node == null) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            JsonNode pathNode = node.get("path");
            if (pathNode != null && pathNode.isTextual()) {
                return pathNode.asText();
            }
            JsonNode urlNode = node.get("url");
            if (urlNode != null && urlNode.isTextual()) {
                return urlNode.asText();
            }
        }
        return "";
    }
    
    public void deleteImageFile(String imagePath) {
        try {
            if (imagePath != null && !imagePath.trim().isEmpty()) {
                // URL에서 파일명만 추출
                String fileName = imagePath.substring(imagePath.lastIndexOf("/") + 1);
                Path filePath = Paths.get(UPLOAD_DIR + fileName);
                
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                }
            }
        } catch (IOException e) {
            System.err.println("미디어 파일 삭제 실패: " + imagePath + " - " + e.getMessage());
        }
    }
}
