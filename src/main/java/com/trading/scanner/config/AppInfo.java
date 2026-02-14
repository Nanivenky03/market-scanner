package com.trading.scanner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppInfo {

    @Value("${app.name}")
    private String name;

    @Value("${app.version}")
    private String version;

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }
}
