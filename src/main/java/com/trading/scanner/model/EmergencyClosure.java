package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "emergency_closure",
       uniqueConstraints = @UniqueConstraint(columnNames = "date"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date", nullable = false, unique = true, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate date;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private String createdAt; // Storing as ISO-8601 string for DB compatibility
}
