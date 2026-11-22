package com.nextturn.service.checkin;

import com.nextturn.dto.checkin.CreateReceptionPointRequest;
import com.nextturn.dto.checkin.ReceptionPointResponse;
import com.nextturn.dto.checkin.ReceptionQrResponse;
import com.nextturn.dto.checkin.UpdateReceptionPointStatusRequest;

import java.util.List;

/**
 * Service contract for managing facility reception points and generating static/dynamic QR codes (REQ-CHECK-IN-03).
 */
public interface TenantReceptionPointService {

    ReceptionPointResponse createReceptionPoint(CreateReceptionPointRequest request);

    List<ReceptionPointResponse> getAllReceptionPoints();

    ReceptionQrResponse getReceptionQr(String receptionPointId);

    ReceptionQrResponse rotateStaticQr(String receptionPointId);

    ReceptionPointResponse updateStatus(String receptionPointId, UpdateReceptionPointStatusRequest request);
}
