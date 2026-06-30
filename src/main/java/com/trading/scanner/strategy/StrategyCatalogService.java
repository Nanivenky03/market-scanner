package com.trading.scanner.strategy;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.trading.scanner.service.runtime.RuleExecutionPolicyService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StrategyCatalogService {

    private final RuleExecutionPolicyService ruleExecutionPolicyService;
    private final YAMLMapper yamlMapper = new YAMLMapper();

    private List<StrategyYamlDefinition> strategies = List.of();

    @PostConstruct
    public void load() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:strategies/*.yaml");

            strategies = java.util.Arrays.stream(resources)
                    .map(this::readStrategy)
                    .sorted(Comparator.comparing(StrategyYamlDefinition::strategyId))
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load strategy catalog", ex);
        }
    }

    public List<StrategyYamlDefinition> all() {
        return strategies;
    }

    public List<StrategyYamlDefinition> historicalEligible() {
        return strategies.stream()
                .filter(ruleExecutionPolicyService::allowHistoricalSimulation)
                .toList();
    }

    public List<StrategyYamlDefinition> liveSignalEligible() {
        return strategies.stream()
                .filter(ruleExecutionPolicyService::allowLiveSignalGeneration)
                .toList();
    }

    public List<StrategyYamlDefinition> realExecutionEligible() {
        return strategies.stream()
                .filter(ruleExecutionPolicyService::allowRealExecution)
                .toList();
    }

    public StrategyYamlDefinition getRequired(String strategyId) {
        return strategies.stream()
                .filter(s -> s.strategyId().equals(strategyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Strategy not found: " + strategyId));
    }

    private StrategyYamlDefinition readStrategy(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return yamlMapper.readValue(in, StrategyYamlDefinition.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read strategy YAML: " + resource.getFilename(), ex);
        }
    }
}