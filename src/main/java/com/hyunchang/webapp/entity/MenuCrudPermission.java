package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "menu_crud_permissions")
public class MenuCrudPermission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
    
    @Column(name = "menu_path", nullable = false)
    private String menuPath;
    
    @Column(name = "can_create", nullable = false)
    private Boolean canCreate = false;
    
    @Column(name = "can_read", nullable = false)
    private Boolean canRead = false;
    
    @Column(name = "can_update", nullable = false)
    private Boolean canUpdate = false;
    
    @Column(name = "can_delete", nullable = false)
    private Boolean canDelete = false;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // 기본 생성자
    public MenuCrudPermission() {}
    
    // 생성자
    public MenuCrudPermission(Role role, String menuPath, Boolean canCreate, Boolean canRead, Boolean canUpdate, Boolean canDelete) {
        this.role = role;
        this.menuPath = menuPath;
        this.canCreate = canCreate;
        this.canRead = canRead;
        this.canUpdate = canUpdate;
        this.canDelete = canDelete;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public Role getRole() {
        return role;
    }
    
    public void setRole(Role role) {
        this.role = role;
    }
    
    public String getMenuPath() {
        return menuPath;
    }
    
    public void setMenuPath(String menuPath) {
        this.menuPath = menuPath;
    }
    
    public Boolean getCanCreate() {
        return canCreate;
    }
    
    public void setCanCreate(Boolean canCreate) {
        this.canCreate = canCreate;
    }
    
    public Boolean getCanRead() {
        return canRead;
    }
    
    public void setCanRead(Boolean canRead) {
        this.canRead = canRead;
    }
    
    public Boolean getCanUpdate() {
        return canUpdate;
    }
    
    public void setCanUpdate(Boolean canUpdate) {
        this.canUpdate = canUpdate;
    }
    
    public Boolean getCanDelete() {
        return canDelete;
    }
    
    public void setCanDelete(Boolean canDelete) {
        this.canDelete = canDelete;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
