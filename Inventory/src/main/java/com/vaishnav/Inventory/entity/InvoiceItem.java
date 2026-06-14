package com.vaishnav.Inventory.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JsonIgnore
    private Invoice invoice;

    @ManyToOne
    private product productInvoiceitem;

    private String description;

    private String itemCategory;

    private Boolean autoCreateProduct;

    private Integer quantity;

    private Integer returnedQuantity;

    private String returnNote;

    private java.time.LocalDateTime returnDate;

    private Double sellPrice;

    private Double purchasePrice;

    private String stockBrand;

    private String stockCategory;

    private String stockLocation;

    private Double totalPrice;

    private Double profit;

   
}
