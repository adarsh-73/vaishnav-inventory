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
    private String candidateSignal;
    private String engineVersion;
    private Long decisionAuditId;

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
    private Double accountRiskPercent;
    private Double initialRiskAmount;
    private Double maxFavorablePrice;
    private Double maxAdversePrice;
    private Boolean breakEvenMoved;

    private String bestAi;
    @Column(columnDefinition = "TEXT")
    private String aiConsensus;

    @Column(columnDefinition = "TEXT")
    private String technicalSummary;
    private String newsRisk;
    private String macroBias;
    private String whaleBias;
    private String onChainBias;
    private String dataReadiness;
    private String closeReason;

    @Column(length = 4000)
    private String indicatorSnapshot;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String entrySnapshot;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String exitSnapshot;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String decisionEvidence;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String aiVotesJson;

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (openedAt == null) openedAt = LocalDateTime.now();
        if (status == null) status = "RUNNING";
        if (pnl == null) pnl = 0.0;
        if (breakEvenMoved == null) breakEvenMoved = false;
        if (engineVersion == null) engineVersion = "AEGIS_V2";
    }
}
