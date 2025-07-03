package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.History;
import com.hyunchang.webapp.repository.HistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public History create(History history) {
        return historyRepository.save(history);
    }

    @Transactional
    public History update(Long id, History history) {
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