package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private product productdata;

    @ManyToOne
    private Supplier supplier;

    private Integer quantity;

    private Double purchasePrice;

    private String invoiceNumber;

    private LocalDateTime purchaseDate;

    private LocalDateTime createdDate;

    public Purchase() {
    }

    @PrePersist
    public void prePersist() {

        this.createdDate = LocalDateTime.now();

        if (this.purchaseDate == null) {
            this.purchaseDate = LocalDateTime.now();
        }
    }
}