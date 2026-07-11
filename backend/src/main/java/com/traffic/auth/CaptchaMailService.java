package com.traffic.auth;

import com.traffic.common.exception.BusinessException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class CaptchaMailService {

    private static final DateTimeFormatter EXPIRE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final AuthProperties authProperties;
    private final JavaMailSender mailSender;

    public CaptchaMailService(AuthProperties authProperties, JavaMailSender mailSender) {
        this.authProperties = authProperties;
        this.mailSender = mailSender;
    }

    public void sendCaptcha(String to, String code, Instant expiresAt) {
        String from = authProperties.getMail().getFrom();
        if (!StringUtils.hasText(from)) {
            throw new BusinessException("邮件发件人未配置");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from, authProperties.getMail().getSenderName());
            helper.setTo(to);
            helper.setSubject("信号灯配时控制系统登录验证码");
            helper.setText(buildMailContent(code, expiresAt), false);
            mailSender.send(message);
        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            throw new BusinessException("验证码邮件发送失败，请检查邮箱 SMTP 配置");
        }
    }

    private String buildMailContent(String code, Instant expiresAt) {
        return """
                您正在登录信号灯配时控制与应急通行信控系统。
                验证码：%s
                有效期至：%s

                如非本人操作，请忽略本邮件。
                """.formatted(code, EXPIRE_FORMATTER.format(expiresAt));
    }
}
