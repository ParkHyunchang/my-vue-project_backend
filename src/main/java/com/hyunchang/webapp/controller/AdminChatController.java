package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/admin/chat")
public class AdminChatController {

    private final ChatHistoryService chatHistoryService;

    public AdminChatController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping("/sessions")
    public List<ChatHistoryService.SessionSummaryDto> sessions() {
        log.info("[관리자] 전체 채팅세션 조회");
        return chatHistoryService.getAllSessionSummaries();
    }

    @GetMapping("/sessions/{sessionKey}/messages")
    public List<ChatHistoryService.MessageDto> messages(@PathVariable String sessionKey) {
        log.info("[관리자] 채팅메시지 조회, session={}", sessionKey);
        return chatHistoryService.getHistory(sessionKey);
    }
}
