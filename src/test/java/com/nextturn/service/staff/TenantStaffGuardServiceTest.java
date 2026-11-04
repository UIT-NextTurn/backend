package com.nextturn.service.staff;

import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.service.staff.impl.TenantStaffGuardServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantStaffGuardServiceTest {

    private static final String STAFF_ID = "staff-uuid-1";
    private static final String STAFF_CODE = "STAFF-0001";
    private static final String FULL_NAME = "Nguyễn Văn A";
    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantStaffRepository staffRepository;

    @InjectMocks
    private TenantStaffGuardServiceImpl guardService;

    @Test
    @DisplayName("Should pass deactivation check when staff has no upcoming appointments and no shifts")
    void validateNoActiveCommitments_shouldPassWhenClear() {
        when(staffRepository.findUpcomingAppointmentCodesByStaffId(STAFF_ID)).thenReturn(Collections.emptyList());
        when(staffRepository.countUpcomingShiftsByStaffId(STAFF_ID)).thenReturn(0L);

        assertThatCode(() -> guardService.validateNoActiveCommitmentsForDeactivation(
                STAFF_ID, STAFF_CODE, FULL_NAME, TENANT_CODE, "vô hiệu hóa"
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw 409 Conflict when staff has incomplete future appointments")
    void validateNoActiveCommitments_shouldThrowConflict_whenUpcomingAppointmentsExist() {
        when(staffRepository.findUpcomingAppointmentCodesByStaffId(STAFF_ID))
                .thenReturn(List.of("APT-1001", "APT-1002"));

        assertThatThrownBy(() -> guardService.validateNoActiveCommitmentsForDeactivation(
                STAFF_ID, STAFF_CODE, FULL_NAME, TENANT_CODE, "vô hiệu hóa"
        ))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE.code());
                    assertThat(sex.getMessage()).contains("APT-1001", "APT-1002", "chuyển giao");
                });
    }

    @Test
    @DisplayName("Should throw 409 Conflict when staff has scheduled future shifts")
    void validateNoActiveCommitments_shouldThrowConflict_whenUpcomingShiftsExist() {
        when(staffRepository.findUpcomingAppointmentCodesByStaffId(STAFF_ID)).thenReturn(Collections.emptyList());
        when(staffRepository.countUpcomingShiftsByStaffId(STAFF_ID)).thenReturn(3L);

        assertThatThrownBy(() -> guardService.validateNoActiveCommitmentsForDeactivation(
                STAFF_ID, STAFF_CODE, FULL_NAME, TENANT_CODE, "vô hiệu hóa"
        ))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE.code());
                    assertThat(sex.getMessage()).contains("3 ca trực trong tương lai");
                });
    }

    @Test
    @DisplayName("Should pass hard delete check when staff has zero historical interactions")
    void validateSafeForHardDelete_shouldPassWhenNoHistory() {
        when(staffRepository.countHistoricalQueueTicketsByStaffId(STAFF_ID)).thenReturn(0L);
        when(staffRepository.countHistoricalFeedbacksByStaffId(STAFF_ID)).thenReturn(0L);
        when(staffRepository.countHistoricalShiftsByStaffId(STAFF_ID)).thenReturn(0L);

        assertThatCode(() -> guardService.validateSafeForHardDelete(
                STAFF_ID, STAFF_CODE, FULL_NAME, TENANT_CODE
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw 409 Conflict when hard delete attempted on staff with queue ticket history")
    void validateSafeForHardDelete_shouldThrow_whenTicketHistoryExists() {
        when(staffRepository.countHistoricalQueueTicketsByStaffId(STAFF_ID)).thenReturn(5L);
        when(staffRepository.countHistoricalFeedbacksByStaffId(STAFF_ID)).thenReturn(0L);
        when(staffRepository.countHistoricalShiftsByStaffId(STAFF_ID)).thenReturn(0L);

        assertThatThrownBy(() -> guardService.validateSafeForHardDelete(
                STAFF_ID, STAFF_CODE, FULL_NAME, TENANT_CODE
        ))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA.code());
                    assertThat(sex.getMessage()).contains("từ chối thao tác Xóa vật lý");
                });
    }

    @Test
    @DisplayName("Should throw 409 Conflict when hard delete attempted on staff with feedback ratings")
    void validateSafeForHardDelete_shouldThrow_whenFeedbackHistoryExists() {
        when(staffRepository.countHistoricalQueueTicketsByStaffId(STAFF_ID)).thenReturn(0L);
        when(staffRepository.countHistoricalFeedbacksByStaffId(STAFF_ID)).thenReturn(2L);
        when(staffRepository.countHistoricalShiftsByStaffId(STAFF_ID)).thenReturn(0L);

        assertThatThrownBy(() -> guardService.validateSafeForHardDelete(
                STAFF_ID, STAFF_CODE, FULL_NAME, TENANT_CODE
        ))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA.code());
                });
    }

    @Test
    @DisplayName("Should throw 409 Conflict when hard delete attempted on staff with completed shifts")
    void validateSafeForHardDelete_shouldThrow_whenShiftHistoryExists() {
        when(staffRepository.countHistoricalQueueTicketsByStaffId(STAFF_ID)).thenReturn(0L);
        when(staffRepository.countHistoricalFeedbacksByStaffId(STAFF_ID)).thenReturn(0L);
        when(staffRepository.countHistoricalShiftsByStaffId(STAFF_ID)).thenReturn(4L);

        assertThatThrownBy(() -> guardService.validateSafeForHardDelete(
                STAFF_ID, STAFF_CODE, FULL_NAME, TENANT_CODE
        ))
                .isInstanceOf(StaffException.class)
                .satisfies(ex -> {
                    StaffException sex = (StaffException) ex;
                    assertThat(sex.getCode()).isEqualTo(StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA.code());
                });
    }
}

