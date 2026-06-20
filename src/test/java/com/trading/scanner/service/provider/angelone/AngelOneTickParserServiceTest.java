package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AngelOneTickParserServiceTest {

    @Mock
    private InstrumentMasterRepository instrumentMasterRepository;

    @InjectMocks
    private AngelOneTickParserService angelOneTickParserService;

    @Test
    void tryParseBinary_shouldParseSampleLtpFrame() {
        byte[] payload = HexFormat.ofDelimiter(" ").parseHex(
                "01 01 31 36 36 37 35 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 88 52 F5 00 00 00 00 00 68 DB 2D DF 9E 01 00 00 C2 B3 02 00 00 00 00 00");

        when(instrumentMasterRepository.findByBrokerToken("16675"))
                .thenReturn(Optional.of(
                        InstrumentMaster.builder()
                                .symbol("WIPRO")
                                .exchange("NSE")
                                .brokerToken("16675")
                                .build()));

        Optional<AngelOneTickParserService.NormalizedTick> parsed = angelOneTickParserService.tryParseBinary(payload);

        assertTrue(parsed.isPresent());

        AngelOneTickParserService.NormalizedTick tick = parsed.get();
        assertEquals("WIPRO", tick.symbol());
        assertEquals("NSE", tick.exchange());
        assertEquals("16675", tick.brokerToken());
        assertEquals(1, tick.subscriptionMode());
        assertEquals(1, tick.exchangeType());
        assertEquals(1770.90, tick.lastPrice());
        assertEquals(177090L, tick.lastPriceRaw());
        assertNotNull(tick.tickTime());
    }
}