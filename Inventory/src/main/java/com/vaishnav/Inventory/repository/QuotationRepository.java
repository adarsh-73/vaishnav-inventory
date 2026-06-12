package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Quotation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {
}
