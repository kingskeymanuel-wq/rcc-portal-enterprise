package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.AuditLogService;
import com.ecobank.rccportal.service.CurrentUserAccessService;
import com.ecobank.rccportal.service.ShiftCatalogService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Catalogue des shifts : lecture pour tous les écrans de planning, modification réservée à l'administrateur. */
@RestController
public class ShiftCodeController {

    private final ShiftCatalogService catalog;
    private final CurrentUserAccessService access;
    private final AuditLogService audit;

    public ShiftCodeController(ShiftCatalogService catalog, CurrentUserAccessService access, AuditLogService audit) {
        this.catalog = catalog;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/api/shift-codes")
    public List<ShiftCatalogService.ShiftCode> list(@RequestParam(defaultValue = "false") boolean all) {
        return all ? catalog.all() : catalog.active();
    }

    @PutMapping("/api/admin/shift-codes/{code}")
    public ShiftCatalogService.SaveResult save(@PathVariable String code, @RequestBody ShiftCatalogService.SaveRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser requester) {
        access.requireAdmin(requester);
        ShiftCatalogService.SaveResult r = catalog.save(code, request, requester.username());
        audit.record(requester.username(), "ADMINISTRATION", "Shift " + r.shift().code() + " : " + r.shift().label() + " (" + r.shift().start() + "–"
                + r.shift().end() + (r.shift().active() ? "" : ", désactivé") + ")" + (r.planningUpdated() > 0 ? ", " + r.planningUpdated() + " jour(s) de planning mis à jour" : ""));
        return r;
    }
}
