package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.service.DatingService;
import com.hyunchang.webapp.service.MenuCrudPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;

@RestController
@RequestMapping("/dating")
public class DatingController {
    private static final Logger log = LoggerFactory.getLogger(DatingController.class);
    private final DatingService datingService;
    private final MenuCrudPermissionService menuCrudPermissionService;
    private static final String UPLOAD_DIR = getUploadDirectory();
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".3gp");
    
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

    public DatingController(DatingService datingService, MenuCrudPermissionService menuCrudPermissionService) {
        this.datingService = datingService;
        this.menuCrudPermissionService = menuCrudPermissionService;
        // 업로드 디렉토리 생성
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
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
        Dating created = datingService.create(dating);
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
        Dating updated = datingService.update(id, dating);
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
        datingService.delete(id);
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
            return ResponseEntity.internalServerError().body("미디어 삭제에 실패했습니다: " + e.getMessage());
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canUpdate(roleName, "/dating")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("미디어 업로드 권한이 없습니다.");
        }
        
        try {
            // 파일이 비어있는지 확인
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("파일이 선택되지 않았습니다.");
            }

            // 파일 확장자 확인
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !isValidMediaFile(originalFilename)) {
                return ResponseEntity.badRequest().body("이미지 또는 동영상 파일만 업로드 가능합니다.");
            }

            // 고유한 파일명 생성 (원본 파일명 + 타임스탬프)
            String fileExtension = getFileExtension(originalFilename);
            String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
            String uniqueFilename = baseName + "_" + System.currentTimeMillis() + fileExtension;
            
            // 파일 저장
            Path targetPath = Paths.get(UPLOAD_DIR + uniqueFilename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            // 저장된 파일의 URL 반환
            String fileUrl = "/uploads/images/dating/" + uniqueFilename;
            log.info("[DATING] user={}, action=UPLOAD_IMAGE, filename={}",
                    SecurityUtils.getCurrentUserId(), uniqueFilename);
            return ResponseEntity.ok(fileUrl);

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("파일 업로드 중 오류가 발생했습니다: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("예상치 못한 오류가 발생했습니다: " + e.getMessage());
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
