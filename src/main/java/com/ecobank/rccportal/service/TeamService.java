package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.TeamRequest;
import com.ecobank.rccportal.dto.TeamResponse;
import com.ecobank.rccportal.model.Team;
import com.ecobank.rccportal.repository.TeamRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Source de vérité unique pour la liste des équipes — remplace les listes codées en dur
 * côté frontend (ECO_TEAMS, IT_TEAMS, etc.) et le VALID_TEAMS d'AuthService, qui pouvaient
 * dériver silencieusement de la vraie table Teams si celle-ci était modifiée sans mise à jour
 * manuelle de ces listes.
 */
@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final com.ecobank.rccportal.repository.UserRepository userRepository;

    public TeamService(TeamRepository teamRepository, com.ecobank.rccportal.repository.UserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> listActive() {
        return teamRepository.findAll().stream()
                .filter(Team::getIsActive)
                .sorted(Comparator.comparing(Team::getLabel))
                .map(t -> new TeamResponse(t.getTeamId(), t.getCode(), t.getLabel(), t.getIconGlyph(), t.getAccentColor()))
                .toList();
    }

    @Transactional
    public TeamResponse create(TeamRequest request) {
        String code = request.code().trim();
        if (teamRepository.findByCode(code).isPresent()) {
            throw ApiException.conflict("team_exists", "This team already exists.");
        }
        Team team = Team.builder()
                .code(code)
                .label(request.label().trim())
                .iconGlyph(request.iconGlyph())
                .accentColor(request.accentColor())
                .isActive(true)
                .build();
        Team saved = teamRepository.save(team);
        return new TeamResponse(saved.getTeamId(), saved.getCode(), saved.getLabel(), saved.getIconGlyph(), saved.getAccentColor());
    }

    /** Nombre d'agents par équipe (via User.activity, le libellé exact) — pour repérer les équipes à 0 agent avant suppression. */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> usageByCode() {
        java.util.Map<String, String> labelByCode = teamRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Team::getLabel, Team::getCode, (a, b) -> a));
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        for (Team t : teamRepository.findAll()) counts.put(t.getCode(), 0L);
        userRepository.findAll().forEach(u -> {
            if (u.getActivity() == null) return;
            String code = labelByCode.get(u.getActivity());
            if (code != null) counts.merge(code, 1L, Long::sum);
        });
        return counts;
    }

    /** Supprime une équipe — refuse si des agents y sont encore rattachés (User.activity), pour ne jamais casser leur affectation sans le vouloir. */
    @Transactional
    public void delete(Integer teamId) {
        Team team = teamRepository.findById(teamId).orElseThrow(() -> ApiException.notFound("Équipe inconnue."));
        boolean stillUsed = userRepository.findAll().stream()
                .anyMatch(u -> team.getLabel().equals(u.getActivity()));
        if (stillUsed) {
            throw ApiException.badRequest("Des agents sont encore rattachés à cette équipe — retirez-les d'abord (ou réimportez le roster à jour).");
        }
        teamRepository.delete(team);
    }
}
