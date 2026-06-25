package com.vaishnav.Inventory.service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaishnav.Inventory.entity.Invoice;
import com.vaishnav.Inventory.entity.InvoiceItem;
import com.vaishnav.Inventory.entity.StockHistory;
import com.vaishnav.Inventory.entity.product;
import com.vaishnav.Inventory.entity.Customer;
import com.vaishnav.Inventory.entity.DailyBookEntry;
import com.vaishnav.Inventory.repository.CustomerRepository;
import com.vaishnav.Inventory.repository.DailyBookEntryRepository;
import com.vaishnav.Inventory.repository.InvoiceItemRepository;
import com.vaishnav.Inventory.repository.InvoiceRepository;
import com.vaishnav.Inventory.repository.ProductRepository;
import com.vaishnav.Inventory.repository.StockHistoryRepository;

@Service
public class InvoiceService {

    private static final String DIRECT_ADD_SELL_LOCATION = "DIRECT_ADD_AND_SELL";

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository; // ✅ add this

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private DailyBookEntryRepository dailyBookEntryRepository;

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Transactional
    public Invoice createInvoice(Invoice invoice) {
        if (invoice.getInvoiceNumber() != null && !invoice.getInvoiceNumber().isBlank()) {
            java.util.List<Invoice> existingInvoices = invoiceRepository.findByInvoiceNumber(invoice.getInvoiceNumber());
            if (!existingInvoices.isEmpty()) {
                Invoice latestInvoice = existingInvoices.stream()
                        .max(java.util.Comparator.comparing(Invoice::getId))
                        .orElse(existingInvoices.get(0));
                return updateInvoice(latestInvoice.getId(), invoice);
            }
        }

        return saveInvoice(invoice, null);
    }

    @Transactional
    public Invoice updateInvoice(Long id, Invoice invoice) {
        Invoice existing = invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        restoreStock(existing, "Stock restored before editing Invoice " + existing.getInvoiceNumber());
        cleanupDirectSaleProducts(existing, "Direct add stock removed before editing Invoice " + existing.getInvoiceNumber());
        if (existing.getInvoiceItems() != null) {
            existing.getInvoiceItems().clear();
        }

        existing.setInvoiceNumber(invoice.getInvoiceNumber());
        existing.setInvoiceDate(invoice.getInvoiceDate());
        existing.setPaidAmount(invoice.getPaidAmount());
        existing.setRemainingAmount(invoice.getRemainingAmount());
        existing.setPaymentMethod(invoice.getPaymentMethod());
        existing.setBusinessCategory(invoice.getBusinessCategory());
        existing.setTotalAmount(invoice.getTotalAmount());
        existing.setDiscountAmount(invoice.getDiscountAmount());
        existing.setDiscountNote(invoice.getDiscountNote());
        existing.setCustomer(resolveCustomer(invoice.getCustomer()));
        if (existing.getInvoiceItems() == null) {
            existing.setInvoiceItems(new java.util.ArrayList<>());
        }
        if (invoice.getInvoiceItems() != null) {
            existing.getInvoiceItems().addAll(invoice.getInvoiceItems());
        }

        return saveInvoice(existing, id);
    }

    @Transactional
    public void deleteInvoice(Long id) {
        Invoice existing = invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        java.util.List<Invoice> invoicesToDelete = existing.getInvoiceNumber() != null && !existing.getInvoiceNumber().isBlank()
                ? invoiceRepository.findByInvoiceNumber(existing.getInvoiceNumber())
                : java.util.List.of(existing);

        if (existing.getInvoiceNumber() != null && !existing.getInvoiceNumber().isBlank()) {
            dailyBookEntryRepository.findFirstByNoteContainingIgnoreCase(existing.getInvoiceNumber())
                    .ifPresent(dailyBookEntryRepository::delete);
        }

        for (Invoice invoiceToDelete : invoicesToDelete) {
            restoreStock(invoiceToDelete, "Stock restored after deleting Invoice " + invoiceToDelete.getInvoiceNumber());
            cleanupDirectSaleProducts(invoiceToDelete, "Direct add stock deleted with Invoice " + invoiceToDelete.getInvoiceNumber());
            invoiceRepository.delete(invoiceToDelete);
        }
    }

