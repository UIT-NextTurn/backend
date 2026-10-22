package com.nextturn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nextturn.exception.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
@JsonPropertyOrder({"timestamp", "status", "success", "errorCode", "message", "data"})
public class BaseResponse<T> {

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    private int status;
    private boolean success;
    private String errorCode;
    private String message;
    private T data;

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
