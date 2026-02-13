package com.trading.scanner.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Converter(autoApply = true)
public class LocalDateTimeConverter implements AttributeConverter<LocalDateTime, String> {
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    
    @Override
    public String convertToDatabaseColumn(LocalDateTime localDateTime) {
        return (localDateTime == null) ? null : localDateTime.format(FORMATTER);
    }
    
    @Override
    public LocalDateTime convertToEntityAttribute(String dateTimeString) {
        return (dateTimeString == null || dateTimeString.trim().isEmpty()) 
            ? null 
            : LocalDateTime.parse(dateTimeString, FORMATTER);
    }
}
