package com.vaishnav.Inventory.service;

import com.vaishnav.Inventory.entity.User;
import com.vaishnav.Inventory.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public String login(User user) {

        User existingUser = userRepository.findByEmail(user.getEmail());

        if (existingUser != null && 
            existingUser.getPassword().equals(user.getPassword())) {

            return "Login Success";
        }

        return "Invalid Credentials";
    }
}