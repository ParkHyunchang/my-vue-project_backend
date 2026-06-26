package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.common.web.ApiResponses;
import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.service.DatingService;
import com.hyunchang.webapp.util.SecurityUtils;
import com.hyunchang.webapp.util.UploadPathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RequestMapping("/api/dating")
public class DatingController {
    private static final Logger log = LoggerFactory.getLogger(DatingController.class);
    private static final String MENU_PATH = "/dating";
    private final DatingService datingService;
    private final MenuAccessGuard menuAccessGuard;
    private static final Path UPLOAD_ROOT = UploadPathUtil.imagesSubdirPath("dating");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".3gp");

    public DatingController(DatingService datingService, MenuAccessGuard menuAccessGuard) {
        this.datingService = datingService;
        this.menuAccessGuard = menuAccessGuard;
        try {
            Files.createDirectories(UPLOAD_ROOT);
        } catch (IOException e) {
            log.error("업로드 디렉토리 생성 실패: {}", e.getMessage());
        }
    }

    private boolean hasAccess() {
        return menuAccessGuard.hasAccess(MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return menuAccessGuard.forbidden();
    }

    @GetMapping
    public ResponseEntity<?> findAll() {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(datingService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(datingService.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Dating dating) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(datingService.create(dating, SecurityUtils.getCurrentUserId()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Dating dating) {
        if (!hasAccess()) return forbidden();
        String roleName = SecurityUtils.getCurrentUserRoleName();
        return ResponseEntity.ok(datingService.update(id, dating, SecurityUtils.getCurrentUserId(), roleName));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        String roleName = SecurityUtils.getCurrentUserRoleName();
        datingService.delete(id, SecurityUtils.getCurrentUserId(), roleName);
        return ApiResponses.deleted();
    }

    @DeleteMapping("/image")
    public ResponseEntity<?> deleteImage(@RequestParam("imagePath") String imagePath) {
        if (!hasAccess()) return forbidden();

        try {
            datingService.deleteImageFile(imagePath);
            log.info("[DATING] user={}, action=DELETE_IMAGE, path={}",
                    SecurityUtils.getCurrentUserId(), imagePath);
            return ResponseEntity.ok("미디어가 삭제되었습니다.");
        } catch (Exception e) {
            log.error("dating 미디어 삭제 실패: path={}", imagePath, e);
            return ResponseEntity.internalServerError().body("미디어 삭제에 실패했습니다.");
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
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
                log.warn("dating 업로드 path traversal 시도 차단: {}", uniqueFilename);
                return ResponseEntity.badRequest().body("잘못된 파일 경로입니다.");
            }
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            String fileUrl = "/uploads/images/dating/" + uniqueFilename;
            log.info("[DATING] user={}, action=UPLOAD_IMAGE, filename={}",
                    SecurityUtils.getCurrentUserId(), uniqueFilename);
            return ResponseEntity.ok(fileUrl);

        } catch (IOException e) {
            log.error("dating 미디어 업로드 IOException", e);
            return ResponseEntity.internalServerError().body("파일 업로드 중 오류가 발생했습니다.");
        } catch (Exception e) {
            log.error("dating 미디어 업로드 예상치 못한 오류", e);
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
