package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureWorkflowNode;
import com.ecobank.rccportal.model.ProcedureWorkflowOption;
import com.ecobank.rccportal.repository.ProcedureRepository;
import com.ecobank.rccportal.repository.ProcedureWorkflowNodeRepository;
import com.ecobank.rccportal.repository.ProcedureWorkflowOptionRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parcours interactif d'une procédure (question -> options -> nœud suivant ou
 * fin "FIDELISATION"/"CLOTURE") — remplace l'ancienne liste d'étapes texte.
 */
@Service
public class ProcedureWorkflowService {

    private static final List<String> VALID_OUTCOMES = List.of("FIDELISATION", "CLOTURE");

    private final ProcedureRepository procedureRepository;
    private final ProcedureWorkflowNodeRepository nodeRepository;
    private final ProcedureWorkflowOptionRepository optionRepository;

    public ProcedureWorkflowService(
            ProcedureRepository procedureRepository,
            ProcedureWorkflowNodeRepository nodeRepository,
            ProcedureWorkflowOptionRepository optionRepository) {
        this.procedureRepository = procedureRepository;
        this.nodeRepository = nodeRepository;
        this.optionRepository = optionRepository;
    }

    /** Tous les nœuds d'une procédure, chacun avec ses options — sert à construire l'écran d'édition QA/admin. */
    @Transactional(readOnly = true)
    public List<ProcedureWorkflowNodeResponse> listNodes(Integer procedureId) {
        Procedure procedure = findProcedure(procedureId);
        List<ProcedureWorkflowNode> nodes = nodeRepository.findByProcedure(procedure);
        Map<Integer, List<ProcedureWorkflowOption>> optionsByNodeId = optionRepository.findByNodeIn(nodes).stream()
                .collect(Collectors.groupingBy(o -> o.getNode().getNodeId()));
        return nodes.stream().map(n -> toResponse(n, optionsByNodeId.getOrDefault(n.getNodeId(), List.of()))).toList();
    }

    /** Le nœud de départ du parcours — premier écran vu par l'agent. */
    @Transactional(readOnly = true)
    public ProcedureWorkflowNodeResponse getStartNode(Integer procedureId) {
        Procedure procedure = findProcedure(procedureId);
        ProcedureWorkflowNode node = nodeRepository.findByProcedureAndIsStartTrue(procedure)
                .orElseThrow(() -> ApiException.notFound("Ce parcours n'a pas encore de question de départ."));
        return toResponse(node, optionRepository.findByNode(node));
    }

    /** Un nœud précis — utilisé pour avancer dans le parcours après le choix d'une option. */
    @Transactional(readOnly = true)
    public ProcedureWorkflowNodeResponse getNode(Integer nodeId) {
        ProcedureWorkflowNode node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> ApiException.notFound("Question introuvable."));
        return toResponse(node, optionRepository.findByNode(node));
    }

    @Transactional
    public ProcedureWorkflowNodeResponse createNode(Integer procedureId, CreateWorkflowNodeRequest request) {
        Procedure procedure = findProcedure(procedureId);

        String questionText = request.questionText() == null ? "" : request.questionText().trim();
        if (questionText.isBlank()) {
            throw ApiException.badRequest("La question est obligatoire.");
        }

        boolean isStart = Boolean.TRUE.equals(request.isStart());
        if (isStart && nodeRepository.findByProcedureAndIsStartTrue(procedure).isPresent()) {
            throw ApiException.badRequest("Cette procédure a déjà une question de départ.");
        }

        ProcedureWorkflowNode node = nodeRepository.save(ProcedureWorkflowNode.builder()
                .procedure(procedure)
                .questionText(questionText)
                .isStart(isStart)
                .suggestionLabel(blankToNull(request.suggestionLabel()))
                .suggestionUrl(blankToNull(request.suggestionUrl()))
                .build());

        return toResponse(node, List.of());
    }

    @Transactional
    public ProcedureWorkflowOptionResponse createOption(CreateWorkflowOptionRequest request) {
        ProcedureWorkflowNode node = nodeRepository.findById(request.nodeId())
                .orElseThrow(() -> ApiException.badRequest("Question introuvable."));

        String label = request.label() == null ? "" : request.label().trim();
        if (label.isBlank()) {
            throw ApiException.badRequest("Le libellé de la réponse est obligatoire.");
        }

        boolean hasNext = request.nextNodeId() != null;
        boolean hasOutcome = request.outcome() != null && !request.outcome().isBlank();

        if (hasNext == hasOutcome) {
            throw ApiException.badRequest(
                    "Une réponse doit mener soit à une question suivante, soit à une fin (FIDELISATION/CLOTURE) — pas les deux, pas ni l'une ni l'autre.");
        }

        ProcedureWorkflowNode nextNode = null;
        if (hasNext) {
            nextNode = nodeRepository.findById(request.nextNodeId())
                    .orElseThrow(() -> ApiException.badRequest("Question suivante introuvable."));
        }

        if (hasOutcome && !VALID_OUTCOMES.contains(request.outcome().toUpperCase())) {
            throw ApiException.badRequest("outcome doit être FIDELISATION ou CLOTURE.");
        }

        ProcedureWorkflowOption option = optionRepository.save(ProcedureWorkflowOption.builder()
                .node(node)
                .label(label)
                .nextNode(nextNode)
                .outcome(hasOutcome ? request.outcome().toUpperCase() : null)
                .build());

        return new ProcedureWorkflowOptionResponse(
                option.getOptionId(), option.getLabel(),
                option.getNextNode() != null ? option.getNextNode().getNodeId() : null,
                option.getOutcome());
    }

    private Procedure findProcedure(Integer procedureId) {
        return procedureRepository.findById(procedureId)
                .orElseThrow(() -> ApiException.notFound("Procedure not found."));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private ProcedureWorkflowNodeResponse toResponse(ProcedureWorkflowNode node, List<ProcedureWorkflowOption> options) {
        List<ProcedureWorkflowOptionResponse> optionResponses = options.stream()
                .map(o -> new ProcedureWorkflowOptionResponse(
                        o.getOptionId(), o.getLabel(),
                        o.getNextNode() != null ? o.getNextNode().getNodeId() : null,
                        o.getOutcome()))
                .toList();

        return new ProcedureWorkflowNodeResponse(
                node.getNodeId(),
                node.getProcedure().getProcedureId(),
                node.getQuestionText(),
                node.getIsStart(),
                node.getSuggestionLabel(),
                node.getSuggestionUrl(),
                optionResponses);
    }
}