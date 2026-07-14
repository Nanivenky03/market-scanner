package com.trading.scanner.service.provider.angelone;

import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
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

                Optional<AngelOneTickParserService.NormalizedTick> parsed = angelOneTickParserService
                                .tryParseBinary(payload);

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

                assertNull(tick.openPrice());
                assertNull(tick.highPrice());
                assertNull(tick.lowPrice());
                assertNull(tick.closePrice());
                assertNull(tick.averageTradedPrice());
                assertNull(tick.volumeTradedForDay());
                assertNull(tick.upperCircuitLimit());
                assertNull(tick.lowerCircuitLimit());
        }

        @Test
        void tryParseText_shouldParseRicherQuoteShape() {
                String payload = """
                                {
                                  "symbol": "WIPRO",
                                  "exchange": "NSE",
                                  "lastPrice": 1770.90,
                                  "lastTradedQuantity": 25,
                                  "openPrice": 1750.00,
                                  "highPrice": 1785.50,
                                  "lowPrice": 1748.10,
                                  "closePrice": 1762.25,
                                  "averageTradedPrice": 1768.40,
                                  "volumeTradedForDay": 1234567,
                                  "totalBuyQuantity": 450000,
                                  "totalSellQuantity": 420000,
                                  "lastTradedTimestamp": 1762671000000,
                                  "openInterest": 0,
                                  "openInterestChangePercentRaw": 0.0,
                                  "upperCircuitLimit": 1938.45,
                                  "lowerCircuitLimit": 1586.05,
                                  "fiftyTwoWeekHighPrice": 1999.95,
                                  "fiftyTwoWeekLowPrice": 1200.10,
                                  "token": "16675",
                                  "subscriptionMode": 3,
                                  "exchangeType": 1
                                }
                                """;

                Optional<AngelOneTickParserService.NormalizedTick> parsed = angelOneTickParserService
                                .tryParseText(payload);

                assertTrue(parsed.isPresent());

                AngelOneTickParserService.NormalizedTick tick = parsed.get();
                assertEquals("WIPRO", tick.symbol());
                assertEquals("NSE", tick.exchange());
                assertEquals(1770.90, tick.lastPrice());
                assertEquals(25L, tick.lastTradedQuantity());
                assertEquals(1750.00, tick.openPrice());
                assertEquals(1785.50, tick.highPrice());
                assertEquals(1748.10, tick.lowPrice());
                assertEquals(1762.25, tick.closePrice());
                assertEquals(1768.40, tick.averageTradedPrice());
                assertEquals(1234567L, tick.volumeTradedForDay());
                assertEquals(450000L, tick.totalBuyQuantity());
                assertEquals(420000L, tick.totalSellQuantity());
                assertEquals(1762671000000L, tick.lastTradedTimestamp());
                assertEquals(0L, tick.openInterest());
                assertEquals(0.0, tick.openInterestChangePercentRaw());
                assertEquals(1938.45, tick.upperCircuitLimit());
                assertEquals(1586.05, tick.lowerCircuitLimit());
                assertEquals(1999.95, tick.fiftyTwoWeekHighPrice());
                assertEquals(1200.10, tick.fiftyTwoWeekLowPrice());
                assertEquals("16675", tick.brokerToken());
                assertEquals(3, tick.subscriptionMode());
                assertEquals(1, tick.exchangeType());
                assertNotNull(tick.tickTime());
        }

        @Test
        void tryParseBinary_shouldParseSyntheticSnapQuoteFrame() {
                byte[] payload = new byte[379];

                payload[0] = 3;
                payload[1] = 1;
                writeAsciiToken(payload, 2, 25, "16675");

                writeLittleEndianLong(payload, 27, 24523400L);
                writeLittleEndianLong(payload, 35, 1762671000000L);
                writeLittleEndianLong(payload, 43, 177090L);

                writeLittleEndianLong(payload, 51, 25L);
                writeLittleEndianLong(payload, 59, 176840L);
                writeLittleEndianLong(payload, 67, 1234567L);
                writeLittleEndianDouble(payload, 75, 450000.0);
                writeLittleEndianDouble(payload, 83, 420000.0);
                writeLittleEndianLong(payload, 91, 175000L);
                writeLittleEndianLong(payload, 99, 178550L);
                writeLittleEndianLong(payload, 107, 174810L);
                writeLittleEndianLong(payload, 115, 176225L);

                writeLittleEndianLong(payload, 123, 1762670995000L);
                writeLittleEndianLong(payload, 131, 654321L);
                writeLittleEndianDouble(payload, 139, 1.25);
                writeLittleEndianLong(payload, 347, 193845L);
                writeLittleEndianLong(payload, 355, 158605L);
                writeLittleEndianLong(payload, 363, 199995L);
                writeLittleEndianLong(payload, 371, 120010L);

                when(instrumentMasterRepository.findByBrokerToken("16675"))
                                .thenReturn(Optional.of(
                                                InstrumentMaster.builder()
                                                                .symbol("WIPRO")
                                                                .exchange("NSE")
                                                                .brokerToken("16675")
                                                                .build()));

                Optional<AngelOneTickParserService.NormalizedTick> parsed = angelOneTickParserService
                                .tryParseBinary(payload);

                assertTrue(parsed.isPresent());

                AngelOneTickParserService.NormalizedTick tick = parsed.get();
                assertEquals("WIPRO", tick.symbol());
                assertEquals("NSE", tick.exchange());
                assertEquals("16675", tick.brokerToken());
                assertEquals(3, tick.subscriptionMode());
                assertEquals(1, tick.exchangeType());
                assertEquals(1770.90, tick.lastPrice());
                assertEquals(25L, tick.lastTradedQuantity());
                assertEquals(1768.40, tick.averageTradedPrice());
                assertEquals(1234567L, tick.volumeTradedForDay());
                assertEquals(450000L, tick.totalBuyQuantity());
                assertEquals(420000L, tick.totalSellQuantity());
                assertEquals(1750.00, tick.openPrice());
                assertEquals(1785.50, tick.highPrice());
                assertEquals(1748.10, tick.lowPrice());
                assertEquals(1762.25, tick.closePrice());
                assertEquals(1762670995000L, tick.lastTradedTimestamp());
                assertEquals(654321L, tick.openInterest());
                assertEquals(1.25, tick.openInterestChangePercentRaw());
                assertEquals(1938.45, tick.upperCircuitLimit());
                assertEquals(1586.05, tick.lowerCircuitLimit());
                assertEquals(1999.95, tick.fiftyTwoWeekHighPrice());
                assertEquals(1200.10, tick.fiftyTwoWeekLowPrice());
                assertNotNull(tick.tickTime());
        }

        private void writeAsciiToken(byte[] payload, int offset, int length, String token) {
                byte[] bytes = token.getBytes(StandardCharsets.US_ASCII);
                int max = Math.min(bytes.length, length);
                System.arraycopy(bytes, 0, payload, offset, max);
        }

        private void writeLittleEndianLong(byte[] payload, int offset, long value) {
                for (int i = 0; i < 8; i++) {
                        payload[offset + i] = (byte) ((value >> (8 * i)) & 0xFF);
                }
        }

        private void writeLittleEndianDouble(byte[] payload, int offset, double value) {
                writeLittleEndianLong(payload, offset, Double.doubleToLongBits(value));
        }
}