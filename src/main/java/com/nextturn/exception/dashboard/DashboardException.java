package com.nextturn.exception.dashboard;

import com.nextturn.exception.BaseException;

/**
 * Exception raised for validation or processing errors in the Dashboard & Analytics module.
 */
public class DashboardException extends BaseException {

    public DashboardException(DashboardErrorCode errorCode) {
        super(errorCode);
    }

    public DashboardException(DashboardErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