    @Transactional
    public Invoice markInvoicePaid(Long id) {
        Invoice existing = invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        double totalAmount = existing.getTotalAmount() == null ? 0 : existing.getTotalAmount();
        existing.setPaidAmount(totalAmount);
        existing.setRemainingAmount(0.0);
        existing.setPaymentMethod(existing.getPaymentMethod() == null ? "CASH/UPI" : existing.getPaymentMethod());

        Invoice savedInvoice = invoiceRepository.save(existing);

        if (existing.getInvoiceNumber() != null && !existing.getInvoiceNumber().isBlank()) {
            DailyBookEntry entry = dailyBookEntryRepository
                    .findFirstByNoteContainingIgnoreCase(existing.getInvoiceNumber())
                    .orElse(new DailyBookEntry());
            entry.setEntryType("income");
            entry.setIncomeCategory(normalizeIncomeCategory(existing.getBusinessCategory(), existing.getInvoiceItems()));
            entry.setPartyName(existing.getCustomer() != null ? existing.getCustomer().getCustomerName() : "Walk-in Customer");
            entry.setNote("Invoice " + existing.getInvoiceNumber() + " (Udhar paid)");
            entry.setAmount(totalAmount);
            entry.setPaymentStatus("paid");
            dailyBookEntryRepository.save(entry);
        }

        return savedInvoice;
    }

    @Transactional
    public Invoice returnInvoiceItems(Long id, ReturnRequest request) {
        Invoice existing = invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        if (existing.getInvoiceItems() == null || existing.getInvoiceItems().isEmpty()) {
            throw new RuntimeException("Is bill me return karne ke liye item nahi hai");
        }

        java.util.Map<Long, Integer> returnQtyByItemId = new java.util.HashMap<>();
        if (request != null && request.getItems() != null) {
            for (ReturnItemRequest itemRequest : request.getItems()) {
                if (itemRequest.getInvoiceItemId() != null && itemRequest.getQuantity() != null && itemRequest.getQuantity() > 0) {
                    returnQtyByItemId.put(itemRequest.getInvoiceItemId(), itemRequest.getQuantity());
                }
            }
        }

        if (returnQtyByItemId.isEmpty()) {
            throw new RuntimeException("Return quantity select karo");
        }

        String reason = request != null && request.getReason() != null && !request.getReason().isBlank()
                ? request.getReason()
                : "Customer returned item";
        java.time.LocalDateTime returnDate = java.time.LocalDateTime.now();

        for (InvoiceItem item : existing.getInvoiceItems()) {
            Integer requestedQty = returnQtyByItemId.get(item.getId());
            if (requestedQty == null) continue;

            int soldQuantity = item.getQuantity() == null ? 0 : item.getQuantity();
            int alreadyReturned = item.getReturnedQuantity() == null ? 0 : item.getReturnedQuantity();
            int returnableQuantity = soldQuantity - alreadyReturned;

            if (requestedQty > returnableQuantity) {
                throw new RuntimeException("Return quantity zyada hai: " + (item.getDescription() == null ? "Item" : item.getDescription()));
            }

            item.setReturnedQuantity(alreadyReturned + requestedQty);
            item.setReturnNote(reason);
            item.setReturnDate(returnDate);

            product productObj = item.getProductInvoiceitem();
            if (productObj != null && productObj.getId() != null) {
                product savedProduct = productRepository.findById(productObj.getId())
                        .orElseThrow(() -> new RuntimeException("Product not found"));
                savedProduct.setQuantity((savedProduct.getQuantity() == null ? 0 : savedProduct.getQuantity()) + requestedQty);
                productRepository.save(savedProduct);

                StockHistory history = new StockHistory();
                history.setProducthistory(savedProduct);
                history.setQuantity(requestedQty);
                history.setStockType("IN");
                history.setNote("Customer return via Invoice " + existing.getInvoiceNumber() + " | " + reason + " | " + productIdentity(savedProduct));
                stockHistoryRepository.save(history);
            }
        }

        recalculateInvoiceAfterReturn(existing);
        existing.setReturnNote(reason);
        existing.setReturnDate(returnDate);

        boolean allReturned = existing.getInvoiceItems().stream().allMatch(item -> {
            int soldQuantity = item.getQuantity() == null ? 0 : item.getQuantity();
            int returnedQuantity = item.getReturnedQuantity() == null ? 0 : item.getReturnedQuantity();
            return soldQuantity > 0 && returnedQuantity >= soldQuantity;
        });
        boolean hasReturn = existing.getInvoiceItems().stream().anyMatch(item -> (item.getReturnedQuantity() == null ? 0 : item.getReturnedQuantity()) > 0);
        existing.setInvoiceStatus(allReturned ? "CANCELLED_RETURNED" : hasReturn ? "PARTIAL_RETURN" : "ACTIVE");

        Invoice savedInvoice = invoiceRepository.save(existing);
        updateDailyBookForReturnedInvoice(savedInvoice);
        return savedInvoice;
    }

