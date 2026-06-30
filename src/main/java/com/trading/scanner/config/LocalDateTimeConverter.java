package com.trading.scanner.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Converter(autoApply = true)
public class LocalDateTimeConverter implements AttributeConverter<LocalDateTime, String> {

    @Override
    public String convertToDatabaseColumn(LocalDateTime attribute) {
        return attribute != null ? attribute.toString() : null;
    }

    @Override
    public LocalDateTime convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dbData);
        } catch (Exception ignored) {
        }

        try {
            return OffsetDateTime.parse(dbData.replace(' ', 'T')).toLocalDateTime();
        } catch (Exception ignored) {
        }

        throw new IllegalArgumentException("Unable to parse LocalDateTime from db value: " + dbData);
    }
}