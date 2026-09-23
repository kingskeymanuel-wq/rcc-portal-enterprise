package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.RosterImportResult;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.RosterImportService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Import du roster RH (équipes, contrats, genre) — réservé QA/RH/Admin, même niveau que les autres imports. */
@RestController
@RequestMapping("/api/users/roster")
public class RosterImportController {

    private final RosterImportService rosterImportService;

    public RosterImportController(RosterImportService rosterImportService) {
        this.rosterImportService = rosterImportService;
    }

    @PostMapping(value = "/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public RosterImportResult importExcel(@RequestParam("file") MultipartFile file,
                                          @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisor(requester);
        return rosterImportService.importFromExcel(file);
    }

    private void requireSupervisor(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isRh && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance, Human Resources, or an administrator can import the HR roster.");
        }
    }
}
