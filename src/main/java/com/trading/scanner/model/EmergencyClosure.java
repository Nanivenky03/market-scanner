package com.trading.scanner.model;

import com.trading.scanner.config.LocalDateConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

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
    private Integer id;

    @Column(name = "date", nullable = false, unique = true, columnDefinition = "TEXT")
    @Convert(converter = LocalDateConverter.class)
    private LocalDate date;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private String createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = java.time.LocalDateTime.now().toString();
        }
    }
}
