package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.ChatMessage;
import com.hyunchang.webapp.entity.Diary;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.DiaryRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.service.prompt.AiPromptCatalog;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import com.hyunchang.webapp.util.SecurityUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DiaryService {

    private final DiaryRepository diaryRepository;
    private final UserRepository userRepository;
    private final ClaudeService claudeService;
    private final AiPromptService aiPromptService;

    public DiaryService(
            DiaryRepository diaryRepository,
            UserRepository userRepository,
            ClaudeService claudeService,
            AiPromptService aiPromptService) {
        this.diaryRepository = diaryRepository;
        this.userRepository = userRepository;
        this.claudeService = claudeService;
        this.aiPromptService = aiPromptService;
    }

    private User getCurrentUser() {
        String userId = SecurityUtils.getCurrentUserId();
        return userRepository
                .findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
    }

    public List<Diary> findAll() {
        return diaryRepository.findByUserOrderByDiaryDateDesc(getCurrentUser());
    }

    public Diary findById(Long id) {
        Diary diary =
                diaryRepository
                        .findById(id)
                        .orElseThrow(() -> new RuntimeException("일기를 찾을 수 없습니다."));
        if (!diary.getUser().getUserId().equals(SecurityUtils.getCurrentUserId())) {
            throw new RuntimeException("접근 권한이 없습니다.");
        }
        return diary;
    }

    public Diary findByDate(LocalDate date) {
        return diaryRepository.findByUserAndDiaryDate(getCurrentUser(), date).orElse(null);
    }

    public Diary save(LocalDate diaryDate, String content) {
        User user = getCurrentUser();
        Diary diary = diaryRepository.findByUserAndDiaryDate(user, diaryDate).orElse(new Diary());
        diary.setUser(user);
        diary.setDiaryDate(diaryDate);
        diary.setContent(content);
        diary.setAiAnalysis(null); // 내용 변경 시 분석 초기화
        return diaryRepository.save(diary);
    }

    public Diary analyze(Long id) {
        Diary diary = findById(id);

        String prompt =
                aiPromptService.render(
                        AiPromptCatalog.DIARY_ANALYSIS,
                        Map.of("일기내용", diary.getContent() == null ? "" : diary.getContent()));

        String analysis = claudeService.chat(List.of(new ChatMessage("user", prompt)));
        diary.setAiAnalysis(analysis);
        return diaryRepository.save(diary);
    }

    public void delete(Long id) {
        Diary diary = findById(id);
        diaryRepository.delete(diary);
    }
}
