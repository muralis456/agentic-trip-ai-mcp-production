package com.example.travel.auth;

import com.example.travel.entity.AppUser;
import com.example.travel.entity.PasswordResetToken;
import com.example.travel.repository.AppUserRepository;
import com.example.travel.repository.PasswordResetTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

@Service
public class PasswordResetService {
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetNotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;

    public PasswordResetService(
            AppUserRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            PasswordResetNotificationService notificationService,
            JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Always completes with the same externally visible outcome. Callers must not
     * reveal whether the email exists.
     */
    @Transactional
    public void requestReset(String email, String baseUrl) {
        String normalized = normalizeEmail(email);
        if (normalized.isBlank() || normalized.length() > 254) {
            return;
        }

        userRepository.findByEmailIgnoreCase(normalized).ifPresent(user -> {
            tokenRepository.deleteByUser_Id(user.getId());

            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

            PasswordResetToken token = new PasswordResetToken();
            token.setUser(user);
            token.setTokenHash(sha256(rawToken));
            token.setCreatedAt(Instant.now());
            token.setExpiresAt(Instant.now().plus(TOKEN_TTL));
            tokenRepository.save(token);

            String safeBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
            String resetUrl = safeBase + "/reset-password?token=" + rawToken;
            notificationService.sendResetLink(user.getEmail(), resetUrl);
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("This password reset link is invalid or has expired.");
        }
        validateNewPassword(newPassword);

        PasswordResetToken token = tokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new IllegalArgumentException("This password reset link is invalid or has expired."));

        Instant now = Instant.now();
        if (!token.isUsable(now)) {
            throw new IllegalArgumentException("This password reset link is invalid or has expired.");
        }

        AppUser user = token.getUser();
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("This password reset link is invalid or has expired.");
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from your current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(now);
        tokenRepository.save(token);

        // Spring Session JDBC schema uses these two tables. Delete the user's
        // existing sessions so a password reset invalidates active logins.
        jdbcTemplate.update(
                "delete from SPRING_SESSION_ATTRIBUTES where SESSION_PRIMARY_ID in " +
                "(select PRIMARY_ID from SPRING_SESSION where PRINCIPAL_NAME = ?)",
                user.getUsername());
        jdbcTemplate.update(
                "delete from SPRING_SESSION where PRINCIPAL_NAME = ?",
                user.getUsername());
    }

    private void validateNewPassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw new IllegalArgumentException("New password must be between 10 and 128 characters.");
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create password reset token.", ex);
        }
    }
}
