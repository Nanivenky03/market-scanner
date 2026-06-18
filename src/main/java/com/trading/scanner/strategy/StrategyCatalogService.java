package com.trading.scanner.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.trading.scanner.service.provider.ProviderException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StrategyCatalogService {

    private final Map<String, StrategyYamlDefinition> definitionsById = new LinkedHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void loadDefinitions() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:strategies/*.yaml");

            definitionsById.clear();

            for (Resource resource : resources) {
                StrategyYamlDefinition definition =
                        yamlMapper.readValue(resource.getInputStream(), StrategyYamlDefinition.class);

                if (definition.strategyId() == null || definition.strategyId().isBlank()) {
                    throw new ProviderException("Strategy file is missing strategyId: " + resource.getFilename());
                }

                if (definitionsById.containsKey(definition.strategyId())) {
                    throw new ProviderException("Duplicate strategyId found: " + definition.strategyId());
                }

                definitionsById.put(definition.strategyId(), definition);
            }

            if (definitionsById.isEmpty()) {
                throw new ProviderException("No strategy YAML files found under classpath:strategies/");
            }

        } catch (Exception ex) {
            throw new ProviderException("Failed to load strategy YAML definitions", ex);
        }
    }

    public StrategyYamlDefinition getRequired(String strategyId) {
        StrategyYamlDefinition definition = definitionsById.get(strategyId);
        if (definition == null) {
            throw new ProviderException("Strategy definition not found for strategyId=" + strategyId);
        }
        return definition;
    }

    public List<StrategyYamlDefinition> all() {
        return definitionsById.values().stream().toList();
    }

    public List<StrategyYamlDefinition> simulationEnabled() {
        return definitionsById.values().stream()
                .filter(def -> Boolean.TRUE.equals(def.simulationEnabled()))
                .collect(Collectors.toList());
    }

    public List<StrategyYamlDefinition> liveEnabled() {
        return definitionsById.values().stream()
                .filter(def -> Boolean.TRUE.equals(def.liveEnabled()))
                .collect(Collectors.toList());
    }
}