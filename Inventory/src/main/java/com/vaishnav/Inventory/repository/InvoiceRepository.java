package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
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

    @Query("select i.id from Invoice i order by i.createdDate desc")
    List<Long> findRecentIds(Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "invoiceItems", "invoiceItems.productInvoiceitem"})
    List<Invoice> findByIdIn(List<Long> ids);

    @EntityGraph(attributePaths = {"customer", "invoiceItems", "invoiceItems.productInvoiceitem"})
    List<Invoice> findByInvoiceDateGreaterThanEqualAndInvoiceDateLessThanOrderByInvoiceDateDesc(
            LocalDateTime start,
            LocalDateTime end
    );
}
