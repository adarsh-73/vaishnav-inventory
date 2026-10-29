package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.product;
import com.vaishnav.Inventory.service.ProductService;
import com.vaishnav.Inventory.service.ScannerBridgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/scanner-bridge")
@CrossOrigin(originPatterns = "*")
public class ScannerBridgeController {

    @Autowired
    private ScannerBridgeService scannerBridgeService;

    @Autowired
    private ProductService productService;

    @PostMapping("/billing/poll")
    public Map<String, Object> pollBilling(@RequestBody BridgeRequest request) {
        return Map.of("active", true, "scans", scannerBridgeService.poll(request.getStationToken()));
    }

    @PostMapping("/billing/close")
    public void closeBilling(@RequestBody BridgeRequest request) {
        scannerBridgeService.close(request.getStationToken());
    }

    @PostMapping("/mobile/scan")
    public Map<String, Object> scanFromPhone(@RequestBody MobileScanRequest request) {
        if (request.getBarcode() == null || request.getBarcode().isBlank()) {
            throw new IllegalArgumentException("Barcode scan nahi hua");
        }
        product productData = productService.getAvailableProductByBarcode(request.getBarcode().trim());
        String stationToken = request.getStationToken();
        boolean billingActive = stationToken != null && !stationToken.isBlank()
                && scannerBridgeService.queueScanIfBillingOpen(stationToken, productData.getBarcode());
        return Map.of("product", productData, "billingActive", billingActive, "addedToBill", billingActive);
    }

    public static class BridgeRequest {
        private String stationToken;
        public String getStationToken() { return stationToken; }
        public void setStationToken(String stationToken) { this.stationToken = stationToken; }
    }

    public static class MobileScanRequest extends BridgeRequest {
        private String barcode;
        public String getBarcode() { return barcode; }
        public void setBarcode(String barcode) { this.barcode = barcode; }
    }
}
