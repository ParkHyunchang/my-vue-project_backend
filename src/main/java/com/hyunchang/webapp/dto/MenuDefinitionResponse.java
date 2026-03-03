package com.hyunchang.webapp.dto;

import com.hyunchang.webapp.entity.MenuDefinition;

import java.util.Arrays;
import java.util.List;

public class MenuDefinitionResponse {
    private Long id;
    private String path;
    private String name;
    private String icon;
    private String description;
    private String category;
    private boolean isRequired;
    private boolean showInNav;
    private String navLabel;
    private boolean isAdminSubMenu;
    private List<String> defaultRoles;
    private int sortOrder;
    private String parentPath;

    public static MenuDefinitionResponse from(MenuDefinition m) {
        MenuDefinitionResponse r = new MenuDefinitionResponse();
        r.id = m.getId();
        r.path = m.getPath();
        r.name = m.getName();
        r.icon = m.getIcon();
        r.description = m.getDescription();
        r.category = m.getCategory();
        r.isRequired = m.isRequired();
        r.showInNav = m.isShowInNav();
        r.navLabel = m.getNavLabel();
        r.isAdminSubMenu = m.isAdminSubMenu();
        r.sortOrder = m.getSortOrder();

        r.parentPath = m.getParentPath();

        if (m.getDefaultRoles() != null && !m.getDefaultRoles().isBlank()) {
            r.defaultRoles = Arrays.asList(m.getDefaultRoles().split(","));
        } else {
            r.defaultRoles = List.of();
        }
        return r;
    }

    public Long getId() { return id; }
    public String getPath() { return path; }
    public String getName() { return name; }
    public String getIcon() { return icon; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public boolean isRequired() { return isRequired; }
    public boolean isShowInNav() { return showInNav; }
    public String getNavLabel() { return navLabel; }
    public boolean isAdminSubMenu() { return isAdminSubMenu; }
    public List<String> getDefaultRoles() { return defaultRoles; }
    public int getSortOrder() { return sortOrder; }
    public String getParentPath() { return parentPath; }
}
