package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.KbDispatchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Bibliothèque de dispatching de la Base de connaissances : dépôt d'un ZIP, aperçu, dispatching réel, comptes rendus. */
@RestController
@RequestMapping("/api/kb/dispatch")
public class KbDispatchController {

    private final KbDispatchService service;

    public KbDispatchController(KbDispatchService service) {
        this.service = service;
    }

    @GetMapping("/packages")
    public List<KbDispatchService.Package> list(@AuthenticationPrincipal AuthenticatedUser requester) {
        return service.list(requester);
    }

    @PostMapping(value = "/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KbDispatchService.Package upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.upload(requester, file);
    }

    @DeleteMapping("/packages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable int id, @AuthenticationPrincipal AuthenticatedUser requester) {
        service.delete(requester, id);
    }

    /** Aperçu : où ira chaque fichier, ce qui sera ajouté, remplacé ou laissé — rien n'est écrit. */
    @PostMapping("/packages/{id}/analyse")
    public KbDispatchService.Report analyse(@PathVariable int id, @RequestParam(defaultValue = "GENERAL") String space,
                                            @RequestParam(defaultValue = "false") boolean removeMissing,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.analyse(requester, id, space, removeMissing);
    }

    /** Dispatching réel dans les rubriques de la Base de connaissances. */
    @PostMapping("/packages/{id}/dispatch")
    public KbDispatchService.Report dispatch(@PathVariable int id, @RequestParam(defaultValue = "GENERAL") String space,
                                             @RequestParam(defaultValue = "false") boolean removeMissing,
                                             @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.dispatch(requester, id, space, removeMissing);
    }

    @GetMapping("/packages/{id}/report")
    public KbDispatchService.Report report(@PathVariable int id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return service.lastReport(requester, id);
    }
}
