package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.CreateShiftSwapRequest;
import com.ecobank.rccportal.dto.ShiftSwapDecisionRequest;
import com.ecobank.rccportal.dto.ShiftSwapResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ShiftSwapService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Permutation de shift entre agents — voir ShiftSwapService pour le workflow complet à
 *  2 étapes (agent visé, puis Team Leader). */
@RestController
@RequestMapping("/api/shift-swaps")
public class ShiftSwapController {

    private final ShiftSwapService shiftSwapService;

    public ShiftSwapController(ShiftSwapService shiftSwapService) {
        this.shiftSwapService = shiftSwapService;
    }

    /** L'agent connecté propose un échange à un pair de sa propre équipe. */
    @PostMapping
    public ShiftSwapResponse submit(@RequestBody CreateShiftSwapRequest request,
                                     @AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftSwapService.submit(requester.username(), requester.role(), request.requesterDate(),
                request.targetUsername(), request.targetDate(), request.message());
    }

    /** Mes permutations — demandées par moi ou qui me sont adressées, tous statuts. */
    @GetMapping("/mine")
    public List<ShiftSwapResponse> mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftSwapService.listMine(requester.username());
    }

    /** File d'attente de l'agent visé (pair) — demandes en attente de SA décision. */
    @GetMapping("/pending-for-me")
    public List<ShiftSwapResponse> pendingForMe(@AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftSwapService.listPendingForPeer(requester.username());
    }

    @PostMapping("/{id}/peer-decide")
    public ShiftSwapResponse decideByPeer(@PathVariable Integer id, @RequestBody ShiftSwapDecisionRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        return shiftSwapService.decideByPeer(id, requester.username(), request.approve(), request.comment());
    }

    /** File d'attente du Team Leader — permutations acceptées par le pair, restreintes à SON
     *  équipe (ledTeam), réservé au rôle TEAM_LEADER. */
    @GetMapping("/pending-for-team-leader")
    public List<ShiftSwapResponse> pendingForTeamLeader(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeader(requester);
        return shiftSwapService.listPendingForTeamLeader(requester.username());
    }

    /** Toutes les permutations de l'équipe du Team Leader, tous statuts — réservé au rôle TEAM_LEADER. */
    @GetMapping("/team-leader")
    public List<ShiftSwapResponse> allForTeamLeader(@AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeader(requester);
        return shiftSwapService.listForTeamLeader(requester.username());
    }

    @PostMapping("/{id}/team-leader-decide")
    public ShiftSwapResponse decideByTeamLeader(@PathVariable Integer id, @RequestBody ShiftSwapDecisionRequest request,
                                                 @AuthenticationPrincipal AuthenticatedUser requester) {
        requireTeamLeader(requester);
        return shiftSwapService.decideByTeamLeader(id, requester.username(), request.approve(), request.comment());
    }

    private void requireTeamLeader(AuthenticatedUser requester) {
        if (!"team_leader".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only a Team Leader can validate shift swaps.");
        }
    }
}
