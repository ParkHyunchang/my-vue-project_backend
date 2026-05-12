package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.History;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.exception.ForbiddenException;
import com.hyunchang.webapp.repository.HistoryRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import com.hyunchang.webapp.util.UploadPathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class HistoryService {
    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private final HistoryRepository historyRepository;
    private final UserRepository userRepository;
    private static final Path UPLOAD_ROOT = UploadPathUtil.imagesSubdirPath("history");

    public HistoryService(HistoryRepository historyRepository, UserRepository userRepository) {
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
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

        if (history.getDateType() == null || history.getDateType().trim().isEmpty()) {
            throw new IllegalArgumentException("날짜 타입은 필수 입력값입니다.");
        }

        if ("single".equals(history.getDateType())) {
            if (history.getDate() == null) {
                throw new IllegalArgumentException("날짜는 필수 입력값입니다.");
            }
            if (history.getDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
            }
        } else if ("range".equals(history.getDateType())) {
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
    public History create(History history, String ownerUserId) {
        validateHistory(history);
        userRepository.findByUserId(ownerUserId).ifPresent(history::setUser);
        History saved = historyRepository.save(history);
        log.info("[HISTORY] user={}({}), CREATE id={} title={} dateType={} date={} category={}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getTitle(), saved.getDateType(),
            saved.getDate() != null ? saved.getDate() : (saved.getStartDate() + "~" + saved.getEndDate()),
            saved.getCategory());
        return saved;
    }

    @Transactional
    public History update(Long id, History history, String currentUserId, String roleName) {
        validateHistory(history);
        History existingHistory = findById(id);
        verifyOwnership(existingHistory, currentUserId, roleName);

        String oldTitle = existingHistory.getTitle();
        LocalDate oldDate = existingHistory.getDate();
        String oldDateType = existingHistory.getDateType();
        LocalDate oldStart = existingHistory.getStartDate();
        LocalDate oldEnd = existingHistory.getEndDate();
        String oldCategory = existingHistory.getCategory();
        String oldDescription = existingHistory.getDescription();
        String oldLocation = existingHistory.getLocation();
        String oldImage = existingHistory.getImage();
        String oldImages = existingHistory.getImages();

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
        History saved = historyRepository.save(existingHistory);

        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(oldTitle, saved.getTitle())) diffs.add(String.format("title '%s'→'%s'", oldTitle, saved.getTitle()));
        if (!Objects.equals(oldDateType, saved.getDateType())) diffs.add(String.format("dateType %s→%s", oldDateType, saved.getDateType()));
        if (!Objects.equals(oldDate, saved.getDate())) diffs.add(String.format("date %s→%s", oldDate, saved.getDate()));
        if (!Objects.equals(oldStart, saved.getStartDate())) diffs.add(String.format("startDate %s→%s", oldStart, saved.getStartDate()));
        if (!Objects.equals(oldEnd, saved.getEndDate())) diffs.add(String.format("endDate %s→%s", oldEnd, saved.getEndDate()));
        if (!Objects.equals(oldCategory, saved.getCategory())) diffs.add(String.format("category %s→%s", oldCategory, saved.getCategory()));
        if (!Objects.equals(oldLocation, saved.getLocation())) diffs.add(String.format("location %s→%s", oldLocation, saved.getLocation()));
        if (!Objects.equals(oldDescription, saved.getDescription())) diffs.add("description (변경됨)");
        if (!Objects.equals(oldImage, saved.getImage())) diffs.add("image (변경됨)");
        if (!Objects.equals(oldImages, saved.getImages())) diffs.add("images (변경됨)");

        log.info("[HISTORY] user={}({}), UPDATE id={} {}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            saved.getId(),
            diffs.isEmpty() ? "(변경 없음)" : String.join(", ", diffs));
        return saved;
    }

    @Transactional
    public void delete(Long id, String currentUserId, String roleName) {
        History existingHistory = findById(id);
        verifyOwnership(existingHistory, currentUserId, roleName);
        deleteImageFile(existingHistory.getImage());
        historyRepository.deleteById(id);
        log.info("[HISTORY] user={}({}), DELETE id={} title={} category={}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            existingHistory.getId(), existingHistory.getTitle(), existingHistory.getCategory());
    }

    private void verifyOwnership(History history, String currentUserId, String roleName) {
        if ("ADMIN".equals(roleName)) {
            return;
        }
        User owner = history.getUser();
        // 레거시: 소유자 정보가 없는 기존 데이터는 권한 검사를 스킵 (메뉴 권한으로 이미 게이팅됨)
        if (owner == null) {
            return;
        }
        if (currentUserId == null || !currentUserId.equals(owner.getUserId())) {
            throw new ForbiddenException("해당 데이터에 대한 권한이 없습니다.");
        }
    }

    public void deleteImageFile(String imagePath) {
        try {
            if (imagePath != null && !imagePath.trim().isEmpty()) {
                String fileName = imagePath.substring(imagePath.lastIndexOf("/") + 1);
                Path filePath = UPLOAD_ROOT.resolve(fileName).normalize();
                if (!filePath.startsWith(UPLOAD_ROOT)) {
                    log.warn("히스토리 이미지 삭제 path traversal 시도 차단: {}", imagePath);
                    return;
                }
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                }
            }
        } catch (IOException e) {
            log.error("히스토리 이미지 파일 삭제 실패: path={}", imagePath, e);
        }
    }
}
