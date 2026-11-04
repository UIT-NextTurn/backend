package com.nextturn.service.calendar;

import com.nextturn.dto.calendar.TimeSlotResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantShift;
import com.nextturn.entity.TenantStaff;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.ShiftStatus;
import com.nextturn.enums.StaffStatus;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.calendar.TenantShiftRepository;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.calendar.impl.TenantTimeSlotGeneratorServiceImpl;
import com.nextturn.service.tenant.TenantCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantTimeSlotGeneratorServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantTimeSlotRepository timeSlotRepository;

    @Mock
    private TenantShiftRepository shiftRepository;

    @Mock
    private TenantCacheService tenantCacheService;

    @InjectMocks
    private TenantTimeSlotGeneratorServiceImpl generatorService;

    private TenantService service;
    private TenantStaff staff;
    private TenantShift shift;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);

        service = TenantService.builder()
                .id("srv-1")
                .serviceCode("SRV-001")
                .serviceName("Dịch vụ tư vấn")
                .standardDuration(30)
                .build();

        staff = TenantStaff.builder()
                .id("staff-1")
                .staffCode("STAFF-001")
                .fullName("Trần B")
                .status(StaffStatus.ACTIVE)
                .build();

        shift = TenantShift.builder()
                .id("shift-1")
                .staff(staff)
                .service(service)
                .workDate(LocalDate.now().plusDays(1))
                .shiftStart(LocalTime.of(8, 0))
                .shiftEnd(LocalTime.of(10, 0))
                .status(ShiftStatus.SCHEDULED)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("generateTimeSlotsForShift: decomposes 2-hour shift with 30m standard duration into 4 sequential slots")
    void generateTimeSlotsForShift_DecomposesCorrectly() {
        LocalDate workDate = shift.getWorkDate();

        when(shiftRepository.countActiveStaffCoveringSlot(eq("srv-1"), eq(workDate), any(LocalTime.class), any(LocalTime.class)))
                .thenReturn(1);

        when(timeSlotRepository.findByServiceIdAndWorkDateAndSlotStartAndSlotEnd(eq("srv-1"), eq(workDate), any(LocalTime.class), any(LocalTime.class)))
                .thenReturn(Optional.empty());

        when(timeSlotRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        TenantTimeSlot slot1 = TenantTimeSlot.builder().id("s1").service(service).workDate(workDate).slotStart(LocalTime.of(8, 0)).slotEnd(LocalTime.of(8, 30)).capacityPerSlot(1).status(TimeSlotStatus.AVAILABLE).build();
        when(timeSlotRepository.findByServiceIdAndWorkDateAndStatusOrderBySlotStartAsc("srv-1", workDate, TimeSlotStatus.AVAILABLE))
                .thenReturn(List.of(slot1));

        List<TimeSlotResponse> result = generatorService.generateTimeSlotsForShift(shift);

        assertThat(result).hasSize(4);
        assertThat(result.get(0).getSlotStart()).isEqualTo(LocalTime.of(8, 0));
        assertThat(result.get(0).getSlotEnd()).isEqualTo(LocalTime.of(8, 30));
        assertThat(result.get(0).getStatus()).isEqualTo(TimeSlotStatus.AVAILABLE);

        assertThat(result.get(3).getSlotStart()).isEqualTo(LocalTime.of(9, 30));
        assertThat(result.get(3).getSlotEnd()).isEqualTo(LocalTime.of(10, 0));

        verify(timeSlotRepository).saveAll(anyList());
        verify(tenantCacheService).cacheTenantSlots(eq(TENANT_CODE), eq("srv-1"), eq(workDate), anyList());
    }

    @Test
    @DisplayName("generateTimeSlotsForShift: updates existing slot capacity when multiple shifts overlap")
    void generateTimeSlotsForShift_UpdatesCapacityForExistingSlots() {
        LocalDate workDate = shift.getWorkDate();

        when(shiftRepository.countActiveStaffCoveringSlot(eq("srv-1"), eq(workDate), any(LocalTime.class), any(LocalTime.class)))
                .thenReturn(2);

        TenantTimeSlot existingSlot = TenantTimeSlot.builder()
                .id("exist-slot-1")
                .service(service)
                .workDate(workDate)
                .slotStart(LocalTime.of(8, 0))
                .slotEnd(LocalTime.of(8, 30))
                .capacityPerSlot(1)
                .bookedCount(1)
                .status(TimeSlotStatus.BOOKED)
                .build();

        when(timeSlotRepository.findByServiceIdAndWorkDateAndSlotStartAndSlotEnd("srv-1", workDate, LocalTime.of(8, 0), LocalTime.of(8, 30)))
                .thenReturn(Optional.of(existingSlot));

        when(timeSlotRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<TimeSlotResponse> result = generatorService.generateTimeSlotsForShift(shift);

        assertThat(result).isNotEmpty();
        assertThat(existingSlot.getCapacityPerSlot()).isEqualTo(2);
        assertThat(existingSlot.getStatus()).isEqualTo(TimeSlotStatus.AVAILABLE);
    }

    @Test
    @DisplayName("recalculateSlotsForCancelledShift: deletes slots when remaining capacity is 0 and bookedCount is 0")
    void recalculateSlotsForCancelledShift_DeletesEmptySlots() {
        LocalDate workDate = shift.getWorkDate();

        TenantTimeSlot slot = TenantTimeSlot.builder()
                .id("slot-to-delete")
                .service(service)
                .workDate(workDate)
                .slotStart(LocalTime.of(8, 0))
                .slotEnd(LocalTime.of(8, 30))
                .capacityPerSlot(1)
                .bookedCount(0)
                .build();

        when(timeSlotRepository.findByServiceIdAndWorkDateAndSlotStartAndSlotEnd("srv-1", workDate, LocalTime.of(8, 0), LocalTime.of(8, 30)))
                .thenReturn(Optional.of(slot));

        when(shiftRepository.countActiveStaffCoveringSlot("srv-1", workDate, LocalTime.of(8, 0), LocalTime.of(8, 30)))
                .thenReturn(0);

        generatorService.recalculateSlotsForCancelledShift(shift);

        verify(timeSlotRepository).deleteAll(anyList());
        verify(tenantCacheService).cacheTenantSlots(eq(TENANT_CODE), eq("srv-1"), eq(workDate), anyList());
    }
}
