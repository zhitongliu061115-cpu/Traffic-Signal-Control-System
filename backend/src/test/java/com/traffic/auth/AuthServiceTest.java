package com.traffic.auth;

import com.traffic.auth.dto.CaptchaLoginRequest;
import com.traffic.auth.dto.LoginRequest;
import com.traffic.auth.dto.RegisterRequest;
import com.traffic.auth.entity.AuthUser;
import com.traffic.auth.repository.AuthUserRepository;
import com.traffic.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void sendCaptchaSendsMailAndPreventsImmediateResend() {
        AuthProperties properties = new AuthProperties();
        AuthUserRepository userRepository = mock(AuthUserRepository.class);
        CaptchaMailService mailService = mock(CaptchaMailService.class);
        AuthService service = new AuthService(
                properties,
                userRepository,
                mailService,
                passwordHasher,
                Clock.fixed(Instant.parse("2026-07-11T09:00:00Z"), ZoneOffset.UTC)
        );

        service.sendCaptcha("USER@example.com");

        verify(mailService).sendCaptcha(eq("user@example.com"), org.mockito.ArgumentMatchers.matches("\\d{6}"), eq(Instant.parse("2026-07-11T09:05:00Z")));
        assertThrows(BusinessException.class, () -> service.sendCaptcha("user@example.com"));
    }

    @Test
    void registerRejectsWrongInviteCode() {
        AuthProperties properties = new AuthProperties();
        AuthService service = new AuthService(
                properties,
                mock(AuthUserRepository.class),
                mock(CaptchaMailService.class),
                passwordHasher
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> service.register(
                new RegisterRequest("operator", "operator@example.com", "secret", "BAD-CODE")
        ));

        assertEquals("邀请码不正确", ex.getMessage());
    }

    @Test
    void initialAdminCanLoginWithDefaultPassword() {
        AuthProperties properties = new AuthProperties();
        AuthUserRepository userRepository = mock(AuthUserRepository.class);
        when(userRepository.existsByNormalizedUsername("admin")).thenReturn(false);
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthService service = new AuthService(properties, userRepository, mock(CaptchaMailService.class), passwordHasher);

        service.seedInitialAccount();

        verify(userRepository).save(any(AuthUser.class));
    }

    @Test
    void loginReadsUserFromDatabaseAndChecksHash() {
        AuthProperties properties = new AuthProperties();
        AuthUserRepository userRepository = mock(AuthUserRepository.class);
        AuthUser admin = new AuthUser(
                UUID.randomUUID(),
                "admin",
                "admin",
                "admin@traffic.local",
                "admin@traffic.local",
                passwordHasher.hash("123456"),
                "ADMIN",
                true,
                Instant.parse("2026-07-11T09:00:00Z"),
                Instant.parse("2026-07-11T09:00:00Z")
        );
        when(userRepository.findByNormalizedUsername("admin")).thenReturn(Optional.of(admin));
        AuthService service = new AuthService(properties, userRepository, mock(CaptchaMailService.class), passwordHasher);

        var result = service.login(new LoginRequest("admin", null, "123456"));

        assertEquals("admin", result.user().username());
    }

    @Test
    void captchaLoginRejectsUnknownCode() {
        AuthProperties properties = new AuthProperties();
        AuthService service = new AuthService(
                properties,
                mock(AuthUserRepository.class),
                mock(CaptchaMailService.class),
                passwordHasher
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> service.loginWithCaptcha(
                new CaptchaLoginRequest("operator@example.com", "123456")
        ));

        assertTrue(ex.getMessage().contains("验证码"));
    }

    @Test
    void registerWritesHashedPasswordToDatabase() {
        AuthProperties properties = new AuthProperties();
        AuthUserRepository userRepository = mock(AuthUserRepository.class);
        when(userRepository.existsByNormalizedUsername("operator")).thenReturn(false);
        when(userRepository.existsByNormalizedEmail("operator@example.com")).thenReturn(false);
        when(userRepository.save(any(AuthUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthService service = new AuthService(properties, userRepository, mock(CaptchaMailService.class), passwordHasher);

        service.register(new RegisterRequest("operator", "operator@example.com", "secret", "123456"));

        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(user ->
                user.getPasswordHash().startsWith("pbkdf2-sha256$")
                        && !user.getPasswordHash().contains("secret")
                        && passwordHasher.matches("secret", user.getPasswordHash())
        ));
    }

    @Test
    void malformedPasswordHashDoesNotMatch() {
        assertFalse(passwordHasher.matches("secret", "pbkdf2-sha256$bad$hash$format"));
    }
}
