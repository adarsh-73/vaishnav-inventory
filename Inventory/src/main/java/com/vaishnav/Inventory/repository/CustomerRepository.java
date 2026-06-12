package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Customer findByEmail(String email);

    Customer findByMobileNumber(String mobileNumber);


}
