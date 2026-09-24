package com.ecobank.rccportal.raf;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Phrases propres à RAF en fr / en / pt / es. Les contenus du portail (procédures, SLA,
 * définitions...) restent dans leur langue d'origine : RAF ne les réécrit jamais.
 */
public final class RafText {

    private RafText() {
    }

    private static final Map<String, Map<String, String>> TEXTS = Map.of(
            "fr", Map.ofEntries(
                    Map.entry("hello", "Bonjour ! Je suis RAF, ton assistant RCC. Je réponds uniquement à partir des données du portail : procédures, SLA, glossaire, agences, modèles de mail, ton planning…"),
                    Map.entry("chat.fallback", "Je ne suis pas sûr d'avoir bien compris 🤔 On discute, ou tu cherches une info précise ? Donne-moi un mot-clé (carte, GAB, SLA, RIB, agence…) ou choisis une piste ci-dessous."),
                    Map.entry("thanks", "Avec plaisir ! Autre chose ?"),
                    Map.entry("bye", "À bientôt !"),
                    Map.entry("howareyou", "Tout va bien, merci ! Sur quoi je t'aide ?"),
                    Map.entry("help", "Voici ce que je sais faire, sans IA externe et uniquement avec les données du portail :"),
                    Map.entry("nothing", "Je n'ai rien trouvé de fiable dans le portail pour « {0} ». Je préfère te le dire plutôt que d'inventer."),
                    Map.entry("nothing.tip", "Essaie avec le nom exact du produit ou de la procédure, ou choisis une piste ci-dessous."),
                    Map.entry("clarify", "Tu parles de laquelle ?"),
                    Map.entry("seeAlso", "À voir aussi"),
                    Map.entry("complement", "Complément"),
                    Map.entry("synth.qa", "Réponse validée par la QA : **{0}**."),
                    Map.entry("synth.from", "D'après « {0} » :"),
                    Map.entry("synth.proc", "Pour le client : la procédure **« {0} »** compte {1} étape(s) — la première : {2} Lance le mode guidé pour la dérouler."),
                    Map.entry("synth.sla", "Délai officiel : **{0}** ({1})."),
                    Map.entry("synth.sources", "Sources croisées : {0}"),
                    Map.entry("details.intro", "Voici le détail sur « {0} », en croisant {1} source(s) du portail :"),
                    Map.entry("details", "Détails"),
                    Map.entry("guided.start", "Mode guidé"),
                    Map.entry("guided.step", "Étape {0}/{1}"),
                    Map.entry("guided.done", "Procédure terminée. Pense au délai à annoncer au client et au message à envoyer."),
                    Map.entry("next", "Étape suivante"),
                    Map.entry("prev", "Étape précédente"),
                    Map.entry("stop", "Quitter le mode guidé"),
                    Map.entry("sla.due", "À annoncer au client : au plus tard le {0}"),
                    Map.entry("sla.unknown", "Ce motif ne figure pas dans le référentiel SLA officiel. Je ne donne pas de délai estimé : vérifie auprès de ton superviseur."),
                    Map.entry("sla.holidays", "(hors jours fériés, non référencés dans le portail)"),
                    Map.entry("login.required", "Connecte-toi pour que je puisse consulter ton planning."),
                    Map.entry("callcard.title", "Fiche appel"),
                    Map.entry("callcard.do", "Ce que je fais"),
                    Map.entry("callcard.say", "Ce que je dis au client"),
                    Map.entry("callcard.route", "Où transmettre"),
                    Map.entry("callcard.template", "Modèle à envoyer"),
                    Map.entry("callcard.script", "Votre demande est bien prise en compte. Le délai de traitement prévu est de {0}{1}."),
                    Map.entry("callcard.scriptDue", ", soit au plus tard le {0}"),
                    Map.entry("draft.missing", "Champs à compléter : {0}"),
                    Map.entry("dataFrench", "Les contenus du portail sont affichés dans leur langue d'origine (français).")),
            "en", Map.ofEntries(
                    Map.entry("hello", "Hello! I'm RAF, your RCC assistant. I only answer from portal data: procedures, SLAs, glossary, branches, mail templates, your schedule…"),
                    Map.entry("chat.fallback", "I'm not sure I got that 🤔 Are we chatting, or are you looking for something specific? Give me a keyword (card, ATM, SLA, branch…) or pick a topic below."),
                    Map.entry("thanks", "You're welcome! Anything else?"),
                    Map.entry("bye", "See you soon!"),
                    Map.entry("howareyou", "All good, thanks! What can I help with?"),
                    Map.entry("help", "Here is what I can do, with no external AI and only portal data:"),
                    Map.entry("nothing", "I found nothing reliable in the portal for \"{0}\". I'd rather say so than make something up."),
                    Map.entry("nothing.tip", "Try the exact product or procedure name, or pick a suggestion below."),
                    Map.entry("clarify", "Which one do you mean?"),
                    Map.entry("seeAlso", "See also"),
                    Map.entry("complement", "More on this"),
                    Map.entry("synth.qa", "QA-validated answer: **{0}**."),
                    Map.entry("synth.from", "According to “{0}”:"),
                    Map.entry("synth.proc", "For the customer: the procedure **“{0}”** has {1} step(s) — the first one: {2} Start guided mode to walk through it."),
                    Map.entry("synth.sla", "Official delay: **{0}** ({1})."),
                    Map.entry("synth.sources", "Sources cross-checked: {0}"),
                    Map.entry("details.intro", "Here are the details on \"{0}\", cross-checked from {1} portal source(s):"),
                    Map.entry("details", "Details"),
                    Map.entry("guided.start", "Guided mode"),
                    Map.entry("guided.step", "Step {0}/{1}"),
                    Map.entry("guided.done", "Procedure complete. Remember the delay to announce and the message to send."),
                    Map.entry("next", "Next step"),
                    Map.entry("prev", "Previous step"),
                    Map.entry("stop", "Exit guided mode"),
                    Map.entry("sla.due", "Tell the customer: by {0} at the latest"),
                    Map.entry("sla.unknown", "This reason is not in the official SLA table. I won't estimate a delay: check with your supervisor."),
                    Map.entry("sla.holidays", "(public holidays excluded, not listed in the portal)"),
                    Map.entry("login.required", "Log in so I can check your schedule."),
                    Map.entry("callcard.title", "Call card"),
                    Map.entry("callcard.do", "What I do"),
                    Map.entry("callcard.say", "What I tell the customer"),
                    Map.entry("callcard.route", "Where to route"),
                    Map.entry("callcard.template", "Template to send"),
                    Map.entry("callcard.script", "Your request has been registered. The expected processing time is {0}{1}."),
                    Map.entry("callcard.scriptDue", ", i.e. by {0} at the latest"),
                    Map.entry("draft.missing", "Fields to complete: {0}"),
                    Map.entry("dataFrench", "Portal content is shown in its original language (French).")),
            "pt", Map.ofEntries(
                    Map.entry("hello", "Olá! Sou o RAF, o seu assistente RCC. Respondo apenas com dados do portal: procedimentos, SLA, glossário, agências, modelos de e-mail, o seu horário…"),
                    Map.entry("chat.fallback", "Não tenho a certeza de ter percebido 🤔 Estamos a conversar ou procura uma informação? Dê-me uma palavra-chave (cartão, ATM, SLA, agência…) ou escolha abaixo."),
                    Map.entry("thanks", "De nada! Mais alguma coisa?"),
                    Map.entry("bye", "Até breve!"),
                    Map.entry("howareyou", "Tudo bem, obrigado! Em que posso ajudar?"),
                    Map.entry("help", "Eis o que sei fazer, sem IA externa e apenas com dados do portal:"),
                    Map.entry("nothing", "Não encontrei nada fiável no portal para « {0} ». Prefiro dizê-lo do que inventar."),
                    Map.entry("nothing.tip", "Tente com o nome exato do produto ou do procedimento, ou escolha uma sugestão abaixo."),
                    Map.entry("clarify", "Qual delas?"),
                    Map.entry("seeAlso", "Ver também"),
                    Map.entry("complement", "Complemento"),
                    Map.entry("synth.qa", "Resposta validada pela QA: **{0}**."),
                    Map.entry("synth.from", "Segundo « {0} »:"),
                    Map.entry("synth.proc", "Para o cliente: o procedimento **« {0} »** tem {1} passo(s) — o primeiro: {2} Inicie o modo guiado para o seguir."),
                    Map.entry("synth.sla", "Prazo oficial: **{0}** ({1})."),
                    Map.entry("synth.sources", "Fontes cruzadas: {0}"),
                    Map.entry("details.intro", "Eis os detalhes sobre « {0} », cruzando {1} fonte(s) do portal:"),
                    Map.entry("details", "Detalhes"),
                    Map.entry("guided.start", "Modo guiado"),
                    Map.entry("guided.step", "Passo {0}/{1}"),
                    Map.entry("guided.done", "Procedimento concluído. Lembre-se do prazo a anunciar e da mensagem a enviar."),
                    Map.entry("next", "Passo seguinte"),
                    Map.entry("prev", "Passo anterior"),
                    Map.entry("stop", "Sair do modo guiado"),
                    Map.entry("sla.due", "A anunciar ao cliente: o mais tardar a {0}"),
                    Map.entry("sla.unknown", "Este motivo não consta da tabela oficial de SLA. Não estimo prazos: confirme com o seu supervisor."),
                    Map.entry("sla.holidays", "(feriados excluídos, não referenciados no portal)"),
                    Map.entry("login.required", "Inicie sessão para que eu possa consultar o seu horário."),
                    Map.entry("callcard.title", "Ficha de chamada"),
                    Map.entry("callcard.do", "O que faço"),
                    Map.entry("callcard.say", "O que digo ao cliente"),
                    Map.entry("callcard.route", "Para onde encaminhar"),
                    Map.entry("callcard.template", "Modelo a enviar"),
                    Map.entry("callcard.script", "O seu pedido foi registado. O prazo de tratamento previsto é de {0}{1}."),
                    Map.entry("callcard.scriptDue", ", ou seja, o mais tardar a {0}"),
                    Map.entry("draft.missing", "Campos a completar: {0}"),
                    Map.entry("dataFrench", "O conteúdo do portal é apresentado na língua original (francês).")),
            "es", Map.ofEntries(
                    Map.entry("hello", "¡Hola! Soy RAF, tu asistente RCC. Solo respondo con datos del portal: procedimientos, SLA, glosario, agencias, plantillas de correo, tu horario…"),
                    Map.entry("chat.fallback", "No estoy seguro de haberte entendido 🤔 ¿Charlamos o buscas algo concreto? Dame una palabra clave (tarjeta, cajero, SLA, agencia…) o elige abajo."),
                    Map.entry("thanks", "¡De nada! ¿Algo más?"),
                    Map.entry("bye", "¡Hasta pronto!"),
                    Map.entry("howareyou", "¡Todo bien, gracias! ¿En qué te ayudo?"),
                    Map.entry("help", "Esto es lo que sé hacer, sin IA externa y solo con datos del portal:"),
                    Map.entry("nothing", "No encontré nada fiable en el portal para « {0} ». Prefiero decirlo antes que inventar."),
                    Map.entry("nothing.tip", "Prueba con el nombre exacto del producto o del procedimiento, o elige una sugerencia."),
                    Map.entry("clarify", "¿Cuál de ellas?"),
                    Map.entry("seeAlso", "Ver también"),
                    Map.entry("complement", "Complemento"),
                    Map.entry("synth.qa", "Respuesta validada por la QA: **{0}**."),
                    Map.entry("synth.from", "Según « {0} »:"),
                    Map.entry("synth.proc", "Para el cliente: el procedimiento **« {0} »** tiene {1} paso(s) — el primero: {2} Inicia el modo guiado para seguirlo."),
                    Map.entry("synth.sla", "Plazo oficial: **{0}** ({1})."),
                    Map.entry("synth.sources", "Fuentes cruzadas: {0}"),
                    Map.entry("details.intro", "Aquí tienes el detalle sobre « {0} », cruzando {1} fuente(s) del portal:"),
                    Map.entry("details", "Detalles"),
                    Map.entry("guided.start", "Modo guiado"),
                    Map.entry("guided.step", "Paso {0}/{1}"),
                    Map.entry("guided.done", "Procedimiento terminado. Recuerda el plazo a anunciar y el mensaje a enviar."),
                    Map.entry("next", "Paso siguiente"),
                    Map.entry("prev", "Paso anterior"),
                    Map.entry("stop", "Salir del modo guiado"),
                    Map.entry("sla.due", "A anunciar al cliente: a más tardar el {0}"),
                    Map.entry("sla.unknown", "Este motivo no figura en la tabla oficial de SLA. No estimo plazos: consulta con tu supervisor."),
                    Map.entry("sla.holidays", "(festivos excluidos, no registrados en el portal)"),
                    Map.entry("login.required", "Inicia sesión para que pueda consultar tu horario."),
                    Map.entry("callcard.title", "Ficha de llamada"),
                    Map.entry("callcard.do", "Lo que hago"),
                    Map.entry("callcard.say", "Lo que digo al cliente"),
                    Map.entry("callcard.route", "A dónde transmitir"),
                    Map.entry("callcard.template", "Plantilla a enviar"),
                    Map.entry("callcard.script", "Su solicitud ha sido registrada. El plazo de tratamiento previsto es de {0}{1}."),
                    Map.entry("callcard.scriptDue", ", es decir, a más tardar el {0}"),
                    Map.entry("draft.missing", "Campos a completar: {0}"),
                    Map.entry("dataFrench", "El contenido del portal se muestra en su idioma original (francés).")));

