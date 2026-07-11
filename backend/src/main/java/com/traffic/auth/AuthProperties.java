package com.traffic.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private final Captcha captcha = new Captcha();
    private final InitialAccount initialAccount = new InitialAccount();
    private final Mail mail = new Mail();
    private String inviteCode = "123456";

    public Captcha getCaptcha() {
        return captcha;
    }

    public Mail getMail() {
        return mail;
    }

    public InitialAccount getInitialAccount() {
        return initialAccount;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }

    public static class Captcha {
        private long resendIntervalSeconds = 60;
        private long ttlMinutes = 5;

        public long getResendIntervalSeconds() {
            return resendIntervalSeconds;
        }

        public void setResendIntervalSeconds(long resendIntervalSeconds) {
            this.resendIntervalSeconds = resendIntervalSeconds;
        }

        public long getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(long ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }

    public static class Mail {
        private String from = "";
        private String senderName = "信号灯配时控制系统";

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getSenderName() {
            return senderName;
        }

        public void setSenderName(String senderName) {
            this.senderName = senderName;
        }
    }

    public static class InitialAccount {
        private String email = "admin@traffic.local";
        private String password = "123456";
        private String username = "admin";

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }
}
