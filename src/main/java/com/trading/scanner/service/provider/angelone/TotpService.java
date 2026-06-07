package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.service.provider.ProviderException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Locale;

@Service
public class TotpService {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int OTP_DIGITS = 6;
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public String generateCurrentTotp(String base32Secret) {
        return generateTotp(base32Secret, Instant.now());
    }

    public String generateTotp(String base32Secret, Instant instant) {
        if (base32Secret == null || base32Secret.isBlank()) {
            throw new ProviderException("ANGELONE_TOTP_SECRET is missing");
        }

        try {
            byte[] secret = decodeBase32(base32Secret);
            long counter = instant.getEpochSecond() / TIME_STEP_SECONDS;

            byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] hmac = mac.doFinal(counterBytes);

            int offset = hmac[hmac.length - 1] & 0x0F;
            int binary =
                    ((hmac[offset] & 0x7F) << 24) |
                    ((hmac[offset + 1] & 0xFF) << 16) |
                    ((hmac[offset + 2] & 0xFF) << 8) |
                    (hmac[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, OTP_DIGITS);
            return String.format("%0" + OTP_DIGITS + "d", otp);

        } catch (ProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderException("Failed to generate TOTP", ex);
        }
    }

    private byte[] decodeBase32(String base32) {
        String normalized = base32
                .replace(" ", "")
                .replace("=", "")
                .toUpperCase(Locale.ROOT)
                .trim();

        if (normalized.isEmpty()) {
            throw new ProviderException("ANGELONE_TOTP_SECRET is blank");
        }

        int buffer = 0;
        int bitsLeft = 0;
        byte[] output = new byte[(normalized.length() * 5) / 8];
        int index = 0;

        for (char c : normalized.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new ProviderException("Invalid Base32 character in ANGELONE_TOTP_SECRET: " + c);
            }

            buffer <<= 5;
            buffer |= value & 0x1F;
            bitsLeft += 5;

            if (bitsLeft >= 8) {
                output[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }

        if (index == output.length) {
            return output;
        }

        byte[] trimmed = new byte[index];
        System.arraycopy(output, 0, trimmed, 0, index);
        return trimmed;
    }
}