package com.nextturn.service;

import com.nextturn.exception.tenant.TenantErrorCode;
import com.nextturn.exception.tenant.TenantException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaseServiceTest {

    @Mock
    private TransactionTemplate transactionTemplate;

    private static class ConcreteTestService extends BaseService {
        ConcreteTestService() {
            super();
        }

        ConcreteTestService(TransactionTemplate transactionTemplate) {
            super(transactionTemplate);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("resolveCurrentTenantCode should return normalized lowercase tenant code")
    void resolveCurrentTenantCode_shouldReturnNormalizedCode() {
        TenantContextHolder.setTenantCode("  TENANT-ALPHA  ");
        ConcreteTestService service = new ConcreteTestService();

        String code = service.resolveCurrentTenantCode();

        assertThat(code).isEqualTo("tenant-alpha");
    }

    @Test
    @DisplayName("resolveCurrentTenantCode should throw when tenant context is null or blank")
    void resolveCurrentTenantCode_shouldThrowWhenContextMissing() {
        TenantContextHolder.clear();
        ConcreteTestService service = new ConcreteTestService();

        assertThatThrownBy(service::resolveCurrentTenantCode)
                .isInstanceOf(TenantException.class)
                .satisfies(ex -> {
                    TenantException tex = (TenantException) ex;
                    assertThat(tex.getCode()).isEqualTo(TenantErrorCode.TENANT_CONTEXT_MISSING.code());
                });

        TenantContextHolder.setTenantCode("   ");
        assertThatThrownBy(service::resolveCurrentTenantCode)
                .isInstanceOf(TenantException.class);
    }

    @Test
    @DisplayName("executeInTransaction should delegate to TransactionTemplate when present")
    void executeInTransaction_shouldDelegateToTemplate() {
        ConcreteTestService service = new ConcreteTestService(transactionTemplate);
        when(transactionTemplate.execute(any())).thenReturn("success-result");

        String result = service.executeInTransaction(status -> "success-result");

        assertThat(result).isEqualTo("success-result");
        verify(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("executeInTransaction should execute callback directly when TransactionTemplate is null")
    void executeInTransaction_shouldExecuteDirectlyWhenTemplateNull() {
        ConcreteTestService service = new ConcreteTestService(null);

        String result = service.executeInTransaction(status -> "direct-result");

        assertThat(result).isEqualTo("direct-result");
    }

    @Test
    @DisplayName("executeInTransactionWithoutResult should execute consumer directly when template is null")
    void executeInTransactionWithoutResult_shouldExecuteDirectly() {
        ConcreteTestService service = new ConcreteTestService(null);
        AtomicBoolean executed = new AtomicBoolean(false);

        service.executeInTransactionWithoutResult(status -> executed.set(true));

        assertThat(executed.get()).isTrue();
    }
}

