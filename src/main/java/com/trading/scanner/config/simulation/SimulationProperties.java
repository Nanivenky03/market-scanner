package com.trading.scanner.config.simulation;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;

@ConfigurationProperties(prefix = "simulation")
@Component
@Validated
@Profile("simulation")
@Data
public class SimulationProperties {

    @NotNull
    private LocalDate baseDate;

}
