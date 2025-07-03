package com.hyunchang.webapp.entity.todo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class TodoHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String action;
    private String todoTitle;
    private Long todoId;
    private LocalDateTime createdDt;

    public TodoHistory() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTodoTitle() {
        return todoTitle;
    }

    public void setTodoTitle(String todoTitle) {
        this.todoTitle = todoTitle;
    }

    public Long getTodoId() {
        return todoId;
    }

    public void setTodoId(Long todoId) {
        this.todoId = todoId;
    }

    public LocalDateTime getCreatedDt() {
        return createdDt;
    }

    public void setCreatedDt(LocalDateTime createdDt) {
        this.createdDt = createdDt;
    }
} 