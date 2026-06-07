package com.trading.scanner.service.provider.angelone;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    @Test
    void generateTotp_shouldGenerateExpectedValueForKnownSecretAndTime() {
        // RFC 6238 test secret in Base32 for "12345678901234567890"
        String base32Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        String totp = totpService.generateTotp(base32Secret, Instant.ofEpochSecond(59));

        assertEquals("287082", totp);
    }

    @Test
    void generateCurrentTotp_shouldReturnSixDigits() {
        String base32Secret = "JBSWY3DPEHPK3PXP";

        String totp = totpService.generateCurrentTotp(base32Secret);

        assertEquals(6, totp.length());
    }

    @Test
    void generateCurrentTotp_shouldFailWhenSecretMissing() {
        assertThrows(RuntimeException.class, () -> totpService.generateCurrentTotp(""));
    }
}