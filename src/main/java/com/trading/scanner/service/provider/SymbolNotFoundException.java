package com.trading.scanner.service.provider;

import com.trading.scanner.model.Exchange;

public class SymbolNotFoundException extends DataProviderException {

    public SymbolNotFoundException(String symbol, Exchange exchange) {
        super(String.format("Symbol '%s' on exchange '%s' not found or returned invalid data from provider.", symbol, exchange));
    }

    public SymbolNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
