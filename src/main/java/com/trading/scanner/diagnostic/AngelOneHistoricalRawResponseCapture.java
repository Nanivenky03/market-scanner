package com.trading.scanner.diagnostic;

import com.trading.scanner.config.provider.AngelOneProperties;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.service.provider.angelone.AngelOneApiExecutor;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.dto.AngelOneAuthDtos;
import com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "provider.angelone.raw-capture.enabled", havingValue = "true")
public class AngelOneHistoricalRawResponseCapture {

    private static final String SYMBOL = "ABB";

    private static final String EXCHANGE = "NSE";

    private static final String INTERVAL = "ONE_MINUTE";

    private static final LocalDate FROM_DATE = LocalDate.of(2026, 8, 3);

    private static final LocalDate TO_DATE = LocalDate.of(2026, 8, 7);

    private static final String HISTORICAL_CANDLE_PATH = "/rest/secure/angelbroking/historical/v1/getCandleData";

    private static final DateTimeFormatter REQUEST_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AngelOneProperties properties;

    private final InstrumentMasterRepository instrumentMasterRepository;

    private final AngelOneSessionService angelOneSessionService;

    private final AngelOneApiExecutor angelOneApiExecutor;

    @Value("${provider.angelone.raw-capture.output-file:./logs/angelone-abb-historical-2026-08-03-to-2026-08-07.raw.json}")
    private String outputFile;

    @EventListener(ApplicationReadyEvent.class)
    public void captureAfterApplicationReady() {

        try {
            captureRawResponse();
        } catch (Exception ex) {
            /*
             * This diagnostic must never prevent the production application
             * from starting or shutting down.
             */
            log.error(
                    "Angel One raw historical capture failed. "
                            + "symbol={}, from={}, to={}, reason={}",
                    SYMBOL,
                    FROM_DATE,
                    TO_DATE,
                    ex.getMessage(),
                    ex);
        }
    }

    private void captureRawResponse() {

        if (!properties.enabled()) {
            log.warn(
                    "Angel One is disabled. Skipping raw historical capture.");

            return;
        }

        InstrumentMaster instrument = instrumentMasterRepository
                .findBySymbolAndExchange(
                        SYMBOL,
                        EXCHANGE)
                .orElse(null);

        if (instrument == null) {
            log.warn(
                    "Skipping raw historical capture because instrument "
                            + "master row is missing. symbol={}, exchange={}",
                    SYMBOL,
                    EXCHANGE);

            return;
        }

        if (instrument.getBrokerToken() == null
                || instrument.getBrokerToken().isBlank()) {
            log.warn(
                    "Skipping raw historical capture because broker token "
                            + "is missing. symbol={}, exchange={}",
                    SYMBOL,
                    EXCHANGE);

            return;
        }

        AngelOneAuthDtos.AngelOneSessionTokens sessionTokens = angelOneSessionService.createSessionTokens();

        AngelOneMarketDtos.AngelOneCandleRequest requestBody = new AngelOneMarketDtos.AngelOneCandleRequest(
                instrument.getExchange(),
                instrument.getBrokerToken(),
                INTERVAL,
                FROM_DATE.atStartOfDay()
                        .format(REQUEST_DATE_FORMAT),
                TO_DATE.atTime(23, 59)
                        .format(REQUEST_DATE_FORMAT));

        Map<String, String> headers = new LinkedHashMap<>();

        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put(
                "Authorization",
                "Bearer " + sessionTokens.jwtToken());
        headers.put("X-UserType", "USER");
        headers.put("X-SourceID", "WEB");
        headers.put(
                "X-ClientLocalIP",
                properties.clientLocalIp());
        headers.put(
                "X-ClientPublicIP",
                properties.clientPublicIp());
        headers.put(
                "X-MACAddress",
                properties.macAddress());
        headers.put(
                "X-PrivateKey",
                properties.apiKey());

        String rawResponse = angelOneApiExecutor.postJsonForBody(
                AngelOneApiExecutor.ApiEndpoint.HISTORICAL_CANDLE,
                properties.baseUrl()
                        + HISTORICAL_CANDLE_PATH,
                headers,
                requestBody);

        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException(
                    "Angel One returned an empty historical response");
        }

        Path file = Path.of(outputFile)
                .toAbsolutePath()
                .normalize();

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }

            Files.writeString(
                    file,
                    rawResponse,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to write raw response file="
                            + file,
                    ex);
        }

        log.info(
                "Angel One raw historical response captured. "
                        + "symbol={}, from={}, to={}, file={}",
                SYMBOL,
                FROM_DATE,
                TO_DATE,
                file);
    }
}
