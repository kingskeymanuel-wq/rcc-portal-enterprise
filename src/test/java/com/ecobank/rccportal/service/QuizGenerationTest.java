package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Évaluation générée sans IA à partir d'un support de formation. */
class QuizGenerationTest {

    static final String SUPPORT = """
            La réclamation GAB doit être enregistrée dans le CRM le jour même de l'appel du client.
            Le conseiller vérifie l'identité du client avant toute ouverture de réclamation GAB.
            Le délai de traitement d'une réclamation GAB est de sept jours ouvrés après l'enregistrement.
            Le remboursement est effectué sur le compte du client après validation par le service monétique.
            Le service monétique contrôle le journal du GAB pour confirmer que le retrait n'a pas été servi.
            Une carte capturée par le GAB est détruite si elle n'est pas récupérée en agence sous quinze jours.
            Le conseiller informe le client du numéro de réclamation généré par le CRM à la fin de l'appel.
            Pour une opposition, le conseiller bloque immédiatement la carte dans l'outil de gestion des cartes.
            La nouvelle carte est commandée par l'agence du client après l'opposition de la carte perdue.
            """;

    @Test
    void localGeneratorBuildsValidMultipleChoiceQuestions() {
        List<QuizGenerationService.Draft> drafts = QuizGenerationService.localDrafts(SUPPORT, 5, new Random(1));
        assertFalse(drafts.isEmpty());
        assertTrue(drafts.size() <= 5);
        for (QuizGenerationService.Draft d : drafts) {
            assertEquals(4, d.options().size(), d.question());
            assertEquals(4, d.options().stream().map(String::toLowerCase).distinct().count(), "réponses en double : " + d.options());
            assertTrue(d.correct() >= 0 && d.correct() < 4);
            assertTrue(d.question().contains("_____"));
            // La bonne réponse remet la phrase du support en place.
            String rebuilt = d.question().replace("Complétez selon le support : « ", "").replace(" »", "")
                    .replace("_____", d.options().get(d.correct()));
            assertEquals(d.explanation(), rebuilt);
        }
    }

    @Test
    void sameTextGivesSameEvaluation() {
        assertEquals(QuizGenerationService.localDrafts(SUPPORT, 4, new Random(7)),
                QuizGenerationService.localDrafts(SUPPORT, 4, new Random(7)));
    }

    @Test
    void aiAnswerIsParsedAndChecked() {
        QuizGenerationService svc = new QuizGenerationService(null, null, null);
        String raw = "Voici : [{\"question\":\"Délai GAB ?\",\"options\":[\"7 jours ouvrés\",\"24 h\",\"30 jours\",\"1 an\"],\"correct\":0,\"explanation\":\"x\"},"
                + "{\"question\":\"Sans bonne réponse\",\"options\":[\"a\",\"b\"],\"correct\":5}]";
        List<QuizGenerationService.Draft> drafts = svc.parseAiDrafts(raw);
        assertEquals(1, drafts.size());
        assertEquals("7 jours ouvrés", drafts.get(0).options().get(drafts.get(0).correct()));
    }
}