    private Invoice saveInvoice(Invoice invoice, Long updatingId) {

        double subtotalAmount = invoice.getTotalAmount() != null ? invoice.getTotalAmount() : 0;

        invoice.setCustomer(resolveCustomer(invoice.getCustomer()));

        if (invoice.getInvoiceItems() != null && !invoice.getInvoiceItems().isEmpty()) {
            subtotalAmount = 0;
        }

        if (invoice.getInvoiceItems() != null) for (InvoiceItem item : invoice.getInvoiceItems()) {
            product productobj = resolveInvoiceProduct(item);
            if (item.getReturnedQuantity() == null) item.setReturnedQuantity(0);

            int soldQuantity = item.getQuantity() == null ? 0 : item.getQuantity();
            if (Boolean.TRUE.equals(item.getAutoCreateProduct())) {
                productobj = null;
                item.setProductInvoiceitem(null);
            }

            if (productobj != null) {
                int availableQuantity = productobj.getQuantity() == null ? 0 : productobj.getQuantity();

                if (availableQuantity < soldQuantity) {
                    throw new RuntimeException("Insufficient stock for " + productobj.getProductName());
                }

                productobj.setQuantity(availableQuantity - soldQuantity);
                item.setProductInvoiceitem(productobj);

                if (item.getDescription() == null || item.getDescription().isBlank()) {
                    item.setDescription(productobj.getProductName());
                }

                if (item.getSellPrice() == null) {
                    item.setSellPrice(productobj.getSellPrice());
                }

                productRepository.save(productobj);

                StockHistory history = new StockHistory();
                history.setProducthistory(productobj);
                history.setQuantity(soldQuantity);
                history.setStockType("OUT");
                history.setNote((updatingId == null ? "Product sold via Invoice " : "Product adjusted via edited Invoice ")
                        + invoice.getInvoiceNumber() + " | " + productIdentity(productobj));

                stockHistoryRepository.save(history);
            }

            double sellPrice = item.getSellPrice() != null ? item.getSellPrice() : 0;
            double itemTotal = (item.getQuantity() == null ? 0 : item.getQuantity()) * sellPrice;
            item.setTotalPrice(itemTotal);
            double purchasePrice = item.getPurchasePrice() == null ? 0 : item.getPurchasePrice();
            item.setProfit((sellPrice - purchasePrice) * (item.getQuantity() == null ? 0 : item.getQuantity()));

            subtotalAmount += itemTotal;

            item.setInvoice(invoice);
        }

        double discountAmount = Math.max(0, invoice.getDiscountAmount() == null ? 0 : invoice.getDiscountAmount());
        discountAmount = Math.min(discountAmount, subtotalAmount);
        double totalAmount = Math.max(0, subtotalAmount - discountAmount);
        invoice.setDiscountAmount(discountAmount);
        invoice.setTotalAmount(totalAmount);
        double paidAmount = Math.max(0, invoice.getPaidAmount() == null ? totalAmount : invoice.getPaidAmount());
        paidAmount = Math.min(paidAmount, totalAmount);
        invoice.setPaidAmount(paidAmount);
        invoice.setRemainingAmount(Math.max(0, totalAmount - paidAmount));

        Invoice savedInvoice = invoiceRepository.save(invoice);

        DailyBookEntry entry = dailyBookEntryRepository
                .findFirstByNoteContainingIgnoreCase(invoice.getInvoiceNumber())
                .orElse(new DailyBookEntry());
        entry.setEntryType("income");
        entry.setIncomeCategory(normalizeIncomeCategory(invoice.getBusinessCategory(), invoice.getInvoiceItems()));
        entry.setPartyName(invoice.getCustomer() != null ? invoice.getCustomer().getCustomerName() : "Walk-in Customer");
        entry.setNote((updatingId == null ? "Invoice " : "Edited Invoice ") + invoice.getInvoiceNumber());
        entry.setAmount(totalAmount);
        entry.setPaymentStatus(invoice.getRemainingAmount() != null && invoice.getRemainingAmount() > 0 ? "udhar" : "paid");
        dailyBookEntryRepository.save(entry);

        return savedInvoice;   // ✅ LAST में ही return
    }

