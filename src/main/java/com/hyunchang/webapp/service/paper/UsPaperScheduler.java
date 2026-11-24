package com.hyunchang.webapp.service.paper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"us-paper.enabled", "us-paper.scheduler-enabled"},
        havingValue = "true")
public class UsPaperScheduler {
    private final UsPaperService service;

    public UsPaperScheduler(UsPaperService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${us-paper.poll-ms:60000}",
            initialDelayString = "${us-paper.poll-ms:60000}")
    public void tick() {
        service.pollRunning();
    }
}
