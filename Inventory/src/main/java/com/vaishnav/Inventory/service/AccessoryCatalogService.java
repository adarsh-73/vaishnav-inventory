package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.AccessoryCatalogItem;
import com.vaishnav.Inventory.entity.AccessoryVehicleFitment;
import com.vaishnav.Inventory.entity.product;
import com.vaishnav.Inventory.repository.AccessoryCatalogRepository;
import com.vaishnav.Inventory.repository.ProductRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AccessoryCatalogService {

    private static final List<String> IMPORT_HEADERS = List.of(
            "Name", "Local Name", "Brand", "Category", "Part Type", "HSN Code",
            "OEM Part Number", "Aftermarket Part Number", "Source URL", "Verification Status",
            "Wholesale Price", "Retail Price", "Bargaining Price", "Stock Quantity",
            "Minimum Stock", "Barcode", "Supplier", "Supplier Phone", "Photo URL",
            "Fitments", "Notes"
    );

    private final AccessoryCatalogRepository catalogRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    public AccessoryCatalogService(AccessoryCatalogRepository catalogRepository,
                                   ProductRepository productRepository,
                                   ProductService productService) {
        this.catalogRepository = catalogRepository;
        this.productRepository = productRepository;
        this.productService = productService;
    }

    public Page<AccessoryCatalogItem> search(String query, String make, String model, String brand,
                                             String category, Boolean inStock, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        Specification<AccessoryCatalogItem> specification = buildSpecification(query, make, model, brand, category, inStock);
        return catalogRepository.findAll(specification,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.ASC, "name")));
    }

    public AccessoryCatalogItem get(Long id) {
        return catalogRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Catalog item nahi mila"));
    }

    @Transactional
    public AccessoryCatalogItem create(AccessoryCatalogItem request) {
        validate(request, null);
        normalize(request);
        List<AccessoryVehicleFitment> fitments = request.getFitments() == null
                ? new ArrayList<>()
                : new ArrayList<>(request.getFitments());
        request.setFitments(new ArrayList<>());
        AccessoryCatalogItem saved = catalogRepository.save(request);
        ensureCodes(saved);
        saved.replaceFitments(fitments);
        return catalogRepository.save(saved);
    }

    @Transactional
    public AccessoryCatalogItem update(Long id, AccessoryCatalogItem request) {
        AccessoryCatalogItem existing = get(id);
        validate(request, id);

        existing.setName(request.getName());
        existing.setLocalName(request.getLocalName());
        existing.setBrand(request.getBrand());
        existing.setCategory(request.getCategory());
        existing.setPartType(request.getPartType());
        existing.setOemPartNumber(request.getOemPartNumber());
        existing.setAftermarketPartNumber(request.getAftermarketPartNumber());
        existing.setHsnCode(request.getHsnCode());
        existing.setSupplier(request.getSupplier());
        existing.setSupplierPhone(request.getSupplierPhone());
        existing.setWholesalePrice(request.getWholesalePrice());
        existing.setRetailPrice(request.getRetailPrice());
        existing.setBargainingPrice(request.getBargainingPrice());
        existing.setStockQuantity(request.getStockQuantity());
        existing.setMinimumStock(request.getMinimumStock());
        existing.setPhotoUrl(request.getPhotoUrl());
        existing.setSourceUrl(request.getSourceUrl());
        existing.setVerificationStatus(request.getVerificationStatus());
        existing.setSourceCheckedAt(request.getSourceCheckedAt());
        existing.setNotes(request.getNotes());
        existing.setActive(request.getActive() == null ? existing.getActive() : request.getActive());
        if (hasText(request.getSku())) existing.setSku(request.getSku().trim());
        if (hasText(request.getBarcode())) existing.setBarcode(request.getBarcode().trim());
        existing.replaceFitments(request.getFitments());
        normalize(existing);
        ensureCodes(existing);
        return catalogRepository.save(existing);
    }

    @Transactional
    public void archive(Long id) {
        AccessoryCatalogItem item = get(id);
        item.setActive(false);
        catalogRepository.save(item);
    }

    @Transactional
    public product addToStock(Long id, int quantity) {
        if (quantity <= 0) throw new RuntimeException("Quantity 1 ya usse zyada honi chahiye");
        AccessoryCatalogItem item = get(id);
        product stockProduct = hasText(item.getBarcode())
                ? productRepository.findByBarcode(item.getBarcode()).orElse(null)
                : null;

        if (stockProduct == null) {
            stockProduct = new product();
            stockProduct.setProductName(item.getName());
            stockProduct.setBrand(item.getBrand());
            stockProduct.setMake(firstFitmentValue(item, true));
            stockProduct.setModel(firstFitmentValue(item, false));
            stockProduct.setCategory(item.getCategory());
            stockProduct.setBarcode(item.getBarcode());
            stockProduct.setSerialNumber(item.getSku());
            stockProduct.setQuantity(quantity);
            stockProduct.setMinimumStock(Math.max(value(item.getMinimumStock()), 1));
            stockProduct.setPurchasePrice(value(item.getWholesalePrice()));
            stockProduct.setSellPrice(value(item.getRetailPrice()));
            stockProduct.setProductLocation("ACCESSORIES_CATALOG");
            stockProduct.setDescription(buildStockDescription(item));
            stockProduct = productService.addProduct(stockProduct);
        } else {
            stockProduct.setQuantity(value(stockProduct.getQuantity()) + quantity);
            stockProduct.setPurchasePrice(value(item.getWholesalePrice()));
            stockProduct.setSellPrice(value(item.getRetailPrice()));
            stockProduct = productRepository.save(stockProduct);
        }

        item.setStockQuantity(value(item.getStockQuantity()) + quantity);
        catalogRepository.save(item);
        return stockProduct;
    }

    @Transactional
    public Map<String, Object> importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new RuntimeException("Import file select karo");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        try {
            List<Map<String, String>> rows = filename.endsWith(".csv") ? readCsv(file) : readWorkbook(file);
            int created = 0;
            int updated = 0;
            List<String> errors = new ArrayList<>();

            for (int index = 0; index < rows.size(); index++) {
                Map<String, String> row = rows.get(index);
                try {
                    String name = text(row, "name");
                    if (!hasText(name)) continue;
                    String barcode = text(row, "barcode");
                    AccessoryCatalogItem request = new AccessoryCatalogItem();
                    applyImportRow(request, row);
                    Optional<AccessoryCatalogItem> existing = hasText(barcode)
                            ? catalogRepository.findByBarcodeIgnoreCase(barcode)
                            : Optional.empty();
                    boolean isNew = existing.isEmpty();
                    if (isNew) create(request); else update(existing.get().getId(), request);
                    if (isNew) created++; else updated++;
                } catch (Exception rowError) {
                    if (errors.size() < 25) errors.add("Row " + (index + 2) + ": " + rowError.getMessage());
                }
            }

            return Map.of(
                    "rowsRead", rows.size(),
                    "created", created,
                    "updated", updated,
                    "failed", errors.size(),
                    "errors", errors
            );
        } catch (Exception error) {
            throw new RuntimeException("Import read nahi hua: " + error.getMessage(), error);
        }
    }

    public String csvTemplate() {
        return String.join(",", IMPORT_HEADERS) + "\n"
                + "\"Fogg Light\",\"Fog light\",\"Uno Minda\",\"Lighting\",\"Aftermarket\",\"851220\","
                + "\"\",\"VA-AFT-001\",\"https://supplier.example/item\",\"SUPPLIER_VERIFIED\","
                + "\"900\",\"1450\",\"1250\",\"0\",\"1\",\"\",\"Vaishnav Supplier\",\"\","
                + "\"https://example.com/photo.jpg\",\"Mahindra|Bolero|All|2016|2026;Mahindra|Scorpio|S3|2018|2022\","
                + "\"Verified supplier price\"\n";
    }

    private Specification<AccessoryCatalogItem> buildSpecification(String query, String make, String model,
                                                                    String brand, String category, Boolean inStock) {
        return (root, criteriaQuery, builder) -> {
            criteriaQuery.distinct(true);
            Join<AccessoryCatalogItem, AccessoryVehicleFitment> fitment = root.join("fitments", JoinType.LEFT);
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.isTrue(root.get("active")));

            if (hasText(query)) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), like),
                        builder.like(builder.lower(root.get("localName")), like),
                        builder.like(builder.lower(root.get("sku")), like),
                        builder.like(builder.lower(root.get("barcode")), like),
                        builder.like(builder.lower(root.get("brand")), like),
                        builder.like(builder.lower(root.get("category")), like),
                        builder.like(builder.lower(root.get("partType")), like),
                        builder.like(builder.lower(root.get("oemPartNumber")), like),
                        builder.like(builder.lower(root.get("aftermarketPartNumber")), like),
                        builder.like(builder.lower(root.get("hsnCode")), like),
                        builder.like(builder.lower(root.get("supplier")), like),
                        builder.like(builder.lower(fitment.get("make")), like),
                        builder.like(builder.lower(fitment.get("model")), like),
                        builder.like(builder.lower(fitment.get("variant")), like)
                ));
            }
            addLike(predicates, builder, fitment.get("make"), make);
            addLike(predicates, builder, fitment.get("model"), model);
            addLike(predicates, builder, root.get("brand"), brand);
            addLike(predicates, builder, root.get("category"), category);
            if (Boolean.TRUE.equals(inStock)) predicates.add(builder.greaterThan(root.get("stockQuantity"), 0));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void addLike(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder builder,
                         jakarta.persistence.criteria.Path<String> path, String value) {
        if (hasText(value)) {
            predicates.add(builder.like(builder.lower(path), "%" + value.trim().toLowerCase(Locale.ROOT) + "%"));
        }
    }

    private void validate(AccessoryCatalogItem item, Long currentId) {
        if (item == null || !hasText(item.getName())) throw new RuntimeException("Product name zaroori hai");
        long id = currentId == null ? -1L : currentId;
        if (hasText(item.getBarcode()) && catalogRepository.existsByBarcodeIgnoreCaseAndIdNot(item.getBarcode().trim(), id)) {
            throw new RuntimeException("Barcode already catalog me hai");
        }
        if (hasText(item.getSku()) && catalogRepository.existsBySkuIgnoreCaseAndIdNot(item.getSku().trim(), id)) {
            throw new RuntimeException("SKU already catalog me hai");
        }
        if (value(item.getRetailPrice()) < 0 || value(item.getWholesalePrice()) < 0) {
            throw new RuntimeException("Price negative nahi ho sakta");
        }
    }

    private void normalize(AccessoryCatalogItem item) {
        item.setName(item.getName().trim());
        item.setSku(clean(item.getSku()));
        item.setBarcode(clean(item.getBarcode()));
        item.setStockQuantity(Math.max(value(item.getStockQuantity()), 0));
        item.setMinimumStock(Math.max(value(item.getMinimumStock()), 0));
        if (!hasText(item.getVerificationStatus())) item.setVerificationStatus("PRICE_AND_FITMENT_VERIFY");
        if (item.getActive() == null) item.setActive(true);
    }

    private void ensureCodes(AccessoryCatalogItem item) {
        String code = "AC-" + String.format("%07d", item.getId());
        if (!hasText(item.getSku())) item.setSku(code);
        if (!hasText(item.getBarcode())) item.setBarcode(code);
    }

    private void applyImportRow(AccessoryCatalogItem item, Map<String, String> row) {
        item.setName(text(row, "name"));
        item.setLocalName(text(row, "localname"));
        item.setBrand(text(row, "brand"));
        item.setCategory(text(row, "category"));
        item.setPartType(text(row, "parttype"));
        item.setOemPartNumber(text(row, "oempartnumber"));
        item.setAftermarketPartNumber(text(row, "aftermarketpartnumber"));
        item.setHsnCode(text(row, "hsncode"));
        item.setSourceUrl(text(row, "sourceurl"));
        item.setVerificationStatus(text(row, "verificationstatus"));
        item.setWholesalePrice(decimal(row, "wholesaleprice"));
        item.setRetailPrice(decimal(row, "retailprice"));
        item.setBargainingPrice(decimal(row, "bargainingprice"));
        item.setStockQuantity(integer(row, "stockquantity"));
        item.setMinimumStock(integer(row, "minimumstock"));
        item.setBarcode(text(row, "barcode"));
        item.setSupplier(text(row, "supplier"));
        item.setSupplierPhone(text(row, "supplierphone"));
        item.setPhotoUrl(text(row, "photourl"));
        item.setNotes(text(row, "notes"));
        item.setActive(true);
        item.setFitments(parseFitments(text(row, "fitments")));
    }

    private List<AccessoryVehicleFitment> parseFitments(String raw) {
        if (!hasText(raw)) return new ArrayList<>();
        List<AccessoryVehicleFitment> fitments = new ArrayList<>();
        for (String entry : raw.split(";")) {
            String[] values = entry.trim().split("\\|", -1);
            AccessoryVehicleFitment fitment = new AccessoryVehicleFitment();
            fitment.setMake(at(values, 0));
            fitment.setModel(at(values, 1));
            fitment.setVariant(at(values, 2));
            fitment.setYearFrom(number(at(values, 3)));
            fitment.setYearTo(number(at(values, 4)));
            fitment.setNotes(at(values, 5));
            if (fitment.hasVehicle()) fitments.add(fitment);
        }
        return fitments;
    }

    private List<Map<String, String>> readWorkbook(MultipartFile file) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) return rows;
            List<String> headers = new ArrayList<>();
            headerRow.forEach(cell -> headers.add(key(formatter.formatCellValue(cell))));
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                Map<String, String> values = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++) {
                    values.put(headers.get(column), formatter.formatCellValue(row.getCell(column)).trim());
                }
                rows.add(values);
            }
        }
        return rows;
    }

    private List<Map<String, String>> readCsv(MultipartFile file) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) return rows;
            List<String> headers = parseCsvLine(line).stream().map(this::key).toList();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cells = parseCsvLine(line);
                Map<String, String> values = new LinkedHashMap<>();
                for (int index = 0; index < headers.size(); index++) {
                    values.put(headers.get(index), index < cells.size() ? cells.get(index).trim() : "");
                }
                rows.add(values);
            }
        }
        return rows;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        values.add(value.toString());
        return values;
    }

    private String buildStockDescription(AccessoryCatalogItem item) {
        String fitment = item.getFitments().stream()
                .map(value -> String.join(" ", safe(value.getMake()), safe(value.getModel()), safe(value.getVariant())).trim())
                .filter(this::hasText)
                .limit(4)
                .reduce((left, right) -> left + ", " + right)
                .orElse("Universal / fitment verify");
        return "Catalog " + item.getSku() + " | Fits: " + fitment;
    }

    private String firstFitmentValue(AccessoryCatalogItem item, boolean make) {
        return item.getFitments().stream()
                .map(value -> make ? value.getMake() : value.getModel())
                .filter(this::hasText)
                .findFirst().orElse("");
    }

    private String text(Map<String, String> row, String key) {
        return clean(row.get(key));
    }

    private Double decimal(Map<String, String> row, String key) {
        String value = text(row, key);
        if (!hasText(value)) return 0.0;
        return Double.parseDouble(value.replace(",", "").replace("₹", "").trim());
    }

    private Integer integer(Map<String, String> row, String key) {
        String value = text(row, key);
        if (!hasText(value)) return 0;
        return (int) Math.round(Double.parseDouble(value.replace(",", "").trim()));
    }

    private String key(String value) {
        return safe(value).replace("\uFEFF", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String clean(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String at(String[] values, int index) {
        return index < values.length ? clean(values[index]) : null;
    }

    private Integer number(String value) {
        try {
            return hasText(value) ? Integer.parseInt(value) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private double value(Double value) {
        return value == null ? 0 : value;
    }
}
