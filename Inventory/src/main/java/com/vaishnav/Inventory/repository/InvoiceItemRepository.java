package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.InvoiceItem;
import com.vaishnav.Inventory.entity.product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {

    List<InvoiceItem> findByProductInvoiceitem(product productData);
}
