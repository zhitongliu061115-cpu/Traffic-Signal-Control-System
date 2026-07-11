package com.traffic.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_user")
public class AuthUser {

    @Id
    private UUID id;

    @Column(nullable = false, length = 128)
    private String username;

    @Column(name = "normalized_username", nullable = false, length = 128, unique = true)
    private String normalizedUsername;

    @Column(nullable = false, length = 256)
    private String email;

    @Column(name = "normalized_email", nullable = false, length = 256, unique = true)
    private String normalizedEmail;

    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;

    @Column(nullable = false, length = 64)
    private String role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AuthUser() {
    }

    public AuthUser(
            UUID id,
            String username,
            String normalizedUsername,
            String email,
            String normalizedEmail,
            String passwordHash,
            String role,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.username = username;
        this.normalizedUsername = normalizedUsername;
        this.email = email;
        this.normalizedEmail = normalizedEmail;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getNormalizedUsername() {
        return normalizedUsername;
    }

    public String getEmail() {
        return email;
    }

    public String getNormalizedEmail() {
        return normalizedEmail;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
