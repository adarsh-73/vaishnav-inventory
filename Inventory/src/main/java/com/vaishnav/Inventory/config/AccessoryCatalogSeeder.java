package com.vaishnav.Inventory.config;

import com.vaishnav.Inventory.entity.AccessoryCatalogItem;
import com.vaishnav.Inventory.entity.AccessoryVehicleFitment;
import com.vaishnav.Inventory.repository.AccessoryCatalogRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class AccessoryCatalogSeeder implements ApplicationRunner {

    private static final List<Vehicle> VEHICLES = List.of(
            new Vehicle("Mahindra", "Bolero"),
            new Vehicle("Mahindra", "Bolero Neo"),
            new Vehicle("Mahindra", "Scorpio"),
            new Vehicle("Mahindra", "Scorpio N"),
            new Vehicle("Mahindra", "Scorpio Classic"),
            new Vehicle("Mahindra", "XUV300"),
            new Vehicle("Mahindra", "XUV 3XO"),
            new Vehicle("Mahindra", "XUV500"),
            new Vehicle("Mahindra", "XUV700"),
            new Vehicle("Mahindra", "XUV 7XO"),
            new Vehicle("Tata", "Punch"),
            new Vehicle("Tata", "Nexon"),
            new Vehicle("Tata", "Sierra"),
            new Vehicle("Tata", "Altroz"),
            new Vehicle("Maruti Suzuki", "Dzire"),
            new Vehicle("Maruti Suzuki", "Swift"),
            new Vehicle("Maruti Suzuki", "Alto 800"),
            new Vehicle("Maruti Suzuki", "Fronx"),
            new Vehicle("Maruti Suzuki", "Ertiga"),
            new Vehicle("Maruti Suzuki", "XL6"),
            new Vehicle("Ford", "EcoSport"),
            new Vehicle("Toyota", "Glanza"),
            new Vehicle("Toyota", "Urban Cruiser Taisor"),
            new Vehicle("Toyota", "Urban Cruiser Hyryder"),
            new Vehicle("Toyota", "Rumion"),
            new Vehicle("Toyota", "Innova Crysta"),
            new Vehicle("Toyota", "Innova Hycross"),
            new Vehicle("Toyota", "Fortuner"),
            new Vehicle("Toyota", "Hilux"),
            new Vehicle("Toyota", "Camry"),
            new Vehicle("Toyota", "Vellfire"),
            new Vehicle("Toyota", "Land Cruiser 300")
    );

    private static final List<StarterItem> STARTER_ITEMS = List.of(
            new StarterItem("LED Headlight Bulb Set", "LED light", "Lighting"),
            new StarterItem("Projector Fog Lamp Set", "Fogg light", "Lighting"),
            new StarterItem("DRL Daytime Running Light", "DRL", "Lighting"),
            new StarterItem("Dual Tone Horn Set", "Horn", "Electrical"),
            new StarterItem("Reverse Parking Camera", "Back camera", "Electronics"),
            new StarterItem("Parking Sensor Kit", "Parking sensor", "Electronics"),
            new StarterItem("Android Touchscreen Stereo", "Android player", "Infotainment"),
            new StarterItem("Component Speaker Set", "Speaker", "Audio"),
            new StarterItem("Coaxial Speaker Set", "Door speaker", "Audio"),
            new StarterItem("Active Subwoofer", "Bass tube", "Audio"),
            new StarterItem("Four Channel Amplifier", "Amplifier", "Audio"),
            new StarterItem("Dash Camera", "Dash cam", "Electronics"),
            new StarterItem("Custom Fit Seat Cover Set", "Seat cover", "Interior"),
            new StarterItem("7D Floor Mat Set", "Foot mat", "Interior"),
            new StarterItem("Boot Mat", "Dickey mat", "Interior"),
            new StarterItem("Steering Wheel Cover", "Steering cover", "Interior"),
            new StarterItem("Armrest Console", "Armrest", "Interior"),
            new StarterItem("Door Visor Set", "Rain visor", "Exterior"),
            new StarterItem("Waterproof Body Cover", "Car cover", "Exterior"),
            new StarterItem("Mud Flap Set", "Mud flap", "Exterior"),
            new StarterItem("Door Sill Guard Set", "Scuff plate", "Exterior"),
            new StarterItem("Bumper Corner Protector", "Bumper guard", "Exterior"),
            new StarterItem("Body Side Moulding Set", "Side beading", "Exterior"),
            new StarterItem("Roof Rail Set", "Roof rail", "Exterior"),
            new StarterItem("Wheel Cover Set", "Wheel cap", "Exterior"),
            new StarterItem("Alloy Wheel Set", "Alloy", "Exterior"),
            new StarterItem("Tyre Inflator", "Air pump", "Utility"),
            new StarterItem("Fast Car Charger", "Mobile charger", "Utility"),
            new StarterItem("Dashboard Mobile Holder", "Mobile stand", "Utility"),
            new StarterItem("Portable Car Vacuum Cleaner", "Vacuum", "Utility"),
            new StarterItem("Ambient Light Kit", "Ambient light", "Interior"),
            new StarterItem("Window Sunshade Set", "Sun shade", "Interior"),
            new StarterItem("Central Locking Kit", "Central lock", "Security"),
            new StarterItem("Anti Theft Security System", "Security system", "Security"),
            new StarterItem("Number Plate Frame Set", "Number plate frame", "Exterior")
    );

    private final AccessoryCatalogRepository repository;

    public AccessoryCatalogSeeder(AccessoryCatalogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> existingBarcodes = new HashSet<>(repository.findAllBarcodes());
        List<AccessoryCatalogItem> newItems = new ArrayList<>();

        for (int index = 0; index < STARTER_ITEMS.size(); index++) {
            StarterItem starter = STARTER_ITEMS.get(index);
            for (Vehicle vehicle : VEHICLES) {
                String reference = starterReference(index + 1, vehicle);
                if (existingBarcodes.contains(reference)) continue;

                AccessoryCatalogItem item = new AccessoryCatalogItem();
                item.setSku(reference);
                item.setName(starter.name() + " - " + vehicle.make() + " " + vehicle.model());
                item.setLocalName(starter.localName());
                item.setBrand("Multiple Aftermarket Brands");
                item.setCategory(starter.category());
                item.setPartType("Aftermarket");
                item.setAftermarketPartNumber(reference);
                item.setBarcode(reference);
                item.setWholesalePrice(0.0);
                item.setRetailPrice(0.0);
                item.setBargainingPrice(0.0);
                item.setStockQuantity(0);
                item.setMinimumStock(0);
                item.setVerificationStatus("PRICE_AND_FITMENT_VERIFY");
                item.setNotes("Vehicle starter record. Exact generation, variant, connector and supplier price verify karke quote karein.");
                item.replaceFitments(List.of(fitment(vehicle)));
                newItems.add(item);
                existingBarcodes.add(reference);
            }
        }

        addOfficialMarutiAccessory(
                newItems,
                existingBarcodes,
                "MSGA Anti Skid Mat Small Black",
                "990J0M999H2-900",
                109.50,
                "https://www.marutisuzuki.com/genuine-accessories/lifestyle/anti-skid-mat"
        );
        addOfficialMarutiAccessory(
                newItems,
                existingBarcodes,
                "MSGA Anti Skid Mat Large Black",
                "990J0M999H2-920",
                137.0,
                "https://www.marutisuzuki.com/genuine-accessories/lifestyle/anti-skid-mat"
        );

        for (int start = 0; start < newItems.size(); start += 100) {
            repository.saveAll(newItems.subList(start, Math.min(start + 100, newItems.size())));
        }
    }

    private void addOfficialMarutiAccessory(List<AccessoryCatalogItem> newItems,
                                            Set<String> existingBarcodes,
                                            String name,
                                            String partNumber,
                                            double mrp,
                                            String sourceUrl) {
        if (existingBarcodes.contains(partNumber)) return;
        AccessoryCatalogItem item = new AccessoryCatalogItem();
        item.setSku(partNumber);
        item.setName(name);
        item.setLocalName("Anti skid mat");
        item.setBrand("Maruti Suzuki Genuine Accessories");
        item.setCategory("Interior");
        item.setPartType("OEM Accessory");
        item.setOemPartNumber(partNumber);
        item.setBarcode(partNumber);
        item.setRetailPrice(mrp);
        item.setBargainingPrice(mrp);
        item.setWholesalePrice(0.0);
        item.setStockQuantity(0);
        item.setMinimumStock(0);
        item.setSourceUrl(sourceUrl);
        item.setVerificationStatus("OFFICIAL_SOURCE_VERIFIED");
        item.setSourceCheckedAt(LocalDateTime.of(2026, 7, 3, 0, 0));
        item.setNotes("Official listed MRP; live stock aur current MRP quote se pehle source par recheck karein.");
        item.replaceFitments(VEHICLES.stream()
                .filter(vehicle -> vehicle.make().equals("Maruti Suzuki"))
                .map(this::fitment)
                .toList());
        newItems.add(item);
        existingBarcodes.add(partNumber);
    }

    private String starterReference(int index, Vehicle vehicle) {
        String makeCode = switch (vehicle.make()) {
            case "Mahindra" -> "MHD";
            case "Tata" -> "TAT";
            case "Maruti Suzuki" -> "MSZ";
            case "Ford" -> "FOR";
            default -> "TOY";
        };
        String modelCode = vehicle.model().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return "VA-" + String.format("%03d", index) + "-" + makeCode + "-" + modelCode;
    }

    private AccessoryVehicleFitment fitment(Vehicle vehicle) {
        AccessoryVehicleFitment fitment = new AccessoryVehicleFitment();
        fitment.setMake(vehicle.make());
        fitment.setModel(vehicle.model());
        fitment.setVariant("All - verify generation");
        return fitment;
    }

    private record Vehicle(String make, String model) {}
    private record StarterItem(String name, String localName, String category) {}
}
