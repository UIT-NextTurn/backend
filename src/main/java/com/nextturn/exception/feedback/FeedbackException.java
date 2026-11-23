package com.nextturn.exception.feedback;

import com.nextturn.exception.AppException;

/**
 * Domain exception thrown during feedback token validation or CSAT submission.
 */
public class FeedbackException extends AppException {

    public FeedbackException(FeedbackErrorCode errorCode) {
        super(errorCode);
    }

    public FeedbackException(FeedbackErrorCode errorCode, String customMessage) {
        super(errorCode, customMessage);
    }
}
