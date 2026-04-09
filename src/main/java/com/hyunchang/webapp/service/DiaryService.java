package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.ChatMessage;
import com.hyunchang.webapp.entity.Diary;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.DiaryRepository;
import com.hyunchang.webapp.repository.UserRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class DiaryService {

    private final DiaryRepository diaryRepository;
    private final UserRepository userRepository;
    private final ClaudeService claudeService;

    public DiaryService(DiaryRepository diaryRepository, UserRepository userRepository, ClaudeService claudeService) {
        this.diaryRepository = diaryRepository;
        this.userRepository = userRepository;
        this.claudeService = claudeService;
    }

    private User getCurrentUser() {
        String userId = SecurityUtils.getCurrentUserId();
        return userRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
    }

    public List<Diary> findAll() {
        return diaryRepository.findByUserOrderByDiaryDateDesc(getCurrentUser());
    }

    public Diary findById(Long id) {
        Diary diary = diaryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("일기를 찾을 수 없습니다."));
        if (!diary.getUser().getUserId().equals(SecurityUtils.getCurrentUserId())) {
            throw new RuntimeException("접근 권한이 없습니다.");
        }
        return diary;
    }

    public Diary findByDate(LocalDate date) {
        return diaryRepository.findByUserAndDiaryDate(getCurrentUser(), date)
                .orElse(null);
    }

    public Diary save(LocalDate diaryDate, String content) {
        User user = getCurrentUser();
        Diary diary = diaryRepository.findByUserAndDiaryDate(user, diaryDate)
                .orElse(new Diary());
        diary.setUser(user);
        diary.setDiaryDate(diaryDate);
        diary.setContent(content);
        diary.setAiAnalysis(null); // 내용 변경 시 분석 초기화
        return diaryRepository.save(diary);
    }

    public Diary analyze(Long id) {
        Diary diary = findById(id);

        String prompt = """
                다음은 사용자가 오늘 작성한 일기입니다. 아래 JSON 형식으로만 응답해주세요. 다른 텍스트는 절대 포함하지 마세요.

                일기 내용:
                %s

                응답 형식 (JSON만):
                {
                  "mood": "감정을 나타내는 이모지 하나와 한 단어 (예: 😊 기쁨)",
                  "moodScore": 감정 점수 1~10 숫자,
                  "summary": "일기 내용을 2~3문장으로 요약",
                  "keywords": ["키워드1", "키워드2", "키워드3"],
                  "comment": "공감하고 격려하는 따뜻한 한마디 (2~3문장)"
                }
                """.formatted(diary.getContent());

        String analysis = claudeService.chat(List.of(new ChatMessage("user", prompt)));
        diary.setAiAnalysis(analysis);
        return diaryRepository.save(diary);
    }

    public void delete(Long id) {
        Diary diary = findById(id);
        diaryRepository.delete(diary);
    }
}
