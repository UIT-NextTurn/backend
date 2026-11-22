package com.nextturn.service.checkin;

import com.nextturn.dto.checkin.AppointmentCheckInRequest;
import com.nextturn.dto.checkin.ResolveReceptionQrResponse;
import com.nextturn.dto.checkin.WalkInCheckInRequest;
import com.nextturn.dto.ticket.TicketResponse;

/**
 * Customer-facing Check-in orchestrator covering lobby QR resolution (REQ-CHECK-IN-03),
 * pre-booked appointment QR check-in (REQ-CHECK-IN-01), and walk-in ticket intake (REQ-CHECK-IN-02).
 */
public interface TenantCheckInService {

    ResolveReceptionQrResponse resolveReceptionQr(String receptionToken);

    TicketResponse checkInAppointment(AppointmentCheckInRequest request);

    TicketResponse checkInWalkIn(WalkInCheckInRequest request);
}
