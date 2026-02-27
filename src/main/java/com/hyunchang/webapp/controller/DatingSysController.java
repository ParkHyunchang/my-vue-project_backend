package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.DatingSys;
import com.hyunchang.webapp.service.DatingSysService;
import com.hyunchang.webapp.service.MenuCrudPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
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
@RequestMapping("/dating_sys")
public class DatingSysController {
    private final DatingSysService datingSysService;
    private final MenuCrudPermissionService menuCrudPermissionService;
    private static final String UPLOAD_DIR = getUploadDirectory();
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".3gp");

    private static String getUploadDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            return "/volume1/docker/my-vue-project_backend/uploads/images/dating_sys/";
        } else {
            return System.getProperty("user.dir") + "/uploads/images/dating_sys/";
        }
    }

    public DatingSysController(DatingSysService datingSysService, MenuCrudPermissionService menuCrudPermissionService) {
        this.datingSysService = datingSysService;
        this.menuCrudPermissionService = menuCrudPermissionService;
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            System.out.println("업로드 디렉토리 생성 실패: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<?> findAll() {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canRead(roleName, "/dating_sys")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("조회 권한이 없습니다.");
        }
        return ResponseEntity.ok(datingSysService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canRead(roleName, "/dating_sys")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("조회 권한이 없습니다.");
        }
        return ResponseEntity.ok(datingSysService.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody DatingSys datingSys) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canCreate(roleName, "/dating_sys")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("생성 권한이 없습니다.");
        }
        return ResponseEntity.ok(datingSysService.create(datingSys));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody DatingSys datingSys) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canUpdate(roleName, "/dating_sys")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("수정 권한이 없습니다.");
        }
        return ResponseEntity.ok(datingSysService.update(id, datingSys));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canDelete(roleName, "/dating_sys")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("삭제 권한이 없습니다.");
        }
        datingSysService.delete(id);
        return ResponseEntity.ok().body("삭제되었습니다.");
    }

    @DeleteMapping("/image")
    public ResponseEntity<?> deleteImage(@RequestParam("imagePath") String imagePath) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canDelete(roleName, "/dating_sys")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("미디어 삭제 권한이 없습니다.");
        }
        try {
            datingSysService.deleteImageFile(imagePath);
            return ResponseEntity.ok("미디어가 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("미디어 삭제에 실패했습니다: " + e.getMessage());
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        String roleName = SecurityUtils.getCurrentUserRoleName();
        if (!menuCrudPermissionService.canUpdate(roleName, "/dating_sys")) {
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

            String fileExtension = getFileExtension(originalFilename);
            String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
            String uniqueFilename = baseName + "_" + System.currentTimeMillis() + fileExtension;

            Path targetPath = Paths.get(UPLOAD_DIR + uniqueFilename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            String fileUrl = "/uploads/images/dating_sys/" + uniqueFilename;
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
