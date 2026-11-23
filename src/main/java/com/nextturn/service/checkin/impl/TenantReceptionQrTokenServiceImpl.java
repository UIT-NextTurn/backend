package com.nextturn.service.checkin.impl;

import com.nextturn.constant.ValkeyKeyConstant;
import com.nextturn.dto.checkin.ReceptionQrResponse;
import com.nextturn.entity.TenantReceptionPoint;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ReceptionPointStatus;
import com.nextturn.enums.ReceptionQrMode;
import com.nextturn.exception.checkin.CheckInErrorCode;
import com.nextturn.exception.checkin.CheckInException;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import com.nextturn.infrastructure.multitenancy.TenantContextHolder;
import com.nextturn.repository.checkin.TenantReceptionPointRepository;
import com.nextturn.service.checkin.TenantReceptionQrTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Default implementation of {@link TenantReceptionQrTokenService} combining HMAC-SHA256
 * 60-second time-bucket signatures with optional Valkey TTL caching (REQ-CHECK-IN-03).
 */
@Component
public class TenantReceptionQrTokenServiceImpl implements TenantReceptionQrTokenService {

    private static final Logger log = LoggerFactory.getLogger(TenantReceptionQrTokenServiceImpl.class);
    private static final String DYNAMIC_PREFIX = "NTRD.";
    private static final String STATIC_PREFIX = "NTRS.";
    private static final int DEFAULT_ROTATION_SECONDS = 60;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TenantReceptionPointRepository receptionPointRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Autowired
    public TenantReceptionQrTokenServiceImpl(
            TenantReceptionPointRepository receptionPointRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate
    ) {
        this(receptionPointRepository, redisTemplate, Clock.systemUTC());
    }

    public TenantReceptionQrTokenServiceImpl(
            TenantReceptionPointRepository receptionPointRepository,
            StringRedisTemplate redisTemplate,
            Clock clock
    ) {
        this.receptionPointRepository = receptionPointRepository;
        this.redisTemplate = redisTemplate;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public String generateQrSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String buildStaticToken(String tenantCode) {
        String cleanTenant = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        String encodedTenant = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cleanTenant.getBytes(StandardCharsets.UTF_8));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return STATIC_PREFIX + encodedTenant + "." + suffix;
    }