    private product resolveInvoiceProduct(InvoiceItem item) {
        if (item.getProductInvoiceitem() != null && item.getProductInvoiceitem().getId() != null) {
            return productRepository.findById(item.getProductInvoiceitem().getId())
                    .orElseThrow(() -> new RuntimeException("Product not found"));
        }

        return null;
    }

    private Customer resolveCustomer(Customer incomingCustomer) {
        if (incomingCustomer == null) return null;

        Customer savedCustomer = null;
        if (incomingCustomer.getMobileNumber() != null && !incomingCustomer.getMobileNumber().isBlank()) {
            savedCustomer = customerRepository.findByMobileNumber(incomingCustomer.getMobileNumber());
        }

        if (savedCustomer == null) {
            return customerRepository.save(incomingCustomer);
        }

        if (incomingCustomer.getCustomerName() != null && !incomingCustomer.getCustomerName().isBlank()) {
            savedCustomer.setCustomerName(incomingCustomer.getCustomerName());
        }

        return customerRepository.save(savedCustomer);
    }

    private void restoreStock(Invoice invoice, String note) {
        if (invoice.getInvoiceItems() == null) return;

        for (InvoiceItem oldItem : invoice.getInvoiceItems()) {
            if (Boolean.TRUE.equals(oldItem.getAutoCreateProduct())) continue;
            product oldProduct = oldItem.getProductInvoiceitem();
            if (oldProduct != null && oldProduct.getId() != null) {
                product productObj = productRepository.findById(oldProduct.getId())
                        .orElse(null);
                if (productObj != null) {
                    int soldQuantity = oldItem.getQuantity() == null ? 0 : oldItem.getQuantity();
                    int returnedQuantity = oldItem.getReturnedQuantity() == null ? 0 : oldItem.getReturnedQuantity();
                    int restoredQuantity = Math.max(0, soldQuantity - returnedQuantity);
                    if (restoredQuantity == 0) continue;
                    productObj.setQuantity((productObj.getQuantity() == null ? 0 : productObj.getQuantity())
                            + restoredQuantity);
                    productRepository.save(productObj);

                    StockHistory history = new StockHistory();
                    history.setProducthistory(productObj);
                    history.setQuantity(restoredQuantity);
                    history.setStockType("IN");
                    history.setNote(note);
                    stockHistoryRepository.save(history);
                }
            }
        }
    }

