package com.nextturn.service.staff;

import com.nextturn.repository.staff.TenantStaffRepository;
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
class TenantStaffCodeGeneratorTest {

    @Mock
    private TenantStaffRepository staffRepository;

    @InjectMocks
    private TenantStaffCodeGenerator codeGenerator;

    @Test
    @DisplayName("Should generate STAFF-0001 when no staff records exist")
    void shouldGenerateInitialStaffCode() {
        when(staffRepository.findMaxStaffCode()).thenReturn(Optional.empty());
        when(staffRepository.existsByStaffCode("STAFF-0001")).thenReturn(false);

        String code = codeGenerator.generateNextStaffCode();

        assertThat(code).isEqualTo("STAFF-0001");
    }

    @Test
    @DisplayName("Should increment sequential badge code from highest existing badge")
    void shouldIncrementSequentialCode() {
        when(staffRepository.findMaxStaffCode()).thenReturn(Optional.of("STAFF-0007"));
        when(staffRepository.existsByStaffCode("STAFF-0008")).thenReturn(false);

        String code = codeGenerator.generateNextStaffCode();

        assertThat(code).isEqualTo("STAFF-0008");
    }

    @Test
    @DisplayName("Should skip collisions when next sequential badge is already taken")
    void shouldSkipCollisions() {
        when(staffRepository.findMaxStaffCode()).thenReturn(Optional.of("STAFF-0010"));
        when(staffRepository.existsByStaffCode("STAFF-0011")).thenReturn(true);
        when(staffRepository.existsByStaffCode("STAFF-0012")).thenReturn(false);

        String code = codeGenerator.generateNextStaffCode();

        assertThat(code).isEqualTo("STAFF-0012");
    }

    @Test
    @DisplayName("Should fallback to total count when max badge code has non-numeric sequence")
    void shouldFallbackToCount_whenMaxBadgeMalformed() {
        when(staffRepository.findMaxStaffCode()).thenReturn(Optional.of("STAFF-ABCD"));
        when(staffRepository.count()).thenReturn(3L);
        when(staffRepository.existsByStaffCode("STAFF-0004")).thenReturn(false);

        String code = codeGenerator.generateNextStaffCode();

        assertThat(code).isEqualTo("STAFF-0004");
    }
}

