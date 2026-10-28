package com.nextturn.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.nextturn.exception.BaseErrorCode;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Backward-compatible subclass of {@link BaseResponse}.
 *
 * @param <T> the data payload type
 */
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"timestamp", "status", "success", "errorCode", "message", "data", "pagination"})
public class ApiResponse<T> extends BaseResponse<T> {

    public static BaseResponse<Void> ok(String message) {
        return BaseResponse.ok(message);
    }

    public static <T> BaseResponse<T> ok(T data) {
        return BaseResponse.ok(data);
    }

    public static <T> BaseResponse<T> ok(String message, T data) {
        return BaseResponse.ok(message, data);
    }

    public static <T> BaseResponse<List<T>> okPaged(Page<T> page) {
        return BaseResponse.okPaged(page);
    }

    public static <T> BaseResponse<List<T>> okPaged(String message, Page<T> page) {
        return BaseResponse.okPaged(message, page);
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
