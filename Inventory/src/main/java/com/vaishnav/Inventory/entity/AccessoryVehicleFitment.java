package com.vaishnav.Inventory.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "accessory_vehicle_fitment", indexes = {
        @Index(name = "idx_fitment_make_model", columnList = "make,model")
})
@Getter
@Setter
public class AccessoryVehicleFitment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String make;
    private String model;
    private String variant;
    private Integer yearFrom;
    private Integer yearTo;
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "catalog_item_id", nullable = false)
    @JsonBackReference
    private AccessoryCatalogItem catalogItem;

    public boolean hasVehicle() {
        return hasText(make) || hasText(model) || hasText(variant);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
