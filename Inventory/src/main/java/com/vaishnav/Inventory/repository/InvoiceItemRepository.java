package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.InvoiceItem;
import com.vaishnav.Inventory.entity.product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {

    List<InvoiceItem> findByProductInvoiceitem(product productData);

    @Query("""
            select coalesce(sum(coalesce(item.profit, coalesce(item.totalPrice, 0))), 0)
            from InvoiceItem item
            join item.invoice invoice
            where invoice.invoiceDate >= :start and invoice.invoiceDate < :end
            """)
    Double sumProfitBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select coalesce(sum(coalesce(item.totalPrice, 0)), 0)
            from InvoiceItem item
            join item.invoice invoice
            where invoice.invoiceDate >= :start and invoice.invoiceDate < :end
            """)
    Double sumSaleBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
