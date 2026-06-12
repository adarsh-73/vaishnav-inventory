package com.vaishnav.Inventory.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
public class AttendanceRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate attendanceDate;
    private String staffName;
    private String status;
    private String note;
    private LocalDateTime createdDate;

    @PrePersist
    public void prePersist() {
        if (attendanceDate == null) attendanceDate = LocalDate.now();
        createdDate = LocalDateTime.now();
    }
}
