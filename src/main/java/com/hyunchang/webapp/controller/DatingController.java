package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Dating;
import com.hyunchang.webapp.service.DatingService;
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
@RequestMapping("/dating")
public class DatingController {
    private final DatingService datingService;
    private static final String UPLOAD_DIR = getUploadDirectory();
    
    private static String getUploadDirectory() {
        // Docker 환경에서는 /app/uploads/images/ 사용, 로컬에서는 상대 경로 사용
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux") || os.contains("unix")) {
            // Linux/Unix 환경 (Docker 컨테이너)
            return "/app/uploads/images/";
        } else {
            // Windows 환경 (로컬 개발)
            return System.getProperty("user.dir") + "/uploads/images/";
        }
    }
    
    // 주의: 이 컨트롤러는 이미지 파일을 삭제하지 않습니다.
    // 업로드된 이미지 파일은 서버에 영구 보존됩니다.

    public DatingController(DatingService datingService) {
        this.datingService = datingService;
        // 업로드 디렉토리 생성
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @GetMapping
    public List<Dating> findAll() {
        return datingService.findAll();
    }

    @GetMapping("/{id}")
    public Dating findById(@PathVariable Long id) {
        return datingService.findById(id);
    }

    @PostMapping
    public Dating create(@RequestBody Dating dating) {
        return datingService.create(dating);
    }

    @PutMapping("/{id}")
    public Dating update(@PathVariable Long id, @RequestBody Dating dating) {
        return datingService.update(id, dating);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        datingService.delete(id);
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            System.out.println("=== Image Upload Request ===");
            System.out.println("File name: " + file.getOriginalFilename());
            System.out.println("File size: " + file.getSize());
            System.out.println("Content type: " + file.getContentType());
            System.out.println("Upload directory: " + UPLOAD_DIR);
            
            // 파일이 비어있는지 확인
            if (file.isEmpty()) {
                System.out.println("Error: File is empty");
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
            System.out.println("Target path: " + targetPath.toString());
            
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("File saved successfully to: " + targetPath.toString());

            // 저장된 파일의 URL 반환
            String fileUrl = "/uploads/images/" + uniqueFilename;
            System.out.println("Returning file URL: " + fileUrl);
            return ResponseEntity.ok(fileUrl);

        } catch (IOException e) {
            System.out.println("IOException during file upload: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("파일 업로드 중 오류가 발생했습니다: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Unexpected error during file upload: " + e.getMessage());
            e.printStackTrace();
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
