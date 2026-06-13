package com.vaishnav.Inventory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
@EnableScheduling
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class , args);

    //   spring application ko run krane ke liye 

    }

    @GetMapping("/test")
    public String test(){
        return "Backend working Database api_shop connected";

    }
}
