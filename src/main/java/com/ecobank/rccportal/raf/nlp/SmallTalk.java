package com.ecobank.rccportal.raf.nlp;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Reconnaissance de la conversation courante (pas une question métier) : salutations, « comment
 * vas-tu », humeur de l'agent, remerciements, identité de RAF, plaisanteries, frustration…
 * Travaille sur le texte normalisé (minuscules, sans accents ni ponctuation — SearchText.normalize).
 * Les formulations sont reconnues par motifs, pas par liste exacte : « comment vas tu »,
 * « comment tu vas », « ça va raf ? », « bonjour, tu vas bien ? » tombent dans la même catégorie.
 */
public final class SmallTalk {

    public enum Kind {
        GREETING, HOW_ARE_YOU, MOOD_GOOD, MOOD_BAD, THANKS, BYE, WHO_ARE_YOU, CAPABILITIES, COMPLIMENT,
        FRUSTRATION, JOKE, TIME, DATE, ACK, PRESENCE, WISHES
    }

    /** limit = nombre de mots maximal du message pour que la règle s'applique. */
    private record Rule(Kind kind, Pattern pattern, int limit) {}

    private static Rule rule(Kind kind, int limit, String regex) {
        return new Rule(kind, Pattern.compile("(^| )(" + regex + ")( |$)"), limit);
    }

    /** Règle qui doit couvrir TOUT le message (formules très courtes et ambiguës : « ça va », « ok »). */
    private static Rule whole(Kind kind, String regex) {
        return new Rule(kind, Pattern.compile("^(" + regex + ")$"), 99);
    }

    private static final String HELLO = "(bonjour|bonsoir|salut|coucou|hello|hey|hi|yo|bjr|slt)";

    // Ordre = priorité : la catégorie la plus spécifique d'abord (« bonjour ça va » → HOW_ARE_YOU).
    private static final List<Rule> RULES = List.of(
            rule(Kind.HOW_ARE_YOU, 8, "comment (vas|va) tu|comment tu vas|comment (ca|sa) va|comment allez vous|vous allez bien|tu vas bien|"
                    + "la forme raf|quoi de neuf|how are you|how r u|hows it going|como estas|como vai|tudo bem"),
            whole(Kind.HOW_ARE_YOU, "et toi( raf)?|et vous|and you|e tu|y tu"),
            whole(Kind.HOW_ARE_YOU, "(" + HELLO + " )?(raf )?((ca|sa) va|cv|la forme|ca roule)( raf| toi| bien)?"),
            rule(Kind.MOOD_BAD, 10, "je suis (fatigue|fatiguee|creve|crevee|epuise|epuisee|stresse|stressee|deborde|debordee|decourage|decouragee|triste|"
                    + "enerve|enervee|a bout)|(ca|sa) va pas|pas (trop|tres) bien|pas la forme|journee (difficile|compliquee|dure|chargee)|"
                    + "j en ai marre|ras le bol|i m tired|i am tired|i m stressed|bad day"),
            whole(Kind.MOOD_BAD, "(un peu |trop |tres )?(fatigue|fatiguee|creve|crevee|epuise|epuisee|stresse|stressee|bof|moyen|pas terrible)( et toi)?"),
            rule(Kind.MOOD_GOOD, 6, "je vais (bien|tres bien)|(bien|super|tres bien|ca va) et toi|tres bien merci|tout va bien|"
                    + "i m fine|i am fine|good and you|fine thanks|estou bem|estoy bien"),
            whole(Kind.MOOD_GOOD, "(ca va )?(bien|tres bien|impeccable|au top|en forme|la forme|ca va bien|ca va tres bien)( merci)?( raf)?"),
            rule(Kind.WHO_ARE_YOU, 8, "qui es tu|tu es qui|t es qui|qui etes vous|c est quoi raf|qui est raf|ton nom|tu t appelles comment|comment tu t appelles|"
                    + "tu es une ia|tu es un robot|es tu une ia|es tu un robot|qui t a (cree|fait|developpe|concu)|who are you|what are you|quem es tu|quien eres"),
            rule(Kind.CAPABILITIES, 6, "que sais tu faire|tu sais faire quoi|tu fais quoi|a quoi tu sers|tu peux faire quoi|que peux tu faire|"
                    + "comment tu marches|comment tu fonctionnes|what can you do"),
            rule(Kind.JOKE, 7, "(raconte|dis|fais) (moi )?une (blague|histoire drole)|une blague|fais moi rire|tell me a joke|conta uma piada|cuentame un chiste"),
            whole(Kind.TIME, "(raf )?(quelle heure est il|il est quelle heure|tu as l heure|what time is it)"),
            whole(Kind.DATE, "(raf )?(on est quel jour|quel jour sommes nous|quel jour on est|on est le combien|quelle est la date|la date d aujourd hui|what day is it)"),
            rule(Kind.FRUSTRATION, 8, "tu es nul|t es nul|tu sers a rien|n importe quoi|tu comprends rien|tu ne comprends pas|tu comprends pas|"
                    + "c est pas normal|ce n est pas normal|c est nul|c est faux|mauvaise reponse|reponds moi|tu ne reponds pas|you are useless|you are wrong"),
            rule(Kind.COMPLIMENT, 6, "tu es (genial|geniale|top|super|fort|forte|le meilleur|la meilleure|gentil|gentille)|t es (genial|top|super|fort)|bravo|"
                    + "bien joue|excellent|trop fort|good job|great job|well done"),
            rule(Kind.THANKS, 5, "merci|merci beaucoup|merci bien|merci raf|je te remercie|thanks|thank you|thx|obrigad[oa]|gracias"),
            rule(Kind.BYE, 6, "au revoir|a plus|a toute|a bientot|a demain|bye|bye bye|ciao|tchao|bonne nuit|see you|good night|adeus|adios|hasta luego"),
            rule(Kind.WISHES, 6, "bon appetit|bon week end|bonne soiree|bonne fete|joyeux noel|bonne annee|have a nice day"),
            whole(Kind.PRESENCE, "(" + HELLO + " )?(raf )?(t es la|tu es la|es tu la|tu m entends|allo|are you there|you there)( raf)?"),
            whole(Kind.GREETING, HELLO + "( raf| a toi| tout le monde)?|bonjour bonjour|ola|hola|bom dia|buenos dias|good morning|good evening|bonne journee"),
            whole(Kind.ACK, "ok|okay|ok merci|d accord|dac|ca marche|entendu|compris|cool|super|top|parfait|nickel|je vois|ah bon|ah ok|genial")
    );

