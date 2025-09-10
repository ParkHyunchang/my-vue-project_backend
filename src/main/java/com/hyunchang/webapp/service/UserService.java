package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Role;
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
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
    
    public User registerUser(String username, String email, String password, Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("Username already exists");
        }
        
        if (userRepository.existsByEmail(email)) {
            throw new RuntimeException("Email already exists");
        }
        
        // 관리자 권한 제한: 특정 사용자만 관리자로 설정 가능
        if (role == Role.ADMIN && !isAllowedAdmin(username)) {
            throw new RuntimeException("관리자 권한을 설정할 수 없습니다. 허용된 사용자만 관리자가 될 수 있습니다.");
        }
        
        User user = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(password))
                .role(role)
                .build();
        
        return userRepository.save(user);
    }
    
    // 관리자 권한을 가질 수 있는 사용자 목록 (설정 파일 기반)
    private boolean isAllowedAdmin(String username) {
        if (allowedAdminUsers == null || allowedAdminUsers.trim().isEmpty()) {
            return false;
        }
        
        List<String> allowedUsers = Arrays.asList(allowedAdminUsers.split(","));
        return allowedUsers.stream()
                .map(String::trim)
                .anyMatch(allowedUser -> allowedUser.equals(username));
    }
    
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }
    
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }
    
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
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
    
    public User updateUserRole(Long userId, Role newRole) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        
        // 관리자 권한 제한: 특정 사용자만 관리자로 설정 가능
        if (newRole == Role.ADMIN && !isAllowedAdmin(user.getUsername())) {
            throw new RuntimeException("관리자 권한을 설정할 수 없습니다. 허용된 사용자만 관리자가 될 수 있습니다.");
        }
        
        user.setRole(newRole);
        return userRepository.save(user);
    }
    
    public void deleteUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new RuntimeException("User not found with id: " + userId);
        }
        userRepository.deleteById(userId);
    }
    
    public boolean isAdmin(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        return user.getRole() == Role.ADMIN;
    }
    
    public List<String> getAllowedAdminUsers() {
        if (allowedAdminUsers == null || allowedAdminUsers.trim().isEmpty()) {
            return Arrays.asList();
        }
        
        return Arrays.asList(allowedAdminUsers.split(","))
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
