package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.History;
import com.hyunchang.webapp.repository.HistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class HistoryService {
    private final HistoryRepository historyRepository;

    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
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
        if (history.getDate() == null) {
            throw new IllegalArgumentException("날짜는 필수 입력값입니다.");
        }
        if (history.getDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("미래의 날짜는 입력할 수 없습니다.");
        }
        if (history.getCategory() == null || history.getCategory().trim().isEmpty()) {
            throw new IllegalArgumentException("카테고리는 필수 입력값입니다.");
        }
    }

    @Transactional
    public History create(History history) {
        validateHistory(history);
        return historyRepository.save(history);
    }

    @Transactional
    public History update(Long id, History history) {
        validateHistory(history);
        History existingHistory = findById(id);
        existingHistory.setTitle(history.getTitle());
        existingHistory.setDate(history.getDate());
        existingHistory.setCategory(history.getCategory());
        existingHistory.setDescription(history.getDescription());
        existingHistory.setLocation(history.getLocation());
        existingHistory.setImage(history.getImage());
        return historyRepository.save(existingHistory);
    }

    @Transactional
    public void delete(Long id) {
        historyRepository.deleteById(id);
    }
} 