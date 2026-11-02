package com.nextturn.service.notification;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Service orchestrating email dispatch via SMTP.
 * <p>
 * Supports branded HTML emails for OTP authentication and customer notifications.
 * If SMTP credentials are not configured or are set to placeholder values,
 * it safely logs the message to the console (*dự phòng an toàn*).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String mailHost;
    private final String mailUsername;
    private final String fromAddress;
    private final String fromName;

    @Autowired
    public EmailService(
            @Autowired(required = false) JavaMailSender mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${app.mail.from:${spring.mail.from:no-reply@nextturn.com}}") String fromAddress,
            @Value("${app.mail.from-name:NextTurn}") String fromName
    ) {
        this.mailSender = mailSender;
        this.mailHost = mailHost != null ? mailHost.trim() : "";
        this.mailUsername = mailUsername != null ? mailUsername.trim() : "";
        this.fromAddress = fromAddress != null && !fromAddress.isBlank() ? fromAddress.trim() : "no-reply@nextturn.com";
        this.fromName = fromName != null && !fromName.isBlank() ? fromName.trim() : "NextTurn";
    }

    /**
     * Determines whether SMTP sending is fully configured with active credentials.
     */
    public boolean isConfigured() {
        return mailSender != null
                && !mailHost.isBlank()
                && !mailHost.equalsIgnoreCase("localhost")
                && !mailUsername.isBlank();
    }

    /**
     * Asynchronously sends an OTP verification code email to the specified recipient.
     * <p>
     * Runs in the background on virtual threads without blocking the customer authentication HTTP request.
     *
     * @param toEmail    recipient's email address
     * @param otpCode    the 6-digit verification code
     * @param ttlSeconds validity duration in seconds
     * @return {@link CompletableFuture} containing true if successfully dispatched via SMTP, false if fallback/simulated
     */
    @Async
    public CompletableFuture<Boolean> sendOtpEmail(String toEmail, String otpCode, long ttlSeconds) {
        String subject = "[NextTurn] Your Verification Code: " + otpCode;
        long minutes = Math.max(1, ttlSeconds / 60);

        String htmlBody = buildOtpHtmlTemplate(toEmail, otpCode, minutes);

        if (!isConfigured()) {
            log.info("================================================================================");
            log.info("[OTP DISPATCH (CONSOLE)] Verification code for [{}] is: [{}]", toEmail, otpCode);
            log.info("                         Expires in {} seconds (SMTP credentials not configured)", ttlSeconds);
            log.info("================================================================================");
            return CompletableFuture.completedFuture(false);
        }

        try {
            sendHtmlEmail(toEmail, subject, htmlBody);
            log.info("Successfully sent real OTP email to [{}] via SMTP [{}]", toEmail, mailHost);
            return CompletableFuture.completedFuture(true);
        } catch (Exception ex) {
            log.error("Failed to send real OTP email to [{}] via SMTP: {}. Falling back to console dispatch.", toEmail, ex.getMessage());
            log.info("================================================================================");
            log.info("[OTP DISPATCH (FALLBACK)] Verification code for [{}] is: [{}]", toEmail, otpCode);
            log.info("                          Expires in {} seconds", ttlSeconds);
            log.info("================================================================================");
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Asynchronously dispatches a general HTML formatted email on virtual threads.
     *
     * @param toEmail     recipient email
     * @param subject     email subject
     * @param htmlContent HTML payload
     * @return {@link CompletableFuture} completing when dispatch concludes
     */
    @Async
    public CompletableFuture<Void> sendHtmlEmailAsync(String toEmail, String subject, String htmlContent) {
        try {
            sendHtmlEmail(toEmail, subject, htmlContent);
        } catch (Exception ex) {
            log.error("Async email dispatch to [{}] failed: {}", toEmail, ex.getMessage(), ex);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Dispatches a general HTML formatted email.
     *
     * @param toEmail     recipient email
     * @param subject     email subject
     * @param htmlContent HTML payload
     * @throws Exception if sending fails
     */
    public void sendHtmlEmail(String toEmail, String subject, String htmlContent) throws Exception {
        if (mailSender == null) {
            throw new IllegalStateException("JavaMailSender is not initialized");
        }

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

        helper.setFrom(resolveFromAddress());
        helper.setTo(toEmail.trim());
        helper.setSubject(subject);
        helper.setText(htmlContent, true);

        mailSender.send(message);
    }

    private InternetAddress resolveFromAddress() throws Exception {
        String emailOnly = fromAddress != null ? fromAddress.trim() : "";
        String displayName = fromName != null ? fromName.trim() : "NextTurn";

        int start = emailOnly.indexOf('<');
        int end = emailOnly.indexOf('>');
        if (start >= 0 && end > start) {
            String extractedName = emailOnly.substring(0, start).trim();
            if (!extractedName.isBlank()) {
                displayName = extractedName;
            }
            emailOnly = emailOnly.substring(start + 1, end).trim();
        }

        if (!emailOnly.contains("@") && mailUsername.contains("@")) {
            emailOnly = mailUsername;
        }

        if (emailOnly.isBlank()) {
            emailOnly = "no-reply@nextturn.com";
        }

        return new InternetAddress(emailOnly, displayName, StandardCharsets.UTF_8.name());
    }

    private String buildOtpHtmlTemplate(String email, String otpCode, long minutes) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>NextTurn Verification Code</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; color: #1e293b; }
                        .card { max-width: 500px; margin: 0 auto; background: #ffffff; border-radius: 12px; border: 1px solid #e2e8f0; padding: 32px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05); }
                        .logo { font-size: 22px; font-weight: 800; color: #0284c7; letter-spacing: -0.5px; margin-bottom: 24px; }
                        .header { font-size: 20px; font-weight: 700; margin-bottom: 12px; color: #0f172a; }
                        .desc { font-size: 14px; color: #64748b; line-height: 1.6; margin-bottom: 24px; }
                        .code-box { background: #f1f5f9; border: 1px dashed #cbd5e1; border-radius: 8px; text-align: center; padding: 18px 0; font-size: 32px; font-weight: 800; letter-spacing: 8px; color: #0f172a; margin-bottom: 24px; font-family: 'Courier New', Courier, monospace; }
                        .footer { font-size: 12px; color: #94a3b8; line-height: 1.5; border-top: 1px solid #f1f5f9; padding-top: 20px; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="logo">NextTurn</div>
                        <div class="header">Your Verification Code</div>
                        <div class="desc">
                            Hello,<br>
                            Use the following one-time verification code to sign in to your NextTurn account.
                        </div>
                        <div class="code-box">%s</div>
                        <div class="desc">
                            This code is valid for <strong>%d minutes</strong>. If you did not request this code, you can safely ignore this email.
                        </div>
                        <div class="footer">
                            This is an automated security message from NextTurn Booking & Queue Management.<br>
                            Please do not reply directly to this email.
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(otpCode, minutes);
    }
}
