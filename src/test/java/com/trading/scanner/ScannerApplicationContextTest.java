package com.trading.scanner;

import com.trading.scanner.service.runtime.RuntimeBootstrapService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ScannerApplicationContextTest {

    @MockBean
    private RuntimeBootstrapService runtimeBootstrapService;

    @Test
    void contextLoads() {
        assertTrue(true, "Application context and all bean wiring loaded successfully");
    }
}
