package com.pibase.pibase_api.exception;

public class ProvisioningException extends RuntimeException {
    public ProvisioningException(String message) {
        super(message);
    }

    public ProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
