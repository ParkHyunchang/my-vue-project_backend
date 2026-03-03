package com.hyunchang.webapp.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "menu")
public class MenuDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "path", nullable = false, unique = true)
    private String path;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "icon", length = 50)
    private String icon;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "is_required")
    private boolean isRequired = false;

    @Column(name = "show_in_nav")
    private boolean showInNav = true;

    @Column(name = "nav_label", length = 100)
    private String navLabel;

    @Column(name = "is_admin_sub_menu")
    private boolean isAdminSubMenu = false;

    // 기본 권한 목록 (콤마 구분, 예: "USER,PREMIUM,ADMIN")
    @Column(name = "default_roles", length = 255)
    private String defaultRoles;

    @Column(name = "sort_order")
    private int sortOrder = 0;

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

    public MenuDefinition() {}

    public MenuDefinition(String path, String name, String icon, String description,
                          String category, boolean isRequired, boolean showInNav,
                          String navLabel, boolean isAdminSubMenu, String defaultRoles, int sortOrder) {
        this.path = path;
        this.name = name;
        this.icon = icon;
        this.description = description;
        this.category = category;
        this.isRequired = isRequired;
        this.showInNav = showInNav;
        this.navLabel = navLabel;
        this.isAdminSubMenu = isAdminSubMenu;
        this.defaultRoles = defaultRoles;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public boolean isRequired() { return isRequired; }
    public void setRequired(boolean required) { isRequired = required; }
    public boolean isShowInNav() { return showInNav; }
    public void setShowInNav(boolean showInNav) { this.showInNav = showInNav; }
    public String getNavLabel() { return navLabel; }
    public void setNavLabel(String navLabel) { this.navLabel = navLabel; }
    public boolean isAdminSubMenu() { return isAdminSubMenu; }
    public void setAdminSubMenu(boolean adminSubMenu) { isAdminSubMenu = adminSubMenu; }
    public String getDefaultRoles() { return defaultRoles; }
    public void setDefaultRoles(String defaultRoles) { this.defaultRoles = defaultRoles; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
