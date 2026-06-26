package com.hyunchang.webapp.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

public final class ApiResponses {
    private static final String FORBIDDEN_MESSAGE = "\uC811\uADFC \uAD8C\uD55C\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.";
    private static final String DELETED_MESSAGE = "\uC0AD\uC81C\uB418\uC5C8\uC2B5\uB2C8\uB2E4.";

    private ApiResponses() {
    }

    public static ResponseEntity<String> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(FORBIDDEN_MESSAGE);
    }

    public static ResponseEntity<String> deleted() {
        return ResponseEntity.ok(DELETED_MESSAGE);
    }

    public static ResponseEntity<Map<String, String>> deletedMessage() {
        return message(DELETED_MESSAGE);
    }

    public static ResponseEntity<Map<String, String>> message(String message) {
        return ResponseEntity.ok(Map.of("message", message));
    }
}
