package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AngelOneSessionService {

    private final AngelOneProperties properties;
    private final TotpService totpService;
    private final AngelOneApiExecutor angelOneApiExecutor;

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

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            headers.put("X-UserType", "USER");
            headers.put("X-SourceID", "WEB");
            headers.put("X-ClientLocalIP", properties.clientLocalIp());
            headers.put("X-ClientPublicIP", properties.clientPublicIp());
            headers.put("X-MACAddress", properties.macAddress());
            headers.put("X-PrivateKey", properties.apiKey());

            AngelOneAuthDtos.AngelOneLoginResponse loginResponse = angelOneApiExecutor.postJson(
                    AngelOneApiExecutor.ApiEndpoint.AUTH_LOGIN,
                    url,
                    headers,
                    requestBody,
                    AngelOneAuthDtos.AngelOneLoginResponse.class);

            if (loginResponse.status() == null || !loginResponse.status()) {
                String errorMessage = "Angel One login failed. message=" + loginResponse.message()
                        + ", errorcode=" + loginResponse.errorcode();
                log.warn(errorMessage);
                throw new ProviderException(errorMessage);
            }

            AngelOneAuthDtos.AngelOneLoginResponse.AngelOneLoginData data = loginResponse.data();
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