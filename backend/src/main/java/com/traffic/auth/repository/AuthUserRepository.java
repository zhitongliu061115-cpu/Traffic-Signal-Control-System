package com.traffic.auth.repository;

import com.traffic.auth.entity.AuthUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AuthUserRepository extends JpaRepository<AuthUser, UUID> {

    boolean existsByNormalizedEmail(String normalizedEmail);

    boolean existsByNormalizedUsername(String normalizedUsername);

    Optional<AuthUser> findByNormalizedEmail(String normalizedEmail);

    Optional<AuthUser> findByNormalizedUsername(String normalizedUsername);
}
