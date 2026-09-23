package com.ecobank.rccportal.raf.agent;

import com.ecobank.rccportal.raf.RafModels.AgentAnswer;
import com.ecobank.rccportal.raf.RafModels.RafIntent;
import com.ecobank.rccportal.raf.RafModels.RafRequest;

import java.util.Set;

/**
 * Un agent spécialisé de RAF : répond à partir d'UNE source de données du portail, cite ce
 * qu'il utilise, ne lève jamais d'exception (réponse « non trouvé » à la place) et n'invente
 * jamais de fait. Aucun appel à une IA ni au réseau.
 */
public interface RafAgent {

    String id();

    /** Libellé affiché dans « Agents consultés ». */
    String label();

    Set<RafIntent> intents();

    /** @param routerScore confiance du routeur pour l'intention de cet agent (0..1). */
    AgentAnswer answer(RafRequest request, double routerScore);

    /** Commande déterministe « raf:... » (bouton du widget) ; null si non gérée par cet agent. */
    default AgentAnswer onCommand(RafRequest request, String command) {
        return null;
    }
}
