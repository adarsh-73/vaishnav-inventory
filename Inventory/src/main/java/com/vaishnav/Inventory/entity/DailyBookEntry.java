package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
public class DailyBookEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate entryDate;
    private String entryType;
    private String incomeCategory;
    private String partyName;
    private String note;
    private Double amount;
    private String paymentStatus;
    private LocalDateTime createdDate;

    @PrePersist
    public void prePersist() {
        if (entryDate == null) entryDate = LocalDate.now();
        createdDate = LocalDateTime.now();
    }
}
