package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.LmsStateService;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lms")
public class LmsController {

    private final LmsStateService lmsStateService;

    public LmsController(LmsStateService lmsStateService) {
        this.lmsStateService = lmsStateService;
    }

    @GetMapping("/state")
    public JsonNode read(@AuthenticationPrincipal AuthenticatedUser requester) {
        if (requester == null) {
            throw ApiException.unauthorized("Authentication required.");
        }
        return lmsStateService.readState(requester.username());
    }

    @PutMapping("/state")
    public JsonNode save(@RequestBody JsonNode state, @AuthenticationPrincipal AuthenticatedUser requester) {
        return lmsStateService.saveState(state, requester);
    }
}