package com.nextturn.service.staff.impl;

import com.nextturn.exception.staff.StaffErrorCode;
import com.nextturn.exception.staff.StaffException;
import com.nextturn.repository.staff.TenantStaffRepository;
import com.nextturn.service.staff.TenantStaffGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default implementation of {@link TenantStaffGuardService} enforcing relational integrity
 * and operational guards for staff status deactivation and physical deletion.
 */
@Service
public class TenantStaffGuardServiceImpl implements TenantStaffGuardService {

    private static final Logger log = LoggerFactory.getLogger(TenantStaffGuardServiceImpl.class);

    private final TenantStaffRepository staffRepository;

    public TenantStaffGuardServiceImpl(TenantStaffRepository staffRepository) {
        this.staffRepository = staffRepository;
    }

    @Override
    public void validateNoActiveCommitmentsForDeactivation(String staffId, String staffCode, String fullName, String tenantCode, String action) {
        List<String> affectedAppointments = staffRepository.findUpcomingAppointmentCodesByStaffId(staffId);
        long upcomingShifts = staffRepository.countUpcomingShiftsByStaffId(staffId);

        if (affectedAppointments != null && !affectedAppointments.isEmpty()) {
            log.warn("Rejected {} of staff [{}] ({}) in tenant [{}]. Found {} affected upcoming appointments: {}",
                    action, staffCode, fullName, tenantCode, affectedAppointments.size(), affectedAppointments);
            String warningMessage = String.format(
                    "Nhân viên hiện đang có %d cuộc hẹn trong tương lai chưa hoàn tất (%s). Vui lòng chuyển giao các cuộc hẹn này sang nhân viên khác trước khi %s.",
                    affectedAppointments.size(),
                    String.join(", ", affectedAppointments),
                    action
            );
            throw new StaffException(StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE, warningMessage);
        }

        if (upcomingShifts > 0) {
            log.warn("Rejected {} of staff [{}] ({}) in tenant [{}]. Found {} scheduled future shifts.",
                    action, staffCode, fullName, tenantCode, upcomingShifts);
            String warningMessage = String.format(
                    "Nhân viên hiện đang có %d ca trực trong tương lai chưa hoàn tất. Vui lòng chuyển giao hoặc hủy ca trực trước khi %s.",
                    upcomingShifts,
                    action
            );
            throw new StaffException(StaffErrorCode.STAFF_CANNOT_DEACTIVATE_ACTIVE_SCHEDULE, warningMessage);
        }
    }

    @Override
    public void validateSafeForHardDelete(String staffId, String staffCode, String fullName, String tenantCode) {
        long ticketHistory = staffRepository.countHistoricalQueueTicketsByStaffId(staffId);
        long feedbackHistory = staffRepository.countHistoricalFeedbacksByStaffId(staffId);
        long shiftHistory = staffRepository.countHistoricalShiftsByStaffId(staffId);

        if (ticketHistory > 0 || feedbackHistory > 0 || shiftHistory > 0) {
            log.warn("Rejected hard delete of staff [{}] ({}) in tenant [{}]. Found {} ticket interactions, {} feedbacks, and {} completed shifts.",
                    staffCode, fullName, tenantCode, ticketHistory, feedbackHistory, shiftHistory);
            throw new StaffException(
                    StaffErrorCode.STAFF_CANNOT_HARD_DELETE_HISTORICAL_DATA,
                    "Hệ thống tuyệt đối từ chối thao tác Xóa vật lý (Hard delete) bản ghi nhân sự nếu nhân viên đó đã từng có lịch sử gọi vé, tiếp nhận phục vụ hoặc phản hồi đánh giá trong cơ sở dữ liệu."
            );
        }
    }
}

