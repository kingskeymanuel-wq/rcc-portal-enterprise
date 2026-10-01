package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CampaignFieldDto;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CampaignFormEngineTest {

    static ObjectNode parse(String json) throws Exception {
        return (ObjectNode) CampaignFormEngine.JSON.readTree(json.replace('\'', '"'));
    }

    @Test
    void everyTemplateIsAValidForm() {
        for (CampaignFormTemplates.Template t : CampaignFormTemplates.all()) {
            ObjectNode f = CampaignFormEngine.normalize(t.form());
            assertFalse(CampaignFormEngine.questions(f).isEmpty(), t.code());
            assertFalse(CampaignFormEngine.toLegacyFields(f).isEmpty(), t.code());
        }
    }

    @Test
    void conditionalLogicScoreAndSuggestedOutcome() {
        ObjectNode form = CampaignFormEngine.normalize(CampaignFormTemplates.byCode("PRET").orElseThrow().form());
        Map<String, String> a = new HashMap<>();
        a.put("joignable", "Oui, disponible");
        a.put("interet", "Très intéressé");
        a.put("projet", "[\"Véhicule\",\"Travaux / habitat\"]");
        a.put("montant", "2 500 000");
        a.put("revenu", "Salaire domicilié");
        a.put("suite", "Rendez-vous en agence");
        a.put("motif_refus", "doit disparaître : question masquée");
        CampaignFormEngine.Evaluation e = CampaignFormEngine.evaluate(form, a);
        assertTrue(e.valid(), e.errors().toString());
        assertFalse(e.visible().contains("motif_refus"));
        assertFalse(e.answers().containsKey("motif_refus"), "réponse d'une question masquée retirée");
        assertEquals(30 + 20 + 25 + 40, e.score());
        assertEquals("YELLOW", e.suggestedStatus(), "option « Rendez-vous en agence » → RDV pris");

        // Pas intéressé : la section « Suite à donner » disparaît, sa question obligatoire n'est plus exigée.
        Map<String, String> b = Map.of("joignable", "Oui, disponible", "interet", "Pas intéressé", "motif_refus", "Taux trop élevé");
        CampaignFormEngine.Evaluation e2 = CampaignFormEngine.evaluate(form, b);
        assertTrue(e2.valid(), e2.errors().toString());
        assertFalse(e2.visible().contains("suite"));
        assertEquals("RED", e2.suggestedStatus());
    }

    @Test
    void validationRules() throws Exception {
        ObjectNode form = CampaignFormEngine.normalize(parse("""
                {'sections':[{'title':'A','questions':[
                  {'id':'mail','type':'EMAIL','label':'E-mail','required':true},
                  {'id':'tel','type':'PHONE','label':'Téléphone'},
                  {'id':'n','type':'NUMBER','label':'Nombre','validation':{'min':1,'max':10}},
                  {'id':'nps','type':'NPS','label':'NPS'},
                  {'id':'multi','type':'MULTIPLE','label':'Multi','options':['A','B','C'],'validation':{'maxSelect':2}},
                  {'id':'grid','type':'MATRIX','label':'Grille','required':true,'options':['Oui','Non'],'rows':['L1','L2']},
                  {'id':'ok','type':'CONSENT','label':'Accord','required':true}]}]}"""));
        Map<String, String> bad = Map.of("mail", "pas-un-mail", "tel", "12", "n", "42", "nps", "11",
                "multi", "[\"A\",\"B\",\"C\"]", "grid", "{\"L1\":\"Oui\"}", "ok", "false");
        Map<String, String> errors = CampaignFormEngine.evaluate(form, bad).errors();
        assertEquals(List.of("mail", "tel", "n", "nps", "multi", "grid", "ok"), List.copyOf(errors.keySet()));
        Map<String, String> good = Map.of("mail", "a.b@ecobank.com", "tel", "+225 07 07 07 07 07", "n", "3", "nps", "9",
                "multi", "[\"A\",\"C\"]", "grid", "{\"L1\":\"Oui\",\"L2\":\"Non\"}", "ok", "true");
        assertTrue(CampaignFormEngine.evaluate(form, good).valid());
    }

    @Test
    void structureErrorsAreExplained() throws Exception {
        ApiException forward = assertThrows(ApiException.class, () -> CampaignFormEngine.normalize(parse("""
                {'sections':[{'title':'A','questions':[
                  {'id':'a','type':'SHORT_TEXT','label':'A','visibleIf':{'rules':[{'q':'b','op':'eq','value':'x'}]}},
                  {'id':'b','type':'SHORT_TEXT','label':'B'}]}]}""")));
        assertTrue(forward.getMessage().contains("placée avant"));
        assertThrows(ApiException.class, () -> CampaignFormEngine.normalize(parse("{'sections':[{'questions':[{'type':'SINGLE','label':'Sans choix'}]}]}")));
        assertThrows(ApiException.class, () -> CampaignFormEngine.normalize(parse("{'sections':[]}")));
        // Identifiants en double : renommés, pas perdus.
        ObjectNode dup = CampaignFormEngine.normalize(parse("{'sections':[{'questions':[{'id':'x','type':'SHORT_TEXT','label':'1'},{'id':'x','type':'SHORT_TEXT','label':'2'}]}]}"));
        List<JsonNode> qs = CampaignFormEngine.questions(dup);
        assertNotEquals(qs.get(0).get("id").asText(), qs.get(1).get("id").asText());
    }

    @Test
    void legacyFieldsRoundTrip() {
        List<CampaignFieldDto> legacy = List.of(new CampaignFieldDto("f1", "Produit", "SELECT", List.of("Prêt", "Carte"), true),
                new CampaignFieldDto("f2", "Commentaire", "TEXTAREA", List.of(), false));
        ObjectNode form = CampaignFormEngine.normalize(CampaignFormEngine.fromLegacy(legacy));
        List<CampaignFieldDto> back = CampaignFormEngine.toLegacyFields(form);
        assertEquals("f1", back.get(0).id());
        assertEquals("SELECT", back.get(0).type());
        assertEquals(List.of("Prêt", "Carte"), back.get(0).options());
        assertEquals("TEXTAREA", back.get(1).type());
        assertFalse(CampaignFormEngine.evaluate(form, Map.of()).valid(), "Produit reste obligatoire");
    }

    @Test
    void closestTemplateFromDescription() {
        ObjectNode f = CampaignFormTemplates.closest("Enquête de satisfaction après passage en agence");
        assertTrue(CampaignFormEngine.questions(f).stream().anyMatch(q -> "NPS".equals(q.get("type").asText())));
        ObjectNode c = CampaignFormTemplates.closest("relance carte VISA gold");
        assertTrue(CampaignFormEngine.questions(c).stream().anyMatch(q -> q.get("label").asText().contains("carte")));
    }
}
