package com.trading.scanner.service.instrument;

import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.repository.InstrumentMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentMasterSyncService {

    private static final String NIFTY_SYMBOL = "NIFTY";
    private static final String NSE = "NSE";

    private final InstrumentMasterRepository instrumentMasterRepository;

    @Transactional
    public int ensureRequiredMarketReferences() {
        Optional<InstrumentMaster> existing = instrumentMasterRepository
                .findBySymbolAndExchange(NIFTY_SYMBOL, NSE);

        if (existing.isEmpty()) {
            InstrumentMaster nifty = InstrumentMaster.builder()
                    .symbol(NIFTY_SYMBOL)
                    .exchange(NSE)
                    .companyName("NIFTY 50")
                    .instrumentType("INDEX")
                    .segment("INDEX")
                    .brokerSymbol("Nifty 50")
                    .brokerToken("99926000")
                    .isin(null)
                    .metadataSource("REQUIRED_MARKET_REFERENCE")
                    .isActive(true)
                    .build();

            instrumentMasterRepository.save(nifty);
            log.info("Created required NIFTY instrument-master row");
            return 1;
        }

        InstrumentMaster nifty = existing.get();
        boolean changed = false;

        if (!"99926000".equals(nifty.getBrokerToken())) {
            nifty.setBrokerToken("99926000");
            changed = true;
        }

        if (!"Nifty 50".equals(nifty.getBrokerSymbol())) {
            nifty.setBrokerSymbol("Nifty 50");
            changed = true;
        }

        if (!"NIFTY 50".equals(nifty.getCompanyName())) {
            nifty.setCompanyName("NIFTY 50");
            changed = true;
        }

        if (!"INDEX".equalsIgnoreCase(nifty.getInstrumentType())) {
            nifty.setInstrumentType("INDEX");
            changed = true;
        }

        if (!"INDEX".equalsIgnoreCase(nifty.getSegment())) {
            nifty.setSegment("INDEX");
            changed = true;
        }

        if (!Boolean.TRUE.equals(nifty.getIsActive())) {
            nifty.setIsActive(true);
            changed = true;
        }

        if (nifty.getMetadataSource() == null || nifty.getMetadataSource().isBlank()) {
            nifty.setMetadataSource("REQUIRED_MARKET_REFERENCE");
            changed = true;
        }

        if (changed) {
            instrumentMasterRepository.save(nifty);
            log.info("Corrected required NIFTY instrument-master row");
            return 1;
        }

        return 0;
    }
}