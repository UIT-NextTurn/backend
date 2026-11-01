package com.nextturn.service.notification;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service orchestrating email dispatch via SMTP.
 * <p>
 * Supports branded HTML emails for OTP authentication and customer notifications.
 * HTML email layouts are loaded from templates in {@code src/main/resources/templates/email/}.
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
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

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
     * Asynchronously sends an activation and password setup email to a newly provisioned tenant administrator (REQ-TNT-01).
     *
     * @param toEmail        recipient admin email
     * @param tenantName     tenant organization name
     * @param activationLink activation and password configuration URL
     * @return {@link CompletableFuture} containing dispatch outcome
     */
    @Async
    public CompletableFuture<Boolean> sendTenantAdminActivationEmail(String toEmail, String tenantName, String activationLink) {
        String subject = "[NextTurn] Kích hoạt tài khoản Quản trị viên cơ sở - " + (tenantName != null ? tenantName : "Cơ sở mới");
        String htmlBody = buildTenantAdminActivationHtmlTemplate(toEmail, tenantName, activationLink);

        if (!isConfigured()) {
            log.info("================================================================================");
            log.info("[TENANT ACTIVATION DISPATCH (CONSOLE)] Account activation for [{}] at [{}]", toEmail, tenantName);
            log.info("                                       Activation Link: [{}]", activationLink);
            log.info("================================================================================");
            return CompletableFuture.completedFuture(false);
        }

        try {
            sendHtmlEmail(toEmail, subject, htmlBody);
            log.info("Successfully sent tenant admin activation email to [{}] for [{}] via SMTP [{}]", toEmail, tenantName, mailHost);
            return CompletableFuture.completedFuture(true);
        } catch (Exception ex) {
            log.error("Failed to send tenant admin activation email to [{}] via SMTP: {}. Falling back to console.", toEmail, ex.getMessage());
            log.info("================================================================================");
            log.info("[TENANT ACTIVATION DISPATCH (FALLBACK)] Account activation for [{}] at [{}]", toEmail, tenantName);
            log.info("                                        Activation Link: [{}]", activationLink);
            log.info("================================================================================");
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Sends an email confirming receipt of a tenant registration application (REQ-TNT-05).
     *
     * @param toEmail    applicant email address
     * @param tenantName organization name
     * @param tenantCode requested tenant code
     * @return future resolving to true if sent, false otherwise
     */
    @Async
    public CompletableFuture<Boolean> sendTenantRegistrationReceivedEmail(String toEmail, String tenantName, String tenantCode) {
        String subject = "[NextTurn] Xác nhận tiếp nhận hồ sơ đăng ký tổ chức - " + (tenantName != null ? tenantName : tenantCode);
        String htmlBody = buildTenantRegistrationReceivedHtmlTemplate(toEmail, tenantName, tenantCode);

        if (!isConfigured()) {
            log.info("================================================================================");
            log.info("[TENANT REGISTRATION RECEIVED (CONSOLE)] Applicant: [{}], Org: [{} ({})]", toEmail, tenantName, tenantCode);
            log.info("================================================================================");
            return CompletableFuture.completedFuture(false);
        }

        try {
            sendHtmlEmail(toEmail, subject, htmlBody);
            log.info("Successfully sent registration confirmation email to [{}] for [{}]", toEmail, tenantName);
            return CompletableFuture.completedFuture(true);
        } catch (Exception ex) {
            log.error("Failed to send registration confirmation email to [{}]: {}", toEmail, ex.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Sends internal notification to platform Superadministrator regarding a new tenant registration request (REQ-TNT-05).
     *
     * @param superadminEmail superadministrator notification email
     * @param tenantName      organization name
     * @param tenantCode      requested tenant code
     * @param adminEmail      applicant email address
     * @return future resolving to true if sent, false otherwise
     */
    @Async
    public CompletableFuture<Boolean> sendTenantRegistrationInternalNotification(
            String superadminEmail,
            String tenantName,
            String tenantCode,
            String adminEmail
    ) {
        String targetEmail = (superadminEmail != null && !superadminEmail.isBlank()) ? superadminEmail : "superadmin@nextturn.com";
        String subject = "[NextTurn Alert] Yêu cầu đăng ký tổ chức mới chờ phê duyệt: " + tenantName + " (" + tenantCode + ")";
        String htmlBody = buildTenantRegistrationInternalHtmlTemplate(tenantName, tenantCode, adminEmail);

        if (!isConfigured()) {
            log.info("================================================================================");
            log.info("[INTERNAL ALERT (CONSOLE)] New tenant pending review: [{}] ([{}]) by [{}]", tenantName, tenantCode, adminEmail);
            log.info("================================================================================");
            return CompletableFuture.completedFuture(false);
        }

        try {
            sendHtmlEmail(targetEmail, subject, htmlBody);
            log.info("Successfully sent internal registration notification to [{}] for [{}]", targetEmail, tenantName);
            return CompletableFuture.completedFuture(true);
        } catch (Exception ex) {
            log.error("Failed to send internal registration notification: {}", ex.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Sends congratulatory email with login guide when a registration application is approved (REQ-TNT-06).
     *
     * @param toEmail        approved admin email address
     * @param tenantName     organization name
     * @param tenantCode     tenant code
     * @param activationLink portal login URL
     * @return future resolving to true if sent, false otherwise
     */
    @Async
    public CompletableFuture<Boolean> sendTenantRegistrationApprovedEmail(
            String toEmail,
            String tenantName,
            String tenantCode,
            String activationLink
    ) {
        String subject = "[NextTurn] Chúc mừng! Hồ sơ đăng ký tổ chức " + tenantName + " đã được phê duyệt";
        String htmlBody = buildTenantRegistrationApprovedHtmlTemplate(toEmail, tenantName, tenantCode, activationLink);

        if (!isConfigured()) {
            log.info("================================================================================");
            log.info("[REGISTRATION APPROVED (CONSOLE)] Org [{}] approved for [{}]. Link: [{}]", tenantName, toEmail, activationLink);
            log.info("================================================================================");
            return CompletableFuture.completedFuture(false);
        }

        try {
            sendHtmlEmail(toEmail, subject, htmlBody);
            log.info("Successfully sent approval email to [{}] for [{}]", toEmail, tenantName);
            return CompletableFuture.completedFuture(true);
        } catch (Exception ex) {
            log.error("Failed to send approval email to [{}]: {}", toEmail, ex.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Sends denial notification email with mandatory rejection reason (REQ-TNT-06).
     *
     * @param toEmail         applicant email address
     * @param tenantName      organization name
     * @param tenantCode      tenant code
     * @param rejectionReason mandatory reason for rejection
     * @return future resolving to true if sent, false otherwise
     */
    @Async
    public CompletableFuture<Boolean> sendTenantRegistrationRejectedEmail(
            String toEmail,
            String tenantName,
            String tenantCode,
            String rejectionReason
    ) {
        String subject = "[NextTurn] Thông báo về kết quả đăng ký tổ chức - " + (tenantName != null ? tenantName : tenantCode);
        String htmlBody = buildTenantRegistrationRejectedHtmlTemplate(toEmail, tenantName, tenantCode, rejectionReason);

        if (!isConfigured()) {
            log.info("================================================================================");
            log.info("[REGISTRATION REJECTED (CONSOLE)] Org [{}] rejected for [{}]. Reason: [{}]", tenantName, toEmail, rejectionReason);
            log.info("================================================================================");
            return CompletableFuture.completedFuture(false);
        }

        try {
            sendHtmlEmail(toEmail, subject, htmlBody);
            log.info("Successfully sent rejection email to [{}] for [{}]", toEmail, tenantName);
            return CompletableFuture.completedFuture(true);
        } catch (Exception ex) {
            log.error("Failed to send rejection email to [{}]: {}", toEmail, ex.getMessage());
            return CompletableFuture.completedFuture(false);
        }
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

    private String loadTemplate(String templatePath, Map<String, String> variables) {
        try {
            String template = templateCache.computeIfAbsent(templatePath, path -> {
                try {
                    ClassPathResource resource = new ClassPathResource(path);
                    try (InputStream is = resource.getInputStream()) {
                        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                } catch (Exception ex) {
                    log.warn("Could not load email template from classpath [{}]: {}", path, ex.getMessage());
                    return null;
                }
            });

            if (template == null || template.isBlank()) {
                return "";
            }

            String result = template;
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
            }
            return result;
        } catch (Exception ex) {
            log.error("Error processing email template [{}]: {}", templatePath, ex.getMessage());
            return "";
        }
    }

    private String buildOtpHtmlTemplate(String email, String otpCode, long minutes) {
        Map<String, String> variables = Map.of(
                "otpCode", otpCode != null ? otpCode : "",
                "minutes", String.valueOf(minutes),
                "email", email != null ? email : ""
        );
        String rendered = loadTemplate("templates/email/otp-verification.html", variables);
        if (!rendered.isBlank()) {
            return rendered;
        }
        return "<p>Your NextTurn verification code is: <strong>" + otpCode + "</strong> (valid for " + minutes + " minutes).</p>";
    }

    private String buildTenantAdminActivationHtmlTemplate(String email, String tenantName, String activationLink) {
        String facility = (tenantName != null && !tenantName.isBlank()) ? tenantName : "Cơ sở của bạn";
        String link = (activationLink != null && !activationLink.isBlank()) ? activationLink : "#";
        Map<String, String> variables = Map.of(
                "email", email != null ? email : "",
                "tenantName", facility,
                "activationLink", link
        );
        String rendered = loadTemplate("templates/email/tenant-activation.html", variables);
        if (!rendered.isBlank()) {
            return rendered;
        }
        return "<p>Xin chào " + email + ", cơ sở " + facility + " đã được tạo. Vui lòng kích hoạt tài khoản tại: <a href=\"" + link + "\">" + link + "</a></p>";
    }

    private String buildTenantRegistrationReceivedHtmlTemplate(String email, String tenantName, String tenantCode) {
        Map<String, String> variables = Map.of(
                "email", email != null ? email : "",
                "tenantName", tenantName != null ? tenantName : "",
                "tenantCode", tenantCode != null ? tenantCode : ""
        );
        String rendered = loadTemplate("templates/email/tenant-registration-received.html", variables);
        if (!rendered.isBlank()) {
            return rendered;
        }
        return "<p>Xin chào " + email + ", hồ sơ đăng ký tổ chức " + tenantName + " (" + tenantCode + ") đã được tiếp nhận và đang chờ phê duyệt.</p>";
    }

    private String buildTenantRegistrationInternalHtmlTemplate(String tenantName, String tenantCode, String adminEmail) {
        Map<String, String> variables = Map.of(
                "tenantName", tenantName != null ? tenantName : "",
                "tenantCode", tenantCode != null ? tenantCode : "",
                "adminEmail", adminEmail != null ? adminEmail : "",
                "submittedAt", java.time.LocalDateTime.now().toString()
        );
        String rendered = loadTemplate("templates/email/tenant-registration-internal.html", variables);
        if (!rendered.isBlank()) {
            return rendered;
        }
        return "<p>Yêu cầu đăng ký tổ chức mới: " + tenantName + " (" + tenantCode + ") bởi " + adminEmail + "</p>";
    }

    private String buildTenantRegistrationApprovedHtmlTemplate(String email, String tenantName, String tenantCode, String activationLink) {
        String link = (activationLink != null && !activationLink.isBlank()) ? activationLink : "#";
        Map<String, String> variables = Map.of(
                "email", email != null ? email : "",
                "tenantName", tenantName != null ? tenantName : "",
                "tenantCode", tenantCode != null ? tenantCode : "",
                "activationLink", link
        );
        String rendered = loadTemplate("templates/email/tenant-registration-approved.html", variables);
        if (!rendered.isBlank()) {
            return rendered;
        }
        return "<p>Chúc mừng! Tổ chức " + tenantName + " đã được phê duyệt. Đăng nhập tại: <a href=\"" + link + "\">" + link + "</a></p>";
    }

    private String buildTenantRegistrationRejectedHtmlTemplate(String email, String tenantName, String tenantCode, String rejectionReason) {
        Map<String, String> variables = Map.of(
                "email", email != null ? email : "",
                "tenantName", tenantName != null ? tenantName : "",
                "tenantCode", tenantCode != null ? tenantCode : "",
                "rejectionReason", rejectionReason != null ? rejectionReason : ""
        );
        String rendered = loadTemplate("templates/email/tenant-registration-rejected.html", variables);
        if (!rendered.isBlank()) {
            return rendered;
        }
        return "<p>Hồ sơ đăng ký tổ chức " + tenantName + " chưa đủ điều kiện. Lý do: " + rejectionReason + "</p>";
    }
}
