package com.example.travel.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpPasswordResetNotificationService implements PasswordResetNotificationService {
    private final JavaMailSender mailSender;
    private final String from;

    public SmtpPasswordResetNotificationService(
            JavaMailSender mailSender,
            @Value("${travel.auth.mail-from:noreply@agentictrip.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendResetLink(String recipientEmail, String resetUrl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipientEmail);
        message.setSubject("Reset your AgenticTripAI password");
        message.setText("""
                We received a request to reset your AgenticTripAI password.

                Reset your password:
                %s

                This link expires in 30 minutes and can only be used once.

                If you did not request this, you can safely ignore this email.
                """.formatted(resetUrl));
        mailSender.send(message);
    }
}
