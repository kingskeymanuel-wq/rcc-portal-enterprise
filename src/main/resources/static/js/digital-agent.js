"use strict";

/**
 * Portail des agents de l'Inbound Mail (/portail-mail, /portail-tchat, /portail-rafiki) : même page, contenu propre au canal —
 * performances de la semaine (rapport hebdo importé par la QA ou le Team Leader, mis à jour automatiquement),
 * règles de calcul, planning des 7 prochains jours, outils et bonnes pratiques.
 */
(function () {
    var esc = RccApi.escapeHtml;
    var page = document.querySelector(".da-page");
    var channel = page ? page.getAttribute("data-channel") : "TCHAT";

    var CHANNELS = {
        MAIL: {
            label: "Inbound Mail", icon: "bi-envelope-paper-fill", chip: "Mails & CIS",
            text: "Vos mails assistés, dossiers CIS et rappels clients : votre production de la semaine face au target et votre planning, au même endroit.",
            platforms: [["bi-envelope-at", "Boîte mails clients"], ["bi-folder2-open", "CIS"], ["bi-telephone-outbound", "Rappels"]],
            rules: [
                ["bi-plus-slash-minus", "Total activités", "CIS + Mails assistés + Sollicitations de la semaine."],
                ["bi-graph-up", "Moyenne / jour", "Total activités ÷ jours travaillés."],
                ["bi-bullseye", "Productivité", "Moyenne / jour ÷ target / jour. Vert à partir de 100 %, orange de 90 à 99 %, rouge sous 90 %."],
                ["bi-patch-check", "Qualité", "Note du rapport hebdo, sinon moyenne de vos évaluations écrites."],
                ["bi-calendar-check", "Présence", "Taux de présence d'après le pointage."]],
            tipsTitle: "Bonnes pratiques mails",
            tips: ["Accuser réception rapidement, même avant d'avoir la réponse définitive.", "Partir d'une réponse type puis la personnaliser (nom du client, numéro de dossier).",
                "Vérifier l'identité du client avant toute information sur un compte.", "Une demande = un dossier CIS à jour, avec la date de rappel si besoin.",
                "Relire avec le correcteur avant d'envoyer : ton clair, sans jargon."]
        },
        TCHAT: {
            label: "Réseaux sociaux", icon: "bi-chat-text-fill", chip: "Live chat",
            text: "Vos conversations du live chat, votre production de la semaine face au target et votre planning, au même endroit.",
            platforms: [["bi-chat-dots", "Live chat Ecobank"]],
            rules: [
                ["bi-plus-slash-minus", "Prod globale", "Live Chat + autres activités de la semaine."],
                ["bi-bullseye", "Taux d'atteinte du target", "Prod globale ÷ target hebdo (450 pour 5 jours, proratisé en cas d'absence). Vert à partir de 100 %."],
                ["bi-calendar-check", "Taux de présence (20 %)", "Jours travaillés ÷ 5 jours de la semaine."],
                ["bi-graph-up", "Prod moyenne", "Prod globale ÷ jours travaillés."],
                ["bi-stopwatch", "DMT", "Durée moyenne de traitement d'une conversation."]],
            tipsTitle: "Bonnes pratiques live chat",
            tips: ["Saluer le client dans les premières secondes, même avant d'avoir la réponse.", "Une conversation à la fois pour les demandes sensibles (carte bloquée, fraude).",
                "Utiliser les réponses types puis les personnaliser avec le prénom du client.", "Toujours vérifier l'identité avant de parler d'un compte.",
                "Clôturer en reformulant la solution et en proposant une autre aide."]
        },
        RAFIKI: {
            label: "Rafiki", icon: "bi-chat-heart-fill", chip: "Réseaux sociaux",
            text: "Vos conversations Rafiki sur Facebook, Instagram et X : conversations résolues, délai de première réponse et planning.",
            platforms: [["bi-facebook", "Facebook"], ["bi-instagram", "Instagram"], ["bi-twitter-x", "X"]],
            rules: [
                ["bi-check2-all", "Conversations résolues", "Conversations clôturées sur Facebook, Instagram et X."],
                ["bi-plus-slash-minus", "Performance globale", "Conversations résolues + activités annexes."],
                ["bi-bullseye", "Taux de productivité (30 %)", "Performance globale ÷ target hebdo (450 pour 5 jours, proratisé). Vert à partir de 100 %."],
                ["bi-calendar-check", "Taux de présence (20 %)", "Jours travaillés ÷ 5 jours de la semaine."],
                ["bi-reply", "First Response Time", "Délai avant la première réponse au client : plus il est court, mieux c'est."]],
            tipsTitle: "Bonnes pratiques réseaux sociaux",
            tips: ["Répondre en public avec courtoisie, puis basculer en message privé pour toute donnée personnelle.",
                "Jamais de numéro de compte, de carte ou de code dans un commentaire public.", "Première réponse en moins de 2 minutes, même pour dire que la demande est prise en charge.",
                "Signaler immédiatement un faux compte ou une tentative d'arnaque au Team Leader.", "Garder le ton Ecobank : clair, bienveillant, sans jargon."]
        }
    };
    var c = CHANNELS[channel] || CHANNELS.TCHAT;
    function $(id) { return document.getElementById(id); }

    $("daHeroIcon").className = "bi " + c.icon;
    $("daChannelChip").innerHTML = '<i class="bi ' + c.icon + '"></i> Portail agent ' + c.label + ' · ' + c.chip;
    $("daHeroText").textContent = c.text;
    $("daPlatforms").innerHTML = c.platforms.map(function (p) { return '<span><i class="bi ' + p[0] + '"></i> ' + esc(p[1]) + '</span>'; }).join("");
    $("daRules").innerHTML = c.rules.map(function (r) {
        return '<div class="da-rule"><i class="bi ' + r[0] + '"></i><div><b>' + esc(r[1]) + '</b><small>' + esc(r[2]) + '</small></div></div>';
    }).join("");
    $("daTipsTitle").textContent = c.tipsTitle;
    $("daTips").innerHTML = c.tips.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join("");

    /** Bandeau « aujourd'hui » du portail, tenu à jour par le bloc « Mon direct » (planning en temps réel). */
    function showToday(st) {
        var box = $("daToday");
        if (st.kind === "on") {
            box.innerHTML = '<i class="bi bi-broadcast"></i> En poste : <b>' + esc(st.entry.shiftLabel || st.entry.shiftCode) + '</b> · ' +
                String(st.entry.startTime).slice(0, 5) + ' – ' + String(st.entry.endTime).slice(0, 5);
        } else if (st.kind === "next") {
            box.innerHTML = '<i class="bi bi-clock"></i> Prochain shift : <b>' + esc(st.entry.shiftCode) + '</b> · ' +
                st.w.start.toLocaleDateString("fr-FR", { weekday: "short", day: "2-digit" }) + ' à ' + String(st.entry.startTime).slice(0, 5);
        } else {
            box.innerHTML = '<i class="bi bi-cup-hot"></i> Aucun shift à venir';
        }
    }

    window.RccSession.init().then(function (session) {
        if (!session) return;
        // Les plannings écrivent le NOM en capitales d'abord (« NDIAYE Biteye Amy ») : on salue par le prénom.
        var words = String(session.user.name || session.user.username || "").trim().split(/\s+/);
        var first = words.filter(function (w) { return w.length > 1 && w !== w.toUpperCase(); })[0]
            || (words[1] ? words[1].charAt(0) + words[1].slice(1).toLowerCase() : words[0]);
        var h = new Date().getHours();
        $("daGreeting").textContent = (h < 18 ? "Bonjour " : "Bonsoir ") + first;
        RccAgentLive.mount($("agentLive"), { onToday: showToday });
        RccPerfFiles.mountMine($("daPerf"), {
            emptyHtml: '<section class="da-card da-empty"><i class="bi bi-hourglass-split"></i><div><b>Vos performances ' + esc(c.label) + ' arrivent bientôt</b>' +
                '<small>Elles s\'affichent ici dès que la QA ou votre Team Leader importe le rapport de la semaine — sans recharger la page.</small></div></section>'
        });
    });
})();
