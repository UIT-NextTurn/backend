package com.nextturn.service.ticket;

import com.nextturn.entity.TenantService;
import com.nextturn.exception.ticket.TicketErrorCode;
import com.nextturn.exception.ticket.TicketException;
import com.nextturn.repository.ticket.TenantQueueTicketRepository;
import com.nextturn.service.ticket.TenantTicketSequenceGenerator.GeneratedTicketNumber;
import com.nextturn.service.ticket.impl.TenantTicketSequenceGeneratorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantTicketSequenceGeneratorTest {

    @Mock
    private TenantQueueTicketRepository ticketRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TenantTicketSequenceGeneratorImpl generator;
    private TenantService serviceA;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        generator = new TenantTicketSequenceGeneratorImpl(ticketRepository, redisTemplate);
        serviceA = TenantService.builder()
                .id("srv-001")
                .serviceCode("SVC-0001")
                .serviceName("Khám Nội")
                .build();
    }

    @Test
    @DisplayName("formatTicketCode: formats 3 zero-padded digits for 1..999 and expands cleanly for >= 1000 (A-001 -> A-1000)")
    void formatTicketCode_DynamicDigits() {
        assertThat(generator.formatTicketCode("A", 1)).isEqualTo("A-001");
        assertThat(generator.formatTicketCode("A", 15)).isEqualTo("A-015");
        assertThat(generator.formatTicketCode("A", 999)).isEqualTo("A-999");
        assertThat(generator.formatTicketCode("A", 1000)).isEqualTo("A-1000");
        assertThat(generator.formatTicketCode("B", 1234)).isEqualTo("B-1234");
    }

    @Test
    @DisplayName("resolveServicePrefix: derives alphabetical prefix from SVC-xxxx numeric suffix")
    void resolveServicePrefix_DerivesLettersCorrectly() {
        assertThat(generator.resolveServicePrefix(TenantService.builder().serviceCode("SVC-0001").build())).isEqualTo("A");
        assertThat(generator.resolveServicePrefix(TenantService.builder().serviceCode("SVC-0002").build())).isEqualTo("B");
        assertThat(generator.resolveServicePrefix(TenantService.builder().serviceCode("SVC-0026").build())).isEqualTo("Z");
        assertThat(generator.resolveServicePrefix(TenantService.builder().serviceCode("SVC-0027").build())).isEqualTo("AA");
    }

    @Test
    @DisplayName("nextTicketNumber: executes Valkey Lua atomic INCR script seeded with MySQL max sequence")
    void nextTicketNumber_ExecutesLuaScriptAtomically() {
        LocalDate today = LocalDate.now();
        when(ticketRepository.findMaxSequenceNumberByServiceAndWorkDate("srv-001", today)).thenReturn(999);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("999"), eq("93600")))
                .thenReturn(1000L);

        GeneratedTicketNumber generated = generator.nextTicketNumber("clinic-alpha", serviceA, today);

        assertThat(generated.sequenceNumber()).isEqualTo(1000);
        assertThat(generated.ticketCode()).isEqualTo("A-1000");
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("nextturn:ticket:seq:clinic-alpha:srv-001:" + today)),
                eq("999"),
                eq("93600")
        );
    }

    @Test
    @DisplayName("nextTicketNumber: falls back to MySQL MAX+1 cleanly when Valkey is offline")
    void nextTicketNumber_FallbackToDatabaseWhenValkeyOffline() {
        LocalDate today = LocalDate.now();
        TenantTicketSequenceGeneratorImpl noRedisGenerator = new TenantTicketSequenceGeneratorImpl(ticketRepository, null);
        when(ticketRepository.findMaxSequenceNumberByServiceAndWorkDate("srv-001", today)).thenReturn(14);

        GeneratedTicketNumber generated = noRedisGenerator.nextTicketNumber("clinic-alpha", serviceA, today);

        assertThat(generated.sequenceNumber()).isEqualTo(15);
        assertThat(generated.ticketCode()).isEqualTo("A-015");
    }

    @Test
    @DisplayName("acquireTicketLock and releaseTicketLock: acquires SET NX lock and releases safely via Lua script")
    void lockAndUnlock_Success() {
        LocalDate today = LocalDate.now();
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        String token = generator.acquireTicketLock("clinic-alpha", "srv-001", today);
        assertThat(token).isNotBlank();

        generator.releaseTicketLock("clinic-alpha", "srv-001", today, token);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("nextturn:lock:ticket:clinic-alpha:srv-001:" + today)),
                eq(token)
        );
    }

    @Test
    @DisplayName("acquireTicketLock: throws TICKET_LOCK_CONFLICT when distributed lock cannot be acquired")
    void acquireTicketLock_ConflictThrowsException() {
        LocalDate today = LocalDate.now();
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> generator.acquireTicketLock("clinic-alpha", "srv-001", today))
                .isInstanceOf(TicketException.class)
                .hasFieldOrPropertyWithValue("code", TicketErrorCode.TICKET_LOCK_CONFLICT.code());
    }
}
