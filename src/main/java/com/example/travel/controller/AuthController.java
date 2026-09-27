package com.example.travel.controller;

import com.example.travel.auth.AuthService;
import com.example.travel.auth.PasswordResetService;
import com.example.travel.entity.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
public class AuthController {
    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final String resetBaseUrl;

    public AuthController(
            AuthService authService,
            PasswordResetService passwordResetService,
            @Value("${travel.auth.reset-base-url:http://localhost:8081}") String resetBaseUrl) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.resetBaseUrl = resetBaseUrl;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String firstName,
                           @RequestParam String lastName,
                           @RequestParam String email,
                           @RequestParam String username,
                           @RequestParam String password,
                           @RequestParam String confirmPassword,
                           org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        if (!password.equals(confirmPassword)) {
            redirect.addFlashAttribute("error", "Passwords do not match.");
            return "redirect:/register";
        }
        try {
            authService.register(firstName, lastName, email, username, password);
            redirect.addFlashAttribute("success", "Account created. Please sign in.");
            return "redirect:/login";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/register";
        }
    }



    @GetMapping(value = "/forgot-password", produces = "text/html")
    public String forgotPasswordPage() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam String email, RedirectAttributes redirect) {
        passwordResetService.requestReset(email, resetBaseUrl);
        // Deliberately generic: do not disclose whether an account exists.
        redirect.addFlashAttribute("success",
                "If an account exists for that email, a password reset link has been sent.");
        return "redirect:/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordPage(@RequestParam(required = false) String token,
                                    org.springframework.ui.Model model) {
        model.addAttribute("token", token == null ? "" : token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token,
                                @RequestParam String password,
                                @RequestParam String confirmPassword,
                                RedirectAttributes redirect) {
        if (!password.equals(confirmPassword)) {
            redirect.addFlashAttribute("error", "Passwords do not match.");
            redirect.addFlashAttribute("token", token);
            return "redirect:/reset-password?token=" + java.net.URLEncoder.encode(
                    token, java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            passwordResetService.resetPassword(token, password);
            redirect.addFlashAttribute("success", "Password reset successfully. Please sign in.");
            return "redirect:/login";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            redirect.addFlashAttribute("token", token);
            return "redirect:/reset-password?token=" + java.net.URLEncoder.encode(
                    token, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @PostMapping("/api/auth/forgot-password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apiForgotPassword(@RequestBody ForgotPasswordRequest request) {
        String email = request == null ? "" : request.email();
        passwordResetService.requestReset(email, resetBaseUrl);
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "message", "If an account exists for that email, a password reset link has been sent."));
    }

    @PostMapping("/api/auth/reset-password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apiResetPassword(@RequestBody ResetPasswordRequest request) {
        if (request == null || request.token() == null || request.newPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Reset token and new password are required."));
        }
        try {
            passwordResetService.resetPassword(request.token(), request.newPassword());
            return ResponseEntity.ok(Map.of("reset", true, "message", "Password reset successfully. Please sign in."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/api/auth/me")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        AppUser user = authService.findByUsername(authentication.getName());
        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "userId", user.getUsername(),
                "username", user.getUsername(),
                "firstName", user.getFirstName() == null ? "" : user.getFirstName(),
                "lastName", user.getLastName() == null ? "" : user.getLastName(),
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "role", user.getRole() == null ? "USER" : user.getRole(),
                "createdAt", user.getCreatedAt() == null ? "" : user.getCreatedAt().toString()));
    }

    @PostMapping("/api/auth/change-password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> changePassword(
            Authentication authentication, @RequestBody ChangePasswordRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password details are required."));
        }
        try {
            authService.changePassword(authentication.getName(), request.currentPassword(), request.newPassword());
            return ResponseEntity.ok(Map.of("changed", true, "message", "Password changed successfully."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/api/auth/register")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apiRegister(@RequestBody RegisterRequest request) {
        if (request == null || request.firstName() == null || request.lastName() == null
                || request.email() == null || request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "First name, last name, email, username and password are required."));
        }
        try {
            AppUser user = authService.register(
                    request.firstName(), request.lastName(), request.email(),
                    request.username(), request.password());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "created", true,
                    "username", user.getUsername(),
                    "email", user.getEmail()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    public record RegisterRequest(String firstName, String lastName, String email, String username, String password) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
    public record ForgotPasswordRequest(String email) {}
    public record ResetPasswordRequest(String token, String newPassword) {}
}
