package com.example.travel.auth;

import com.example.travel.entity.AppUser;
import com.example.travel.repository.AppUserRepository;
import com.example.travel.service.ApiRateLimitService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {
    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final ApiRateLimitService rateLimitService;

    public AuthService(AppUserRepository repository, PasswordEncoder passwordEncoder, ApiRateLimitService rateLimitService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
    }

    @Transactional
    public AppUser register(String firstName, String lastName, String email, String username, String password) {
        String normalizedFirstName = normalizeName(firstName);
        String normalizedLastName = normalizeName(lastName);
        String normalizedEmail = normalizeEmail(email);
        String normalizedUsername = normalizeUsername(username);

        validateName("First name", normalizedFirstName);
        validateName("Last name", normalizedLastName);
        validateEmail(normalizedEmail);

        if (normalizedUsername.length() < 3 || normalizedUsername.length() > 80) {
            throw new IllegalArgumentException("Username must be between 3 and 80 characters.");
        }
        if (!normalizedUsername.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException("Username may contain letters, numbers, dot, underscore and hyphen only.");
        }
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw new IllegalArgumentException("Password must be between 10 and 128 characters.");
        }
        if (repository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new IllegalArgumentException("That username is already registered.");
        }
        if (repository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalArgumentException("That email address is already registered.");
        }

        AppUser user = new AppUser();
        user.setFirstName(normalizedFirstName);
        user.setLastName(normalizedLastName);
        user.setEmail(normalizedEmail);
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole("USER");
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        try {
            AppUser saved = repository.saveAndFlush(user);
            rateLimitService.initialize(saved.getUsername());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("That username or email is already registered.");
        }
    }


    @Transactional(readOnly = true)
    public AppUser findByUsername(String username) {
        return repository.findByUsernameIgnoreCase(normalizeUsername(username))
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        String normalizedUsername = normalizeUsername(username);
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("Current password is required.");
        }
        if (newPassword == null || newPassword.length() < 10 || newPassword.length() > 128) {
            throw new IllegalArgumentException("New password must be between 10 and 128 characters.");
        }

        AppUser user = repository.findByUsernameIgnoreCase(normalizedUsername)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from your current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        repository.save(user);
    }

    /** Backward-compatible overload for internal callers/tests that only provide credentials. */
    @Transactional
    public AppUser register(String username, String password) {
        return register("Travel", "User", username + "@local.invalid", username, password);
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    private void validateName(String field, String value) {
        if (value.length() < 1 || value.length() > 80) {
            throw new IllegalArgumentException(field + " must be between 1 and 80 characters.");
        }
        if (!value.matches("[A-Za-z][A-Za-z .'-]*")) {
            throw new IllegalArgumentException(field + " contains invalid characters.");
        }
    }

    private void validateEmail(String email) {
        if (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
    }
}
