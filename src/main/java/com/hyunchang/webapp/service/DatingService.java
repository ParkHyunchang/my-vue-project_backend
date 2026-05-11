package com.hyunchang.webapp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.exception.ForbiddenException;
import com.hyunchang.webapp.exception.NotFoundException;
import com.hyunchang.webapp.repository.DatingRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.UploadPathUtil;
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
import java.time.LocalDate;
import java.util.List;

@Service
public class DatingService {
    private static final Logger log = LoggerFactory.getLogger(DatingService.class);
    private final DatingRepository datingRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Path UPLOAD_ROOT = UploadPathUtil.imagesSubdirPath("dating");

    public DatingService(DatingRepository datingRepository, UserRepository userRepository) {
        this.datingRepository = datingRepository;
        this.userRepository = userRepository;
    }

    public List<Dating> findAll() {
        return datingRepository.findAll();
    }

    public Dating findById(Long id) {
        return datingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("추억을 찾을 수 없습니다. id: " + id));
    }

    private void validateDating(Dating dating) {
        if (dating.getTitle() == null || dating.getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("제목은 필수 입력값입니다.");
        }

        if (dating.getDateType() == null || dating.getDateType().trim().isEmpty()) {
            throw new IllegalArgumentException("날짜 타입은 필수 입력값입니다.");
        }

        if ("single".equals(dating.getDateType())) {
            if (dating.getDate() == null) {
                throw new IllegalArgumentException("날짜는 필수 입력값입니다.");
            }
            if (dating.getDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
        } else if ("range".equals(dating.getDateType())) {
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
    public Dating create(Dating dating, String ownerUserId) {
        validateDating(dating);
        userRepository.findByUserId(ownerUserId).ifPresent(dating::setUser);
        return datingRepository.save(dating);
    }

    @Transactional
    public Dating update(Long id, Dating dating, String currentUserId, String roleName) {
        validateDating(dating);
        Dating existingDating = findById(id);
        verifyOwnership(existingDating, currentUserId, roleName);
        existingDating.setTitle(dating.getTitle());
        existingDating.setDate(dating.getDate());
        existingDating.setDateType(dating.getDateType());
        existingDating.setStartDate(dating.getStartDate());
        existingDating.setEndDate(dating.getEndDate());
        existingDating.setCategory(dating.getCategory());
        existingDating.setDescription(dating.getDescription());
        existingDating.setLocation(dating.getLocation());

        if (dating.getImage() != null && !dating.getImage().trim().isEmpty()) {
            existingDating.setImage(dating.getImage());
        } else {
            existingDating.setImage(null);
        }

        if (dating.getImages() != null && !dating.getImages().trim().isEmpty()) {
            existingDating.setImages(dating.getImages());
        } else {
            existingDating.setImages(null);
        }

        return datingRepository.save(existingDating);
    }

    @Transactional
    public void delete(Long id, String currentUserId, String roleName) {
        Dating existingDating = datingRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("이미 삭제된 추억입니다."));
        verifyOwnership(existingDating, currentUserId, roleName);

        deleteImageFiles(existingDating);

        try {
            datingRepository.delete(existingDating);
        } catch (ObjectOptimisticLockingFailureException | EmptyResultDataAccessException e) {
            log.warn("이미 삭제된 추억 삭제 시도 - id: {}, message: {}", id, e.getMessage());
            throw new EntityNotFoundException("이미 삭제된 추억입니다.");
        }
    }

    private void verifyOwnership(Dating dating, String currentUserId, String roleName) {
        if ("ADMIN".equals(roleName)) {
            return;
        }
        User owner = dating.getUser();
        // 레거시: 소유자 정보가 없는 기존 데이터는 권한 검사를 스킵 (메뉴 권한으로 이미 게이팅됨)
        if (owner == null) {
            return;
        }
        if (currentUserId == null || !currentUserId.equals(owner.getUserId())) {
            throw new ForbiddenException("해당 데이터에 대한 권한이 없습니다.");
        }
    }

    private void deleteImageFiles(Dating dating) {
        try {
            if (dating.getImage() != null && !dating.getImage().trim().isEmpty()) {
                deleteImageFile(dating.getImage());
            }

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
                } catch (JsonProcessingException parseException) {
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
            log.error("미디어 파일 삭제 중 오류 발생", e);
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
                String fileName = imagePath.substring(imagePath.lastIndexOf("/") + 1);
                Path filePath = UPLOAD_ROOT.resolve(fileName).normalize();
                if (!filePath.startsWith(UPLOAD_ROOT)) {
                    log.warn("dating 이미지 삭제 path traversal 시도 차단: {}", imagePath);
                    return;
                }
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                }
            }
        } catch (IOException e) {
            log.error("미디어 파일 삭제 실패: path={}", imagePath, e);
        }
    }
}
