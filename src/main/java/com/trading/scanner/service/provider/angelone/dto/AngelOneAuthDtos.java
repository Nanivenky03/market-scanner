package com.trading.scanner.service.provider.angelone.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

public final class AngelOneAuthDtos {

        private AngelOneAuthDtos() {
        }

        public record AngelOneLoginRequest(
                        String clientcode,
                        String password,
                        String totp) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AngelOneLoginResponse(
                        Boolean status,
                        String message,
                        String errorcode,
                        AngelOneLoginData data) {
                @JsonIgnoreProperties(ignoreUnknown = true)
                public record AngelOneLoginData(
                                String jwtToken,
                                String refreshToken,
                                String feedToken) {
                }
        }

        public record AngelOneSessionInfo(
                        boolean success,
                        String message,
                        boolean hasJwtToken,
                        boolean hasRefreshToken,
                        boolean hasFeedToken,
                        String jwtTokenPreview,
                        String refreshTokenPreview,
                        String feedTokenPreview) {
        }

        public record AngelOneSessionTokens(
                        String jwtToken,
                        String refreshToken,
                        String feedToken) {
        }

        public record SmartApiProxyRequest(
                        @Schema(description = "HTTP Method (e.g. GET, POST, PUT, DELETE)", example = "POST", defaultValue = "POST") String httpMethod,

                        @Schema(description = "SmartAPI Endpoint path or full URL (e.g. /rest/secure/angelbroking/market/v1/quote/)", example = "/rest/secure/angelbroking/market/v1/quote/", requiredMode = Schema.RequiredMode.REQUIRED) String endpoint,

                        @Schema(description = "Request JSON payload for POST/PUT requests (e.g. {\"mode\": \"FULL\", \"exchangeTokens\": {\"NSE\": [\"3045\"]}})", example = "{\"mode\": \"FULL\", \"exchangeTokens\": {\"NSE\": [\"3045\"]}}") Object payload,

                        @Schema(description = "Optional additional headers to merge/override", example = "{}") Map<String, String> customHeaders) {
        }

        public record SmartApiProxyResponse(
                        int statusCode,
                        boolean success,
                        String httpMethod,
                        String url,
                        Object data,
                        String errorMessage) {
        }
}