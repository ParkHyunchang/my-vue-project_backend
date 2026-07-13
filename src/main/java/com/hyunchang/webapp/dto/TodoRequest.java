package com.hyunchang.webapp.dto;

import java.time.LocalDate;

public record TodoRequest(
        String title,
        String description,
        Boolean done,
        Integer priority,
        LocalDate dueDate,
        String category) {}
