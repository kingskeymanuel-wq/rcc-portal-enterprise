package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.ProcessPuzzleResponse;
import com.ecobank.rccportal.dto.ProcessPuzzleResultResponse;
import com.ecobank.rccportal.dto.ProcessPuzzleSubmitRequest;
import com.ecobank.rccportal.service.ProcessPuzzleService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games/process-puzzle")
public class ProcessPuzzleController {

    private final ProcessPuzzleService service;

    public ProcessPuzzleController(ProcessPuzzleService service) {
        this.service = service;
    }

    @GetMapping("/random")
    public ProcessPuzzleResponse random() {
        return service.randomPuzzle();
    }

    @PostMapping("/validate")
    public ProcessPuzzleResultResponse validate(@RequestBody ProcessPuzzleSubmitRequest request) {
        return service.validate(request);
    }
}
