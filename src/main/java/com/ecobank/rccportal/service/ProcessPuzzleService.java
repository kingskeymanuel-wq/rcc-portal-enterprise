package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.ProcessPuzzleResponse;
import com.ecobank.rccportal.dto.ProcessPuzzleResultResponse;
import com.ecobank.rccportal.dto.ProcessPuzzleSubmitRequest;
import com.ecobank.rccportal.model.Procedure;
import com.ecobank.rccportal.model.ProcedureWorkflowNode;
import com.ecobank.rccportal.model.ProcedureWorkflowOption;
import com.ecobank.rccportal.repository.ProcedureRepository;
import com.ecobank.rccportal.repository.ProcedureWorkflowNodeRepository;
import com.ecobank.rccportal.repository.ProcedureWorkflowOptionRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Jeu "Parcours Client" — ne fabrique AUCUNE procédure : il pioche parmi les
 * vrais parcours interactifs déjà saisis par QA (ProcedureWorkflowNode/Option,
 * voir écran Procédures) et demande à l'agent de remettre les étapes dans
 * l'ordre. Plus QA complète de parcours interactifs, plus ce jeu s'enrichit
 * automatiquement — aucun contenu à maintenir séparément.
 */
@Service
public class ProcessPuzzleService {

    private final ProcedureRepository procedureRepository;
    private final ProcedureWorkflowNodeRepository nodeRepository;
    private final ProcedureWorkflowOptionRepository optionRepository;

    public ProcessPuzzleService(ProcedureRepository procedureRepository,
                                 ProcedureWorkflowNodeRepository nodeRepository,
                                 ProcedureWorkflowOptionRepository optionRepository) {
        this.procedureRepository = procedureRepository;
        this.nodeRepository = nodeRepository;
        this.optionRepository = optionRepository;
    }

    @Transactional(readOnly = true)
    public ProcessPuzzleResponse randomPuzzle() {
        List<Procedure> candidates = procedureRepository.findAllByOrderByTitleAsc().stream()
                .filter(p -> nodeRepository.findByProcedureAndIsStartTrue(p).isPresent())
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            throw ApiException.notFound("Aucun parcours interactif disponible pour l'instant — QA doit d'abord en créer au moins un depuis l'écran Procédures.");
        }
        Procedure procedure = candidates.get(new Random().nextInt(candidates.size()));
        List<ProcedureWorkflowNode> chain = walkChain(procedure);
        if (chain.size() < 3) {
            // Chaîne trop courte pour être un vrai casse-tête — retente une autre procédure si possible.
            candidates.remove(procedure);
            if (!candidates.isEmpty()) {
                procedure = candidates.get(new Random().nextInt(candidates.size()));
                chain = walkChain(procedure);
            }
        }

        List<ProcessPuzzleResponse.ProcessPuzzleStep> steps = new ArrayList<>();
        for (ProcedureWorkflowNode node : chain) {
            steps.add(new ProcessPuzzleResponse.ProcessPuzzleStep(node.getNodeId(), node.getQuestionText()));
        }
        Collections.shuffle(steps);
        return new ProcessPuzzleResponse(procedure.getTitle(), steps);
    }

    @Transactional(readOnly = true)
    public ProcessPuzzleResultResponse validate(ProcessPuzzleSubmitRequest request) {
        Procedure procedure = procedureRepository.findAllByOrderByTitleAsc().stream()
                .filter(p -> p.getTitle().equalsIgnoreCase(request.procedureTitle()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Parcours introuvable."));
        List<ProcedureWorkflowNode> chain = walkChain(procedure);
        List<Integer> correctOrder = chain.stream().map(ProcedureWorkflowNode::getNodeId).collect(Collectors.toList());

        List<Integer> submitted = request.orderedStepIds() == null ? List.of() : request.orderedStepIds();
        int correctPositions = 0;
        for (int i = 0; i < Math.min(correctOrder.size(), submitted.size()); i++) {
            if (correctOrder.get(i).equals(submitted.get(i))) correctPositions++;
        }
        boolean fullyCorrect = correctPositions == correctOrder.size() && submitted.size() == correctOrder.size();
        return new ProcessPuzzleResultResponse(fullyCorrect, correctPositions, correctOrder.size(), correctOrder);
    }

    /** Suit la chaîne depuis le nœud de départ en empruntant systématiquement la première option — les
     *  parcours réels de ce système sont linéaires (voir ProcedureSeedBootstrap), une branche éventuelle
     *  n'empêche pas le jeu de fonctionner, elle est simplement ignorée au profit du premier choix. */
    private List<ProcedureWorkflowNode> walkChain(Procedure procedure) {
        List<ProcedureWorkflowNode> chain = new ArrayList<>();
        Optional<ProcedureWorkflowNode> startOpt = nodeRepository.findByProcedureAndIsStartTrue(procedure);
        if (startOpt.isEmpty()) return chain;

        ProcedureWorkflowNode current = startOpt.get();
        Set<Integer> visited = new HashSet<>();
        while (current != null && visited.add(current.getNodeId()) && chain.size() < 12) {
            chain.add(current);
            List<ProcedureWorkflowOption> options = optionRepository.findByNode(current);
            if (options.isEmpty()) break;
            ProcedureWorkflowOption first = options.get(0);
            current = first.getNextNode();
        }
        return chain;
    }
}
