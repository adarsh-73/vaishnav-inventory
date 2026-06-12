package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

}