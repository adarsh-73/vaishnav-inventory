package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.Customer;
import com.vaishnav.Inventory.repository.CustomerRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    @Autowired
    private CustomerRepository customerRepository;

    public String login(Customer customer) {
        Customer existing = customerRepository.findByEmail(customer.getEmail());

        if (existing != null && existing.getPassword().equals(customer.getPassword())) {
            return "Login Successful";
        } else {
            return "Invalid Email or Password";
        }
    }

    public Customer register(Customer customer) {
        return customerRepository.save(customer);
    }
}