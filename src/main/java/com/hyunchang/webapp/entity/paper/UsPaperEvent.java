package com.hyunchang.webapp.entity.paper;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "us_paper_event")
@Getter
@Setter
public class UsPaperEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sessionId;
    private Instant at;
    private String type;

    @Column(length = 600)
    private String message;
}
