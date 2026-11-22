package com.nextturn.service.calendar;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.calendar.SlotHoldingResponse;
import com.nextturn.entity.TenantService;
import com.nextturn.entity.TenantTimeSlot;
import com.nextturn.enums.TimeSlotStatus;
import com.nextturn.exception.calendar.CalendarErrorCode;
import com.nextturn.exception.calendar.CalendarException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.calendar.TenantTimeSlotRepository;
import com.nextturn.service.calendar.impl.TenantTimeSlotHoldingServiceImpl;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantTimeSlotHoldingServiceTest {

    private static final String TENANT_CODE = "tenant-alpha";

    @Mock
    private TenantTimeSlotRepository timeSlotRepository;

    @Mock
    private TenantTimeSlotGeneratorService slotGeneratorService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TenantTimeSlotHoldingServiceImpl holdingService;

    private TenantService service;
    private TenantTimeSlot availableSlot;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);

        service = TenantService.builder()
                .id("srv-1")
                .serviceCode("SRV-01")
                .serviceName("Khám tư vấn")
                .build();

        availableSlot = TenantTimeSlot.builder()
                .id("slot-1")
                .service(service)
                .workDate(LocalDate.now().plusDays(1))
                .slotStart(LocalTime.of(9, 0))
                .slotEnd(LocalTime.of(9, 30))
                .capacityPerSlot(1)
                .bookedCount(0)
                .status(TimeSlotStatus.AVAILABLE)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("holdSlot: successfully acquires distributed lock and updates status to HOLDING")
    void holdSlot_Success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(ValkeyKeyConstant.slotLockKey(TENANT_CODE, "slot-1")), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(timeSlotRepository.findByIdForUpdate("slot-1")).thenReturn(Optional.of(availableSlot));
        when(timeSlotRepository.save(any(TenantTimeSlot.class))).thenAnswer(i -> i.getArgument(0));

        SlotHoldingResponse response = holdingService.holdSlot("slot-1", 10);

        assertThat(response).isNotNull();
        assertThat(response.getSlotId()).isEqualTo("slot-1");
        assertThat(response.getStatus()).isEqualTo(TimeSlotStatus.HOLDING);
        assertThat(response.getHoldingToken()).isNotBlank();
        assertThat(response.getHoldingExpiresAt()).isAfter(LocalDateTime.now());

        assertThat(availableSlot.getStatus()).isEqualTo(TimeSlotStatus.HOLDING);
        assertThat(availableSlot.getHoldingToken()).isEqualTo(response.getHoldingToken());
        verify(slotGeneratorService).syncAvailableSlotsToValkey(eq("srv-1"), any(LocalDate.class));

        ArgumentCaptor<String> acquiredOwnerCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(
                eq(ValkeyKeyConstant.slotLockKey(TENANT_CODE, "slot-1")),
                acquiredOwnerCaptor.capture(),
                any(Duration.class)
        );
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(ValkeyKeyConstant.slotLockKey(TENANT_CODE, "slot-1"))),
                eq(acquiredOwnerCaptor.getValue())
        );
    }

    @Test
    @DisplayName("holdSlot: throws 409 conflict with Vietnamese error message when distributed lock fails")
    void holdSlot_DistributedLockConflict_ThrowsException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> holdingService.holdSlot("slot-1", 10))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED.code())
                .hasMessage("Khung giờ này vừa có người khác nhanh tay chọn trước. Vui lòng chọn một khung giờ khác.");

        verify(timeSlotRepository, never()).findByIdForUpdate(any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
    }

    @Test
    @DisplayName("holdSlot: throws 409 conflict when slot is actively held by another customer")
    void holdSlot_ActivelyHeld_ThrowsException() {
        availableSlot.setStatus(TimeSlotStatus.HOLDING);
        availableSlot.setHoldingToken("active-token");
        availableSlot.setHoldingExpiresAt(LocalDateTime.now().plusMinutes(5));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        when(timeSlotRepository.findByIdForUpdate("slot-1")).thenReturn(Optional.of(availableSlot));

        assertThatThrownBy(() -> holdingService.holdSlot("slot-1", 10))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.SLOT_ALREADY_HOLDING_OR_BOOKED.code())
                .hasMessage("Khung giờ này vừa có người khác nhanh tay chọn trước. Vui lòng chọn một khung giờ khác.");

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(ValkeyKeyConstant.slotLockKey(TENANT_CODE, "slot-1"))),
                anyString()
        );
    }

    @Test
    @DisplayName("holdSlot: reclaims slot when existing holding lease is expired")
    void holdSlot_ExpiredHolding_ReclaimsSuccessfully() {
        availableSlot.setStatus(TimeSlotStatus.HOLDING);
        availableSlot.setHoldingToken("old-token");
        availableSlot.setHoldingExpiresAt(LocalDateTime.now().minusMinutes(2));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        when(timeSlotRepository.findByIdForUpdate("slot-1")).thenReturn(Optional.of(availableSlot));
        when(timeSlotRepository.save(any(TenantTimeSlot.class))).thenAnswer(i -> i.getArgument(0));

        SlotHoldingResponse response = holdingService.holdSlot("slot-1", 10);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(TimeSlotStatus.HOLDING);
        assertThat(response.getHoldingToken()).isNotEqualTo("old-token");
        assertThat(availableSlot.getHoldingToken()).isEqualTo(response.getHoldingToken());
    }

    @Test
    @DisplayName("releaseHeldSlot: releases held slot back to AVAILABLE when token matches")
    void releaseHeldSlot_Success() {
        availableSlot.setStatus(TimeSlotStatus.HOLDING);
        availableSlot.setHoldingToken("valid-token");
        availableSlot.setHoldingExpiresAt(LocalDateTime.now().plusMinutes(5));

        when(timeSlotRepository.findByIdForUpdate("slot-1")).thenReturn(Optional.of(availableSlot));
        when(timeSlotRepository.save(any(TenantTimeSlot.class))).thenAnswer(i -> i.getArgument(0));

        holdingService.releaseHeldSlot("slot-1", "valid-token");

        assertThat(availableSlot.getStatus()).isEqualTo(TimeSlotStatus.AVAILABLE);
        assertThat(availableSlot.getHoldingToken()).isNull();
        assertThat(availableSlot.getHoldingExpiresAt()).isNull();
        verify(slotGeneratorService).syncAvailableSlotsToValkey(eq("srv-1"), any(LocalDate.class));
    }

    @Test
    @DisplayName("releaseHeldSlot: throws HOLDING_TOKEN_INVALID when token does not match")
    void releaseHeldSlot_TokenMismatch_ThrowsException() {
        availableSlot.setStatus(TimeSlotStatus.HOLDING);
        availableSlot.setHoldingToken("real-token");

        when(timeSlotRepository.findByIdForUpdate("slot-1")).thenReturn(Optional.of(availableSlot));

        assertThatThrownBy(() -> holdingService.releaseHeldSlot("slot-1", "wrong-token"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.HOLDING_TOKEN_INVALID.code());
    }

    @Test
    @DisplayName("confirmSlot: transitions to BOOKED when capacity reached and clears holding token")
    void confirmSlot_Success_TransitionsToBooked() {
        availableSlot.setStatus(TimeSlotStatus.HOLDING);
        availableSlot.setHoldingToken("token-xyz");
        availableSlot.setHoldingExpiresAt(LocalDateTime.now().plusMinutes(5));
        availableSlot.setCapacityPerSlot(1);
        availableSlot.setBookedCount(0);

        when(timeSlotRepository.findByIdForUpdate("slot-1")).thenReturn(Optional.of(availableSlot));
        when(timeSlotRepository.save(any(TenantTimeSlot.class))).thenAnswer(i -> i.getArgument(0));

        holdingService.confirmSlot("slot-1", "token-xyz");

        assertThat(availableSlot.getStatus()).isEqualTo(TimeSlotStatus.BOOKED);
        assertThat(availableSlot.getBookedCount()).isEqualTo(1);
        assertThat(availableSlot.getHoldingToken()).isNull();
        assertThat(availableSlot.getHoldingExpiresAt()).isNull();
        verify(slotGeneratorService).syncAvailableSlotsToValkey(eq("srv-1"), any(LocalDate.class));
    }

    @Test
    @DisplayName("confirmSlot: throws HOLDING_TOKEN_EXPIRED when holding lease expired before confirmation")
    void confirmSlot_ExpiredToken_ThrowsException() {
        availableSlot.setStatus(TimeSlotStatus.HOLDING);
        availableSlot.setHoldingToken("token-xyz");
        availableSlot.setHoldingExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(timeSlotRepository.findByIdForUpdate("slot-1")).thenReturn(Optional.of(availableSlot));

        assertThatThrownBy(() -> holdingService.confirmSlot("slot-1", "token-xyz"))
                .isInstanceOf(CalendarException.class)
                .hasFieldOrPropertyWithValue("code", CalendarErrorCode.HOLDING_TOKEN_EXPIRED.code());
    }

    @Test
    @DisplayName("releaseExpiredHoldingSlots: resets expired slots and synchronizes Valkey once per service-date")
    void releaseExpiredHoldingSlots_Success() {
        LocalDate workDate = LocalDate.now().plusDays(1);
        TenantTimeSlot expiredSlot1 = TenantTimeSlot.builder()
                .id("slot-exp-1")
                .service(service)
                .workDate(workDate)
                .status(TimeSlotStatus.HOLDING)
                .holdingToken("expired-token-1")
                .holdingExpiresAt(LocalDateTime.now().minusMinutes(5))
                .build();
        TenantTimeSlot expiredSlot2 = TenantTimeSlot.builder()
                .id("slot-exp-2")
                .service(service)
                .workDate(workDate)
                .status(TimeSlotStatus.HOLDING)
                .holdingToken("expired-token-2")
                .holdingExpiresAt(LocalDateTime.now().minusMinutes(3))
                .build();

        when(timeSlotRepository.findExpiredHoldingSlots(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(expiredSlot1, expiredSlot2));

        int released = holdingService.releaseExpiredHoldingSlots();

        assertThat(released).isEqualTo(2);
        assertThat(expiredSlot1.getStatus()).isEqualTo(TimeSlotStatus.AVAILABLE);
        assertThat(expiredSlot1.getHoldingToken()).isNull();
        assertThat(expiredSlot1.getHoldingExpiresAt()).isNull();
        assertThat(expiredSlot2.getStatus()).isEqualTo(TimeSlotStatus.AVAILABLE);
        verify(timeSlotRepository).saveAll(List.of(expiredSlot1, expiredSlot2));
        verify(slotGeneratorService, times(1)).syncAvailableSlotsToValkey(eq("srv-1"), eq(workDate));
    }

    @Test
    @DisplayName("releaseExpiredHoldingSlots: processes full batches in loop until partial batch")
    void releaseExpiredHoldingSlots_MultipleBatches_LoopsUntilPartialBatch() {
        LocalDate workDate = LocalDate.now().plusDays(1);
        List<TenantTimeSlot> firstBatch = IntStream.range(0, 100)
                .mapToObj(i -> TenantTimeSlot.builder()
                        .id("slot-batch1-" + i)
                        .service(service)
                        .workDate(workDate)
                        .status(TimeSlotStatus.HOLDING)
                        .holdingToken("token-" + i)
                        .holdingExpiresAt(LocalDateTime.now().minusMinutes(5))
                        .build())
                .toList();
        TenantTimeSlot secondBatchSlot = TenantTimeSlot.builder()
                .id("slot-batch2-0")
                .service(service)
                .workDate(workDate.plusDays(1))
                .status(TimeSlotStatus.HOLDING)
                .holdingToken("token-last")
                .holdingExpiresAt(LocalDateTime.now().minusMinutes(2))
                .build();

        when(timeSlotRepository.findExpiredHoldingSlots(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(firstBatch)
                .thenReturn(List.of(secondBatchSlot));

        int released = holdingService.releaseExpiredHoldingSlots();

        assertThat(released).isEqualTo(101);
        verify(timeSlotRepository, times(2)).findExpiredHoldingSlots(any(LocalDateTime.class), any(Pageable.class));
        verify(slotGeneratorService).syncAvailableSlotsToValkey("srv-1", workDate);
        verify(slotGeneratorService).syncAvailableSlotsToValkey("srv-1", workDate.plusDays(1));
    }

    @Test
    @DisplayName("findExpiredHoldingSlots repository query configures PESSIMISTIC_WRITE with SKIP LOCKED timeout hint (-2)")
    void findExpiredHoldingSlots_ConfiguresSkipLocked() throws NoSuchMethodException {
        Method method = TenantTimeSlotRepository.class.getMethod(
                "findExpiredHoldingSlots", LocalDateTime.class, Pageable.class);

        Lock lock = method.getAnnotation(Lock.class);
        QueryHints queryHints = method.getAnnotation(QueryHints.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(queryHints).isNotNull();
        assertThat(queryHints.value())
                .extracting(QueryHint::name, QueryHint::value)
                .contains(org.assertj.core.groups.Tuple.tuple("jakarta.persistence.lock.timeout", "-2"));
    }
}

