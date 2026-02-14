package com.trading.scanner.service.provider;

public class DataProviderException extends Exception {
    public DataProviderException(String message) {
        super(message);
    }

    public DataProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
