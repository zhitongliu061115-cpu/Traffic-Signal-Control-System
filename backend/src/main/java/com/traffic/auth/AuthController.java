package com.traffic.auth;

import com.traffic.auth.dto.AuthResult;
import com.traffic.auth.dto.CaptchaLoginRequest;
import com.traffic.auth.dto.LoginRequest;
import com.traffic.auth.dto.RegisterRequest;
import com.traffic.auth.dto.SendCaptchaRequest;
import com.traffic.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/send-captcha")
    public ApiResponse<Void> sendCaptcha(@Valid @RequestBody SendCaptchaRequest request) {
        authService.sendCaptcha(request.email());
        return ApiResponse.ok(null);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/captcha-login")
    public ApiResponse<AuthResult> captchaLogin(@Valid @RequestBody CaptchaLoginRequest request) {
        return ApiResponse.ok(authService.loginWithCaptcha(request));
    }

    @PostMapping("/register")
    public ApiResponse<AuthResult> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }
}