    private SmallTalk() {}

    /** Catégorie conversationnelle du message, ou null si c'est (aussi) une vraie question métier. */
    public static Kind detect(String normalized) {
        if (normalized == null) return null;
        String n = normalized.trim();
        if (n.isEmpty()) return null;
        int words = n.split(" ").length;
        for (Rule r : RULES) {
            if (words <= r.limit() && r.pattern().matcher(n).find()) return r.kind();
        }
        return null;
    }

    public static boolean isConversational(String normalized) {
        return detect(normalized) != null;
    }

    private static final Pattern REPLY_GOOD = Pattern.compile("^(oui|ouais|oui oui|oui ca va|oui merci|oui et toi|moi aussi|pareil|ca va|"
            + "plutot bien|pas mal|tranquille|ras|yes|yeah|sim|si)( merci)?( et toi)?$");
    private static final Pattern REPLY_BAD = Pattern.compile("^(non|pas vraiment|pas trop|non pas trop|non pas vraiment|bof|no|not really|nao|no mucho)( et toi)?$");

    /**
     * Comme {@link #detect(String)}, mais en tenant compte de la réplique précédente : après
     * « comment vas-tu ? » (ou un bonjour de RAF qui demande comment ça va), un simple « oui »,
     * « pareil » ou « non pas trop » est une réponse d'humeur, pas une commande.
     */
    public static Kind detect(String normalized, String previousUserMessage) {
        Kind direct = detect(normalized);
        if (direct != null || normalized == null || previousUserMessage == null) return direct;
        Kind previous = detect(com.ecobank.rccportal.util.SearchText.normalize(previousUserMessage));
        if (previous != Kind.HOW_ARE_YOU && previous != Kind.GREETING && previous != Kind.MOOD_GOOD && previous != Kind.MOOD_BAD) return null;
        String n = normalized.trim();
        if (REPLY_GOOD.matcher(n).matches()) return Kind.MOOD_GOOD;
        if (REPLY_BAD.matcher(n).matches()) return Kind.MOOD_BAD;
        return null;
    }

    /** Contexte de dialogue : message précédent de l'utilisateur s'il s'agissait de conversation. */
    public static Kind detect(com.ecobank.rccportal.raf.RafModels.RafRequest request) {
        var state = request.state();
        String previous = state != null && state.lastIntent() == com.ecobank.rccportal.raf.RafModels.RafIntent.SMALL_TALK
                ? state.lastQuestion() : null;
        return detect(request.normalized(), previous);
    }
}
