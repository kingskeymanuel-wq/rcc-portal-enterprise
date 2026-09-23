package com.ecobank.rccportal.service;

import org.springframework.stereotype.Service;

/**
 * Façade de haut niveau au-dessus de {@link CopilotStudioClient}, pour qu'aucun contrôleur
 * ni service métier ne dépende directement du protocole Direct Line. Aujourd'hui RAF est le
 * seul consommateur, mais cette couche est prévue pour être réutilisée telle quelle par de
 * futurs agents (RCC360 AI, QA AI, Management AI, Marketing AI...) sur le même canal.
 */
@Service
public class CopilotAgentService {

    private final CopilotStudioClient copilotStudioClient;

    public CopilotAgentService(CopilotStudioClient copilotStudioClient) {
        this.copilotStudioClient = copilotStudioClient;
    }

    public boolean isConfigured() {
        return copilotStudioClient.isConfigured();
    }

    public String ask(String question) {
        return copilotStudioClient.ask(question);
    }
}
