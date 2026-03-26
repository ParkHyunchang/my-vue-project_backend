package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.RoleInfoRequest;
import com.hyunchang.webapp.dto.RoleInfoResponse;
import com.hyunchang.webapp.entity.RoleInfo;
import com.hyunchang.webapp.exception.DuplicateException;
import com.hyunchang.webapp.exception.ForbiddenException;
import com.hyunchang.webapp.exception.NotFoundException;
import com.hyunchang.webapp.exception.ValidationException;
import com.hyunchang.webapp.repository.RoleInfoRepository;
import com.hyunchang.webapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleInfoService {

    private final RoleInfoRepository roleInfoRepository;
    private final UserRepository userRepository;

    private static final List<String> DEFAULT_ROLE_NAMES = List.of("USER", "PREMIUM", "ADMIN", "GUEST");

    @Transactional
    public void initializeDefaultRoles() {
        record DefaultRole(String roleName, String displayName, String description) {}

        List<DefaultRole> defaults = List.of(
            new DefaultRole("USER",    "일반 사용자",    "기본 서비스 이용 권한을 가진 일반 사용자입니다."),
            new DefaultRole("PREMIUM", "프리미엄 사용자", "유료 구독으로 프리미엄 기능을 이용할 수 있는 사용자입니다."),
            new DefaultRole("ADMIN",   "관리자",        "시스템 전체를 관리할 수 있는 최고 권한을 가진 관리자입니다."),
            new DefaultRole("GUEST",   "비로그인 사용자", "로그인 없이 접근 가능한 공개 메뉴를 볼 수 있는 비로그인 사용자입니다.")
        );

        for (DefaultRole d : defaults) {
            roleInfoRepository.findByRoleName(d.roleName()).ifPresentOrElse(
                existing -> {
                    if (!existing.isDefault()) {
                        existing.setDefault(true);
                        roleInfoRepository.save(existing);
                    }
                },
                () -> roleInfoRepository.save(RoleInfo.builder()
                        .roleName(d.roleName())
                        .displayName(d.displayName())
                        .description(d.description())
                        .isDefault(true)
                        .build())
            );
        }
    }

    @Transactional(readOnly = true)
    public List<RoleInfoResponse> getAllRoleInfos() {
        return roleInfoRepository.findAll().stream()
                .map(info -> RoleInfoResponse.from(info, countUsersForRole(info.getRoleName())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RoleInfoResponse getRoleInfoByRoleName(String roleName) {
        RoleInfo info = roleInfoRepository.findByRoleName(roleName)
                .orElseThrow(() -> new NotFoundException("권한 정보를 찾을 수 없습니다: " + roleName));
        return RoleInfoResponse.from(info, countUsersForRole(roleName));
    }

    @Transactional
    public RoleInfoResponse createRoleInfo(RoleInfoRequest request) {
        if (request.getRoleName() == null || request.getRoleName().trim().isEmpty()) {
            throw new ValidationException("권한 이름은 필수입니다.");
        }
        if (request.getDisplayName() == null || request.getDisplayName().trim().isEmpty()) {
            throw new ValidationException("표시명은 필수입니다.");
        }
        if (request.getDescription() == null || request.getDescription().trim().isEmpty()) {
            throw new ValidationException("설명은 필수입니다.");
        }

        String roleName = request.getRoleName().trim().toUpperCase();
        if (roleInfoRepository.existsByRoleName(roleName)) {
            throw new DuplicateException("이미 존재하는 권한 이름입니다: " + roleName);
        }

        RoleInfo info = roleInfoRepository.save(RoleInfo.builder()
                .roleName(roleName)
                .displayName(request.getDisplayName().trim())
                .description(request.getDescription().trim())
                .isDefault(false)
                .build());

        return RoleInfoResponse.from(info, 0);
    }

    @Transactional
    public RoleInfoResponse updateRoleInfo(Long id, RoleInfoRequest request) {
        RoleInfo info = roleInfoRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("권한 정보를 찾을 수 없습니다."));

        if (request.getDisplayName() != null && !request.getDisplayName().trim().isEmpty()) {
            info.setDisplayName(request.getDisplayName().trim());
        }
        if (request.getDescription() != null && !request.getDescription().trim().isEmpty()) {
            info.setDescription(request.getDescription().trim());
        }

        // 시스템 기본 권한(USER/PREMIUM/ADMIN)은 isDefault 변경 불가, 커스텀 권한만 허용
        boolean isSystemDefault = DEFAULT_ROLE_NAMES.contains(info.getRoleName());
        if (!isSystemDefault && request.getIsDefault() != null) {
            info.setDefault(request.getIsDefault());
        }

        RoleInfo saved = roleInfoRepository.save(info);
        return RoleInfoResponse.from(saved, countUsersForRole(saved.getRoleName()));
    }

    @Transactional
    public void deleteRoleInfo(Long id) {
        RoleInfo info = roleInfoRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("권한 정보를 찾을 수 없습니다."));
        if (info.isDefault()) {
            throw new ForbiddenException("기본 권한(" + info.getRoleName() + ")은 삭제할 수 없습니다.");
        }
        roleInfoRepository.deleteById(id);
    }

    private long countUsersForRole(String roleName) {
        return userRepository.countByRole(roleName);
    }
}
