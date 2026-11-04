package com.nextturn.service.auth;

import com.nextturn.dto.auth.AuthTokenResponse;
import com.nextturn.dto.auth.CustomerOtpResponse;
import com.nextturn.dto.auth.CustomerSendOtpRequest;
import com.nextturn.dto.auth.CustomerVerifyOtpRequest;
import com.nextturn.dto.auth.LogoutRequest;
import com.nextturn.entity.CentralAllowedDomain;
import com.nextturn.entity.CentralUser;

import java.net.URI;
import java.util.List;

/**
 * Service contract managing passwordless authentication lifecycle for Public Customers (END_USER)
 * across standalone web applications and embedded booking/queue widgets (REQ-AUTH-06 & REQ-AUTH-07).
 */
public interface CustomerAuthService {

    void validateWidgetIntegration(
            String widgetApiKey,
            String originHeader,
            String refererHeader,
            String originDomainParam
    );

    CustomerOtpResponse sendOtp(
            CustomerSendOtpRequest request,
            String widgetKeyHeader,
            String originHeader,
            String refererHeader
    );

    CustomerOtpResponse sendOtp(CustomerSendOtpRequest request);

    AuthTokenResponse verifyOtp(CustomerVerifyOtpRequest request);

    CentralUser resolveOrCreateUser(String contact);

    void logout(LogoutRequest request);

    static String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return host.toLowerCase();
            }
        } catch (Exception ignored) {
        }

        String cleaned = url.trim().toLowerCase().replaceFirst("^[a-zA-Z]+://", "");
        int slashIdx = cleaned.indexOf('/');
        if (slashIdx >= 0) {
            cleaned = cleaned.substring(0, slashIdx);
        }
        int colonIdx = cleaned.indexOf(':');
        if (colonIdx >= 0) {
            cleaned = cleaned.substring(0, colonIdx);
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    static boolean isDomainWhitelisted(String candidateDomain, List<?> allowedDomains) {
        if (candidateDomain == null || candidateDomain.isBlank() || allowedDomains == null || allowedDomains.isEmpty()) {
            return false;
        }
        String host = candidateDomain.trim().toLowerCase();
        if (host.contains("://")) {
            host = host.substring(host.indexOf("://") + 3);
        }
        if (host.contains("/")) {
            host = host.substring(0, host.indexOf('/'));
        }
        String hostWithoutPort = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;

        for (Object entry : allowedDomains) {
            if (entry == null) {
                continue;
            }
            String domainName;
            if (entry instanceof CentralAllowedDomain cad) {
                domainName = cad.getDomainName();
            } else if (entry instanceof String str) {
                domainName = str;
            } else {
                domainName = entry.toString();
            }
            if (domainName == null || domainName.isBlank()) {
                continue;
            }
            String allowed = domainName.trim().toLowerCase();
            String allowedWithoutPort = allowed.contains(":") ? allowed.substring(0, allowed.indexOf(':')) : allowed;

            if ("*".equals(allowed)) {
                return true;
            }
            if (allowed.equals(host) || allowedWithoutPort.equals(hostWithoutPort)) {
                return true;
            }
            if (allowedWithoutPort.startsWith("*.")) {
                String baseDomain = allowedWithoutPort.substring(2);
                if (hostWithoutPort.endsWith("." + baseDomain) && hostWithoutPort.length() > baseDomain.length() + 1) {
                    return true;
                }
            }
        }
        return false;
    }
}
