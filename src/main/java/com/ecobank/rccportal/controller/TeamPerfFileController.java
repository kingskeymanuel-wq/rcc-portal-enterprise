package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.TeamPerfFileService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Fichiers de performance par équipe : import par la QA (choix de l'équipe), tableau d'équipe, performances de l'agent. */
@RestController
@RequestMapping("/api/team-perf-files")
public class TeamPerfFileController {

    private final TeamPerfFileService service;

    public TeamPerfFileController(TeamPerfFileService service) {
        this.service = service;
    }

    /** Équipes et indicateurs pris en compte pour chacune. */
    @GetMapping("/catalog")
    public List<TeamPerfFileService.TeamDef> catalog(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.catalog(requester);
    }

    /** dryRun=true : aperçu (colonnes reconnues, agents rattachés, période détectée) sans rien enregistrer. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TeamPerfFileService.ImportReport importFile(@RequestParam("file") MultipartFile file, @RequestParam String team,
                                                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                       @RequestParam(required = false) String countryCode,
                                                       @RequestParam(defaultValue = "true") boolean dryRun,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.importFile(requester, file, team, from, to, countryCode, dryRun);
    }

    /** Dispatching d'un fichier consolidé : chaque agent part dans son équipe. dryRun=true : aperçu sans rien enregistrer. */
    @PostMapping(value = "/dispatch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TeamPerfFileService.DispatchReport dispatch(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                       @RequestParam(required = false) String countryCode,
                                                       @RequestParam(defaultValue = "true") boolean dryRun,
                                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.dispatch(requester, file, from, to, countryCode, dryRun);
    }

    @GetMapping("/sheet")
    public TeamPerfFileService.TeamSheet sheet(@RequestParam String team, @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.sheet(requester, team, from, to);
    }

    @GetMapping("/periods")
    public List<TeamPerfFileService.Period> periods(@RequestParam String team, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.periods(requester, team);
    }

    @DeleteMapping("/batches/{batchId}")
    public Map<String, Boolean> deleteBatch(@PathVariable String batchId, @AuthenticationPrincipal AuthenticatedUser requester) {
        service.deleteBatch(requester, batchId);
        return Map.of("ok", true);
    }

    /** Performances de l'agent connecté (semaines importées, rang, moyenne de l'équipe). */
    @GetMapping("/me")
    public TeamPerfFileService.MyPerformance mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.mine(requester);
    }
}
