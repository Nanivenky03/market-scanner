package com.trading.scanner.service.provider.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AngelOneSessionServiceTest {

        private AngelOneProperties properties;
        private TotpService totpService;
        private AngelOneApiExecutor angelOneApiExecutor;
        private TimeProvider timeProvider;
        private ObjectMapper objectMapper;
        private AngelOneSessionService sessionService;

        @BeforeEach
        void setUp() {
                properties = mock(AngelOneProperties.class);
                totpService = mock(TotpService.class);
                angelOneApiExecutor = mock(AngelOneApiExecutor.class);
                timeProvider = mock(TimeProvider.class);
                objectMapper = new ObjectMapper();

                when(properties.enabled()).thenReturn(true);
                when(properties.apiKey()).thenReturn("test-api-key");
                when(properties.clientId()).thenReturn("test-client-id");
                when(properties.password()).thenReturn("test-password");
                when(properties.baseUrl()).thenReturn("https://apiconnect.angelone.in");
                when(properties.clientLocalIp()).thenReturn("127.0.0.1");
                when(properties.clientPublicIp()).thenReturn("127.0.0.1");
                when(properties.macAddress()).thenReturn("00:00:00:00:00:00");
                when(properties.totpSecret()).thenReturn("SECRET");
                when(timeProvider.nowDateTime()).thenReturn(LocalDateTime.of(2026, 9, 27, 10, 0));
                when(totpService.generateCurrentTotp(anyString())).thenReturn("123456");

                sessionService = new AngelOneSessionService(
                                properties,
                                totpService,
                                angelOneApiExecutor,
                                timeProvider,
                                objectMapper);
        }

        @Test
        void executeSmartApiProxy_shouldExecuteRequestAndParseJsonResponse() {
                // Setup mock login response
                var loginResponse = new AngelOneAuthDtos.AngelOneLoginResponse(
                                true, "SUCCESS", null,
                                new AngelOneAuthDtos.AngelOneLoginResponse.AngelOneLoginData("jwt-123", "refresh-123",
                                                "feed-123"));

                when(angelOneApiExecutor.postJson(
                                eq(AngelOneApiExecutor.ApiEndpoint.AUTH_LOGIN),
                                anyString(),
                                anyMap(),
                                any(),
                                eq(AngelOneAuthDtos.AngelOneLoginResponse.class))).thenReturn(loginResponse);

                // Setup mock raw API response
                when(angelOneApiExecutor.executeRawRequest(eq("POST"),
                                eq("https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/"),
                                anyMap(), anyString()))
                                .thenReturn(new AngelOneApiExecutor.RawApiResponse(200,
                                                "{\"status\":true,\"data\":{\"fetched\":[{\"symbolToken\":\"3045\"}]}}",
                                                Map.of()));

                var request = new AngelOneAuthDtos.SmartApiProxyRequest(
                                "POST",
                                "/rest/secure/angelbroking/market/v1/quote/",
                                Map.of("mode", "FULL", "exchangeTokens", Map.of("NSE", java.util.List.of("3045"))),
                                Map.of());

                var response = sessionService.executeSmartApiProxy(request);

                assertNotNull(response);
                assertEquals(200, response.statusCode());
                assertTrue(response.success());
                assertEquals("POST", response.httpMethod());
                assertEquals("https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/",
                                response.url());
                assertTrue(response.data() instanceof Map);
                assertNull(response.errorMessage());
        }

        @Test
        void executeSmartApiProxy_shouldHandleMissingEndpoint() {
                var request = new AngelOneAuthDtos.SmartApiProxyRequest("GET", "  ", null, null);
                var response = sessionService.executeSmartApiProxy(request);

                assertEquals(400, response.statusCode());
                assertFalse(response.success());
                assertEquals("Endpoint path is required (e.g. /rest/secure/angelbroking/market/v1/quote/)",
                                response.errorMessage());
        }
}
