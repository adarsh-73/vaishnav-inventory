package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.repository.DailyBookEntryRepository;
import com.vaishnav.Inventory.repository.InvoiceItemRepository;
import com.vaishnav.Inventory.repository.InvoiceRepository;
import com.vaishnav.Inventory.repository.ProductRepository;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
@CrossOrigin(originPatterns = "*")
public class DashboardController {
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final DailyBookEntryRepository dailyBookEntryRepository;
    private final ProductRepository productRepository;

    public DashboardController(InvoiceRepository invoiceRepository,
                               InvoiceItemRepository invoiceItemRepository,
                               DailyBookEntryRepository dailyBookEntryRepository,
                               ProductRepository productRepository) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceItemRepository = invoiceItemRepository;
        this.dailyBookEntryRepository = dailyBookEntryRepository;
        this.productRepository = productRepository;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        LocalDate monthStart = LocalDate.now(ZoneId.of("Asia/Kolkata")).withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1);
        LocalDateTime start = monthStart.atStartOfDay();
        LocalDateTime end = monthEnd.atStartOfDay();

        double invoiceProfit = value(invoiceItemRepository.sumProfitBetween(start, end));
        double invoiceSales = value(invoiceItemRepository.sumSaleBetween(start, end));
        double paidExpense = value(dailyBookEntryRepository.sumPaidExpenseBetween(monthStart, monthEnd));
        double udhar = value(invoiceRepository.sumRemainingBetween(start, end))
                + value(dailyBookEntryRepository.sumManualUdharBetween(monthStart, monthEnd));
        double invoiceTotal = value(invoiceRepository.sumTotalBetween(start, end));
        double netProfit = invoiceProfit - paidExpense;

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("washing", 0);
        totals.put("accessories", invoiceSales);
        totals.put("accessoriesProfit", invoiceProfit);
        totals.put("oldAccessoriesProfit", 0);
        totals.put("expense", paidExpense);
        totals.put("udhar", udhar);
        totals.put("invoiceTotal", invoiceTotal);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totals", totals);
        response.put("grossProfit", invoiceProfit);
        response.put("netProfit", netProfit);
        response.put("invoiceTotal", invoiceTotal);
        response.put("stockValue", value(productRepository.sumStockSaleValue()));
        response.put("stockCost", value(productRepository.sumStockCostValue()));
        response.put("totalProducts", productRepository.count());
        response.put("productsInStock", value(productRepository.countProductsInStock()));
        response.put("lowStockCount", value(productRepository.countLowStockProducts()));
        return response;
    }

    private double value(Number number) {
        return number == null ? 0 : number.doubleValue();
    }
}
