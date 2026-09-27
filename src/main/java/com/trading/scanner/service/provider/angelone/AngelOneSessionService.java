package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AngelOneSessionService {

    private final AngelOneProperties properties;
    private final TotpService totpService;
    private final AngelOneApiExecutor angelOneApiExecutor;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    private volatile AngelOneAuthDtos.AngelOneSessionTokens cachedSessionTokens;
    private volatile LocalDateTime lastLoginAt;

    public AngelOneAuthDtos.AngelOneSessionInfo createSession() {
        AngelOneAuthDtos.AngelOneSessionTokens tokens = createSessionTokens();
        return toSessionInfo(tokens, "SUCCESS");
    }

    public AngelOneAuthDtos.AngelOneSessionInfo createSession(String manualTotp) {
        AngelOneAuthDtos.AngelOneSessionTokens tokens = createSessionTokens(manualTotp);
        return toSessionInfo(tokens, "SUCCESS");
    }

    public synchronized AngelOneAuthDtos.AngelOneSessionTokens createSessionTokens() {
        validateBaseConfig();

        if (cachedSessionTokens != null) {
            return cachedSessionTokens;
        }

        return refreshSessionTokens();
    }

    public synchronized AngelOneAuthDtos.AngelOneSessionTokens createSessionTokens(String manualTotp) {
        validateBaseConfig();

        String totpToUse = (manualTotp != null && !manualTotp.isBlank())
                ? manualTotp.trim()
                : totpService.generateCurrentTotp(properties.totpSecret());

        AngelOneAuthDtos.AngelOneSessionTokens tokens = createSessionTokensInternal(totpToUse);
        cachedSessionTokens = tokens;
        lastLoginAt = timeProvider.nowDateTime();
        return tokens;
    }

    public synchronized AngelOneAuthDtos.AngelOneSessionTokens refreshSessionTokens() {
        validateBaseConfig();

        String generatedTotp = totpService.generateCurrentTotp(properties.totpSecret());
        AngelOneAuthDtos.AngelOneSessionTokens tokens = createSessionTokensInternal(generatedTotp);
        cachedSessionTokens = tokens;
        lastLoginAt = timeProvider.nowDateTime();
        return tokens;
    }

    public synchronized SessionWarmupResult warmUpSession() {
        AngelOneAuthDtos.AngelOneSessionTokens tokens = refreshSessionTokens();
        return new SessionWarmupResult(
                true,
                lastLoginAt,
                !isBlank(tokens.jwtToken()),
                !isBlank(tokens.refreshToken()),
                !isBlank(tokens.feedToken()),
                "Angel One session warmed up");
    }

    public synchronized SessionClearResult clearCachedSession() {
        boolean hadSession = cachedSessionTokens != null;
        cachedSessionTokens = null;
        lastLoginAt = null;

        return new SessionClearResult(
                hadSession,
                "Cleared cached Angel One session");
    }

    public SessionStatus sessionStatus() {
        AngelOneAuthDtos.AngelOneSessionTokens tokens = cachedSessionTokens;
        return new SessionStatus(
                tokens != null,
                lastLoginAt,
                tokens != null && !isBlank(tokens.jwtToken()),
                tokens != null && !isBlank(tokens.refreshToken()),
                tokens != null && !isBlank(tokens.feedToken()));
    }

    public AngelOneAuthDtos.SmartApiProxyResponse executeSmartApiProxy(AngelOneAuthDtos.SmartApiProxyRequest request) {
        if (request == null || isBlank(request.endpoint())) {
            return new AngelOneAuthDtos.SmartApiProxyResponse(
                    400,
                    false,
                    request != null ? request.httpMethod() : null,
                    request != null ? request.endpoint() : null,
                    null,
                    "Endpoint path is required (e.g. /rest/secure/angelbroking/market/v1/quote/)");
        }

        try {
            validateBaseConfig();
            AngelOneAuthDtos.AngelOneSessionTokens tokens = createSessionTokens();

            String rawEndpoint = request.endpoint().trim();
            String targetUrl;
            if (rawEndpoint.startsWith("http://") || rawEndpoint.startsWith("https://")) {
                targetUrl = rawEndpoint;
            } else {
                String base = properties.baseUrl() != null ? properties.baseUrl().trim()
                        : "https://apiconnect.angelone.in";
                if (base.endsWith("/") && rawEndpoint.startsWith("/")) {
                    targetUrl = base + rawEndpoint.substring(1);
                } else if (!base.endsWith("/") && !rawEndpoint.startsWith("/")) {
                    targetUrl = base + "/" + rawEndpoint;
                } else {
                    targetUrl = base + rawEndpoint;
                }
            }

            String method = (request.httpMethod() != null && !request.httpMethod().isBlank())
                    ? request.httpMethod().trim().toUpperCase(Locale.ROOT)
                    : "POST";

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            headers.put("Authorization", "Bearer " + tokens.jwtToken());
            headers.put("X-UserType", "USER");
            headers.put("X-SourceID", "WEB");
            headers.put("X-ClientLocalIP", properties.clientLocalIp());
            headers.put("X-ClientPublicIP", properties.clientPublicIp());
            headers.put("X-MACAddress", properties.macAddress());
            headers.put("X-PrivateKey", properties.apiKey());

            if (request.customHeaders() != null) {
                headers.putAll(request.customHeaders());
            }

            String bodyString = null;
            if (request.payload() != null) {
                if (request.payload() instanceof String s) {
                    bodyString = s;
                } else {
                    try {
                        bodyString = objectMapper.writeValueAsString(request.payload());
                    } catch (Exception ex) {
                        return new AngelOneAuthDtos.SmartApiProxyResponse(
                                400,
                                false,
                                method,
                                targetUrl,
                                null,
                                "Failed to serialize request payload to JSON: " + ex.getMessage());
                    }
                }
            }

            AngelOneApiExecutor.RawApiResponse rawResponse = angelOneApiExecutor.executeRawRequest(
                    method,
                    targetUrl,
                    headers,
                    bodyString);

            Object parsedData = null;
            String rawBody = rawResponse.body();
            if (rawBody != null && !rawBody.isBlank()) {
                String trimmed = rawBody.trim();
                if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                        || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                    try {
                        parsedData = objectMapper.readValue(trimmed, Object.class);
                    } catch (Exception ignored) {
                        parsedData = trimmed;
                    }
                } else {
                    parsedData = trimmed;
                }
            }

            boolean isSuccess = rawResponse.statusCode() >= 200 && rawResponse.statusCode() < 300;
            return new AngelOneAuthDtos.SmartApiProxyResponse(
                    rawResponse.statusCode(),
                    isSuccess,
                    method,
                    targetUrl,
                    parsedData,
                    isSuccess ? null : "Angel One returned HTTP " + rawResponse.statusCode());

        } catch (Exception ex) {
            log.warn("SmartAPI proxy execution failed for endpoint={}: {}", request.endpoint(), ex.getMessage());
            return new AngelOneAuthDtos.SmartApiProxyResponse(
                    500,
                    false,
                    request.httpMethod(),
                    request.endpoint(),
                    null,
                    "SmartAPI proxy execution error: " + ex.getMessage());
        }
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

    public record SessionStatus(
            boolean cachedSessionPresent,
            LocalDateTime lastLoginAt,
            boolean hasJwtToken,
            boolean hasRefreshToken,
            boolean hasFeedToken) {
    }

    public record SessionWarmupResult(
            boolean success,
            LocalDateTime lastLoginAt,
            boolean hasJwtToken,
            boolean hasRefreshToken,
            boolean hasFeedToken,
            String message) {
    }

    public record SessionClearResult(
            boolean clearedExistingSession,
            String message) {
    }
}