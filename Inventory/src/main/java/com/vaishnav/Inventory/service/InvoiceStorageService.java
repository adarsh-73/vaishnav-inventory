package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.Invoice;
import com.vaishnav.Inventory.repository.DailyBookEntryRepository;
import com.vaishnav.Inventory.repository.InvoiceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DatabaseMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InvoiceStorageService {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final int MAX_RETENTION_YEARS = 10;

    private final InvoiceRepository invoiceRepository;
    private final DailyBookEntryRepository dailyBookEntryRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${database.storage.limit.mb:0}")
    private long configuredStorageLimitMb;

    public InvoiceStorageService(InvoiceRepository invoiceRepository,
                                 DailyBookEntryRepository dailyBookEntryRepository,
                                 JdbcTemplate jdbcTemplate) {
        this.invoiceRepository = invoiceRepository;
        this.dailyBookEntryRepository = dailyBookEntryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> storageStats() {
        long invoiceCount = invoiceRepository.count();
        long invoiceItemCount = queryLong("select count(*) from invoice_item");
        long databaseBytes = databaseUsedBytes();
        long billTableBytes = billTableUsedBytes();
        long limitBytes = configuredStorageLimitMb > 0
                ? configuredStorageLimitMb * 1024L * 1024L
                : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceCount", invoiceCount);
        result.put("invoiceItemCount", invoiceItemCount);
        result.put("databaseUsedBytes", databaseBytes);
        result.put("billTablesUsedBytes", billTableBytes);
        result.put("averageBillBytes", invoiceCount == 0 ? 0 : Math.max(1, billTableBytes / invoiceCount));
        result.put("storageLimitBytes", limitBytes > 0 ? limitBytes : null);
        result.put("remainingBytes", limitBytes > 0 ? Math.max(0, limitBytes - databaseBytes) : null);
        result.put("estimatedAdditionalBills", limitBytes > 0 && invoiceCount > 0 && billTableBytes > 0
                ? Math.max(0, (limitBytes - databaseBytes) / Math.max(1, billTableBytes / invoiceCount))
                : null);
        result.put("oldestInvoiceDate", invoiceRepository.findOldestInvoiceDate());
        result.put("newestInvoiceDate", invoiceRepository.findNewestInvoiceDate());
        result.put("note", limitBytes > 0
                ? "Remaining capacity is an estimate based on current average bill size."
                : "Hosting database quota is not configured, so exact remaining capacity is unavailable.");
        return result;
    }

    public Map<String, Object> cleanupPreview(int years) {
        int safeYears = validateYears(years);
        LocalDateTime cutoff = LocalDate.now(INDIA).minusYears(safeYears).atStartOfDay();
        long totalOld = invoiceRepository.countOlderThan(cutoff);
        long protectedUnpaid = invoiceRepository.countUnpaidOlderThan(cutoff);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retentionYears", safeYears);
        result.put("cutoffDate", cutoff.toLocalDate());
        result.put("eligiblePaidBills", Math.max(0, totalOld - protectedUnpaid));
        result.put("protectedUnpaidBills", protectedUnpaid);
        result.put("warning", "Cleanup permanently removes paid bills and their items. Stock quantity is not changed.");
        return result;
    }

    @Transactional
    public Map<String, Object> cleanup(int years, String confirmation) {
        if (!"DELETE OLD BILLS".equals(confirmation)) {
            throw new IllegalArgumentException("Confirmation text must be DELETE OLD BILLS");
        }

        int safeYears = validateYears(years);
        LocalDateTime cutoff = LocalDate.now(INDIA).minusYears(safeYears).atStartOfDay();
        List<Invoice> invoices = invoiceRepository.findPaidInvoicesOlderThan(cutoff);
        long itemCount = invoices.stream()
                .map(Invoice::getInvoiceItems)
                .filter(items -> items != null)
                .mapToLong(List::size)
                .sum();

        // Historical cleanup must not restore stock; current stock already reflects these sales.
        int dailyBookEntriesDeleted = invoices.stream()
                .map(Invoice::getInvoiceNumber)
                .filter(number -> number != null && !number.isBlank())
                .mapToInt(dailyBookEntryRepository::deleteByNoteContainingIgnoreCase)
                .sum();
        invoiceRepository.deleteAll(invoices);
        invoiceRepository.flush();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deletedBills", invoices.size());
        result.put("deletedBillItems", itemCount);
        result.put("deletedDailyBookEntries", dailyBookEntriesDeleted);
        result.put("protectedUnpaidBills", invoiceRepository.countUnpaidOlderThan(cutoff));
        result.put("stockChanged", false);
        result.put("message", "Old paid bills cleaned. Database can reuse the released table space.");
        return result;
    }

    private int validateYears(int years) {
        if (years < 2 || years > MAX_RETENTION_YEARS) {
            throw new IllegalArgumentException("Retention must be between 2 and 10 years");
        }
        return years;
    }

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long databaseUsedBytes() {
        String database = databaseProductName();
        try {
            if (database.contains("postgres")) {
                return queryLong("select pg_database_size(current_database())");
            }

            if (database.contains("mysql") || database.contains("mariadb")) {
                return queryLong("""
                        select coalesce(sum(data_length + index_length), 0)
                        from information_schema.tables
                        where table_schema = database()
                        """);
            }
        } catch (Exception ignored) {
            return 0;
        }

        return 0;
    }

    private long billTableUsedBytes() {
        String database = databaseProductName();
        try {
            if (database.contains("postgres")) {
                return queryLong("""
                        select coalesce(sum(pg_total_relation_size(to_regclass(table_name))), 0)
                        from (values ('invoice'), ('invoice_item')) as tables(table_name)
                        where to_regclass(table_name) is not null
                        """);
            }

            if (database.contains("mysql") || database.contains("mariadb")) {
                return queryLong("""
                        select coalesce(sum(data_length + index_length), 0)
                        from information_schema.tables
                        where table_schema = database()
                          and table_name in ('invoice', 'invoice_item')
                        """);
            }
        } catch (Exception ignored) {
            return 0;
        }

        return 0;
    }

    private String databaseProductName() {
        try {
            return jdbcTemplate.execute((java.sql.Connection connection) -> {
                DatabaseMetaData metadata = connection.getMetaData();
                return metadata.getDatabaseProductName().toLowerCase();
            });
        } catch (Exception ignored) {
            return "";
        }
    }
}
