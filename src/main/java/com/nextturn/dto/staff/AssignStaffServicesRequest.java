package com.nextturn.dto.staff;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Payload for assigning a list of services to an encounter staff member.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload for assigning services to a staff member")
public class AssignStaffServicesRequest {

    @NotEmpty(message = "Danh sách dịch vụ phân công không được để trống")
    @Schema(description = "List of service UUIDs that this staff member is authorized to handle", example = "[\"svc-uuid-1\", \"svc-uuid-2\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> serviceIds;
}

