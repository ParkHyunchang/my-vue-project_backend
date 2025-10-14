package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.History;
import com.hyunchang.webapp.repository.HistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

@Service
public class HistoryService {
    private final HistoryRepository historyRepository;
    private static final String UPLOAD_DIR = getUploadDirectory();
    
    private static String getUploadDirectory() {
        // Docker 환경에서는 실제 NAS 경로 사용, 로컬에서는 상대 경로 사용
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            // Linux/Unix 환경 (Docker 컨테이너) - 실제 NAS 경로
            return "/volume1/docker/my-vue-project_backend/uploads/images/history/";
        } else {
            // Windows 환경 (로컬 개발)
            return System.getProperty("user.dir") + "/uploads/images/history/";
        }
    }

    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    public List<History> findAll() {
        return historyRepository.findAll();
    }

    public History findById(Long id) {
        return historyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("History not found"));
    }

    private void validateHistory(History history) {
        if (history.getTitle() == null || history.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("제목은 필수 입력값입니다.");
        }
        
        // 날짜 타입에 따른 검증
        if (history.getDateType() == null || history.getDateType().trim().isEmpty()) {
            throw new IllegalArgumentException("날짜 타입은 필수 입력값입니다.");
        }
        
        if ("single".equals(history.getDateType())) {
            // 단일 날짜 검증
            if (history.getDate() == null) {
                throw new IllegalArgumentException("날짜는 필수 입력값입니다.");
            }
            if (history.getDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
        } else if ("range".equals(history.getDateType())) {
            // 기간 날짜 검증
            if (history.getStartDate() == null) {
                throw new IllegalArgumentException("시작일은 필수 입력값입니다.");
            }
            if (history.getEndDate() == null) {
                throw new IllegalArgumentException("종료일은 필수 입력값입니다.");
            }
            if (history.getStartDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
            if (history.getEndDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
            if (history.getStartDate().isAfter(history.getEndDate())) {
                throw new IllegalArgumentException("시작일은 종료일보다 이전이어야 합니다.");
            }
        } else {
            throw new IllegalArgumentException("올바른 날짜 타입을 선택해주세요.");
        }
        
        if (history.getCategory() == null || history.getCategory().trim().isEmpty()) {
            throw new IllegalArgumentException("카테고리는 필수 입력값입니다.");
        }
    }

    @Transactional
    public History create(History history) {
        validateHistory(history);
        return historyRepository.save(history);
    }

    @Transactional
    public History update(Long id, History history) {
        validateHistory(history);
        History existingHistory = findById(id);
        existingHistory.setTitle(history.getTitle());
        existingHistory.setDate(history.getDate());
        existingHistory.setDateType(history.getDateType());
        existingHistory.setStartDate(history.getStartDate());
        existingHistory.setEndDate(history.getEndDate());
        existingHistory.setCategory(history.getCategory());
        existingHistory.setDescription(history.getDescription());
        existingHistory.setLocation(history.getLocation());
        existingHistory.setImage(history.getImage());
        existingHistory.setImages(history.getImages());
        return historyRepository.save(existingHistory);
    }

    @Transactional
    public void delete(Long id) {
        History existingHistory = findById(id);
        
        // 이미지 파일 삭제
        deleteImageFile(existingHistory.getImage());
        
        // 데이터베이스에서 레코드 삭제
        historyRepository.deleteById(id);
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
            System.err.println("히스토리 이미지 파일 삭제 실패: " + imagePath + " - " + e.getMessage());
        }
    }
} 