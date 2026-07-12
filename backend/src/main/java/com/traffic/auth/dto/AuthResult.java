package com.traffic.auth.dto;

public record AuthResult(
        String token,
        User user
) {
    public record User(
            String id,
            String username,
            String email
    ) {
    }
}
