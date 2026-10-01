package com.hyunchang.webapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.nio.charset.StandardCharsets;

/** Shared storage for descriptions and media references in both timelines. */
@MappedSuperclass
public abstract class TimelineContent {
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String images;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public void validateContent() {
        if (description != null && description.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new IllegalArgumentException("설명은 UTF-8 기준 65,535바이트까지 저장할 수 있습니다.");
        }
    }
}
