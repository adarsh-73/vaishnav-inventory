package com.vaishnav.Inventory.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
public class InvoicePayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JsonIgnore
    private Invoice invoice;

    private Double amount;
    private LocalDate paymentDate;
    private String paymentMethod;
    private String receivedFrom;
    private String receivedBy;
    @Column(length = 500)
    private String note;
    private LocalDateTime createdDate;

    @PrePersist
    public void prePersist() {
        if (paymentDate == null) paymentDate = LocalDate.now();
        if (createdDate == null) createdDate = LocalDateTime.now();
    }
}
