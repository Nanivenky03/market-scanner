package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.dto.AngelOneLoginRequest;
import com.trading.scanner.service.provider.angelone.dto.AngelOneLoginResponse;
import com.trading.scanner.service.provider.angelone.dto.AngelOneSessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class AngelOneSessionService {

    private final AngelOneProperties properties;
    private final ObjectMapper objectMapper;
    private final TotpService totpService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public AngelOneSessionInfo createSession() {
        validateBaseConfig();

        String generatedTotp = totpService.generateCurrentTotp(properties.totpSecret());
        return createSessionWithTotp(generatedTotp);
    }

    public AngelOneSessionInfo createSession(String manualTotp) {
        validateBaseConfig();

        String totpToUse = (manualTotp != null && !manualTotp.isBlank())
                ? manualTotp.trim()
                : totpService.generateCurrentTotp(properties.totpSecret());

        return createSessionWithTotp(totpToUse);
    }

    private AngelOneSessionInfo createSessionWithTotp(String totp) {
        if (totp == null || totp.isBlank()) {
            throw new ProviderException("TOTP is required");
        }

        try {
            String url = properties.baseUrl() + "/rest/auth/angelbroking/user/v1/loginByPassword";

            AngelOneLoginRequest requestBody = new AngelOneLoginRequest(
                    properties.clientId() != null ? properties.clientId().trim() : null,
                    properties.password() != null ? properties.password().trim() : null,
                    totp.trim()
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-UserType", "USER")
                    .header("X-SourceID", "WEB")
                    .header("X-ClientLocalIP", properties.clientLocalIp())
                    .header("X-ClientPublicIP", properties.clientPublicIp())
                    .header("X-MACAddress", properties.macAddress())
                    .header("X-PrivateKey", properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            AngelOneLoginResponse loginResponse =
                    objectMapper.readValue(response.body(), AngelOneLoginResponse.class);

            if (loginResponse.status() == null || !loginResponse.status()) {
                String errorMessage = "Angel One login failed. message=" + loginResponse.message()
                        + ", errorcode=" + loginResponse.errorcode();
                log.warn(errorMessage);
                return new AngelOneSessionInfo(
                        false,
                        errorMessage,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null
                );
            }

            AngelOneLoginResponse.AngelOneLoginData data = loginResponse.data();

            return new AngelOneSessionInfo(
                    true,
                    loginResponse.message(),
                    data != null && data.jwtToken() != null && !data.jwtToken().isBlank(),
                    data != null && data.refreshToken() != null && !data.refreshToken().isBlank(),
                    data != null && data.feedToken() != null && !data.feedToken().isBlank(),
                    preview(data != null ? data.jwtToken() : null),
                    preview(data != null ? data.refreshToken() : null),
                    preview(data != null ? data.feedToken() : null)
            );

        } catch (Exception ex) {
            throw new ProviderException("Failed to create Angel One session", ex);
        }
    }

    private void validateBaseConfig() {
        if (!properties.enabled()) {
            throw new ProviderException("Angel One provider is disabled. Set ANGELONE_ENABLED=true");
        }
        if (isBlank(properties.apiKey())) {
            throw new ProviderException("ANGELONE_API_KEY is missing");
        }
        if (isBlank(properties.clientId())) {
            throw new ProviderException("ANGELONE_CLIENT_ID is missing");
        }
        if (isBlank(properties.password())) {
            throw new ProviderException("ANGELONE_PASSWORD is missing");
        }
        if (isBlank(properties.baseUrl())) {
            throw new ProviderException("ANGELONE_BASE_URL is missing");
        }
        if (isBlank(properties.clientLocalIp())) {
            throw new ProviderException("ANGELONE_CLIENT_LOCAL_IP is missing");
        }
        if (isBlank(properties.clientPublicIp())) {
            throw new ProviderException("ANGELONE_CLIENT_PUBLIC_IP is missing");
        }
        if (isBlank(properties.macAddress())) {
            throw new ProviderException("ANGELONE_MAC_ADDRESS is missing");
        }
        if (isBlank(properties.totpSecret())) {
            throw new ProviderException("ANGELONE_TOTP_SECRET is missing");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String preview(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() <= 12) {
            return token;
        }
        return token.substring(0, 6) + "..." + token.substring(token.length() - 6);
    }
}