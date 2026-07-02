package com.vaishnav.Inventory.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "accessory_catalog_item", indexes = {
        @Index(name = "idx_accessory_name", columnList = "name"),
        @Index(name = "idx_accessory_brand", columnList = "brand"),
        @Index(name = "idx_accessory_category", columnList = "category")
})
@Getter
@Setter
public class AccessoryCatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 40)
    private String sku;

    @Column(unique = true, length = 80)
    private String barcode;

    @Column(nullable = false)
    private String name;

    private String localName;
    private String brand;
    private String category;
    private String partType;
    private String hsnCode;
    private String supplier;
    private String supplierPhone;
    private Double wholesalePrice;
    private Double retailPrice;
    private Double bargainingPrice;
    private Integer stockQuantity;
    private Integer minimumStock;

    @Column(length = 1200)
    private String photoUrl;

    @Column(length = 2000)
    private String notes;

    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "catalogItem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonManagedReference
    @OrderBy("make ASC, model ASC, yearFrom ASC")
    private List<AccessoryVehicleFitment> fitments = new ArrayList<>();

    @Transient
    public double getProfitAmount() {
        return value(retailPrice) - value(wholesalePrice);
    }

    @Transient
    public double getProfitPercent() {
        double cost = value(wholesalePrice);
        return cost <= 0 ? 0 : ((value(retailPrice) - cost) / cost) * 100.0;
    }

    public void replaceFitments(List<AccessoryVehicleFitment> nextFitments) {
        fitments.clear();
        if (nextFitments == null) return;
        nextFitments.stream()
                .filter(AccessoryVehicleFitment::hasVehicle)
                .forEach(fitment -> {
                    fitment.setId(null);
                    fitment.setCatalogItem(this);
                    fitments.add(fitment);
                });
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (stockQuantity == null) stockQuantity = 0;
        if (minimumStock == null) minimumStock = 0;
        if (active == null) active = true;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    private double value(Double amount) {
        return amount == null ? 0 : amount;
    }
}
