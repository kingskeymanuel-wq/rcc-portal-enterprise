package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AdminDashboardResponse;
import com.ecobank.rccportal.service.AdminDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @GetMapping("/admin/dashboard")
    public AdminDashboardResponse dashboard() {
        return adminDashboardService.getAdminDashboard();
    }
}
