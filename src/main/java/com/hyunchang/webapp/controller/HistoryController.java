package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.History;
import com.hyunchang.webapp.service.HistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/histories")
public class HistoryController {
    private final HistoryService historyService;
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

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
        // 업로드 디렉토리 생성
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @GetMapping
    public List<History> findAll() {
        return historyService.findAll();
    }

    @GetMapping("/{id}")
    public History findById(@PathVariable Long id) {
        return historyService.findById(id);
    }

    @PostMapping
    public History create(@RequestBody History history) {
        return historyService.create(history);
    }

    @PutMapping("/{id}")
    public History update(@PathVariable Long id, @RequestBody History history) {
        return historyService.update(id, history);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        historyService.delete(id);
    }

    @DeleteMapping("/image")
    public ResponseEntity<String> deleteImage(@RequestParam("imagePath") String imagePath) {
        try {
            historyService.deleteImageFile(imagePath);
            return ResponseEntity.ok("이미지가 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("이미지 삭제에 실패했습니다: " + e.getMessage());
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            // 파일이 비어있는지 확인
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("파일이 선택되지 않았습니다.");
            }

            // 파일 확장자 확인
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !isValidImageFile(originalFilename)) {
                return ResponseEntity.badRequest().body("이미지 파일만 업로드 가능합니다.");
            }

            // 고유한 파일명 생성
            String fileExtension = getFileExtension(originalFilename);
            String uniqueFilename = UUID.randomUUID().toString() + fileExtension;
            
            // 파일 저장
            Path targetPath = Paths.get(UPLOAD_DIR + uniqueFilename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            // 저장된 파일의 URL 반환
            String fileUrl = "/uploads/images/history/" + uniqueFilename;
            return ResponseEntity.ok(fileUrl);

        } catch (IOException e) {
            System.err.println("히스토리 파일 업로드 중 IOException 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body("파일 업로드 중 오류가 발생했습니다: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("히스토리 파일 업로드 중 예상치 못한 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body("예상치 못한 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private boolean isValidImageFile(String filename) {
        String extension = getFileExtension(filename).toLowerCase();
        return extension.equals(".jpg") || extension.equals(".jpeg") || 
               extension.equals(".png") || extension.equals(".gif") || 
               extension.equals(".webp");
    }

    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf(".");
        return lastDotIndex > 0 ? filename.substring(lastDotIndex) : "";
    }
} 