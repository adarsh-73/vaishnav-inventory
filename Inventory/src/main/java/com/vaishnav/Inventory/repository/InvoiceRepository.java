package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @Override
    @EntityGraph(attributePaths = {"customer", "invoiceItems", "invoiceItems.productInvoiceitem"})
    List<Invoice> findAll();

    @EntityGraph(attributePaths = {"customer", "invoiceItems", "invoiceItems.productInvoiceitem"})
    List<Invoice> findByInvoiceNumber(String invoiceNumber);

    @Override
    @EntityGraph(attributePaths = {"customer", "invoiceItems", "invoiceItems.productInvoiceitem"})
    Optional<Invoice> findById(Long id);
}
