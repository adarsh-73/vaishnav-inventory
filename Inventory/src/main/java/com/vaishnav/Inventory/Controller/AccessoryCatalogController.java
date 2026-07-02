package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.AccessoryCatalogItem;
import com.vaishnav.Inventory.entity.product;
import com.vaishnav.Inventory.service.AccessoryCatalogService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/accessories")
@CrossOrigin(originPatterns = "*")
public class AccessoryCatalogController {

    private final AccessoryCatalogService catalogService;

    public AccessoryCatalogController(AccessoryCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public Page<AccessoryCatalogItem> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String make,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return catalogService.search(q, make, model, brand, category, inStock, page, size);
    }

    @GetMapping("/{id}")
    public AccessoryCatalogItem get(@PathVariable Long id) {
        return catalogService.get(id);
    }

    @PostMapping
    public AccessoryCatalogItem create(@RequestBody AccessoryCatalogItem request) {
        return catalogService.create(request);
    }

    @PutMapping("/{id}")
    public AccessoryCatalogItem update(@PathVariable Long id, @RequestBody AccessoryCatalogItem request) {
        return catalogService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> archive(@PathVariable Long id) {
        catalogService.archive(id);
        return Map.of("archived", true, "id", id);
    }

    @PostMapping("/{id}/add-to-stock")
    public product addToStock(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        int quantity = request.get("quantity") instanceof Number value ? value.intValue() : 1;
        return catalogService.addToStock(id, quantity);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importFile(@RequestParam("file") MultipartFile file) {
        return catalogService.importFile(file);
    }

    @GetMapping(value = "/import-template", produces = "text/csv")
    public ResponseEntity<byte[]> template() {
        byte[] content = catalogService.csvTemplate().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=accessories-catalog-template.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(content);
    }
}
