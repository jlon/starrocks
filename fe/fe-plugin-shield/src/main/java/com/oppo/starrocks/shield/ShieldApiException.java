package com.oppo.starrocks.shield;

import java.io.IOException;
import java.net.SocketTimeoutException;

public class ShieldApiException extends RuntimeException {
    private final boolean retryable;

    public ShieldApiException(String message) {
        this(message, null, false);
    }

    public ShieldApiException(String message, Throwable cause) {
        this(message, cause, cause instanceof IOException);
    }

    ShieldApiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isTimeout() {
        for (Throwable current = getCause(); current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    public String toUserMessage() {
        if (isTimeout()) {
            return "神盾请求超时，请稍后重试或联系管理员";
        }
        Throwable cause = getCause();
        if (cause instanceof IOException) {
            return "神盾接口请求失败: " + cause.getMessage();
        }
        return "神盾接口不可用: " + getMessage();
    }
}
