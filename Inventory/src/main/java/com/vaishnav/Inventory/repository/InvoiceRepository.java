package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByInvoiceNumber(String invoiceNumber);
}
