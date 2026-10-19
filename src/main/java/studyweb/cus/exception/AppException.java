package studyweb.cus.exception;

import lombok.Getter;

/**
 * Base custom runtime exception for NextTurn application errors.
 */
@Getter
public class AppException extends RuntimeException {

    private final int status;

    public AppException(String message) {
        super(message);
        this.status = 400;
    }

    public AppException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AppException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