    public static String get(String lang, String key, Object... args) {
        Map<String, String> table = TEXTS.getOrDefault(normalizeLang(lang), TEXTS.get("fr"));
        String pattern = table.getOrDefault(key, TEXTS.get("fr").getOrDefault(key, key));
        if (args.length == 0) return pattern;
        return new MessageFormat(pattern.replace("'", "''"), Locale.ROOT).format(args);
    }

    public static String normalizeLang(String lang) {
        if (lang == null) return "fr";
        String l = lang.trim().toLowerCase(Locale.ROOT);
        return l.startsWith("en") ? "en" : l.startsWith("pt") ? "pt" : l.startsWith("es") ? "es" : "fr";
    }

    /** « mardi 30/09/2026 à 14:30 » dans la langue de réponse. */
    public static String formatDateTime(String lang, java.time.LocalDateTime dt) {
        String l = normalizeLang(lang);
        Locale locale = switch (l) { case "en" -> Locale.ENGLISH; case "pt" -> Locale.forLanguageTag("pt-PT");
            case "es" -> Locale.forLanguageTag("es-ES"); default -> Locale.FRENCH; };
        String at = switch (l) { case "en" -> "at"; case "pt" -> "às"; case "es" -> "a las"; default -> "à"; };
        return dt.format(java.time.format.DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy '" + at + "' HH:mm", locale));
    }

    public static Map<String, Map<String, String>> all() {
        return TEXTS;
    }
}
