package com.ecobank.rccportal.raf.nlp;

/** Accès public aux utilitaires du routeur pour les agents (paquet différent). */
public final class IntentRouterAccess {

    private IntentRouterAccess() {
    }

    public static String stripGlossaryTriggers(String normalized) {
        return IntentRouter.stripGlossaryTriggers(normalized);
    }
}
