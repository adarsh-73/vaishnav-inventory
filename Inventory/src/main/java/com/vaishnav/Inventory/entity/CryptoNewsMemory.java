package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"eventKey", "symbol"}))
public class CryptoNewsMemory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 64, nullable = false)
    private String eventKey;
    @Column(length = 20, nullable = false)
    private String symbol;
    @Column(length = 1000)
    private String title;
    @Column(columnDefinition = "TEXT")
    private String url;
    private String source;
    private String category;
    private String sentiment;
    private Double priceAtObservation;
    private Double priceAfter24h;
    private Double priceAfter72h;
    private Double return24hPercent;
    private Double return72hPercent;
    private String outcomeStatus;
    @Column(columnDefinition = "TEXT")
    private String tags;
    private LocalDateTime observedAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (observedAt == null) observedAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = observedAt;
        if (outcomeStatus == null) outcomeStatus = "WAITING_24H";
    }
}
