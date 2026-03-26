package com.hyunchang.webapp.exception;

public class ClaudeApiException extends RuntimeException {

    private final String userMessage;

    public ClaudeApiException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
