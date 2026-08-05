package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.CareerPublicResponse;
import com.hyunchang.webapp.dto.ExperiencePublicResponse;
import com.hyunchang.webapp.dto.MenuDefinitionResponse;
import com.hyunchang.webapp.dto.PortfolioSkillPublicResponse;
import com.hyunchang.webapp.service.CareerService;
import com.hyunchang.webapp.service.ExperienceService;
import com.hyunchang.webapp.service.MenuDefinitionService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.PortfolioSkillService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final MenuDefinitionService menuDefinitionService;
    private final MenuPermissionService menuPermissionService;
    private final CareerService careerService;
    private final ExperienceService experienceService;
    private final PortfolioSkillService portfolioSkillService;

    @GetMapping("/menus")
    public ResponseEntity<?> getPublicMenus() {
        try {
            List<MenuDefinitionResponse> allMenus = menuDefinitionService.getAllMenuDefinitions();
            List<String> guestMenuPaths =
                    menuPermissionService.getMenuPermissionsByRoleName("GUEST");
            return ResponseEntity.ok(
                    Map.of(
                            "allMenus", allMenus,
                            "guestMenuPaths", guestMenuPaths));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("메뉴 정보 조회에 실패했습니다.");
        }
    }

    @GetMapping("/career")
    public List<CareerPublicResponse> getCareers() {
        return careerService.findAllPublic();
    }

    @GetMapping("/experience")
    public List<ExperiencePublicResponse> getExperiences() {
        return experienceService.findAllPublic();
    }

    @GetMapping("/portfolio-skills")
    public List<PortfolioSkillPublicResponse> getPortfolioSkills() {
        return portfolioSkillService.findAllPublic();
    }
}
