package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query("""
            select coalesce(sum(coalesce(i.totalAmount, 0)), 0)
            from Invoice i
            where i.invoiceDate >= :start and i.invoiceDate < :end
            """)
    Double sumTotalBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select coalesce(sum(coalesce(i.remainingAmount, 0)), 0)
            from Invoice i
            where i.invoiceDate >= :start and i.invoiceDate < :end
            """)
    Double sumRemainingBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select coalesce(sum(coalesce(i.remainingAmount, 0)), 0)
            from Invoice i
            where i.remainingAmount is not null and i.remainingAmount > 0
            """)
    Double sumRemainingAll();

    @Query("""
            select distinct i from Invoice i
            left join fetch i.invoiceItems
            where i.invoiceDate < :cutoff
              and (i.remainingAmount is null or i.remainingAmount <= 0)
            order by i.invoiceDate
            """)
    List<Invoice> findPaidInvoicesOlderThan(LocalDateTime cutoff);

    @Query("select count(i) from Invoice i where i.invoiceDate < :cutoff")
    long countOlderThan(LocalDateTime cutoff);

    @Query("""
            select count(i) from Invoice i
            where i.invoiceDate < :cutoff
              and i.remainingAmount > 0
            """)
    long countUnpaidOlderThan(LocalDateTime cutoff);

    @Query("select min(i.invoiceDate) from Invoice i")
    LocalDateTime findOldestInvoiceDate();

    @Query("select max(i.invoiceDate) from Invoice i")
    LocalDateTime findNewestInvoiceDate();
}
package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query("""
            select coalesce(sum(coalesce(i.totalAmount, 0)), 0)
            from Invoice i
            where i.invoiceDate >= :start and i.invoiceDate < :end
            """)
    Double sumTotalBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select coalesce(sum(coalesce(i.remainingAmount, 0)), 0)
            from Invoice i
            where i.invoiceDate >= :start and i.invoiceDate < :end
            """)
    Double sumRemainingBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("""
            select distinct i from Invoice i
            left join fetch i.invoiceItems
            where i.invoiceDate < :cutoff
              and (i.remainingAmount is null or i.remainingAmount <= 0)
            order by i.invoiceDate
            """)
    List<Invoice> findPaidInvoicesOlderThan(LocalDateTime cutoff);

    @Query("select count(i) from Invoice i where i.invoiceDate < :cutoff")
    long countOlderThan(LocalDateTime cutoff);

    @Query("""
            select count(i) from Invoice i
            where i.invoiceDate < :cutoff
              and i.remainingAmount > 0
            """)
    long countUnpaidOlderThan(LocalDateTime cutoff);

    @Query("select min(i.invoiceDate) from Invoice i")
    LocalDateTime findOldestInvoiceDate();

    @Query("select max(i.invoiceDate) from Invoice i")
    LocalDateTime findNewestInvoiceDate();
}
