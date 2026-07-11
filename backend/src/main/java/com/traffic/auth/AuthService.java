package com.traffic.auth;

import com.traffic.auth.dto.AuthResult;
import com.traffic.auth.dto.CaptchaLoginRequest;
import com.traffic.auth.dto.LoginRequest;
import com.traffic.auth.dto.RegisterRequest;
import com.traffic.auth.entity.AuthUser;
import com.traffic.auth.repository.AuthUserRepository;
import com.traffic.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final AuthProperties authProperties;
    private final AuthUserRepository authUserRepository;
    private final CaptchaMailService captchaMailService;
    private final Clock clock;
    private final PasswordHasher passwordHasher;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, CaptchaEntry> captchaStore = new ConcurrentHashMap<>();

    @Autowired
    public AuthService(
            AuthProperties authProperties,
            AuthUserRepository authUserRepository,
            CaptchaMailService captchaMailService,
            PasswordHasher passwordHasher
    ) {
        this(authProperties, authUserRepository, captchaMailService, passwordHasher, Clock.systemUTC());
    }

    AuthService(
            AuthProperties authProperties,
            AuthUserRepository authUserRepository,
            CaptchaMailService captchaMailService,
            PasswordHasher passwordHasher,
            Clock clock
    ) {
        this.authProperties = authProperties;
        this.authUserRepository = authUserRepository;
        this.captchaMailService = captchaMailService;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    public void sendCaptcha(String email) {
        String normalizedEmail = normalizeEmail(email);
        Instant now = clock.instant();
        CaptchaEntry lastEntry = captchaStore.get(normalizedEmail);
        if (lastEntry != null && Duration.between(lastEntry.sentAt(), now).getSeconds()
                < authProperties.getCaptcha().getResendIntervalSeconds()) {
            throw new BusinessException("验证码发送过于频繁，请稍后再试");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        Instant expiresAt = now.plus(Duration.ofMinutes(authProperties.getCaptcha().getTtlMinutes()));
        captchaStore.put(normalizedEmail, new CaptchaEntry(code, now, expiresAt));
        captchaMailService.sendCaptcha(normalizedEmail, code, expiresAt);
    }

    @Transactional(readOnly = true)
    public AuthResult login(LoginRequest request) {
        AuthUser account = authUserRepository.findByNormalizedUsername(normalizeUsername(request.username()))
                .filter(AuthUser::isEnabled)
                .orElseThrow(() -> new BusinessException("用户名或密码不正确"));
        if (!passwordHasher.matches(request.password(), account.getPasswordHash())) {
            throw new BusinessException("用户名或密码不正确");
        }

        return issueToken(account);
    }

    @Transactional(readOnly = true)
    public AuthResult loginWithCaptcha(CaptchaLoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        CaptchaEntry entry = captchaStore.get(normalizedEmail);
        if (entry == null || entry.expiresAt().isBefore(clock.instant())) {
            throw new BusinessException("验证码已过期，请重新发送");
        }
        if (!entry.code().equals(request.captcha().trim())) {
            throw new BusinessException("验证码不正确");
        }

        captchaStore.remove(normalizedEmail);
        AuthUser account = authUserRepository.findByNormalizedEmail(normalizedEmail)
                .filter(AuthUser::isEnabled)
                .orElseThrow(() -> new BusinessException("账号不存在，请先注册"));
        return issueToken(account);
    }

    @Transactional
    public AuthResult register(RegisterRequest request) {
        if (!authProperties.getInviteCode().equals(request.inviteCode().trim())) {
            throw new BusinessException("邀请码不正确");
        }

        String normalizedUsername = normalizeUsername(request.username());
        String normalizedEmail = normalizeEmail(request.email());
        if (authUserRepository.existsByNormalizedUsername(normalizedUsername)) {
            throw new BusinessException("用户名已存在");
        }
        if (authUserRepository.existsByNormalizedEmail(normalizedEmail)) {
            throw new BusinessException("邮箱已存在");
        }

        AuthUser account = createAccount(
                request.username().trim(),
                normalizedUsername,
                request.email().trim(),
                normalizedEmail,
                request.password(),
                "OPERATOR"
        );
        return issueToken(authUserRepository.save(account));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedInitialAccount() {
        AuthProperties.InitialAccount initial = authProperties.getInitialAccount();
        String username = StringUtils.hasText(initial.getUsername()) ? initial.getUsername().trim() : "admin";
        String email = StringUtils.hasText(initial.getEmail()) ? initial.getEmail().trim() : "admin@traffic.local";
        String password = StringUtils.hasText(initial.getPassword()) ? initial.getPassword() : "123456";
        String normalizedUsername = normalizeUsername(username);
        if (authUserRepository.existsByNormalizedUsername(normalizedUsername)) {
            return;
        }

        authUserRepository.save(createAccount(
                username,
                normalizedUsername,
                email,
                normalizeEmail(email),
                password,
                "ADMIN"
        ));
    }

    private AuthUser createAccount(
            String username,
            String normalizedUsername,
            String email,
            String normalizedEmail,
            String password,
            String role
    ) {
        Instant now = clock.instant();
        return new AuthUser(
                UUID.randomUUID(),
                username,
                normalizedUsername,
                email,
                normalizedEmail,
                passwordHasher.hash(password),
                role,
                true,
                now,
                now
        );
    }

    private AuthResult issueToken(AuthUser account) {
        return new AuthResult(
                UUID.randomUUID().toString(),
                new AuthResult.User(account.getId().toString(), account.getUsername(), account.getEmail())
        );
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private record CaptchaEntry(String code, Instant sentAt, Instant expiresAt) {
    }
}
