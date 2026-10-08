package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.KnowledgeCategory;
import com.ecobank.rccportal.model.KnowledgeCountry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Bibliothèque de dispatching : clé de titre (mise à jour par titre), rubriques, filiales et dossier enveloppe. */
class KbDispatchServiceTest {

    private static KnowledgeCategory cat(int id, String code, String title, String team) {
        return KnowledgeCategory.builder().categoryId(id).code(code).title(title).team(team).sortOrder(0).build();
    }

    private static final List<KnowledgeCategory> CATS = List.of(
            cat(1, "CARTES", "Cartes bancaires", null),
            cat(2, "COMPTES", "Ouverture de compte", null),
            cat(3, "CIB_VIREMENTS", "Virements locaux & internationaux (SWIFT)", "CIB"),
            cat(4, "CIB_COMPTES", "Ouverture & gestion de compte entreprise", "CIB"));

    @Test
    void sameTitleWhateverVersionDateOrFormat() {
        String k = KbDispatchService.titleKey("Procédure carte.pdf");
        assertEquals("procedure carte", k);
        assertEquals(k, KbDispatchService.titleKey("Procedure_Carte_V2.pdf"));
        assertEquals(k, KbDispatchService.titleKey("PROCÉDURE CARTE (final) 2026-10-01.docx"));
        assertEquals(k, KbDispatchService.titleKey("Procédure carte - MAJ 2026.pdf"));
        assertEquals(k, KbDispatchService.titleKey("dossier/Procédure carte version 3.pptx"));
        assertNotEquals(k, KbDispatchService.titleKey("Procédure compte.pdf"));
    }

    @Test
    void folderMatchesItsRubricInTheRightSpace() {
        assertEquals(1, KbDispatchService.matchCategory(KbDispatchService.cleanFolder("01 - Cartes Bancaires"), "GENERAL", CATS).getCategoryId());
        assertEquals(2, KbDispatchService.matchCategory("COMPTES", "GENERAL", CATS).getCategoryId(), "par code");
        assertEquals(3, KbDispatchService.matchCategory("Virements locaux et internationaux SWIFT", "CIB", CATS).getCategoryId());
        assertNull(KbDispatchService.matchCategory("Cartes bancaires", "CIB", CATS), "rubrique générale absente de la base CIB");
        assertNull(KbDispatchService.matchCategory("Réclamations", "GENERAL", CATS), "rubrique inconnue → à créer");
    }

    @Test
    void rootFileIsGuessedFromItsTitle() {
        assertEquals(1, KbDispatchService.guessCategory(KbDispatchService.titleKey("Guide cartes bancaires Visa.pdf"), "GENERAL", CATS).getCategoryId());
        assertNull(KbDispatchService.guessCategory(KbDispatchService.titleKey("Note de service.pdf"), "GENERAL", CATS));
    }

    @Test
    void countryFolders() {
        List<KnowledgeCountry> cs = List.of(KnowledgeCountry.builder().countryCode("CI").label("Côte d'Ivoire").sortOrder(1).build(),
                KnowledgeCountry.builder().countryCode("TG").label("Togo").sortOrder(2).build());
        assertEquals("CI", KbDispatchService.matchCountry("Côte d'Ivoire", cs).getCountryCode());
        assertEquals("CI", KbDispatchService.matchCountry("ECI", cs).getCountryCode());
        assertEquals("CI", KbDispatchService.matchCountry("RCC ECI", cs).getCountryCode());
        assertEquals("TG", KbDispatchService.matchCountry("TOGO", cs).getCountryCode());
        assertEquals("TG", KbDispatchService.matchCountry("etg", cs).getCountryCode());
        assertNull(KbDispatchService.matchCountry("Cartes bancaires", cs));
    }

    private static final java.util.function.Predicate<String> WRAP = seg -> KbDispatchService.matchCategory(KbDispatchService.cleanFolder(seg), "GENERAL", CATS) != null;

    @Test
    void singleWrapperFolderIsIgnored() {
        assertEquals(1, KbDispatchService.commonWrapperDepth(WRAP, List.of(new String[]{"Base 2026", "Cartes", "a.pdf"}, new String[]{"Base 2026", "Comptes", "b.pdf"})));
        assertEquals(0, KbDispatchService.commonWrapperDepth(WRAP, List.of(new String[]{"Cartes", "a.pdf"}, new String[]{"Comptes", "b.pdf"})));
        assertEquals(0, KbDispatchService.commonWrapperDepth(WRAP, List.of(new String[]{"CIB", "Virements", "a.pdf"}, new String[]{"CIB", "Comptes", "b.pdf"})));
        assertEquals(0, KbDispatchService.commonWrapperDepth(WRAP, List.of(new String[]{"Cartes bancaires", "a.pdf"}, new String[]{"Cartes bancaires", "b.pdf"})), "une seule rubrique : pas d'enveloppe à retirer");
        assertTrue(KbDispatchService.isJunk("__MACOSX/Cartes/._a.pdf"));
        assertTrue(KbDispatchService.isJunk("Cartes/~$brouillon.docx"));
        assertFalse(KbDispatchService.isJunk("Cartes/a.pdf"));
    }
}
