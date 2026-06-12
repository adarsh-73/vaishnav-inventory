package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String supplierName;

    @Column(unique = true)
    private String mobileNumber;

    private String email;

    private String gstNumber;

    private String companyName;

    private String address;

    private LocalDateTime createdDate;

    private LocalDateTime updatedDate;

    public Supplier() {
    }

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