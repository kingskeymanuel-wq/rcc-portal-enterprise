package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.AttendanceRecordResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AttendanceService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final com.ecobank.rccportal.service.AttendanceImportService attendanceImportService;

    public AttendanceController(AttendanceService attendanceService,
                                com.ecobank.rccportal.service.AttendanceImportService attendanceImportService) {
        this.attendanceService = attendanceService;
        this.attendanceImportService = attendanceImportService;
    }

    /** Le pointage lui-même est automatique (voir AuthService.completeLogin/logout) — pas de route POST manuelle. */
    @GetMapping("/me")
    public AttendanceRecordResponse today(@AuthenticationPrincipal AuthenticatedUser user) {
        return attendanceService.today(user.username());
    }

    @GetMapping("/me/history")
    public List<AttendanceRecordResponse> myHistory(@AuthenticationPrincipal AuthenticatedUser user) {
        return attendanceService.historyFor(user.username());
    }

    /** Vue globale d'un jour donné — réservée aux admins et superviseurs (lecture seule pour ces derniers). */
    @GetMapping
    public List<AttendanceRecordResponse> forDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthenticatedUser user) {
        boolean isAdmin = "admin".equalsIgnoreCase(user.role());
        boolean isSupervisor = "supervisor".equalsIgnoreCase(user.role());
        if (!isAdmin && !isSupervisor) {
            throw ApiException.forbidden("Only an administrator or a supervisor can view team-wide attendance.");
        }
        return attendanceService.forDate(date);
    }

    @PostMapping(value = "/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.ecobank.rccportal.dto.AttendanceImportResult importExcel(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthenticatedUser user) {
        if (!"admin".equalsIgnoreCase(user.role())) {
            throw ApiException.forbidden("Only an administrator can import attendance.");
        }
        return attendanceImportService.importFromExcel(file, date);
    }
}
