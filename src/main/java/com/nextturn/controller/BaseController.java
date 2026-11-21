package com.nextturn.controller;

import com.nextturn.constant.TenantHeaderConstant;
import com.nextturn.exception.auth.AuthErrorCode;
import com.nextturn.exception.auth.AuthException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.infrastructure.security.principal.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base controller providing cross-cutting helper methods for multi-tenant context binding,
 * header-to-parameter synchronization validation, and authenticated principal resolution
 * (*lớp điều khiển cơ sở cung cấp các hàm dùng chung cho ngữ cảnh đa tổ chức và xác thực*).
 */
public abstract class BaseController {

    private static final Logger log = LoggerFactory.getLogger(BaseController.class);

    /**
     * Resolves the effective tenant code from HTTP header ({@code X-Tenant-Code}) or query parameter
     * ({@code tenantCode}) and binds it to {@link TenantContextHolder}.
     *
     * @param headerTenantCode tenant code from {@code X-Tenant-Code} request header (optional)
     * @param paramTenantCode  tenant code from query or path parameter (optional)
     * @return the effective resolved tenant code, or current context tenant code if neither was provided
     */
    protected String ensureTenantContext(String headerTenantCode, String paramTenantCode) {
        String effective = (headerTenantCode != null && !headerTenantCode.isBlank())
                ? headerTenantCode.trim()
                : (paramTenantCode != null && !paramTenantCode.isBlank() ? paramTenantCode.trim() : null);

        if (effective != null && !effective.isBlank()) {
            TenantContextHolder.setTenantCode(effective);
            return effective;
        }
        return TenantContextHolder.getTenantCode();
    }

    /**
     * Convenience overload that binds tenant context from the {@code X-Tenant-Code} request header.
     *
     * @param headerTenantCode tenant code from {@code X-Tenant-Code} request header (optional)
     * @return the effective resolved tenant code
     */
    protected String ensureTenantContext(String headerTenantCode) {
        return ensureTenantContext(headerTenantCode, null);
    }

    /**
     * Validates that if an {@code X-Tenant-Code} or {@code X-Tenant-Id} header is present,
     * it matches the target tenant code requested in the payload or URL path.
     *
     * @param targetTenantCode requested tenant code in payload or path variable
     * @param servletRequest   current HTTP servlet request
     */
    protected void validateHeaderSync(String targetTenantCode, HttpServletRequest servletRequest) {
        if (servletRequest == null || targetTenantCode == null || targetTenantCode.isBlank()) {
            return;
        }
        String headerTenant = servletRequest.getHeader(TenantHeaderConstant.X_TENANT_CODE_HEADER);
        if (headerTenant == null || headerTenant.isBlank()) {
            headerTenant = servletRequest.getHeader(TenantHeaderConstant.X_TENANT_ID_HEADER);
        }
        if (headerTenant != null && !headerTenant.isBlank()) {
            String cleanHeader = headerTenant.trim();
            if (!cleanHeader.equalsIgnoreCase(targetTenantCode.trim())) {
                log.warn("Tenant code mismatch: Header [{}] does not match requested tenantCode [{}]", cleanHeader, targetTenantCode);
                throw new AuthException(
                        AuthErrorCode.TENANT_MISMATCH,
                        "Header " + TenantHeaderConstant.X_TENANT_CODE_HEADER + " [" + cleanHeader + "] does not match requested tenantCode [" + targetTenantCode.trim() + "]"
                );
            }
        }
    }

    /**
     * Null-safe extraction of user ID from {@link AuthenticatedUser}.
     */
    protected String extractUserId(AuthenticatedUser currentUser) {
        return currentUser != null ? currentUser.userId() : null;
    }

    /**
     * Null-safe check whether the current user is a facility staff member (TENANT_ADMIN or COUNTER_STAFF).
     */
    protected boolean isStaffUser(AuthenticatedUser currentUser) {
        return currentUser != null && currentUser.isStaff();
    }
}
