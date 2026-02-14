package com.trading.scanner.config;

import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.StockUniverseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Seeds the stock_universe table on startup for the simulation profile.
 * This replaces the need for manual SQL seeding, allowing Hibernate to manage the
 * schema while ensuring necessary data is present for simulation runs.
 */
@Component
@Profile("simulation")
@RequiredArgsConstructor
@Slf4j
public class SimulationUniverseSeeder implements ApplicationRunner {

    private final StockUniverseRepository repository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (repository.count() > 0) {
            log.info("Simulation universe already seeded. Skipping.");
            return;
        }

        log.info("Seeding simulation stock universe...");

        List<StockUniverse> stocks = Arrays.asList(
            StockUniverse.builder()
                .symbol("RELIANCE")
                .exchange(Exchange.NSE)
                .companyName("Reliance Industries")
                .sector("Energy")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("TCS")
                .exchange(Exchange.NSE)
                .companyName("Tata Consultancy Services")
                .sector("IT")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("HDFCBANK")
                .exchange(Exchange.NSE)
                .companyName("HDFC Bank")
                .sector("Banking")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("INFY")
                .exchange(Exchange.NSE)
                .companyName("Infosys")
                .sector("IT")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("HINDUNILVR")
                .exchange(Exchange.NSE)
                .companyName("Hindustan Unilever")
                .sector("FMCG")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("ICICIBANK")
                .exchange(Exchange.NSE)
                .companyName("ICICI Bank")
                .sector("Banking")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("SBIN")
                .exchange(Exchange.NSE)
                .companyName("State Bank of India")
                .sector("Banking")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("BHARTIARTL")
                .exchange(Exchange.NSE)
                .companyName("Bharti Airtel")
                .sector("Telecom")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("KOTAKBANK")
                .exchange(Exchange.NSE)
                .companyName("Kotak Mahindra Bank")
                .sector("Banking")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("ITC")
                .exchange(Exchange.NSE)
                .companyName("ITC Limited")
                .sector("FMCG")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("LT")
                .exchange(Exchange.NSE)
                .companyName("Larsen & Toubro")
                .sector("Infrastructure")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("AXISBANK")
                .exchange(Exchange.NSE)
                .companyName("Axis Bank")
                .sector("Banking")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("BAJFINANCE")
                .exchange(Exchange.NSE)
                .companyName("Bajaj Finance")
                .sector("Finance")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("ASIANPAINT")
                .exchange(Exchange.NSE)
                .companyName("Asian Paints")
                .sector("Paints")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("MARUTI")
                .exchange(Exchange.NSE)
                .companyName("Maruti Suzuki")
                .sector("Auto")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("HCLTECH")
                .exchange(Exchange.NSE)
                .companyName("HCL Technologies")
                .sector("IT")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("WIPRO")
                .exchange(Exchange.NSE)
                .companyName("Wipro")
                .sector("IT")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("ULTRACEMCO")
                .exchange(Exchange.NSE)
                .companyName("UltraTech Cement")
                .sector("Cement")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("TITAN")
                .exchange(Exchange.NSE)
                .companyName("Titan Company")
                .sector("Consumer")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("SUNPHARMA")
                .exchange(Exchange.NSE)
                .companyName("Sun Pharma")
                .sector("Pharma")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("NESTLEIND")
                .exchange(Exchange.NSE)
                .companyName("Nestle India")
                .sector("FMCG")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("TATAMOTORS")
                .exchange(Exchange.NSE)
                .companyName("Tata Motors")
                .sector("Auto")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("TATASTEEL")
                .exchange(Exchange.NSE)
                .companyName("Tata Steel")
                .sector("Metals")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("POWERGRID")
                .exchange(Exchange.NSE)
                .companyName("Power Grid Corp")
                .sector("Power")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("NTPC")
                .exchange(Exchange.NSE)
                .companyName("NTPC")
                .sector("Power")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("ONGC")
                .exchange(Exchange.NSE)
                .companyName("ONGC")
                .sector("Energy")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("COALINDIA")
                .exchange(Exchange.NSE)
                .companyName("Coal India")
                .sector("Mining")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("M&M")
                .exchange(Exchange.NSE)
                .companyName("Mahindra & Mahindra")
                .sector("Auto")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("BAJAJFINSV")
                .exchange(Exchange.NSE)
                .companyName("Bajaj Finserv")
                .sector("Finance")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("TECHM")
                .exchange(Exchange.NSE)
                .companyName("Tech Mahindra")
                .sector("IT")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("ADANIPORTS")
                .exchange(Exchange.NSE)
                .companyName("Adani Ports")
                .sector("Infrastructure")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("GODREJCP")
                .exchange(Exchange.NSE)
                .companyName("Godrej Consumer")
                .sector("FMCG")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("INDIGO")
                .exchange(Exchange.NSE)
                .companyName("InterGlobe Aviation")
                .sector("Aviation")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("SIEMENS")
                .exchange(Exchange.NSE)
                .companyName("Siemens")
                .sector("Engineering")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("DLF")
                .exchange(Exchange.NSE)
                .companyName("DLF")
                .sector("Real Estate")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("GAIL")
                .exchange(Exchange.NSE)
                .companyName("GAIL India")
                .sector("Gas")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("ABB")
                .exchange(Exchange.NSE)
                .companyName("ABB India")
                .sector("Engineering")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("PIDILITIND")
                .exchange(Exchange.NSE)
                .companyName("Pidilite Industries")
                .sector("Chemicals")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("HAVELLS")
                .exchange(Exchange.NSE)
                .companyName("Havells India")
                .sector("Consumer")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("BERGEPAINT")
                .exchange(Exchange.NSE)
                .companyName("Berger Paints")
                .sector("Paints")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("AMBUJACEM")
                .exchange(Exchange.NSE)
                .companyName("Ambuja Cements")
                .sector("Cement")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("ACC")
                .exchange(Exchange.NSE)
                .companyName("ACC Limited")
                .sector("Cement")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("TATACONSUM")
                .exchange(Exchange.NSE)
                .companyName("Tata Consumer")
                .sector("FMCG")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("DIVISLAB")
                .exchange(Exchange.NSE)
                .companyName("Divis Laboratories")
                .sector("Pharma")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("DRREDDY")
                .exchange(Exchange.NSE)
                .companyName("Dr Reddys Labs")
                .sector("Pharma")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("CIPLA")
                .exchange(Exchange.NSE)
                .companyName("Cipla")
                .sector("Pharma")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("TORNTPHARM")
                .exchange(Exchange.NSE)
                .companyName("Torrent Pharma")
                .sector("Pharma")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("APOLLOHOSP")
                .exchange(Exchange.NSE)
                .companyName("Apollo Hospitals")
                .sector("Healthcare")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("GRASIM")
                .exchange(Exchange.NSE)
                .companyName("Grasim Industries")
                .sector("Cement")
                .isActive(true)
                .build(),
            StockUniverse.builder()
                .symbol("VEDL")
                .exchange(Exchange.NSE)
                .companyName("Vedanta")
                .sector("Metals")
                .isActive(true)
                .build()
        );

        repository.saveAll(stocks);
        log.info("Simulation universe seeded successfully with 50 stocks.");
    }
}
