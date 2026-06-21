package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class CryptoDecisionAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String symbol;
    private String candidateSignal;
    private String finalSignal;
    private Boolean allowed;
    private Double finalScore;
    private String dataReadiness;
    @Column(columnDefinition = "TEXT")
    private String blockReason;
    private String engineVersion;
    private Long openedTradeId;

    @Column(columnDefinition = "TEXT")
    private String completeSnapshot;

    @Column(columnDefinition = "TEXT")
    private String decisionEvidence;

    @Column(columnDefinition = "TEXT")
    private String aiVotesJson;

    private LocalDateTime createdAt;
    @PrePersist public void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
