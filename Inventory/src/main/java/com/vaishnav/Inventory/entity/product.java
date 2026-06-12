package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String productName;
    private String brand;
    private String make;
    private String model;
    private String serialNumber;
    private String category;

    @Column(unique = true)
    private String barcode;

    private Integer quantity;
    private Integer minimumStock;
    private Double purchasePrice;
    private Double sellPrice;
    private String productLocation;

    @Column(length = 1000)
    private String description;

    private LocalDateTime createdDate;
    private LocalDateTime updatedDate;

    @PrePersist
    public void prePersist() {
        this.createdDate = LocalDateTime.now();
        this.updatedDate = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedDate = LocalDateTime.now();
    }
}
