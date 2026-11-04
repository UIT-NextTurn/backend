package com.nextturn.service.notification;

import java.util.concurrent.CompletableFuture;

/**
 * Service contract orchestrating email dispatch via SMTP.
 * <p>
 * Supports branded HTML emails for OTP authentication and customer notifications.
 */
public interface EmailService {

    /**
     * Determines whether SMTP sending is fully configured with active credentials.
     *
     * @return true if JavaMailSender and SMTP host/username are available
     */
    boolean isConfigured();

    /**
     * Asynchronously sends an OTP verification code email to the specified recipient.
     *
     * @param toEmail    recipient's email address
     * @param otpCode    the 6-digit verification code
     * @param ttlSeconds validity duration in seconds
     * @return CompletableFuture containing true if successfully dispatched via SMTP, false if fallback/simulated
     */
    CompletableFuture<Boolean> sendOtpEmail(String toEmail, String otpCode, long ttlSeconds);

    /**
     * Asynchronously dispatches a general HTML formatted email on virtual threads.
     *
     * @param toEmail     recipient email
     * @param subject     email subject
     * @param htmlContent HTML payload
     * @return CompletableFuture completing when dispatch concludes
     */
    CompletableFuture<Void> sendHtmlEmailAsync(String toEmail, String subject, String htmlContent);

    /**
     * Asynchronously sends an activation and password setup email to a newly provisioned tenant administrator.
     *
     * @param toEmail        recipient admin email
     * @param tenantName     tenant organization name
     * @param activationLink activation and password configuration URL
     * @return CompletableFuture containing dispatch outcome
     */
    CompletableFuture<Boolean> sendTenantAdminActivationEmail(String toEmail, String tenantName, String activationLink);

    /**
     * Asynchronously sends an automated activation and password setup email to a newly created staff member.
     *
     * @param toEmail        recipient staff email
     * @param fullName       staff full name
     * @param tenantName     tenant organization name
     * @param activationLink password setup / activation URL
     * @return CompletableFuture containing dispatch outcome
     */
    CompletableFuture<Boolean> sendStaffActivationEmail(String toEmail, String fullName, String tenantName, String activationLink);

    /**
     * Sends an email confirming receipt of a tenant registration application.
     *
     * @param toEmail    applicant email address
     * @param tenantName organization name
     * @param tenantCode requested tenant code
     * @return future resolving to true if sent, false otherwise
     */
    CompletableFuture<Boolean> sendTenantRegistrationReceivedEmail(String toEmail, String tenantName, String tenantCode);

    /**
     * Sends internal notification to platform Superadministrator regarding a new tenant registration request.
     *
     * @param superadminEmail superadministrator notification email
     * @param tenantName      organization name
     * @param tenantCode      requested tenant code
     * @param adminEmail      applicant email address
     * @return future resolving to true if sent, false otherwise
     */
    CompletableFuture<Boolean> sendTenantRegistrationInternalNotification(
            String superadminEmail,
            String tenantName,
            String tenantCode,
            String adminEmail
    );

    /**
     * Sends congratulatory email with login guide when a registration application is approved.
     *
     * @param toEmail        approved admin email address
     * @param tenantName     organization name
     * @param tenantCode     tenant code
     * @param activationLink portal login URL
     * @return future resolving to true if sent, false otherwise
     */
    CompletableFuture<Boolean> sendTenantRegistrationApprovedEmail(
            String toEmail,
            String tenantName,
            String tenantCode,
            String activationLink
    );

    /**
     * Sends denial notification email with mandatory rejection reason.
     *
     * @param toEmail         applicant email address
     * @param tenantName      organization name
     * @param tenantCode      tenant code
     * @param rejectionReason mandatory reason for rejection
     * @return future resolving to true if sent, false otherwise
     */
    CompletableFuture<Boolean> sendTenantRegistrationRejectedEmail(
            String toEmail,
            String tenantName,
            String tenantCode,
            String rejectionReason
    );

    /**
     * Dispatches a general HTML formatted email synchronously.
     *
     * @param toEmail     recipient email
     * @param subject     email subject
     * @param htmlContent HTML payload
     * @throws Exception if sending fails
     */
    void sendHtmlEmail(String toEmail, String subject, String htmlContent) throws Exception;
}
