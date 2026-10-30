package com.nextturn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nextturn.exception.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Standard uniform JSON response envelope for NextTurn APIs.
 *
 * @param <T> the data payload type
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"timestamp", "status", "success", "errorCode", "message", "data", "pagination"})
public class BaseResponse<T> {

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    private int status;
    private boolean success;
    private String errorCode;
    private String message;
    private T data;
    private PaginationMeta pagination;

    public static BaseResponse<Void> ok(String message) {
        return BaseResponse.<Void>builder()
                .status(200)
                .success(true)
                .message(message)
                .build();
    }

    public static <T> BaseResponse<T> ok(T data) {
        return BaseResponse.<T>builder()
                .status(200)
                .success(true)
                .message("Success")
                .data(data)
                .build();
    }

    public static <T> BaseResponse<T> ok(String message, T data) {
        return BaseResponse.<T>builder()
                .status(200)
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> BaseResponse<List<T>> okPaged(Page<T> page) {
        return BaseResponse.<List<T>>builder()
                .status(200)
                .success(true)
                .message("Success")
                .data(page != null ? page.getContent() : Collections.emptyList())
                .pagination(PaginationMeta.fromPage(page))
                .build();
    }

    public static <T> BaseResponse<List<T>> okPaged(String message, Page<T> page) {
        return BaseResponse.<List<T>>builder()
                .status(200)
                .success(true)
                .message(message)
                .data(page != null ? page.getContent() : Collections.emptyList())
                .pagination(PaginationMeta.fromPage(page))
                .build();
    }

    public static <T> BaseResponse<List<T>> okPaged(List<T> data, PaginationMeta pagination) {
        return BaseResponse.<List<T>>builder()
                .status(200)
                .success(true)
                .message("Success")
                .data(data)
                .pagination(pagination)
                .build();
    }

    public static <T> BaseResponse<List<T>> okPaged(String message, List<T> data, PaginationMeta pagination) {
        return BaseResponse.<List<T>>builder()
                .status(200)
                .success(true)
                .message(message)
                .data(data)
                .pagination(pagination)
                .build();
    }

    public static <T> BaseResponse<T> created(String message, T data) {
        return BaseResponse.<T>builder()
                .status(201)
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> BaseResponse<T> error(int status, String message) {
        return BaseResponse.<T>builder()
                .status(status)
                .success(false)
                .message(message)
                .build();
    }

    public static <T> BaseResponse<T> error(int status, String message, String errorCode) {
        return BaseResponse.<T>builder()
                .status(status)
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .build();
    }

    public static <T> BaseResponse<T> error(BaseErrorCode errorCode) {
        return BaseResponse.<T>builder()
                .status(errorCode.httpStatus().value())
                .success(false)
                .errorCode(errorCode.code())
                .message(errorCode.message())
                .build();
    }

    public static <T> BaseResponse<T> error(BaseErrorCode errorCode, String customMessage) {
        return BaseResponse.<T>builder()
                .status(errorCode.httpStatus().value())
                .success(false)
                .errorCode(errorCode.code())
                .message(customMessage)
                .build();
    }
}
