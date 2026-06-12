package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class CryptoPaperTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String side;
    private String status;
    private String timeframe;

    private Double entryPrice;
    private Double stopLoss;
    private Double takeProfit;
    private Double trailingStop;
    private Double exitPrice;
    private Double quantity;
    private Double pnl;
    private Double confidence;
    private Double finalScore;
    private Double riskReward;

    private String bestAi;
    private String aiConsensus;
    private String technicalSummary;
    private String newsRisk;
    private String closeReason;

    @Column(length = 4000)
    private String indicatorSnapshot;

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (openedAt == null) openedAt = LocalDateTime.now();
        if (status == null) status = "RUNNING";
        if (pnl == null) pnl = 0.0;
    }
}
