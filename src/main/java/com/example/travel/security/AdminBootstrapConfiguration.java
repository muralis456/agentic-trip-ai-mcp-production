package com.example.travel.security;

import com.example.travel.entity.AppUser;
import com.example.travel.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Creates the first administrative account only when explicitly configured
 * through deployment environment variables.
 *
 * This is intentionally bootstrap-only: it never promotes an existing USER
 * account and never logs the configured password.
 */
@Configuration
public class AdminBootstrapConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapConfiguration.class);

    @Bean
    ApplicationRunner bootstrapAdmin(
            AppUserRepository repository,
            PasswordEncoder passwordEncoder,
            @Value("${travel.security.bootstrap-admin.username:}") String configuredUsername,
            @Value("${travel.security.bootstrap-admin.password:}") String configuredPassword) {

        return args -> bootstrap(repository, passwordEncoder, configuredUsername, configuredPassword);
    }

    @Transactional
    void bootstrap(
            AppUserRepository repository,
            PasswordEncoder passwordEncoder,
            String configuredUsername,
            String configuredPassword) {

        String username = normalizeUsername(configuredUsername);
        boolean usernameConfigured = !username.isBlank();
        boolean passwordConfigured = configuredPassword != null && !configuredPassword.isBlank();

        if (!usernameConfigured && !passwordConfigured) {
            return;
        }

        if (!usernameConfigured || !passwordConfigured) {
            throw new IllegalStateException(
                    "Bootstrap admin requires both BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD.");
        }

        validateUsername(username);
        if (configuredPassword.length() < 10 || configuredPassword.length() > 128) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD must be between 10 and 128 characters.");
        }

        AppUser existing = repository.findByUsernameIgnoreCase(username).orElse(null);
        if (existing != null) {
            if ("ADMIN".equalsIgnoreCase(existing.getRole())) {
                log.info("Bootstrap admin '{}' already exists; no changes made.", username);
                return;
            }
            throw new IllegalStateException(
                    "Bootstrap admin username '" + username
                            + "' already belongs to a non-admin account. "
                            + "Refusing to change its role automatically.");
        }

        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(configuredPassword));
        admin.setFirstName("System");
        admin.setLastName("Admin");
        admin.setEmail(username + "@local.invalid");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        admin.setCreatedAt(Instant.now());

        repository.save(admin);
        log.info("Bootstrap admin '{}' created with ADMIN role.", username);
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private void validateUsername(String username) {
        if (username.length() < 3 || username.length() > 80
                || !username.matches("[a-z0-9._-]+")) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_USERNAME must contain only letters, numbers, dot, underscore "
                            + "and hyphen, and be between 3 and 80 characters.");
        }
    }
}
