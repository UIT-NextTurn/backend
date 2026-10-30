package com.nextturn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

/**
 * Standard uniform pagination metadata for NextTurn APIs.
 * Sits at the root level of {@link BaseResponse} alongside {@code data}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaginationMeta {

    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;

    /**
     * Builds a {@link PaginationMeta} instance from a Spring Data {@link Page}.
     *
     * @param page the source Spring Data page
     * @return populated {@link PaginationMeta}, or {@code null} if page is null
     */
    public static PaginationMeta fromPage(Page<?> page) {
        if (page == null) {
            return null;
        }
        return PaginationMeta.builder()
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }
}