    private void cleanupDirectSaleProducts(Invoice invoice, String note) {
        if (invoice.getInvoiceItems() == null) return;

        for (InvoiceItem oldItem : invoice.getInvoiceItems()) {
            if (!Boolean.TRUE.equals(oldItem.getAutoCreateProduct())) continue;
            product directProduct = oldItem.getProductInvoiceitem();
            if (directProduct == null || directProduct.getId() == null) continue;

            product savedProduct = productRepository.findById(directProduct.getId()).orElse(null);
            if (savedProduct == null) continue;

            int soldQuantity = oldItem.getQuantity() == null ? 0 : oldItem.getQuantity();
            int returnedQuantity = oldItem.getReturnedQuantity() == null ? 0 : oldItem.getReturnedQuantity();
            int directQuantityToRemove = Math.max(0, soldQuantity - returnedQuantity);
            if (directQuantityToRemove > 0) {
                StockHistory history = new StockHistory();
                history.setProducthistory(savedProduct);
                history.setQuantity(directQuantityToRemove);
                history.setStockType("DIRECT_DELETE");
                history.setNote(note + " | " + productIdentity(savedProduct));
                stockHistoryRepository.save(history);
            }

            java.util.List<InvoiceItem> linkedItems = invoiceItemRepository.findByProductInvoiceitem(savedProduct);
            boolean linkedOnlyToThisBill = linkedItems.stream()
                    .allMatch(linked -> linked.getInvoice() != null && linked.getInvoice().getId() != null
                            && linked.getInvoice().getId().equals(invoice.getId()));

            boolean markedDirect = DIRECT_ADD_SELL_LOCATION.equals(savedProduct.getProductLocation())
                    || Boolean.TRUE.equals(oldItem.getAutoCreateProduct());

            if (markedDirect && linkedOnlyToThisBill) {
                java.util.List<StockHistory> histories = stockHistoryRepository.findByProducthistory(savedProduct);
                for (StockHistory history : histories) {
                    history.setProducthistory(null);
                }
                stockHistoryRepository.saveAll(histories);

                for (InvoiceItem linked : linkedItems) {
                    if (linked.getDescription() == null || linked.getDescription().isBlank()) {
                        linked.setDescription(savedProduct.getProductName());
                    }
                    linked.setProductInvoiceitem(null);
                }
                invoiceItemRepository.saveAll(linkedItems);
                productRepository.delete(savedProduct);
            } else if (markedDirect) {
                savedProduct.setQuantity(Math.max(0, (savedProduct.getQuantity() == null ? 0 : savedProduct.getQuantity()) - directQuantityToRemove));
                productRepository.save(savedProduct);
            }
        }
    }

    private String normalizeIncomeCategory(String businessCategory, java.util.List<InvoiceItem> items) {
        String source = businessCategory == null ? "" : businessCategory.toLowerCase();
        if (source.contains("mixed")) return "mixed";
        if (isServiceText(source)) return "washing";

        if (items != null) {
            boolean hasService = items.stream()
                    .anyMatch(this::isServiceItem);
            boolean hasAccessories = items.stream()
                    .anyMatch(item -> !isServiceItem(item));
            if (hasService && hasAccessories) return "mixed";
            if (hasService) return "washing";
        }

        return "accessories";
    }

    private boolean isServiceItem(InvoiceItem item) {
        if (item == null) return false;
        String itemCategory = item.getItemCategory() == null ? "" : item.getItemCategory().toLowerCase();
        if (itemCategory.contains("service") || itemCategory.contains("washing") || itemCategory.contains("labour")) return true;
        if (itemCategory.contains("accessor") || itemCategory.contains("inventory")) return false;
        return item.getProductInvoiceitem() == null && isServiceText(item.getDescription());
    }

    private boolean isServiceText(String text) {
        String source = text == null ? "" : text.toLowerCase();
        return source.contains("wash")
                || source.contains("washing")
                || source.contains("foam")
                || source.contains("vacuum")
                || source.contains("polish")
                || source.contains("detailing")
                || source.contains("labour")
                || source.contains("labor")
                || source.contains("service")
                || source.contains("work")
                || source.contains("fitting")
                || source.contains("feeting")
                || source.contains("fitment")
                || source.contains("install")
                || source.contains("repair")
                || source.contains("gas cutting")
                || source.contains("cutting")
                || source.contains("welding")
                || source.contains("seat")
                || source.contains("seating")
                || source.contains("clean")
                || source.contains("rubbing")
                || source.contains("coating")
                || source.contains("lamination")
                || source.contains("laminate")
                || source.contains("denting")
                || source.contains("painting");
    }

