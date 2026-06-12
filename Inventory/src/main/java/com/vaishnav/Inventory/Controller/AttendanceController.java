package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.AttendanceRecord;
import com.vaishnav.Inventory.repository.AttendanceRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/attendance")
@CrossOrigin(originPatterns = "*")
public class AttendanceController {
    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @GetMapping
    public List<AttendanceRecord> getAttendance() {
        return attendanceRecordRepository.findAll();
    }

    @PostMapping
    public AttendanceRecord addAttendance(@RequestBody AttendanceRecord record) {
        return attendanceRecordRepository.save(record);
    }

    @PutMapping("/{id}")
    public AttendanceRecord updateAttendance(@PathVariable Long id, @RequestBody AttendanceRecord record) {
        AttendanceRecord existing = attendanceRecordRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Attendance record not found"));
        existing.setAttendanceDate(record.getAttendanceDate());
        existing.setStaffName(record.getStaffName());
        existing.setStatus(record.getStatus());
        existing.setNote(record.getNote());
        return attendanceRecordRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    public String deleteAttendance(@PathVariable Long id) {
        attendanceRecordRepository.deleteById(id);
        return "Attendance deleted";
    }
}
