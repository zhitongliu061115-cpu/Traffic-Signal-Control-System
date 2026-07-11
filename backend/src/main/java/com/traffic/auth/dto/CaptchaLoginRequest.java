package com.traffic.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CaptchaLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String captcha
) {
}
