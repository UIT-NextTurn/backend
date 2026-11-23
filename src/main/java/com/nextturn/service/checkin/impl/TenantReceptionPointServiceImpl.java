package com.nextturn.service.checkin.impl;

import com.nextturn.dto.checkin.CreateReceptionPointRequest;
import com.nextturn.dto.checkin.ReceptionPointResponse;
import com.nextturn.dto.checkin.ReceptionQrResponse;
import com.nextturn.dto.checkin.UpdateReceptionPointStatusRequest;
import com.nextturn.entity.TenantReceptionPoint;
import com.nextturn.entity.TenantService;
import com.nextturn.enums.ReceptionPointStatus;
import com.nextturn.enums.ReceptionQrMode;
import com.nextturn.exception.checkin.CheckInErrorCode;
import com.nextturn.exception.checkin.CheckInException;
import com.nextturn.exception.service.ServiceErrorCode;
import com.nextturn.exception.service.ServiceException;
import com.nextturn.repository.checkin.TenantReceptionPointRepository;
import com.nextturn.repository.service.TenantServiceRepository;
import com.nextturn.service.BaseService;
import com.nextturn.service.checkin.TenantReceptionPointService;
import com.nextturn.service.checkin.TenantReceptionQrTokenService;
import com.nextturn.infrastructure.logging.LogContract;
import com.nextturn.infrastructure.logging.LogDomain;
import com.nextturn.infrastructure.logging.LogOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Default implementation of {@link TenantReceptionPointService} managing lobby/counter reception points
 * and static/dynamic QR payload generation (REQ-CHECK-IN-03).
 */
@Service
public class TenantReceptionPointServiceImpl extends BaseService implements TenantReceptionPointService {

    private static final Logger log = LoggerFactory.getLogger(TenantReceptionPointServiceImpl.class);

    private final TenantReceptionPointRepository receptionPointRepository;
    private final TenantServiceRepository serviceRepository;
    private final TenantReceptionQrTokenService qrTokenService;

    @Autowired
    public TenantReceptionPointServiceImpl(
            TenantReceptionPointRepository receptionPointRepository,
            TenantServiceRepository serviceRepository,
            TenantReceptionQrTokenService qrTokenService,
            @Autowired(required = false) TransactionTemplate transactionTemplate
    ) {
        super(transactionTemplate);
        this.receptionPointRepository = receptionPointRepository;
        this.serviceRepository = serviceRepository;
        this.qrTokenService = qrTokenService;
    }

    @Override
    @Transactional
    public ReceptionPointResponse createReceptionPoint(CreateReceptionPointRequest request) {
        String tenantCode = resolveCurrentTenantCode();
        String pointCode = request.getPointCode().trim().toUpperCase();

        if (receptionPointRepository.existsByPointCodeIgnoreCaseAndIsDeletedFalse(pointCode)) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_POINT_CODE_EXISTS);
        }

        TenantService service = resolveOptionalService(request.getServiceId());
        ReceptionQrMode qrMode = request.getQrMode() != null ? request.getQrMode() : ReceptionQrMode.DYNAMIC;
        int rotationSeconds = (request.getRotationSeconds() != null && request.getRotationSeconds() >= 15)
                ? request.getRotationSeconds()
                : 60;

        String staticToken = qrMode == ReceptionQrMode.STATIC
                ? qrTokenService.buildStaticToken(tenantCode)
                : null;

        TenantReceptionPoint point = TenantReceptionPoint.builder()
                .id(UUID.randomUUID().toString())
                .pointCode(pointCode)
                .pointName(request.getPointName().trim())
                .locationLabel(request.getLocationLabel() != null ? request.getLocationLabel().trim() : null)
                .service(service)
                .qrMode(qrMode)
                .staticToken(staticToken)
                .qrSecret(qrTokenService.generateQrSecret())
                .rotationSeconds(rotationSeconds)
                .status(ReceptionPointStatus.ACTIVE)
                .isDeleted(false)
                .build();

        TenantReceptionPoint saved = receptionPointRepository.save(point);
        LogContract.info(log, LogDomain.CHECKIN, "reception_point_created")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("receptionPoint", saved.getPointCode())
                .field("qrMode", saved.getQrMode())
                .field("rotationSeconds", saved.getRotationSeconds())
                .emit();
        return ReceptionPointResponse.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceptionPointResponse> getAllReceptionPoints() {
        return receptionPointRepository.findAllActiveOrderByCreatedAtDesc().stream()
                .map(ReceptionPointResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReceptionQrResponse getReceptionQr(String receptionPointId) {
        String tenantCode = resolveCurrentTenantCode();
        TenantReceptionPoint point = findActivePoint(receptionPointId);
        return qrTokenService.generateQrPayload(point, tenantCode);
    }

    @Override
    @Transactional
    public ReceptionQrResponse rotateStaticQr(String receptionPointId) {
        String tenantCode = resolveCurrentTenantCode();
        TenantReceptionPoint point = findActivePoint(receptionPointId);
        if (point.getStatus() != ReceptionPointStatus.ACTIVE) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_POINT_INACTIVE);
        }

        point.setStaticToken(qrTokenService.buildStaticToken(tenantCode));
        point.setQrSecret(qrTokenService.generateQrSecret());
        TenantReceptionPoint saved = receptionPointRepository.save(point);
        LogContract.info(log, LogDomain.CHECKIN, "reception_qr_rotated")
                .outcome(LogOutcome.SUCCESS)
                .tenant(tenantCode)
                .entity("receptionPoint", saved.getPointCode())
                .emit();
        return qrTokenService.generateQrPayload(saved, tenantCode);
    }

    @Override
    @Transactional
    public ReceptionPointResponse updateStatus(String receptionPointId, UpdateReceptionPointStatusRequest request) {
        TenantReceptionPoint point = findActivePoint(receptionPointId);
        point.setStatus(request.getStatus());
        TenantReceptionPoint saved = receptionPointRepository.save(point);
        LogContract.info(log, LogDomain.CHECKIN, "reception_point_status_updated")
                .outcome(LogOutcome.SUCCESS)
                .entity("receptionPoint", saved.getPointCode())
                .field("status", saved.getStatus())
                .emit();
        return ReceptionPointResponse.fromEntity(saved);
    }

    private TenantReceptionPoint findActivePoint(String receptionPointId) {
        if (receptionPointId == null || receptionPointId.isBlank()) {
            throw new CheckInException(CheckInErrorCode.RECEPTION_POINT_NOT_FOUND);
        }
        return receptionPointRepository.findByIdWithServiceAndIsDeletedFalse(receptionPointId.trim())
                .orElseThrow(() -> new CheckInException(CheckInErrorCode.RECEPTION_POINT_NOT_FOUND));
    }

    private TenantService resolveOptionalService(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return null;
        }
        return serviceRepository.findByIdAndIsDeletedFalse(serviceId.trim())
                .orElseThrow(() -> new ServiceException(ServiceErrorCode.SERVICE_NOT_FOUND));
    }
}
