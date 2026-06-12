package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
public class Quotation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String quotationNumber;
    private LocalDate quotationDate;
    private String customerName;
    private String mobileNumber;
    private String vehicleNumber;

    @Column(length = 4000)
    private String itemsJson;

    private Double totalAmount;
    private String note;
    private LocalDateTime createdDate;

    @PrePersist
    public void prePersist() {
        if (quotationDate == null) quotationDate = LocalDate.now();
        createdDate = LocalDateTime.now();
    }
}
