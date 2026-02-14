package com.trading.scanner.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Converter(autoApply = true)
public class LocalDateConverter implements AttributeConverter<LocalDate, String> {
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    
    @Override
    public String convertToDatabaseColumn(LocalDate localDate) {
        return (localDate == null) ? null : localDate.format(FORMATTER);
    }
    
    @Override
    public LocalDate convertToEntityAttribute(String dateString) {
        return (dateString == null || dateString.trim().isEmpty()) 
            ? null 
            : LocalDate.parse(dateString, FORMATTER);
    }
}
