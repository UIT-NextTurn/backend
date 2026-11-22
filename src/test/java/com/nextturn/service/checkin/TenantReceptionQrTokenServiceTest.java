package com.nextturn.service.checkin;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.checkin.ReceptionQrResponse;
import com.nextturn.entity.TenantReceptionPoint;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ReceptionPointStatus;
import com.nextturn.enums.ReceptionQrMode;
import com.nextturn.exception.checkin.CheckInErrorCode;
import com.nextturn.exception.checkin.CheckInException;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.checkin.TenantReceptionPointRepository;
import com.nextturn.service.checkin.impl.TenantReceptionQrTokenServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantReceptionQrTokenServiceTest {

    private static final String TENANT_CODE = "clinic-alpha";

    @Mock
    private TenantReceptionPointRepository receptionPointRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TenantReceptionPoint dynamicPoint;
    private TenantReceptionPoint staticPoint;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantCode(TENANT_CODE);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        TenantService service = TenantService.builder()
                .id("srv-001")
                .serviceCode("SVC-0001")
                .serviceName("Khám Nội")
                .build();

        dynamicPoint = TenantReceptionPoint.builder()
                .id("rp-dyn-1")
                .pointCode("REC-01")
                .pointName("Sảnh Chính")
                .locationLabel("Tầng 1")
                .service(service)
                .qrMode(ReceptionQrMode.DYNAMIC)
                .qrSecret("super-secret-key-for-hmac-sha256-32bytes!")
                .rotationSeconds(60)
                .status(ReceptionPointStatus.ACTIVE)
                .isDeleted(false)
                .build();

        staticPoint = TenantReceptionPoint.builder()
                .id("rp-stat-1")
                .pointCode("REC-02")
                .pointName("Quầy Poster Tầng 2")
                .qrMode(ReceptionQrMode.STATIC)
                .staticToken("NTRS.Y2xpbmljLWFscGhh.abcdef1234567890")
                .qrSecret("super-secret-static-key")
                .rotationSeconds(60)
                .status(ReceptionPointStatus.ACTIVE)
                .isDeleted(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("REQ-CHECK-IN-03: Generates 60-second rotating dynamic QR token, caches in Valkey, and validates within window")
    void dynamicQr_GenerateAndValidateWithin60sWindow_Success() {
        Instant fixedTime = Instant.ofEpochSecond(1_795_320_015L); // 15s into 60s bucket -> 45s left
        Clock fixedClock = Clock.fixed(fixedTime, ZoneOffset.UTC);
        TenantReceptionQrTokenServiceImpl tokenService =
                new TenantReceptionQrTokenServiceImpl(receptionPointRepository, redisTemplate, fixedClock);

        ReceptionQrResponse qr = tokenService.generateQrPayload(dynamicPoint, TENANT_CODE);

        assertThat(qr.getQrMode()).isEqualTo(ReceptionQrMode.DYNAMIC);
        assertThat(qr.getQrToken()).startsWith("NTRD.");
        assertThat(qr.getRotationSeconds()).isEqualTo(60);
        assertThat(qr.getRemainingSeconds()).isEqualTo(45L);
        assertThat(qr.getCheckInUrl()).contains("/check-in?tenantCode=clinic-alpha&token=" + qr.getQrToken());
        assertThat(tokenService.extractTenantCodeFromToken(qr.getQrToken())).isEqualTo(TENANT_CODE);

        long expectedBucket = 1_795_320_015L / 60L;
        verify(valueOperations).set(
                eq(ValkeyKeyConstant.receptionQrKey(TENANT_CODE, "rp-dyn-1", expectedBucket)),
                eq(qr.getQrToken()),
                eq(Duration.ofSeconds(45))
        );

        when(receptionPointRepository.findByIdWithServiceAndIsDeletedFalse("rp-dyn-1"))
                .thenReturn(Optional.of(dynamicPoint));

        TenantReceptionPoint resolved = tokenService.validateAndResolveToken(qr.getQrToken());
        assertThat(resolved.getId()).isEqualTo("rp-dyn-1");
    }

    @Test
    @DisplayName("REQ-CHECK-IN-03: Rejects dynamic QR token after 60-second window expires with CHECKIN_005")
    void dynamicQr_ExpiredAfter60Seconds_ThrowsExpired() {
        Instant t0 = Instant.ofEpochSecond(1_795_320_015L);
        TenantReceptionQrTokenServiceImpl generatorAtT0 =
                new TenantReceptionQrTokenServiceImpl(receptionPointRepository, redisTemplate, Clock.fixed(t0, ZoneOffset.UTC));
        String oldToken = generatorAtT0.generateQrPayload(dynamicPoint, TENANT_CODE).getQrToken();

        Instant t61 = t0.plusSeconds(60); // next 60s bucket
        TenantReceptionQrTokenServiceImpl validatorAtT61 =
                new TenantReceptionQrTokenServiceImpl(receptionPointRepository, redisTemplate, Clock.fixed(t61, ZoneOffset.UTC));

        when(receptionPointRepository.findByIdWithServiceAndIsDeletedFalse("rp-dyn-1"))
                .thenReturn(Optional.of(dynamicPoint));

        assertThatThrownBy(() -> validatorAtT61.validateAndResolveToken(oldToken))
                .isInstanceOf(CheckInException.class)
                .hasFieldOrPropertyWithValue("code", CheckInErrorCode.RECEPTION_QR_EXPIRED.code());
    }

    @Test
    @DisplayName("REQ-CHECK-IN-03: Rejects tampered dynamic QR HMAC signature with CHECKIN_004")
    void dynamicQr_TamperedSignature_ThrowsInvalid() {
        Instant t0 = Instant.ofEpochSecond(1_795_320_015L);
        TenantReceptionQrTokenServiceImpl tokenService =
                new TenantReceptionQrTokenServiceImpl(receptionPointRepository, redisTemplate, Clock.fixed(t0, ZoneOffset.UTC));

        String validToken = tokenService.generateQrPayload(dynamicPoint, TENANT_CODE).getQrToken();
        String tampered = validToken.substring(0, validToken.length() - 4) + "XXXX";

        when(receptionPointRepository.findByIdWithServiceAndIsDeletedFalse("rp-dyn-1"))
                .thenReturn(Optional.of(dynamicPoint));

        assertThatThrownBy(() -> tokenService.validateAndResolveToken(tampered))
                .isInstanceOf(CheckInException.class)
                .hasFieldOrPropertyWithValue("code", CheckInErrorCode.RECEPTION_QR_INVALID.code());
    }

    @Test
    @DisplayName("REQ-CHECK-IN-03: Validates static QR token and rejects INACTIVE reception point")
    void staticQr_ValidateAndRejectInactive() {
        TenantReceptionQrTokenServiceImpl tokenService =
                new TenantReceptionQrTokenServiceImpl(receptionPointRepository, null);

        when(receptionPointRepository.findByStaticTokenAndIsDeletedFalse(staticPoint.getStaticToken()))
                .thenReturn(Optional.of(staticPoint));

        TenantReceptionPoint activeResolved = tokenService.validateAndResolveToken(staticPoint.getStaticToken());
        assertThat(activeResolved.getPointCode()).isEqualTo("REC-02");

        staticPoint.setStatus(ReceptionPointStatus.INACTIVE);
        assertThatThrownBy(() -> tokenService.validateAndResolveToken(staticPoint.getStaticToken()))
                .isInstanceOf(CheckInException.class)
                .hasFieldOrPropertyWithValue("code", CheckInErrorCode.RECEPTION_POINT_INACTIVE.code());
    }
}
