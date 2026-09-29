package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.SupervisionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Workflow de supervision : Superviseur → Team Leaders et Head QA ; Head QA → agents QA et formateurs. */
@RestController
@RequestMapping("/api/supervision")
public class SupervisionController {

    private final SupervisionService supervision;

    public SupervisionController(SupervisionService supervision) {
        this.supervision = supervision;
    }

    @GetMapping("/board")
    public SupervisionService.Board board(@RequestParam(required = false) String scope, @AuthenticationPrincipal AuthenticatedUser requester) {
        return supervision.board(requester, scope);
    }

    @GetMapping("/tasks")
    public List<SupervisionService.Task> tasks(@RequestParam(required = false) String scope, @AuthenticationPrincipal AuthenticatedUser requester) {
        return supervision.tasksForManager(requester, scope);
    }

    @PostMapping("/tasks")
    public List<SupervisionService.Task> create(@RequestBody SupervisionService.CreateTask body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return supervision.create(requester, body);
    }

    /** Missions confiées à l'utilisateur connecté (Team Leader, Head QA, agent QA…). */
    @GetMapping("/tasks/mine")
    public List<SupervisionService.Task> mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return supervision.myTasks(requester);
    }

    @PostMapping("/tasks/{id}/action")
    public SupervisionService.Task act(@PathVariable Long id, @RequestBody ActionRequest body, @AuthenticationPrincipal AuthenticatedUser requester) {
        return supervision.act(requester, id, body.action(), body.comment());
    }

    @GetMapping("/tasks/{id}/events")
    public List<SupervisionService.TaskEvent> events(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return supervision.events(requester, id);
    }

    public record ActionRequest(String action, String comment) {}
}
