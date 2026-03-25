package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hyunchang.webapp.entity.DatingSys;
import com.hyunchang.webapp.exception.NotFoundException;
import com.hyunchang.webapp.repository.DatingSysRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class DatingSysService {
    private static final Logger log = LoggerFactory.getLogger(DatingSysService.class);
    private final DatingSysRepository datingSysRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String UPLOAD_DIR = getUploadDirectory();

    private static String getUploadDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            return "/volume1/docker/my-vue-project_backend/uploads/images/dating_sys/";
        } else {
            return System.getProperty("user.dir") + "/uploads/images/dating_sys/";
        }
    }

    public DatingSysService(DatingSysRepository datingSysRepository) {
        this.datingSysRepository = datingSysRepository;
    }

    public List<DatingSys> findAll() {
        return datingSysRepository.findAll();
    }

    public DatingSys findById(Long id) {
        return datingSysRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("추억을 찾을 수 없습니다. id: " + id));
    }

    private void validateDatingSys(DatingSys datingSys) {
        if (datingSys.getTitle() == null || datingSys.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("제목은 필수 입력값입니다.");
        }

        if (datingSys.getDateType() == null || datingSys.getDateType().trim().isEmpty()) {
            throw new IllegalArgumentException("날짜 타입은 필수 입력값입니다.");
        }

        if ("single".equals(datingSys.getDateType())) {
            if (datingSys.getDate() == null) {
                throw new IllegalArgumentException("날짜는 필수 입력값입니다.");
            }
            if (datingSys.getDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
        } else if ("range".equals(datingSys.getDateType())) {
            if (datingSys.getStartDate() == null) {
                throw new IllegalArgumentException("시작일은 필수 입력값입니다.");
            }
            if (datingSys.getEndDate() == null) {
                throw new IllegalArgumentException("종료일은 필수 입력값입니다.");
            }
            if (datingSys.getStartDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
            if (datingSys.getEndDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
            if (datingSys.getStartDate().isAfter(datingSys.getEndDate())) {
                throw new IllegalArgumentException("시작일은 종료일보다 이전이어야 합니다.");
            }
        } else {
            throw new IllegalArgumentException("올바른 날짜 타입을 선택해주세요.");
        }

        if (datingSys.getCategory() == null || datingSys.getCategory().trim().isEmpty()) {
            throw new IllegalArgumentException("카테고리는 필수 입력값입니다.");
        }
    }

    @Transactional
    public DatingSys create(DatingSys datingSys) {
        validateDatingSys(datingSys);
        return datingSysRepository.save(datingSys);
    }

    @Transactional
    public DatingSys update(Long id, DatingSys datingSys) {
        validateDatingSys(datingSys);
        DatingSys existing = findById(id);
        existing.setTitle(datingSys.getTitle());
        existing.setDate(datingSys.getDate());
        existing.setDateType(datingSys.getDateType());
        existing.setStartDate(datingSys.getStartDate());
        existing.setEndDate(datingSys.getEndDate());
        existing.setCategory(datingSys.getCategory());
        existing.setDescription(datingSys.getDescription());
        existing.setLocation(datingSys.getLocation());

        if (datingSys.getImage() != null && !datingSys.getImage().trim().isEmpty()) {
            existing.setImage(datingSys.getImage());
        } else {
            existing.setImage(null);
        }

        if (datingSys.getImages() != null && !datingSys.getImages().trim().isEmpty()) {
            existing.setImages(datingSys.getImages());
        } else {
            existing.setImages(null);
        }

        return datingSysRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        DatingSys existing = datingSysRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("이미 삭제된 추억입니다."));

        deleteImageFiles(existing);

        try {
            datingSysRepository.delete(existing);
        } catch (ObjectOptimisticLockingFailureException | EmptyResultDataAccessException e) {
            log.warn("이미 삭제된 추억 삭제 시도 - id: {}, message: {}", id, e.getMessage());
            throw new EntityNotFoundException("이미 삭제된 추억입니다.");
        }
    }

    private void deleteImageFiles(DatingSys datingSys) {
        try {
            if (datingSys.getImage() != null && !datingSys.getImage().trim().isEmpty()) {
                deleteImageFile(datingSys.getImage());
            }

            if (datingSys.getImages() != null && !datingSys.getImages().trim().isEmpty()) {
                boolean parsed = false;
                try {
                    JsonNode node = objectMapper.readTree(datingSys.getImages());
                    if (node.isArray()) {
                        parsed = true;
                        for (JsonNode item : (ArrayNode) node) {
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
                    String[] imagePaths = datingSys.getImages()
                            .replace("[", "").replace("]", "").replace("\"", "").split(",");
                    for (String imagePath : imagePaths) {
                        if (imagePath != null && !imagePath.trim().isEmpty()) {
                            deleteImageFile(imagePath.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("미디어 파일 삭제 중 오류 발생: " + e.getMessage());
        }
    }

    private String extractMediaPath(JsonNode node) {
        if (node == null) return "";
        if (node.isTextual()) return node.asText();
        if (node.isObject()) {
            JsonNode pathNode = node.get("path");
            if (pathNode != null && pathNode.isTextual()) return pathNode.asText();
            JsonNode urlNode = node.get("url");
            if (urlNode != null && urlNode.isTextual()) return urlNode.asText();
        }
        return "";
    }

    public void deleteImageFile(String imagePath) {
        try {
            if (imagePath != null && !imagePath.trim().isEmpty()) {
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
