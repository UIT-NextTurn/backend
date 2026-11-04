package com.nextturn.service.staff;

import com.nextturn.constant.StaffConstant;
import com.nextturn.repository.staff.TenantStaffRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Generates unique sequential badge identification codes (STAFF-xxxx) for tenant staff.
 */
@Component
public class TenantStaffCodeGenerator {

    private final TenantStaffRepository staffRepository;

    public TenantStaffCodeGenerator(TenantStaffRepository staffRepository) {
        this.staffRepository = staffRepository;
    }

    /**
     * Generates sequential staff code in format STAFF-xxxx (e.g. STAFF-0001, STAFF-0002).
     *
     * @return unique sequential badge code
     */
    public String generateNextStaffCode() {
        Optional<String> maxCodeOpt = staffRepository.findMaxStaffCode();
        int nextSequence = 1;

        if (maxCodeOpt.isPresent() && maxCodeOpt.get() != null) {
            String maxCode = maxCodeOpt.get().trim();
            if (maxCode.startsWith(StaffConstant.STAFF_CODE_PREFIX)) {
                String numPart = maxCode.substring(StaffConstant.STAFF_CODE_PREFIX.length());
                try {
                    nextSequence = Integer.parseInt(numPart) + 1;
                } catch (NumberFormatException ignored) {
                    nextSequence = (int) staffRepository.count() + 1;
                }
            }
        }

        for (int i = 0; i < 20; i++) {
            String candidate = String.format("%s%04d", StaffConstant.STAFF_CODE_PREFIX, nextSequence + i);
            if (!staffRepository.existsByStaffCode(candidate)) {
                return candidate;
            }
        }

        return StaffConstant.STAFF_CODE_PREFIX + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}

