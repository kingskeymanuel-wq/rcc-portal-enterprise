package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ToolAccessService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Outils & Portails → Profils : accès de chaque agent aux outils (voir ToolAccessService). */
@RestController
@RequestMapping("/api/tools")
public class ToolAccessController {

    private final ToolAccessService service;

    public ToolAccessController(ToolAccessService service) {
        this.service = service;
    }

    @GetMapping("/profiles")
    public ToolAccessService.Profiles profiles(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.profiles(requester);
    }

    public record UpdateRequest(List<String> tools) {}

    @PutMapping("/profiles/{userId}")
    public ToolAccessService.Profile update(@PathVariable Long userId, @RequestBody UpdateRequest body,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.update(requester, userId, body.tools());
    }
}
