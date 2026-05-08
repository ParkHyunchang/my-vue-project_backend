package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.service.DatingService;
import com.hyunchang.webapp.service.MenuCrudPermissionService;
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
@RequestMapping("/api/dating")
public class DatingController {
    private static final Logger log = LoggerFactory.getLogger(DatingController.class);
    private final DatingService datingService;
    private final MenuCrudPermissionService menuCrudPermissionService;
    private static final Path UPLOAD_ROOT = UploadPathUtil.imagesSubdirPath("dating");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".3gp");

    public DatingController(DatingService datingService, MenuCrudPermissionService menuCrudPermissionService) {
        this.datingService = datingService;
        this.menuCrudPermissionService = menuCrudPermissionService;
        try {
            Files.createDirectories(UPLOAD_ROOT);
        } catch (IOException e) {
            log.error("업로드 디렉토리 생성 실패: {}", e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<?> findAll() {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canRead(roleName, "/dating")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("조회 권한이 없습니다.");
        }
        return ResponseEntity.ok(datingService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canRead(roleName, "/dating")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("조회 권한이 없습니다.");
        }
        return ResponseEntity.ok(datingService.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Dating dating) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canCreate(roleName, "/dating")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("생성 권한이 없습니다.");
        }
        Dating created = datingService.create(dating, SecurityUtils.getCurrentUserId());
        log.info("[DATING] user={}, action=CREATE, id={}, title={}, date={}, category={}",
                SecurityUtils.getCurrentUserId(), created.getId(),
                created.getTitle(), created.getDate() != null ? created.getDate() : created.getStartDate(),
                created.getCategory());
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Dating dating) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canUpdate(roleName, "/dating")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("수정 권한이 없습니다.");
        }
        Dating updated = datingService.update(id, dating, SecurityUtils.getCurrentUserId(), roleName);
        log.info("[DATING] user={}, action=UPDATE, id={}, title={}",
                SecurityUtils.getCurrentUserId(), id, updated.getTitle());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canDelete(roleName, "/dating")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("삭제 권한이 없습니다.");
        }
        Dating target = datingService.findById(id);
        String title = target != null ? target.getTitle() : "id=" + id;
        datingService.delete(id, SecurityUtils.getCurrentUserId(), roleName);
        log.info("[DATING] user={}, action=DELETE, id={}, title={}",
                SecurityUtils.getCurrentUserId(), id, title);
        return ResponseEntity.ok().body("삭제되었습니다.");
    }

    @DeleteMapping("/image")
    public ResponseEntity<?> deleteImage(@RequestParam("imagePath") String imagePath) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canDelete(roleName, "/dating")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("미디어 삭제 권한이 없습니다.");
        }

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
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canUpdate(roleName, "/dating")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("미디어 업로드 권한이 없습니다.");
        }

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
