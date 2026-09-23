package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.SalesAppointmentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/outbound")
public class OutboundController {

    private final SalesAppointmentService service;

    public OutboundController(SalesAppointmentService service) {
        this.service = service;
    }

    // ===================== VENTES =====================

    @PostMapping("/sales")
    public SalesRecordResponse createSale(@RequestBody SalesRecordRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.createSale(requester, request);
    }

    @GetMapping("/sales/me")
    public List<SalesRecordResponse> mySales(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.mySales(requester);
    }

    /** Ventes de toute l'équipe Outbound — Team Leader OUTBOUND ou QA/Admin/Superviseur uniquement. */
    @GetMapping("/sales/team")
    public List<SalesRecordResponse> teamSales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.teamSales(requester, from, to);
    }

    @DeleteMapping("/sales/{id}")
    public void deleteSale(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        service.deleteSale(requester, id);
    }

    // ===================== RENDEZ-VOUS =====================

    @PostMapping("/appointments")
    public AppointmentResponse createAppointment(@RequestBody AppointmentRequest request,
                                                  @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.createAppointment(requester, request);
    }

    @GetMapping("/appointments/me")
    public List<AppointmentResponse> myAppointments(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.myAppointments(requester);
    }

    /** RDV de toute l'équipe Outbound — Team Leader OUTBOUND ou QA/Admin/Superviseur uniquement. */
    @GetMapping("/appointments/team")
    public List<AppointmentResponse> teamAppointments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.teamAppointments(requester, from, to);
    }

    @PutMapping("/appointments/{id}/status")
    public AppointmentResponse updateAppointmentStatus(@PathVariable Integer id, @RequestBody java.util.Map<String, String> body,
                                                         @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.updateAppointmentStatus(requester, id, body.get("status"));
    }

    @DeleteMapping("/appointments/{id}")
    public void deleteAppointment(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        service.deleteAppointment(requester, id);
    }
}
