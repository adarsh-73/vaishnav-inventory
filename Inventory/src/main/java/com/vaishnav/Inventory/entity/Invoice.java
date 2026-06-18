package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String invoiceNumber;

    @ManyToOne
    private Customer customer;

    private Double totalAmount;

    private Double discountAmount;

    private String discountNote;

    private Double paidAmount;

    private Double remainingAmount;

    private String paymentMethod;

    private String businessCategory;

    private String invoiceStatus;

    private String returnNote;

    private LocalDateTime returnDate;

    private LocalDateTime invoiceDate;

    private LocalDateTime createdDate;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @Getter
    @Setter
    private List<InvoiceItem> invoiceItems;

    public Invoice() {
    }

    @PrePersist
    public void prePersist() {

        this.createdDate = LocalDateTime.now();

        if (this.invoiceDate == null) {
            this.invoiceDate = LocalDateTime.now();
        }
    }
}
