package com.example.travel.auth;

public interface PasswordResetNotificationService {
    void sendResetLink(String recipientEmail, String resetUrl);
}
