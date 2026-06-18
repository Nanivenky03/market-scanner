package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos.AngelOneLoginResponse.AngelOneLoginData;

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

    public AngelOneAuthDtos.AngelOneSessionInfo createSession() {
        AngelOneAuthDtos.AngelOneSessionTokens tokens = createSessionTokens();
        return toSessionInfo(tokens, "SUCCESS");
    }

    public AngelOneAuthDtos.AngelOneSessionInfo createSession(String manualTotp) {
        AngelOneAuthDtos.AngelOneSessionTokens tokens = createSessionTokens(manualTotp);
        return toSessionInfo(tokens, "SUCCESS");
    }

    public AngelOneAuthDtos.AngelOneSessionTokens createSessionTokens() {
        validateBaseConfig();

        String generatedTotp = totpService.generateCurrentTotp(properties.totpSecret());
        return createSessionTokensInternal(generatedTotp);
    }

    public AngelOneAuthDtos.AngelOneSessionTokens createSessionTokens(String manualTotp) {
        validateBaseConfig();

        String totpToUse = (manualTotp != null && !manualTotp.isBlank())
                ? manualTotp.trim()
                : totpService.generateCurrentTotp(properties.totpSecret());

        return createSessionTokensInternal(totpToUse);
    }

    private AngelOneAuthDtos.AngelOneSessionTokens createSessionTokensInternal(String totp) {
        if (totp == null || totp.isBlank()) {
            throw new ProviderException("TOTP is required");
        }

        try {
            String url = properties.baseUrl() + "/rest/auth/angelbroking/user/v1/loginByPassword";

            AngelOneAuthDtos.AngelOneLoginRequest requestBody = new AngelOneAuthDtos.AngelOneLoginRequest(
                    properties.clientId() != null ? properties.clientId().trim() : null,
                    properties.password() != null ? properties.password().trim() : null,
                    totp.trim());

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

            AngelOneAuthDtos.AngelOneLoginResponse loginResponse = objectMapper.readValue(response.body(),
                    AngelOneAuthDtos.AngelOneLoginResponse.class);

            if (loginResponse.status() == null || !loginResponse.status()) {
                String errorMessage = "Angel One login failed. message=" + loginResponse.message()
                        + ", errorcode=" + loginResponse.errorcode();
                log.warn(errorMessage);
                throw new ProviderException(errorMessage);
            }

            AngelOneLoginData data = loginResponse.data();
            if (data == null || isBlank(data.jwtToken()) || isBlank(data.refreshToken()) || isBlank(data.feedToken())) {
                throw new ProviderException("Angel One login succeeded but tokens were missing in the response");
            }

            return new AngelOneAuthDtos.AngelOneSessionTokens(
                    data.jwtToken(),
                    data.refreshToken(),
                    data.feedToken());

        } catch (ProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderException("Failed to create Angel One session", ex);
        }
    }

    private AngelOneAuthDtos.AngelOneSessionInfo toSessionInfo(AngelOneAuthDtos.AngelOneSessionTokens tokens,
            String message) {
        return new AngelOneAuthDtos.AngelOneSessionInfo(
                true,
                message,
                !isBlank(tokens.jwtToken()),
                !isBlank(tokens.refreshToken()),
                !isBlank(tokens.feedToken()),
                preview(tokens.jwtToken()),
                preview(tokens.refreshToken()),
                preview(tokens.feedToken()));
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