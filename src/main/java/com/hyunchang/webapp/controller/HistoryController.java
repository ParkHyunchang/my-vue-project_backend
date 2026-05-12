package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.History;
import com.hyunchang.webapp.service.HistoryService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
import com.hyunchang.webapp.util.UploadPathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/histories")
public class HistoryController {
    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);
    private static final String MENU_PATH = "/history";
    private final HistoryService historyService;
    private final MenuPermissionService menuPermissionService;
    private static final Path UPLOAD_ROOT = UploadPathUtil.imagesSubdirPath("history");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".3gp");

    public HistoryController(HistoryService historyService, MenuPermissionService menuPermissionService) {
        this.historyService = historyService;
        this.menuPermissionService = menuPermissionService;
        try {
            Files.createDirectories(UPLOAD_ROOT);
        } catch (IOException e) {
            log.error("히스토리 업로드 디렉토리 생성 실패: {}", e.getMessage());
        }
    }

    private boolean hasAccess() {
        return menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
    }

    @GetMapping
    public ResponseEntity<?> findAll() {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(historyService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(historyService.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody History history) {
        if (!hasAccess()) return forbidden();
        History created = historyService.create(history, SecurityUtils.getCurrentUserId());
        log.info("[HISTORY] user={}, action=CREATE, id={}, title={}",
                SecurityUtils.getCurrentUserId(), created.getId(), created.getTitle());
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody History history) {
        if (!hasAccess()) return forbidden();
        String roleName = SecurityUtils.getCurrentUserRoleName();
        History updated = historyService.update(id, history, SecurityUtils.getCurrentUserId(), roleName);
        log.info("[HISTORY] user={}, action=UPDATE, id={}, title={}",
                SecurityUtils.getCurrentUserId(), id, updated.getTitle());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        String roleName = SecurityUtils.getCurrentUserRoleName();
        historyService.delete(id, SecurityUtils.getCurrentUserId(), roleName);
        log.info("[HISTORY] user={}, action=DELETE, id={}",
                SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.ok().body("삭제되었습니다.");
    }

    @DeleteMapping("/media")
    public ResponseEntity<?> deleteMedia(@RequestParam("mediaPath") String mediaPath) {
        if (!hasAccess()) return forbidden();
        try {
            historyService.deleteImageFile(mediaPath);
            return ResponseEntity.ok("미디어가 삭제되었습니다.");
        } catch (Exception e) {
            log.error("히스토리 미디어 삭제 실패: path={}", mediaPath, e);
            return ResponseEntity.internalServerError().body("미디어 삭제에 실패했습니다.");
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadMedia(@RequestParam("file") MultipartFile file) {
        if (!hasAccess()) return forbidden();

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("파일이 선택되지 않았습니다.");
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !isValidMediaFile(originalFilename)) {
                return ResponseEntity.badRequest().body("이미지 또는 동영상 파일만 업로드 가능합니다.");
            }

            String fileExtension = getFileExtension(originalFilename).toLowerCase();
            String uniqueFilename = UUID.randomUUID().toString().replace("-", "") + fileExtension;

            Path targetPath = UPLOAD_ROOT.resolve(uniqueFilename).normalize();
            if (!targetPath.startsWith(UPLOAD_ROOT)) {
                log.warn("히스토리 업로드 path traversal 시도 차단: {}", uniqueFilename);
                return ResponseEntity.badRequest().body("잘못된 파일 경로입니다.");
            }
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            String fileUrl = "/uploads/images/history/" + uniqueFilename;
            log.info("[HISTORY] user={}, action=UPLOAD_MEDIA, filename={}",
                    SecurityUtils.getCurrentUserId(), uniqueFilename);
            return ResponseEntity.ok(fileUrl);

        } catch (IOException e) {
            log.error("히스토리 미디어 업로드 IOException", e);
            return ResponseEntity.internalServerError().body("파일 업로드 중 오류가 발생했습니다.");
        } catch (Exception e) {
            log.error("히스토리 미디어 업로드 예상치 못한 오류", e);
            return ResponseEntity.internalServerError().body("예상치 못한 오류가 발생했습니다.");
        }
    }

    private boolean isValidMediaFile(String filename) {
        String extension = getFileExtension(filename).toLowerCase();
        return IMAGE_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension);
    }

    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf(".");
        return lastDotIndex > 0 ? filename.substring(lastDotIndex) : "";
    }
}
