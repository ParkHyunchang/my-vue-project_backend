package com.hyunchang.webapp.service;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class KiwoomUsEventService {
    private final Sinks.Many<Map<String, Object>> sink =
            Sinks.many().multicast().directBestEffort();

    public void publish(String type, String message, Long proposalId) {
        sink.tryEmitNext(
                Map.of(
                        "type",
                        type,
                        "message",
                        message,
                        "proposalId",
                        proposalId == null ? 0 : proposalId,
                        "createdAt",
                        LocalDateTime.now().toString()));
    }

    public Flux<Map<String, Object>> events() {
        return sink.asFlux();
    }
}
