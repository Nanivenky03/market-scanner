package com.trading.scanner.service.instrument;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MarketIndexRegistryTest {

    private MarketIndexRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MarketIndexRegistry();
    }

    @Test
    void shouldRegisterAllCoreIndices() {
        List<MarketIndexRegistry.IndexDefinition> all = registry.getAllRegisteredIndices();
        assertNotNull(all);
        assertTrue(all.size() >= 25, "Should have registered at least 25 core indices");

        assertTrue(registry.isRegisteredIndex("NIFTY"));
        assertTrue(registry.isRegisteredIndex("NIFTY BANK"));
        assertTrue(registry.isRegisteredIndex("NIFTY IT"));
        assertTrue(registry.isRegisteredIndex("NIFTY AUTO"));
        assertTrue(registry.isRegisteredIndex("NIFTY METAL"));
        assertTrue(registry.isRegisteredIndex("NIFTY PHARMA"));
        assertTrue(registry.isRegisteredIndex("NIFTY FMCG"));
        assertTrue(registry.isRegisteredIndex("NIFTY ENERGY"));
        assertTrue(registry.isRegisteredIndex("NIFTY INFRA"));
        assertTrue(registry.isRegisteredIndex("NIFTY REALTY"));
        assertTrue(registry.isRegisteredIndex("NIFTY MEDIA"));
        assertTrue(registry.isRegisteredIndex("NIFTY FIN SERVICE"));
        assertTrue(registry.isRegisteredIndex("INDIA VIX"));
    }

    @Test
    void shouldResolveCanonicalSymbolsFromAliases() {
        assertEquals("NIFTY BANK", registry.resolveCanonicalSymbol("BANKNIFTY", "UNKNOWN"));
        assertEquals("NIFTY BANK", registry.resolveCanonicalSymbol("Nifty Bank", "UNKNOWN"));
        assertEquals("NIFTY FIN SERVICE", registry.resolveCanonicalSymbol("FINNIFTY", "UNKNOWN"));
        assertEquals("NIFTY FIN SERVICE", registry.resolveCanonicalSymbol("Nifty Fin Service", "UNKNOWN"));
        assertEquals("NIFTY", registry.resolveCanonicalSymbol("Nifty 50", "UNKNOWN"));
        assertEquals("NIFTY NEXT 50", registry.resolveCanonicalSymbol("NIFTYNXT50", "UNKNOWN"));
        assertEquals("INDIA VIX", registry.resolveCanonicalSymbol("India VIX", "UNKNOWN"));
    }

    @Test
    void shouldLookupByToken() {
        Optional<MarketIndexRegistry.IndexDefinition> nifty = registry.getByToken("99926000");
        assertTrue(nifty.isPresent());
        assertEquals("NIFTY", nifty.get().canonicalSymbol());

        Optional<MarketIndexRegistry.IndexDefinition> bank = registry.getByToken("99926009");
        assertTrue(bank.isPresent());
        assertEquals("NIFTY BANK", bank.get().canonicalSymbol());

        Optional<MarketIndexRegistry.IndexDefinition> it = registry.getByToken("99926008");
        assertTrue(it.isPresent());
        assertEquals("NIFTY IT", it.get().canonicalSymbol());
    }

    @Test
    void shouldReturnDefaultIfNotFound() {
        assertEquals("UNKNOWN_CUSTOM", registry.resolveCanonicalSymbol("NON_EXISTENT_INDEX", "UNKNOWN_CUSTOM"));
        assertFalse(registry.isRegisteredIndex("NON_EXISTENT_INDEX"));
        assertFalse(registry.getByCanonicalSymbol("NON_EXISTENT_INDEX").isPresent());
    }
}
