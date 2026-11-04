package com.nextturn.service.service;

import com.nextturn.repository.service.TenantServiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceCodeGeneratorTest {

    @Mock
    private TenantServiceRepository serviceRepository;

    @InjectMocks
    private TenantServiceCodeGenerator codeGenerator;

    @Test
    @DisplayName("Should generate SVC-0001 when catalog has no existing services")
    void shouldGenerateInitialCode_whenNoServicesExist() {
        when(serviceRepository.findMaxServiceCode()).thenReturn(Optional.empty());
        when(serviceRepository.existsByServiceCodeAndIsDeletedFalse("SVC-0001")).thenReturn(false);

        String code = codeGenerator.generateNextServiceCode();

        assertThat(code).isEqualTo("SVC-0001");
    }

    @Test
    @DisplayName("Should increment code from highest existing service code")
    void shouldIncrementSequentialCode() {
        when(serviceRepository.findMaxServiceCode()).thenReturn(Optional.of("SVC-0042"));
        when(serviceRepository.existsByServiceCodeAndIsDeletedFalse("SVC-0043")).thenReturn(false);

        String code = codeGenerator.generateNextServiceCode();

        assertThat(code).isEqualTo("SVC-0043");
    }

    @Test
    @DisplayName("Should skip collisions when next sequential code is already taken")
    void shouldSkipCollisions() {
        when(serviceRepository.findMaxServiceCode()).thenReturn(Optional.of("SVC-0010"));
        when(serviceRepository.existsByServiceCodeAndIsDeletedFalse("SVC-0011")).thenReturn(true);
        when(serviceRepository.existsByServiceCodeAndIsDeletedFalse("SVC-0012")).thenReturn(false);

        String code = codeGenerator.generateNextServiceCode();

        assertThat(code).isEqualTo("SVC-0012");
    }

    @Test
    @DisplayName("Should fallback to total count when max code has unparseable sequence")
    void shouldFallbackToCount_whenMaxCodeMalformed() {
        when(serviceRepository.findMaxServiceCode()).thenReturn(Optional.of("SVC-MALFORMED"));
        when(serviceRepository.countByIsDeletedFalse()).thenReturn(5L);
        when(serviceRepository.existsByServiceCodeAndIsDeletedFalse("SVC-0006")).thenReturn(false);

        String code = codeGenerator.generateNextServiceCode();

        assertThat(code).isEqualTo("SVC-0006");
    }
}