    @Override
    public String extractTenantCodeFromToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String clean = rawToken.trim();
        try {
            if (clean.startsWith(DYNAMIC_PREFIX)) {
                String[] parts = clean.split("\\.");
                if (parts.length != 3) {
                    return null;
                }
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                String[] fields = payload.split("\\|");
                return fields.length >= 1 && !fields[0].isBlank() ? fields[0] : null;
            }
            if (clean.startsWith(STATIC_PREFIX)) {
                String[] parts = clean.split("\\.");
                if (parts.length != 3) {
                    return null;
                }
                String decodedTenant = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                return !decodedTenant.isBlank() ? decodedTenant : null;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    @Override
    public ReceptionQrResponse generateQrPayload(TenantReceptionPoint point, String tenantCode) {
        if (point == null) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_POINT_NOT_FOUND);
        }
        if (point.getStatus() != ReceptionPointStatus.ACTIVE || Boolean.TRUE.equals(point.getIsDeleted())) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_POINT_INACTIVE);
        }

        String cleanTenant = tenantCode != null ? tenantCode.trim().toLowerCase() : "";
        TenantService service = point.getService();

        if (point.getQrMode() == ReceptionQrMode.STATIC) {
            String staticToken = point.getStaticToken();
            if (staticToken == null || staticToken.isBlank()) {
                throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
            }
            return ReceptionQrResponse.builder()
                    .receptionPointId(point.getId())
                    .pointCode(point.getPointCode())
                    .pointName(point.getPointName())
                    .locationLabel(point.getLocationLabel())
                    .tenantCode(cleanTenant)
                    .serviceId(service != null ? service.getId() : null)
                    .serviceCode(service != null ? service.getServiceCode() : null)
                    .serviceName(service != null ? service.getServiceName() : null)
                    .qrMode(ReceptionQrMode.STATIC)
                    .qrToken(staticToken)
                    .checkInUrl(buildCheckInUrl(cleanTenant, staticToken, service))
                    .issuedAt(clock.instant())
                    .build();
        }

        int rotationSeconds = resolveRotationSeconds(point);
        long nowEpoch = clock.instant().getEpochSecond();
        long bucket = nowEpoch / rotationSeconds;
        long windowStartEpoch = bucket * rotationSeconds;
        long windowEndEpoch = (bucket + 1) * rotationSeconds;
        long remainingSeconds = Math.max(1L, windowEndEpoch - nowEpoch);

        String rawPayload = cleanTenant + "|" + point.getId() + "|" + bucket;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawPayload.getBytes(StandardCharsets.UTF_8));
        String signature = computeHmac(point.getQrSecret(), rawPayload);
        String dynamicToken = DYNAMIC_PREFIX + encodedPayload + "." + signature;

        if (redisTemplate != null) {
            try {
                String valkeyKey = ValkeyKeyConstant.receptionQrKey(cleanTenant, point.getId(), bucket);
                redisTemplate.opsForValue().set(valkeyKey, dynamicToken, Duration.ofSeconds(remainingSeconds));
            } catch (Exception ex) {
                LogContract.warn(log, LogDomain.CHECKIN, "qr_cache_write")
                        .outcome(LogOutcome.DEGRADED)
                        .tenant(cleanTenant)
                        .entity("receptionPoint", point.getId())
                        .cause(ex)
                        .emit();
            }
        }

        return ReceptionQrResponse.builder()
                .receptionPointId(point.getId())
                .pointCode(point.getPointCode())
                .pointName(point.getPointName())
                .locationLabel(point.getLocationLabel())
                .tenantCode(cleanTenant)
                .serviceId(service != null ? service.getId() : null)
                .serviceCode(service != null ? service.getServiceCode() : null)
                .serviceName(service != null ? service.getServiceName() : null)
                .qrMode(ReceptionQrMode.DYNAMIC)
                .qrToken(dynamicToken)
                .checkInUrl(buildCheckInUrl(cleanTenant, dynamicToken, service))
                .rotationSeconds(rotationSeconds)
                .issuedAt(Instant.ofEpochSecond(windowStartEpoch))
                .expiresAt(Instant.ofEpochSecond(windowEndEpoch))
                .remainingSeconds(remainingSeconds)
                .build();
    }

    @Override
    public TenantReceptionPoint validateAndResolveToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }
        String cleanToken = rawToken.trim();
        if (cleanToken.startsWith(DYNAMIC_PREFIX)) {
            return validateDynamicToken(cleanToken);
        }
        return validateStaticToken(cleanToken);
    }

    private TenantReceptionPoint validateDynamicToken(String cleanToken) {
        String[] parts = cleanToken.split("\\.");
        if (parts.length != 3) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }

        String rawPayload;
        try {
            rawPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }

        String[] fields = rawPayload.split("\\|");
        if (fields.length != 3) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }

        String tokenTenant = fields[0];
        String pointId = fields[1];
        long tokenBucket;
        try {
            tokenBucket = Long.parseLong(fields[2]);
        } catch (NumberFormatException ex) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }

        String activeTenant = TenantContextHolder.getTenantCode();
        if (activeTenant != null && !activeTenant.isBlank() && !activeTenant.trim().equalsIgnoreCase(tokenTenant)) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }

        TenantReceptionPoint point = receptionPointRepository.findByIdWithServiceAndIsDeletedFalse(pointId)
                .orElseThrow(() -> new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID));

        if (point.getStatus() != ReceptionPointStatus.ACTIVE) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_POINT_INACTIVE);
        }
        if (point.getQrMode() != ReceptionQrMode.DYNAMIC) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }

        String expectedSig = computeHmac(point.getQrSecret(), rawPayload);
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }

        int rotationSeconds = resolveRotationSeconds(point);
        long currentBucket = clock.instant().getEpochSecond() / rotationSeconds;
        if (tokenBucket > currentBucket) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID);
        }
        if (tokenBucket < currentBucket) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_QR_EXPIRED);
        }

        return point;
    }

    private TenantReceptionPoint validateStaticToken(String cleanToken) {
        TenantReceptionPoint point = receptionPointRepository.findByStaticTokenAndIsDeletedFalse(cleanToken)
                .orElseThrow(() -> new CheckInException(CheckInErrorCode.RECEPTION_QR_INVALID));
        if (point.getStatus() != ReceptionPointStatus.ACTIVE) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_POINT_INACTIVE);
        }
        return point;
    }

    private int resolveRotationSeconds(TenantReceptionPoint point) {
        return (point.getRotationSeconds() != null && point.getRotationSeconds() > 0)
                ? point.getRotationSeconds()
                : DEFAULT_ROTATION_SECONDS;
    }

    private String computeHmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 reception QR signature", ex);
        }
    }

    private String buildCheckInUrl(String tenantCode, String token, TenantService service) {
        StringBuilder sb = new StringBuilder("/check-in?tenantCode=")
                .append(tenantCode)
                .append("&token=")
                .append(token);
        if (service != null && service.getId() != null) {
            sb.append("&serviceId=").append(service.getId());
        }
        return sb.toString();
    }
}
