package com.trading.scanner.service.instrument;

import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstrumentMasterSyncServiceTest {

    @Mock
    private InstrumentMasterRepository instrumentMasterRepository;

    @Mock
    private StockUniverseRepository stockUniverseRepository;

    @InjectMocks
    private InstrumentMasterSyncService instrumentMasterSyncService;

    @Test
    void syncFromStockUniverseIfEmpty_shouldSkipWhenInstrumentMasterAlreadyPopulated() {
        when(instrumentMasterRepository.count()).thenReturn(10L);

        int result = instrumentMasterSyncService.syncFromStockUniverseIfEmpty();

        assertEquals(0, result);
        verify(stockUniverseRepository, never()).findAll();
        verify(instrumentMasterRepository, never()).saveAll(anyList());
    }

    @Test
    void syncFromStockUniverseIfEmpty_shouldReturnZeroWhenUniverseEmpty() {
        when(instrumentMasterRepository.count()).thenReturn(0L);
        when(stockUniverseRepository.findAll()).thenReturn(List.of());

        int result = instrumentMasterSyncService.syncFromStockUniverseIfEmpty();

        assertEquals(0, result);
        verify(instrumentMasterRepository, never()).saveAll(anyList());
    }

    @Test
    void syncFromStockUniverseIfEmpty_shouldPopulateInstrumentMasterFromUniverse() {
        StockUniverse tcs = StockUniverse.builder()
                .symbol("TCS")
                .exchange(Exchange.NSE)
                .companyName("Tata Consultancy Services")
                .sector("IT")
                .isActive(true)
                .build();

        StockUniverse reliance = StockUniverse.builder()
                .symbol("RELIANCE")
                .exchange(Exchange.NSE)
                .companyName("Reliance Industries")
                .sector("Energy")
                .isActive(true)
                .build();

        when(instrumentMasterRepository.count()).thenReturn(0L);
        when(stockUniverseRepository.findAll()).thenReturn(List.of(tcs, reliance));

        int result = instrumentMasterSyncService.syncFromStockUniverseIfEmpty();

        assertEquals(2, result);

        ArgumentCaptor<List<InstrumentMaster>> captor = ArgumentCaptor.forClass(List.class);
        verify(instrumentMasterRepository).saveAll(captor.capture());

        List<InstrumentMaster> saved = captor.getValue();
        assertEquals(2, saved.size());

        assertEquals("RELIANCE", saved.get(0).getSymbol());
        assertEquals("NSE", saved.get(0).getExchange());
        assertEquals("Reliance Industries", saved.get(0).getCompanyName());
        assertEquals("EQUITY", saved.get(0).getInstrumentType());
        assertEquals("CASH", saved.get(0).getSegment());
        assertEquals("RELIANCE", saved.get(0).getBrokerSymbol());
        assertEquals(true, saved.get(0).getIsActive());

        assertEquals("TCS", saved.get(1).getSymbol());
        assertEquals("NSE", saved.get(1).getExchange());
        assertEquals("Tata Consultancy Services", saved.get(1).getCompanyName());
        assertEquals("EQUITY", saved.get(1).getInstrumentType());
        assertEquals("CASH", saved.get(1).getSegment());
        assertEquals("TCS", saved.get(1).getBrokerSymbol());
        assertEquals(true, saved.get(1).getIsActive());
    }
}