package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.InvoicePayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, Long> {
    List<InvoicePayment> findByInvoiceIdOrderByPaymentDateAscIdAsc(Long invoiceId);

    @Query("select coalesce(sum(p.amount), 0) from InvoicePayment p where p.invoice.id = :invoiceId")
    Double sumAmountByInvoiceId(@Param("invoiceId") Long invoiceId);
}
