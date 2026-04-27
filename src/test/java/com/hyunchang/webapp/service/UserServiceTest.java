package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.UpdateUserRequest;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.exception.UserAlreadyExistsException;
import com.hyunchang.webapp.exception.UserNotFoundException;
import com.hyunchang.webapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @InjectMocks private UserService userService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(userService, "allowedAdminUsers", "hyunchang88,admin");
    }

    @Test
    void registerUser_whenUserIdAlreadyExists_throwsUserAlreadyExistsException() {
        when(userRepository.existsByUserId("duplicate")).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class, () ->
            userService.registerUser("duplicate", "이름", "test@test.com", null, "password123", "USER"));
    }

    @Test
    void registerUser_whenEmailAlreadyExists_throwsUserAlreadyExistsException() {
        when(userRepository.existsByUserId("newUser")).thenReturn(false);
        when(userRepository.existsByEmail("dup@test.com")).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class, () ->
            userService.registerUser("newUser", "이름", "dup@test.com", null, "password123", "USER"));
    }

    @Test
    void registerUser_whenUnauthorizedAdminRole_throwsIllegalArgumentException() {
        when(userRepository.existsByUserId("hacker")).thenReturn(false);
        when(userRepository.existsByEmail("hacker@test.com")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () ->
            userService.registerUser("hacker", "이름", "hacker@test.com", null, "password123", "ADMIN"));
    }

    @Test
    void updateUser_whenUserNotFound_throwsUserNotFoundException() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () ->
            userService.updateUser(999L, new UpdateUserRequest()));
    }

    @Test
    void deleteUser_whenUserNotFound_throwsUserNotFoundException() {
        when(userRepository.existsById(999L)).thenReturn(false);

        assertThrows(UserNotFoundException.class, () ->
            userService.deleteUser(999L));
    }

    @Test
    void registerUser_whenAllowedAdmin_savesUser() {
        when(userRepository.existsByUserId("hyunchang88")).thenReturn(false);
        when(userRepository.existsByEmail("admin@test.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        User savedUser = User.builder()
                .userId("hyunchang88").name("이름").email("admin@test.com").role("ADMIN").build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        User result = userService.registerUser("hyunchang88", "이름", "admin@test.com", null, "password123", "ADMIN");

        assertEquals("hyunchang88", result.getUserId());
        assertEquals("ADMIN", result.getRole());
    }
}
