package com.nextturn.dto.queue;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Specialist workload summary used for load-balanced ticket handoff and counter capacity checks (REQ-QC-01, REQ-QC-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Specialist availability and current daily workload summary")
public class SpecialistLoadResponse {

    private String staffId;
    private String staffCode;
    private String fullName;
    private Long busyServingCount;
    private Long servedCountToday;
    private Boolean available;
}
