package com.hyunchang.webapp.dto;

public class MenuDefinitionRequest {
    private String path;
    private String name;
    private String icon;
    private String description;
    private String category;
    private Boolean isRequired;
    private Boolean showInNav;
    private String navLabel;
    private Boolean isAdminSubMenu;
    private String defaultRoles;
    private Integer sortOrder;
    private String parentPath;

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
    public Boolean getIsRequired() { return isRequired; }
    public void setIsRequired(Boolean isRequired) { this.isRequired = isRequired; }
    public Boolean getShowInNav() { return showInNav; }
    public void setShowInNav(Boolean showInNav) { this.showInNav = showInNav; }
    public String getNavLabel() { return navLabel; }
    public void setNavLabel(String navLabel) { this.navLabel = navLabel; }
    public Boolean getIsAdminSubMenu() { return isAdminSubMenu; }
    public void setIsAdminSubMenu(Boolean isAdminSubMenu) { this.isAdminSubMenu = isAdminSubMenu; }
    public String getDefaultRoles() { return defaultRoles; }
    public void setDefaultRoles(String defaultRoles) { this.defaultRoles = defaultRoles; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getParentPath() { return parentPath; }
    public void setParentPath(String parentPath) { this.parentPath = parentPath; }
}
