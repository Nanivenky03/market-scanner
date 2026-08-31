package com.trading.scanner.controller;

import com.trading.scanner.service.runtime.BaseDataResetService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal/admin")
@RequiredArgsConstructor
public class BaseDataResetController {

    private static final String ADMIN_SECRET_HEADER = "X-Mithron-Admin-Secret";

    private final BaseDataResetService resetService;

    @Value("${runtime.admin.reset-secret:}")
    private String configuredSecret;

    @PostMapping("/reset-base")
    public ResponseEntity<?> resetBaseData(
            @RequestHeader(value = ADMIN_SECRET_HEADER, required = false) String suppliedSecret,
            HttpServletRequest request) {

        if (!isLoopback(request.getRemoteAddr())) {
            return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body("Base reset is available only from localhost");
        }

        if (configuredSecret == null
                || configuredSecret.isBlank()) {
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Base reset secret is not configured");
        }

        if (suppliedSecret == null
                || !configuredSecret.equals(suppliedSecret)) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid base reset secret");
        }

        return ResponseEntity.ok(resetService.resetBaseData());
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address)
                || "::1".equals(address);
    }
}
