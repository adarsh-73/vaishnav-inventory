package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @Override
    @EntityGraph(attributePaths = {"customer", "invoiceItems", "invoiceItems.productInvoiceitem"})
    List<Invoice> findAll();

    @Override
    @EntityGraph(attributePaths = {"customer", "invoiceItems", "invoiceItems.productInvoiceitem"})
    Optional<Invoice> findById(Long id);

    @EntityGraph(attributePaths = {"customer", "invoiceItems", "invoiceItems.productInvoiceitem"})
    List<Invoice> findByInvoiceNumber(String invoiceNumber);
}
