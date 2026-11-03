package com.nextturn.exception.service;

import com.nextturn.exception.AppException;

/**
 * Domain exception for Tenant Service catalog operations.
 */
public class ServiceException extends AppException {

    public ServiceException(ServiceErrorCode errorCode) {
        super(errorCode);
    }

    public ServiceException(ServiceErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
