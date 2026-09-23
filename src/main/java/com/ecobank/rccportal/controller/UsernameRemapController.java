package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.UsernameRemapResult;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.UsernameRemapService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Renommage en masse des USERNAME depuis la liste de référence RH (Nom / Nouvel ID) —
 *  réservé QA/RH/Admin, même niveau d'accès que les autres imports (roster, planning). */
@RestController
@RequestMapping("/api/users/remap-usernames")
public class UsernameRemapController {

    private final UsernameRemapService usernameRemapService;

    public UsernameRemapController(UsernameRemapService usernameRemapService) {
        this.usernameRemapService = usernameRemapService;
    }

    @PostMapping(value = "/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public UsernameRemapResult importExcel(@RequestParam("file") MultipartFile file,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        requireSupervisor(requester);
        return usernameRemapService.importFromExcel(file, requester.username());
    }

    private void requireSupervisor(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isRh = "rh".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isAdmin && !isRh && !isQa) {
            throw ApiException.forbidden("Only Quality Assurance, Human Resources, or an administrator can remap usernames.");
        }
    }
}
