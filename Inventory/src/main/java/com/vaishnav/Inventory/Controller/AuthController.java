package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.Customer;
import com.vaishnav.Inventory.service.AuthService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    public String login(@RequestBody Customer customer) {
        return authService.login(customer);
    }

    @PostMapping("/register")
    public Customer register(@RequestBody Customer customer) {
        return authService.register(customer);
    }

}