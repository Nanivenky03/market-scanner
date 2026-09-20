package com.trading.scanner.service.instrument;

import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstrumentMasterSyncServiceTest {

    @Mock
    private InstrumentMasterRepository instrumentMasterRepository;

    @InjectMocks
    private InstrumentMasterSyncService instrumentMasterSyncService;

    @Test
    void ensureRequiredMarketReferences_shouldCreateNiftyWhenMissing() {
        when(instrumentMasterRepository.findBySymbolAndExchange("NIFTY", "NSE"))
                .thenReturn(Optional.empty());

        int result = instrumentMasterSyncService.ensureRequiredMarketReferences();

        assertEquals(1, result);

        verify(instrumentMasterRepository).save(argThat(instrument ->
                "NIFTY".equals(instrument.getSymbol())
                        && "NSE".equals(instrument.getExchange())
                        && "INDEX".equals(instrument.getInstrumentType())
                        && "INDEX".equals(instrument.getSegment())
                        && Boolean.TRUE.equals(instrument.getIsActive())));
    }

    @Test
    void ensureRequiredMarketReferences_shouldCorrectNiftyWhenAttributesDiffer() {
        InstrumentMaster existing = InstrumentMaster.builder()
                .symbol("NIFTY")
                .exchange("NSE")
                .companyName("Old Name")
                .instrumentType("EQUITY")
                .segment("CASH")
                .isActive(false)
                .build();

        when(instrumentMasterRepository.findBySymbolAndExchange("NIFTY", "NSE"))
                .thenReturn(Optional.of(existing));

        int result = instrumentMasterSyncService.ensureRequiredMarketReferences();

        assertEquals(1, result);
        assertEquals("NIFTY 50", existing.getCompanyName());
        assertEquals("INDEX", existing.getInstrumentType());
        assertEquals("INDEX", existing.getSegment());
        assertEquals(true, existing.getIsActive());
        verify(instrumentMasterRepository).save(existing);
    }

    @Test
    void ensureRequiredMarketReferences_shouldDoNothingWhenNiftyAlreadyCorrect() {
        InstrumentMaster existing = InstrumentMaster.builder()
                .symbol("NIFTY")
                .exchange("NSE")
                .companyName("NIFTY 50")
                .instrumentType("INDEX")
                .segment("INDEX")
                .metadataSource("REQUIRED_MARKET_REFERENCE")
                .isActive(true)
                .build();

        when(instrumentMasterRepository.findBySymbolAndExchange("NIFTY", "NSE"))
                .thenReturn(Optional.of(existing));

        int result = instrumentMasterSyncService.ensureRequiredMarketReferences();

        assertEquals(0, result);
        verify(instrumentMasterRepository, never()).save(any());
    }
}