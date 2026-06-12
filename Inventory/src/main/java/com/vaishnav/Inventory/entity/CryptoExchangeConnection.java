package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class CryptoExchangeConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String exchangeName;
    private String keyPreview;
    private Boolean testnet;
    private Boolean connected;
    private Boolean liveTradingLocked;
    private String vaultMode;

    @Column(length = 2000)
    private String encryptedApiKey;

    @Column(length = 2000)
    private String encryptedApiSecret;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (liveTradingLocked == null) liveTradingLocked = true;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
