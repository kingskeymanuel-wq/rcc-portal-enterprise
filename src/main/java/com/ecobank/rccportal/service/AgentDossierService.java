package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AgentDossierRequest;
import com.ecobank.rccportal.dto.AgentDossierResponse;
import com.ecobank.rccportal.model.AgentDossier;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.AgentDossierRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentDossierService {

    private final AgentDossierRepository agentDossierRepository;
    private final UserRepository userRepository;

    public AgentDossierService(
            AgentDossierRepository agentDossierRepository,
            UserRepository userRepository) {

        this.agentDossierRepository = agentDossierRepository;
        this.userRepository = userRepository;
    }

    /**
     * Retourne tous les dossiers agents.
     * Réservé normalement aux profils QA / ADMIN.
     */
    @Transactional(readOnly = true)
    public List<AgentDossierResponse> listAll() {

        return agentDossierRepository
                .findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Retourne uniquement les dossiers liés à un utilisateur.
     *
     * Ancien système :
     * username / getid()
     *
     * Nouveau système :
     * username / getId()
     */
    @Transactional(readOnly = true)
    public List<AgentDossierResponse> listForUser(String username) {

        User user = findUserByUsername(username);

        return agentDossierRepository
                .findAll()
                .stream()
                .filter(dossier ->
                        dossier.getLinkedUser() != null
                                && dossier.getLinkedUser().getId() != null
                                && user.getId().equals(
                                dossier.getLinkedUser().getId()
                        )
                )
                .map(this::toResponse)
                .toList();
    }

    /**
     * Création d'un dossier agent.
     */
    @Transactional
    public AgentDossierResponse create(
            AgentDossierRequest request) {

        User linkedUser = null;

        /*
         * Le DTO porte encore le nom linkedUserMatricule
         * pour compatibilité avec le frontend existant.
         *
         * Mais sa valeur est maintenant interprétée
         * comme USERNAME dans dbo.USERS.
         */
        if (request.linkedUserMatricule() != null
                && !request.linkedUserMatricule().isBlank()) {

            linkedUser = userRepository
                    .findFirstByUsernameIgnoreCase(
                            request.linkedUserMatricule().trim()
                    )
                    .orElseThrow(
                            () -> ApiException.badRequest(
                                    "Unknown linked user."
                            )
                    );
        }

        AgentDossier dossier = AgentDossier.builder()
                .linkedUser(linkedUser)
                .source(
                        request.source() != null
                                ? request.source().trim()
                                : null
                )
                .payload(request.payload())
                .build();

        AgentDossier saved =
                agentDossierRepository.save(dossier);

        return toResponse(saved);
    }

    /**
     * Modification d'un dossier existant.
     */
    @Transactional
    public AgentDossierResponse update(
            Integer id,
            AgentDossierRequest request) {

        AgentDossier dossier =
                agentDossierRepository
                        .findById(id)
                        .orElseThrow(
                                () -> ApiException.notFound(
                                        "Agent dossier not found."
                                )
                        );

        if (request.linkedUserMatricule() != null
                && !request.linkedUserMatricule().isBlank()) {

            User linkedUser =
                    userRepository
                            .findFirstByUsernameIgnoreCase(
                                    request.linkedUserMatricule().trim()
                            )
                            .orElseThrow(
                                    () -> ApiException.badRequest(
                                            "Unknown linked user."
                                    )
                            );

            dossier.setLinkedUser(linkedUser);
        }

        if (request.source() != null
                && !request.source().isBlank()) {

            dossier.setSource(
                    request.source().trim()
            );
        }

        if (request.payload() != null
                && !request.payload().isBlank()) {

            dossier.setPayload(
                    request.payload()
            );
        }

        AgentDossier saved =
                agentDossierRepository.save(dossier);

        return toResponse(saved);
    }

    /**
     * Suppression d'un dossier.
     */
    @Transactional
    public void remove(Integer id) {

        if (!agentDossierRepository.existsById(id)) {

            throw ApiException.notFound(
                    "Agent dossier not found."
            );
        }

        agentDossierRepository.deleteById(id);
    }

    /**
     * Recherche d'un utilisateur RCC via USERS.USERNAME.
     */
    private User findUserByUsername(String username) {

        if (username == null || username.isBlank()) {

            throw ApiException.badRequest(
                    "Username is required."
            );
        }

        return userRepository
                .findFirstByUsernameIgnoreCase(
                        username.trim()
                )
                .orElseThrow(
                        () -> ApiException.notFound(
                                "Unknown user."
                        )
                );
    }

    /**
     * Conversion de l'entité vers le DTO.
     *
     * linkedUserMatricule reste le nom du champ DTO
     * pour compatibilité frontend, mais contient
     * désormais USERS.USERNAME.
     */
    private AgentDossierResponse toResponse(
            AgentDossier dossier) {

        User linkedUser =
                dossier.getLinkedUser();

        String username =
                linkedUser != null
                        ? linkedUser.getUsername()
                        : null;

        String name =
                linkedUser != null
                        ? linkedUser.getName()
                        : null;

        return new AgentDossierResponse(
                dossier.getDossierId(),
                username,
                name,
                dossier.getSource(),
                dossier.getPayload(),
                dossier.getCreatedAt(),
                dossier.getUpdatedAt()
        );
    }
}