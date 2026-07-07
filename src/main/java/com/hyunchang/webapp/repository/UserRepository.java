package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUserId(String userId);

    Optional<User> findByEmail(String email);

    boolean existsByUserId(String userId);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    long countByRole(String role);

    List<User> findByRole(String role);
}
