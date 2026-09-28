"use strict";

/**
 * Portail des agents des canaux digitaux (/portail-tchat, /portail-rafiki) : même page, contenu propre au canal —
 * performances de la semaine (rapport hebdo importé par la QA ou le Team Leader, mis à jour automatiquement),
 * règles de calcul, planning des 7 prochains jours, outils et bonnes pratiques.
 */
(function () {
    var esc = RccApi.escapeHtml;
    var page = document.querySelector(".da-page");
    var channel = page ? page.getAttribute("data-channel") : "TCHAT";

    var CHANNELS = {
        TCHAT: {
            label: "Tchat", icon: "bi-chat-text-fill", chip: "Live chat",
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
    function iso(d) { return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }

    $("daHeroIcon").className = "bi " + c.icon;
    $("daChannelChip").innerHTML = '<i class="bi ' + c.icon + '"></i> Portail agent ' + c.label + ' · ' + c.chip;
    $("daHeroText").textContent = c.text;
    $("daPlatforms").innerHTML = c.platforms.map(function (p) { return '<span><i class="bi ' + p[0] + '"></i> ' + esc(p[1]) + '</span>'; }).join("");
    $("daRules").innerHTML = c.rules.map(function (r) {
        return '<div class="da-rule"><i class="bi ' + r[0] + '"></i><div><b>' + esc(r[1]) + '</b><small>' + esc(r[2]) + '</small></div></div>';
    }).join("");
    $("daTipsTitle").textContent = c.tipsTitle;
    $("daTips").innerHTML = c.tips.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join("");

    function loadWeek() {
        var from = new Date(), to = new Date();
        to.setDate(to.getDate() + 6);
        RccApi.getJson("/api/schedule/me?from=" + iso(from) + "&to=" + iso(to)).then(function (rows) {
            var byDate = {};
            (rows || []).forEach(function (r) { byDate[r.workDate] = r; });
            var days = [];
            for (var i = 0; i < 7; i++) { var d = new Date(); d.setDate(d.getDate() + i); days.push(d); }
            $("daWeek").innerHTML = days.map(function (d, i) {
                var r = byDate[iso(d)];
                var hours = r && r.startTime ? r.startTime.slice(0, 5) + "–" + (r.endTime || "").slice(0, 5) : "";
                var off = !r || !r.startTime;
                return '<div class="da-day' + (i === 0 ? " today" : "") + (off ? " off" : "") + '"><span>' + d.toLocaleDateString("fr-FR", { weekday: "short", day: "2-digit" }) +
                    '</span><b>' + esc(r ? r.shiftCode : "—") + '</b><small>' + esc(hours || (r ? r.shiftLabel || "" : "Non planifié")) + '</small></div>';
            }).join("");
            var today = byDate[iso(new Date())];
            $("daToday").innerHTML = today && today.startTime
                ? '<i class="bi bi-clock"></i> Aujourd\'hui : <b>' + esc(today.shiftLabel || today.shiftCode) + '</b> · ' + today.startTime.slice(0, 5) + ' – ' + (today.endTime || "").slice(0, 5)
                : '<i class="bi bi-cup-hot"></i> ' + (today ? esc(today.shiftLabel || today.shiftCode) + " aujourd'hui" : "Pas de shift planifié aujourd'hui");
        }).catch(function () {
            $("daWeek").innerHTML = '<div class="text-muted small">Planning indisponible.</div>';
            $("daToday").textContent = "";
        });
    }

    window.RccSession.init().then(function (session) {
        if (!session) return;
        // Les plannings écrivent le NOM en capitales d'abord (« NDIAYE Biteye Amy ») : on salue par le prénom.
        var words = String(session.user.name || session.user.username || "").trim().split(/\s+/);
        var first = words.filter(function (w) { return w.length > 1 && w !== w.toUpperCase(); })[0]
            || (words[1] ? words[1].charAt(0) + words[1].slice(1).toLowerCase() : words[0]);
        var h = new Date().getHours();
        $("daGreeting").textContent = (h < 18 ? "Bonjour " : "Bonsoir ") + first;
        loadWeek();
        RccPerfFiles.mountMine($("daPerf"), {
            emptyHtml: '<section class="da-card da-empty"><i class="bi bi-hourglass-split"></i><div><b>Vos performances ' + esc(c.label) + ' arrivent bientôt</b>' +
                '<small>Elles s\'affichent ici dès que la QA ou votre Team Leader importe le rapport de la semaine — sans recharger la page.</small></div></section>'
        });
    });
})();
