package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.CryptoExchangeConnection;
import com.vaishnav.Inventory.repository.CryptoExchangeConnectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CryptoExchangeService {

    private static final String EXCHANGE = "BINANCE";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private CryptoExchangeConnectionRepository connectionRepository;

    public Map<String, Object> status() {
        return connectionRepository.findFirstByExchangeNameOrderByIdDesc(EXCHANGE)
                .map(this::toStatus)
                .orElseGet(() -> {
                    Map<String, Object> empty = new LinkedHashMap<>();
                    empty.put("connected", false);
                    empty.put("exchangeName", EXCHANGE);
                    empty.put("testnet", true);
                    empty.put("liveTradingLocked", true);
                    empty.put("keyPreview", "");
                    empty.put("vaultMode", vaultMode());
                    empty.put("message", "Binance testnet not connected");
                    return empty;
                });
    }

    public Map<String, Object> connect(BinanceConnectRequest request) {
        if (request == null || isBlank(request.getApiKey()) || isBlank(request.getApiSecret())) {
            throw new RuntimeException("API key aur API secret required hai");
        }
        if (request.getApiKey().length() < 10 || request.getApiSecret().length() < 10) {
            throw new RuntimeException("API key/secret valid length ka nahi lag raha");
        }

        CryptoExchangeConnection connection = connectionRepository
                .findFirstByExchangeNameOrderByIdDesc(EXCHANGE)
                .orElse(new CryptoExchangeConnection());
        connection.setExchangeName(EXCHANGE);
        connection.setEncryptedApiKey(encrypt(request.getApiKey()));
        connection.setEncryptedApiSecret(encrypt(request.getApiSecret()));
        connection.setKeyPreview(maskKey(request.getApiKey()));
        connection.setTestnet(request.getTestnet() == null || request.getTestnet());
        connection.setConnected(true);
        connection.setLiveTradingLocked(true);
        connection.setVaultMode(vaultMode());

        return toStatus(connectionRepository.save(connection));
    }

    public Map<String, Object> disconnect() {
        connectionRepository.findFirstByExchangeNameOrderByIdDesc(EXCHANGE).ifPresent(connection -> {
            connection.setConnected(false);
            connection.setEncryptedApiKey(null);
            connection.setEncryptedApiSecret(null);
            connection.setKeyPreview("");
            connectionRepository.save(connection);
        });
        return status();
    }

    public Map<String, Object> testnetClientStatus() {
        Map<String, Object> status = status();
        boolean connected = Boolean.TRUE.equals(status.get("connected"));
        status.put("testnetClientReady", connected && Boolean.TRUE.equals(status.get("testnet")));
        status.put("orderEngine", connected ? "TESTNET_READY_PAPER_GUARDED" : "NOT_CONNECTED");
        status.put("liveOrders", "LOCKED");
        status.put("note", connected
                ? "Keys encrypted in backend vault. Next step can call Binance testnet signed endpoints."
                : "Connect Binance testnet keys first.");
        return status;
    }

    private Map<String, Object> toStatus(CryptoExchangeConnection connection) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", Boolean.TRUE.equals(connection.getConnected()));
        status.put("exchangeName", connection.getExchangeName());
        status.put("testnet", Boolean.TRUE.equals(connection.getTestnet()));
        status.put("liveTradingLocked", true);
        status.put("keyPreview", connection.getKeyPreview());
        status.put("vaultMode", connection.getVaultMode());
        status.put("updatedAt", connection.getUpdatedAt());
        status.put("message", Boolean.TRUE.equals(connection.getConnected())
                ? "Binance testnet credentials stored in encrypted backend vault"
                : "Binance disconnected");
        return status;
    }

    private String encrypt(String plainText) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(vaultKey(), "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception error) {
            throw new RuntimeException("Vault encryption failed");
        }
    }

    private byte[] vaultKey() {
        try {
            String configured = System.getenv("CRYPTO_VAULT_MASTER_KEY");
            String source = isBlank(configured)
                    ? "vaishnav-local-dev-crypto-vault-key-change-before-live"
                    : configured;
            return MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new RuntimeException("Vault key failed");
        }
    }

    private String vaultMode() {
        return isBlank(System.getenv("CRYPTO_VAULT_MASTER_KEY")) ? "DEV_LOCAL_KEY" : "ENV_MASTER_KEY";
    }

    private String maskKey(String apiKey) {
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class BinanceConnectRequest {
        private String apiKey;
        private String apiSecret;
        private Boolean testnet;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public Boolean getTestnet() {
            return testnet;
        }

        public void setTestnet(Boolean testnet) {
            this.testnet = testnet;
        }
    }
}
