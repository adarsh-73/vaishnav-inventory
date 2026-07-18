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
            select coalesce(sum(
                case
                    when lower(coalesce(item.itemCategory, '')) like '%service%'
                      or lower(coalesce(item.itemCategory, '')) like '%washing%'
                      or lower(coalesce(item.itemCategory, '')) like '%labour%'
                      or (
                          item.productInvoiceitem is null
                          and (
                              lower(coalesce(item.description, '')) like '%wash%'
                              or lower(coalesce(item.description, '')) like '%labour%'
                              or lower(coalesce(item.description, '')) like '%service%'
                              or lower(coalesce(item.description, '')) like '%fitting%'
                              or lower(coalesce(item.description, '')) like '%repair%'
                              or lower(coalesce(item.description, '')) like '%polish%'
                              or lower(coalesce(item.description, '')) like '%clean%'
                          )
                      )
                    then coalesce(item.totalPrice, 0)
                    else 0
                end
            ), 0)
            from InvoiceItem item
            join item.invoice invoice
            where invoice.invoiceDate >= :start and invoice.invoiceDate < :end
            """)
    Double sumServiceProfitBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select coalesce(sum(
                case
                    when lower(coalesce(item.itemCategory, '')) like '%service%'
                      or lower(coalesce(item.itemCategory, '')) like '%washing%'
                      or lower(coalesce(item.itemCategory, '')) like '%labour%'
                      or (
                          item.productInvoiceitem is null
                          and (
                              lower(coalesce(item.description, '')) like '%wash%'
                              or lower(coalesce(item.description, '')) like '%labour%'
                              or lower(coalesce(item.description, '')) like '%service%'
                              or lower(coalesce(item.description, '')) like '%fitting%'
                              or lower(coalesce(item.description, '')) like '%repair%'
                              or lower(coalesce(item.description, '')) like '%polish%'
                              or lower(coalesce(item.description, '')) like '%clean%'
                          )
                      )
                    then 0
                    else coalesce(item.profit, coalesce(item.totalPrice, 0))
                end
            ), 0)
            from InvoiceItem item
            join item.invoice invoice
            where invoice.invoiceDate >= :start and invoice.invoiceDate < :end
            """)
    Double sumAccessoriesProfitBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select coalesce(sum(
                case
                    when lower(coalesce(item.itemCategory, '')) like '%service%'
                      or lower(coalesce(item.itemCategory, '')) like '%washing%'
                      or lower(coalesce(item.itemCategory, '')) like '%labour%'
                      or (
                          item.productInvoiceitem is null
                          and (
                              lower(coalesce(item.description, '')) like '%wash%'
                              or lower(coalesce(item.description, '')) like '%labour%'
                              or lower(coalesce(item.description, '')) like '%service%'
                              or lower(coalesce(item.description, '')) like '%fitting%'
                              or lower(coalesce(item.description, '')) like '%repair%'
                              or lower(coalesce(item.description, '')) like '%polish%'
                              or lower(coalesce(item.description, '')) like '%clean%'
                          )
                      )
                    then 0
                    else coalesce(item.totalPrice, 0)
                end
            ), 0)
            from InvoiceItem item
            join item.invoice invoice
            where invoice.invoiceDate >= :start and invoice.invoiceDate < :end
            """)
    Double sumAccessoriesSaleBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
