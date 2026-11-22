package com.nextturn.exception.checkin;

import com.nextturn.exception.BaseErrorCode;
import com.nextturn.exception.BaseException;

/**
 * Domain exception thrown during Check-in and Reception Point QR operations.
 */
public class CheckInException extends BaseException {

    public CheckInException(BaseErrorCode errorCode) {
        super(errorCode);
    }

    public CheckInException(BaseErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
