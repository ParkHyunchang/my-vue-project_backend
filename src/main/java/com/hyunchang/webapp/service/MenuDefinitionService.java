package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.MenuDefinitionRequest;
import com.hyunchang.webapp.dto.MenuDefinitionResponse;
import com.hyunchang.webapp.entity.MenuDefinition;
import com.hyunchang.webapp.repository.MenuDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MenuDefinitionService {

    private final MenuDefinitionRepository menuDefinitionRepository;

    @Transactional(readOnly = true)
    public List<MenuDefinitionResponse> getAllMenuDefinitions() {
        return menuDefinitionRepository.findAllByOrderBySortOrderAscIdAsc()
                .stream()
                .map(MenuDefinitionResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public MenuDefinitionResponse createMenuDefinition(MenuDefinitionRequest request) {
        if (request.getPath() == null || request.getPath().isBlank()) {
            throw new RuntimeException("메뉴 경로는 필수입니다.");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new RuntimeException("메뉴 이름은 필수입니다.");
        }
        if (menuDefinitionRepository.existsByPath(request.getPath().trim())) {
            throw new RuntimeException("이미 존재하는 메뉴 경로입니다: " + request.getPath());
        }

        MenuDefinition menu = new MenuDefinition(
            request.getPath().trim(),
            request.getName().trim(),
            request.getIcon() != null ? request.getIcon().trim() : "",
            request.getDescription() != null ? request.getDescription().trim() : "",
            request.getCategory() != null ? request.getCategory().trim() : "main",
            request.getIsRequired() != null && request.getIsRequired(),
            request.getShowInNav() == null || request.getShowInNav(),
            request.getNavLabel() != null ? request.getNavLabel().trim() : request.getName().trim(),
            request.getIsAdminSubMenu() != null && request.getIsAdminSubMenu(),
            request.getDefaultRoles() != null ? request.getDefaultRoles().trim() : "",
            request.getSortOrder() != null ? request.getSortOrder() : 0
        );

        return MenuDefinitionResponse.from(menuDefinitionRepository.save(menu));
    }

    @Transactional
    public MenuDefinitionResponse updateMenuDefinition(Long id, MenuDefinitionRequest request) {
        MenuDefinition menu = menuDefinitionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("메뉴를 찾을 수 없습니다."));

        if (request.getName() != null && !request.getName().isBlank()) {
            menu.setName(request.getName().trim());
        }
        if (request.getIcon() != null) {
            menu.setIcon(request.getIcon().trim());
        }
        if (request.getDescription() != null) {
            menu.setDescription(request.getDescription().trim());
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            menu.setCategory(request.getCategory().trim());
        }
        if (request.getIsRequired() != null) {
            menu.setRequired(request.getIsRequired());
        }
        if (request.getShowInNav() != null) {
            menu.setShowInNav(request.getShowInNav());
        }
        if (request.getNavLabel() != null) {
            menu.setNavLabel(request.getNavLabel().trim());
        }
        if (request.getIsAdminSubMenu() != null) {
            menu.setAdminSubMenu(request.getIsAdminSubMenu());
        }
        if (request.getDefaultRoles() != null) {
            menu.setDefaultRoles(request.getDefaultRoles().trim());
        }
        if (request.getSortOrder() != null) {
            menu.setSortOrder(request.getSortOrder());
        }

        return MenuDefinitionResponse.from(menuDefinitionRepository.save(menu));
    }

    @Transactional
    public void deleteMenuDefinition(Long id) {
        if (!menuDefinitionRepository.existsById(id)) {
            throw new RuntimeException("메뉴를 찾을 수 없습니다.");
        }
        menuDefinitionRepository.deleteById(id);
    }

    // 앱 시작 시 기본 메뉴 정의 초기화 (이미 존재하면 스킵)
    @Transactional
    public void initializeDefaultMenuDefinitions() {
        record DefaultMenu(String path, String name, String icon, String description,
                           String category, boolean isRequired, boolean showInNav,
                           String navLabel, boolean isAdminSubMenu, String defaultRoles, int sortOrder) {}

        List<DefaultMenu> defaults = List.of(
            new DefaultMenu("/", "홈", "🏠", "메인 홈페이지", "main", true, true, "HOME", false, "USER,PREMIUM,ADMIN", 1),
            new DefaultMenu("/portfolio", "포트폴리오", "💼", "개인 포트폴리오 페이지", "main", false, true, "PORTFOLIO", false, "USER,PREMIUM,ADMIN", 2),
            new DefaultMenu("/projects", "프로젝트", "🚀", "프로젝트 관리 및 조회", "work", false, true, "PROJECTS", false, "USER,PREMIUM,ADMIN", 3),
            new DefaultMenu("/history", "히스토리", "📚", "작업 이력 및 기록", "work", false, true, "HISTORY", false, "PREMIUM,ADMIN", 4),
            new DefaultMenu("/dating", "데이팅", "💕", "데이팅 관련 기능", "personal", false, true, "DATING", false, "PREMIUM,ADMIN", 5),
            new DefaultMenu("/dating_sys", "데이팅 추억", "📸", "데이팅 추억 기록", "personal", false, true, "DATING SYS", false, "PREMIUM,ADMIN", 6),
            new DefaultMenu("/todos", "할일 목록", "📝", "할일 관리", "productivity", false, true, "TODOS", false, "USER,PREMIUM,ADMIN", 7),
            new DefaultMenu("/todos/create", "할일 생성", "➕", "새로운 할일 추가", "productivity", false, false, "할일 생성", false, "USER,PREMIUM,ADMIN", 8),
            new DefaultMenu("/expense", "지출 관리", "💰", "지출 내역 관리", "finance", false, true, "가계부", false, "ADMIN", 9),
            new DefaultMenu("/admin", "관리자 대시보드", "🎛️", "관리자 메인 대시보드", "admin", false, false, "관리자 대시보드", false, "ADMIN", 10),
            new DefaultMenu("/admin/users", "사용자 관리", "👥", "사용자 계정 관리", "admin", false, false, "사용자 관리", true, "ADMIN", 11),
            new DefaultMenu("/admin/menu-management", "권한별 접근메뉴관리", "🔐", "메뉴 접근 권한 설정", "admin", false, false, "권한별 접근메뉴관리", true, "ADMIN", 12),
            new DefaultMenu("/admin/role-management", "권한 관리", "🛡️", "사용자 권한(Role) 관리", "admin", false, false, "권한 관리", true, "ADMIN", 13)
        );

        for (DefaultMenu d : defaults) {
            if (!menuDefinitionRepository.existsByPath(d.path())) {
                menuDefinitionRepository.save(new MenuDefinition(
                    d.path(), d.name(), d.icon(), d.description(),
                    d.category(), d.isRequired(), d.showInNav(),
                    d.navLabel(), d.isAdminSubMenu(), d.defaultRoles(), d.sortOrder()
                ));
            }
        }
    }
}
