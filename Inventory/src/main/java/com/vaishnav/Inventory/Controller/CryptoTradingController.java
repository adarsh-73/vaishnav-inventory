package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.CryptoPaperTrade;
import com.vaishnav.Inventory.service.CryptoExchangeService;
import com.vaishnav.Inventory.service.CryptoTradingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/crypto")
@CrossOrigin(originPatterns = "*")
public class CryptoTradingController {

    @Autowired
    private CryptoTradingService cryptoTradingService;

    @Autowired
    private CryptoExchangeService cryptoExchangeService;

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return cryptoTradingService.getDashboard();
    }

    @PostMapping("/paper-scan")
    public List<CryptoPaperTrade> paperScan() {
        return cryptoTradingService.runPaperScan();
    }

    @PostMapping("/close-running")
    public List<CryptoPaperTrade> closeRunning() {
        return cryptoTradingService.closeRunningTrades();
    }

    @PostMapping("/cleanup-bad-data")
    public Map<String, Object> cleanupBadData() {
        return cryptoTradingService.cleanupBadFallbackTrades();
    }

    @GetMapping("/binance/status")
    public Map<String, Object> binanceStatus() {
        return cryptoExchangeService.testnetClientStatus();
    }

    @PostMapping("/binance/connect")
    public Map<String, Object> connectBinance(@RequestBody CryptoExchangeService.BinanceConnectRequest request) {
        return cryptoExchangeService.connect(request);
    }

    @PostMapping("/binance/disconnect")
    public Map<String, Object> disconnectBinance() {
        return cryptoExchangeService.disconnect();
    }
}
