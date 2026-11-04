package com.nextturn.service;

import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

/**
 * Base service class providing common utilities for tenant context resolution
 * and programmatic transaction execution across multi-tenant application services.
 */
public abstract class BaseService {

    protected final TransactionTemplate transactionTemplate;

    protected BaseService() {
        this(null);
    }

    protected BaseService(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Resolves the current tenant code from {@link TenantContextHolder}.
     * Throws {@link TenantException} with {@link TenantErrorCode#TENANT_CONTEXT_MISSING}
     * if the context is uninitialized or blank.
     *
     * @return normalized lowercase tenant code
     */
    protected String resolveCurrentTenantCode() {
        String tenantCode = TenantContextHolder.getTenantCode();
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new TenantException(
                    TenantErrorCode.TENANT_CONTEXT_MISSING,
                    "Multi-tenant routing context is missing or could not be resolved from request headers/token"
            );
        }
        return tenantCode.trim().toLowerCase();
    }

    /**
     * Executes the given callback action within a database transaction if a
     * {@link TransactionTemplate} is available, or executes directly if absent (e.g. in unit tests).
     *
     * @param <T> result type
     * @param action the transaction callback
     * @return result from callback
     */
    protected <T> T executeInTransaction(TransactionCallback<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(action);
        }
        return action.doInTransaction(null);
    }

    /**
     * Executes the given action without a return value within a transaction if a
     * {@link TransactionTemplate} is available, or executes directly if absent.
     *
     * @param action consumer accepting {@link TransactionStatus}
     */
    protected void executeInTransactionWithoutResult(Consumer<TransactionStatus> action) {
        executeInTransaction(status -> {
            action.accept(status);
            return null;
        });
    }
}

