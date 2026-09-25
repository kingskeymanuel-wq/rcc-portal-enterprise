package com.ecobank.rccportal.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MailFieldSuggestionServiceTest {

    private final MailFieldSuggestionService svc = new MailFieldSuggestionService(null, null, null, null, null);

    @Test
    void clientDataIsNeverLearnedNorSuggestedToOthers() {
        for (String k : List.of("NOM_DU_CLIENT", "NUMERO_DE_COMPTE", "CONTACT", "ADRESSE_EMAIL", "MONTANT_TRANSACTION", "REFERENCE", "PIN_MASQUE",
                "NUMERO_DE_CARTE", "ID_CARTE", "CLE_ACTIVATION", "NOM", "COMPTE_A_AJOUTER", "NUMERO_ORANGE")) {
            assertTrue(MailFieldSuggestionService.isPersonal(k), k);
        }
        for (String k : List.of("NOM_CONSEILLER", "NOM_AGENT", "AGENCE", "TYPE_DE_CARTE", "DELAI", "MOTIF", "COORDONNEES_DEMANDEES", "PIECES_A_FOURNIR", "DATE", "PRODUIT")) {
            assertFalse(MailFieldSuggestionService.isPersonal(k), k);
        }
    }

    @Test
    void eachFieldGetsTheRightKindOfSuggestion() {
        var coord = svc.suggest("COORDONNEES_DEMANDEES", List.of(), "Awa Koné");
        assertEquals("MULTI", coord.kind());
        assertTrue(coord.options().containsAll(List.of("Numéro de téléphone", "Adresse e-mail", "Adresse de domicile")));
        assertEquals("MULTI", svc.suggest("PIECES_A_FOURNIR", List.of(), null).kind());
        var advisor = svc.suggest("NOM_CONSEILLER", List.of(), "Awa Koné");
        assertEquals("PREFILL", advisor.kind());
        assertEquals("Awa Koné", advisor.prefill());
        assertEquals("CHOICE", svc.suggest("DELAI", List.of(), null).kind());
        assertEquals("FCFA (XOF)", svc.suggest("DEVISE", List.of(), null).prefill());
        assertNotNull(svc.suggest("DATE", List.of(), null).prefill());
        var client = svc.suggest("NOM_DU_CLIENT", List.of("Yao"), null);
        assertEquals("FREE", client.kind());
        assertTrue(client.personal());
        assertTrue(client.options().isEmpty());
        var motif = svc.suggest("MOTIF", List.of("Remboursement frais"), null);
        assertEquals("Remboursement frais", motif.options().get(0));   // réponses des collègues en tête
        var unknown = svc.suggest("CANAL_DE_RETOUR_SOUHAITE", List.of(), null);
        assertEquals("CHOICE", unknown.kind());                          // « CANAL » reconnu dans un nom composé
    }

    @Test
    void creatingACoordinatesRequestSuggestsTheRightFields() {
        var fields = svc.suggestFields("Demande de coordonnées", "Bonjour [NOM_DU_CLIENT],\nAfin de mettre à jour votre dossier…");
        var keys = fields.stream().map(MailFieldSuggestionService.SuggestedField::key).toList();
        assertTrue(keys.containsAll(List.of("NUMERO_DE_COMPTE", "COORDONNEES_DEMANDEES", "PIECES_A_FOURNIR", "DELAI", "NOM_CONSEILLER")), keys.toString());
        assertFalse(keys.contains("NOM_DU_CLIENT"));                    // déjà dans le masque
        assertEquals("Coordonnées demandées", MailFieldSuggestionService.humanize("COORDONNEES_DEMANDEES"));
        assertEquals("Pièces à fournir", MailFieldSuggestionService.humanize("PIECES_A_FOURNIR"));
        var card = svc.suggestFields("Opposition sur votre carte", "").stream().map(MailFieldSuggestionService.SuggestedField::key).toList();
        assertTrue(card.contains("TYPE_DE_CARTE"));
    }
}
