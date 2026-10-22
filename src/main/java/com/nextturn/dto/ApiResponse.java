package com.nextturn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nextturn.exception.BaseErrorCode;
import lombok.NoArgsConstructor;

/**
 * Backward-compatible subclass of {@link BaseResponse}.
 *
 * @param <T> the data payload type
 */
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"timestamp", "status", "success", "errorCode", "message", "data"})
public class ApiResponse<T> extends BaseResponse<T> {

    public static <T> BaseResponse<T> ok(T data) {
        return BaseResponse.ok(data);
    }

    public static <T> BaseResponse<T> ok(String message, T data) {
        return BaseResponse.ok(message, data);
    }

    public static <T> BaseResponse<T> error(int status, String message) {
        return BaseResponse.error(status, message);
    }

    public static <T> BaseResponse<T> error(int status, String message, String errorCode) {
        return BaseResponse.error(status, message, errorCode);
    }

    public static <T> BaseResponse<T> error(BaseErrorCode errorCode) {
        return BaseResponse.error(errorCode);
    }

    public static <T> BaseResponse<T> error(BaseErrorCode errorCode, String customMessage) {
        return BaseResponse.error(errorCode, customMessage);
    }
}
