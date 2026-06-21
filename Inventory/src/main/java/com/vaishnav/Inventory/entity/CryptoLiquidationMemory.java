package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(indexes = {
        @Index(name = "idx_liquidation_symbol_time", columnList = "symbol,eventTime")
})
public class CryptoLiquidationMemory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 20, nullable = false)
    private String symbol;
    @Column(length = 8, nullable = false)
    private String side;
    private Double price;
    private Double quantity;
    private Double notional;
    private LocalDateTime eventTime;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (eventTime == null) eventTime = createdAt;
    }
}