    private String productIdentity(product productObj) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (productObj.getProductName() != null && !productObj.getProductName().isBlank()) parts.add(productObj.getProductName());
        if (productObj.getMake() != null && !productObj.getMake().isBlank()) parts.add(productObj.getMake());
        if (productObj.getModel() != null && !productObj.getModel().isBlank()) parts.add(productObj.getModel());
        if (productObj.getSerialNumber() != null && !productObj.getSerialNumber().isBlank()) parts.add("SR " + productObj.getSerialNumber());
        if (productObj.getBarcode() != null && !productObj.getBarcode().isBlank()) parts.add("Barcode " + productObj.getBarcode());
        parts.add("ID " + productObj.getId());
        return String.join(" | ", parts);
    }

    private void recalculateInvoiceAfterReturn(Invoice invoice) {
        double totalAmount = 0;

        if (invoice.getInvoiceItems() != null) {
            for (InvoiceItem item : invoice.getInvoiceItems()) {
                int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
                int returnedQuantity = item.getReturnedQuantity() == null ? 0 : item.getReturnedQuantity();
                int activeQuantity = Math.max(0, quantity - returnedQuantity);
                double sellPrice = item.getSellPrice() == null ? 0 : item.getSellPrice();
                double activeTotal = activeQuantity * sellPrice;
                item.setTotalPrice(activeTotal);
                totalAmount += activeTotal;
            }
        }

        double discountAmount = Math.max(0, invoice.getDiscountAmount() == null ? 0 : invoice.getDiscountAmount());
        discountAmount = Math.min(discountAmount, totalAmount);
        totalAmount = Math.max(0, totalAmount - discountAmount);
        invoice.setDiscountAmount(discountAmount);
        invoice.setTotalAmount(totalAmount);
        double paidAmount = invoice.getPaidAmount() == null ? 0 : invoice.getPaidAmount();
        invoice.setPaidAmount(Math.min(paidAmount, totalAmount));
        invoice.setRemainingAmount(Math.max(0, totalAmount - invoice.getPaidAmount()));
    }

    private void updateDailyBookForReturnedInvoice(Invoice invoice) {
        if (invoice.getInvoiceNumber() == null || invoice.getInvoiceNumber().isBlank()) return;

        DailyBookEntry entry = dailyBookEntryRepository
                .findFirstByNoteContainingIgnoreCase(invoice.getInvoiceNumber())
                .orElse(new DailyBookEntry());
        entry.setEntryType("income");
        entry.setIncomeCategory(normalizeIncomeCategory(invoice.getBusinessCategory(), invoice.getInvoiceItems()));
        entry.setPartyName(invoice.getCustomer() != null ? invoice.getCustomer().getCustomerName() : "Walk-in Customer");
        entry.setNote("Returned/Adjusted Invoice " + invoice.getInvoiceNumber());
        entry.setAmount(invoice.getTotalAmount() == null ? 0 : invoice.getTotalAmount());
        entry.setPaymentStatus(invoice.getRemainingAmount() != null && invoice.getRemainingAmount() > 0 ? "udhar" : "paid");
        dailyBookEntryRepository.save(entry);
    }

    public static class ReturnRequest {
        private String reason;
        private java.util.List<ReturnItemRequest> items;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public java.util.List<ReturnItemRequest> getItems() {
            return items;
        }

        public void setItems(java.util.List<ReturnItemRequest> items) {
            this.items = items;
        }
    }

    public static class ReturnItemRequest {
        private Long invoiceItemId;
        private Integer quantity;

        public Long getInvoiceItemId() {
            return invoiceItemId;
        }

        public void setInvoiceItemId(Long invoiceItemId) {
            this.invoiceItemId = invoiceItemId;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }
}
