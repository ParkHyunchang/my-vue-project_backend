package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.UpdateUserRequest;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.users:hyunchang88,admin}")
    private String allowedAdminUsers;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUserId(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    public User registerUser(String userId, String name, String email, String phone, String password, String role) {
        if (userRepository.existsByUserId(userId)) {
            throw new RuntimeException("이미 사용 중인 사용자ID입니다.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new RuntimeException("가입된 메일주소가 있습니다.");
        }
        if (phone != null && !phone.trim().isEmpty() && userRepository.existsByPhone(phone.trim())) {
            throw new RuntimeException("가입된 전화번호가 있습니다.");
        }

        String roleUpper = (role != null) ? role.trim().toUpperCase() : "USER";
        if ("ADMIN".equals(roleUpper) && !isAllowedAdmin(userId)) {
            throw new RuntimeException("관리자 권한을 설정할 수 없습니다. 허용된 사용자만 관리자가 될 수 있습니다.");
        }

        User user = User.builder()
                .userId(userId)
                .name(name)
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(password))
                .role(roleUpper)
                .build();

        return userRepository.save(user);
    }

    private boolean isAllowedAdmin(String username) {
        if (allowedAdminUsers == null || allowedAdminUsers.trim().isEmpty()) {
            return false;
        }
        return Arrays.stream(allowedAdminUsers.split(","))
                .map(String::trim)
                .anyMatch(u -> u.equals(username));
    }

    public Optional<User> findByUserId(String userId) {
        return userRepository.findByUserId(userId);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findByNameAndEmail(String name, String email) {
        if (name == null || email == null) return Optional.empty();
        String trimName = name.trim();
        String trimEmail = email.trim();
        if (trimName.isEmpty() || trimEmail.isEmpty()) return Optional.empty();
        return userRepository.findByEmail(trimEmail)
                .filter(u -> u.getName() != null && u.getName().trim().equalsIgnoreCase(trimName));
    }

    public boolean existsByUserId(String userId) {
        return userRepository.existsByUserId(userId);
    }

    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    // 관리자 기능들
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public User updateUserRole(Long userId, String newRole) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        String roleUpper = newRole.trim().toUpperCase();
        if ("ADMIN".equals(roleUpper) && !isAllowedAdmin(user.getUserId())) {
            throw new RuntimeException("관리자 권한을 설정할 수 없습니다. 허용된 사용자만 관리자가 될 수 있습니다.");
        }

        user.setRole(roleUpper);
        return userRepository.save(user);
    }

    public User updateUser(Long userId, UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            user.setName(request.getName().trim());
        }
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            String newEmail = request.getEmail().trim();
            if (!newEmail.equals(user.getEmail()) && userRepository.existsByEmail(newEmail)) {
                throw new RuntimeException("가입된 메일주소가 있습니다.");
            }
            user.setEmail(newEmail);
        }
        if (request.getPhone() != null) {
            String newPhone = request.getPhone().trim().isEmpty() ? null : request.getPhone().trim();
            if (newPhone != null && !newPhone.equals(user.getPhone()) && userRepository.existsByPhone(newPhone)) {
                throw new RuntimeException("가입된 전화번호가 있습니다.");
            }
            user.setPhone(newPhone);
        }
        if (request.getRole() != null) {
            String roleUpper = request.getRole().trim().toUpperCase();
            if ("ADMIN".equals(roleUpper) && !isAllowedAdmin(user.getUserId())) {
                throw new RuntimeException("관리자 권한을 설정할 수 없습니다. 허용된 사용자만 관리자가 될 수 있습니다.");
            }
            user.setRole(roleUpper);
        }

        return userRepository.save(user);
    }

    public List<User> getUsersByRole(String role) {
        return userRepository.findByRole(role);
    }

    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new RuntimeException("User not found with id: " + userId);
        }
        userRepository.deleteById(userId);
    }

    public void updatePassword(User user, String rawPassword) {
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }

    public boolean isAdmin(String userId) {
        User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        return "ADMIN".equals(user.getRole());
    }

    public List<String> getAllowedAdminUsers() {
        if (allowedAdminUsers == null || allowedAdminUsers.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(allowedAdminUsers.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
