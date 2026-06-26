package com.oppo.starrocks.shield;

public class ShieldApiException extends RuntimeException {
    public ShieldApiException(String message) {
        super(message);
    }

    public ShieldApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
