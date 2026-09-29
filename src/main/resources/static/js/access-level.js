"use strict";

/**
 * Administration — contrôle partagé « Niveau d'accès » (organigramme ET annuaire).
 * Un seul geste change tout l'accès d'une personne (Team Leader → Agent, changement d'équipe, QA, RH…) via
 * PUT /api/admin/users/{id}/access-level : l'ancien accès est retiré d'un bloc côté serveur, aucun reste.
 * Chaque modification de rôle, service, statut ou niveau émet l'événement « rcc:access-changed » : l'organigramme
 * et l'annuaire se rechargent ensemble et restent synchronisés ; les portails appliquent le nouvel accès aussitôt.
 */
(function () {
    var esc = RccApi.escapeHtml;

    var LEVELS = [
        ["AGENT", "Agent"], ["TEAM_LEADER", "Team Leader"], ["QA", "Quality Assurance"], ["FORMATEUR", "Formateur"],
        ["HEAD_QA", "Head QA (Superviseur QA)"], ["SUPERVISEUR", "Superviseur · Head RCC"], ["RH", "Ressources Humaines"],
        ["AGENCE", "Agence"], ["ADMIN", "Administrateur"]
    ];
    var TEAMS = [["INBOUND_VOICE", "Inbound Voix"], ["INBOUND_MAIL", "Inbound Mail"], ["TCHAT", "Tchat"], ["RAFIKI", "Rafiki"], ["CIB", "CIB"], ["OUTBOUND", "Outbound"]];
    var AGENCY_POSTS = [["CAISSIER", "Caissier"], ["GESTIONNAIRE", "Gestionnaire clientèle"]];
    /** Niveaux de l'organigramme (AdminHierarchyService) → niveau proposé par défaut. */
    var FROM_HIERARCHY = { SUPERVISEUR: "SUPERVISEUR", RH: "RH", HEAD_QA: "HEAD_QA", QA: "QA", TEAM_LEADER: "TEAM_LEADER", AGENT: "AGENT", ADMIN: "ADMIN", AGENCE: "AGENCE" };

    function label(list, code) {
        var f = list.filter(function (x) { return x[0] === code; })[0];
        return f ? f[1] : code;
    }

    function options(list, selected) {
        return list.map(function (x) { return '<option value="' + x[0] + '"' + (x[0] === selected ? " selected" : "") + '>' + esc(x[1]) + '</option>'; }).join("");
    }

    function subChoice(level) {
        if (level === "AGENT" || level === "TEAM_LEADER") return TEAMS;
        if (level === "AGENCE") return AGENCY_POSTS;
        return null;
    }

    /** Ligne de contrôle : niveau + équipe (ou poste en agence) + Appliquer. p = { id, level, team, name }. */
    function controlHtml(p) {
        var level = FROM_HIERARCHY[p.level] || "";
        var sub = subChoice(level);
        return '<div class="al-ctl" data-al-user="' + p.id + '" data-al-name="' + esc(p.name || "") + '">' +
            '<span class="al-lbl"><i class="bi bi-shield-lock"></i> Niveau d\'accès</span>' +
            '<select class="al-level" aria-label="Niveau d\'accès"><option value="">— choisir —</option>' + options(LEVELS, level) + '</select>' +
            '<select class="al-team" aria-label="Équipe"' + (sub ? "" : ' style="display:none"') + '><option value="">— équipe —</option>' +
            (sub ? options(sub, p.team || "") : "") + '</select>' +
            '<button type="button" class="al-apply" disabled>Appliquer</button></div>';
    }

    function refreshSub(ctl) {
        var level = ctl.querySelector(".al-level").value, team = ctl.querySelector(".al-team");
        var sub = subChoice(level);
        var keep = team.value;
        team.style.display = sub ? "" : "none";
        team.innerHTML = '<option value="">' + (level === "AGENCE" ? "— poste —" : "— équipe —") + '</option>' + (sub ? options(sub, keep) : "");
    }

    function ready(ctl) {
        var level = ctl.querySelector(".al-level").value, team = ctl.querySelector(".al-team").value;
        return !!level && (!(level === "AGENT" || level === "TEAM_LEADER") || !!team);
    }

    function notify(source, userId) {
        window.dispatchEvent(new CustomEvent("rcc:access-changed", { detail: { source: source, userId: userId } }));
    }

    function apply(userId, level, team) {
        return fetch("/api/admin/users/" + userId + "/access-level", {
            method: "PUT", credentials: "same-origin", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ level: level, team: team || null })
        }).then(function (res) {
            if (res.ok) return res.json();
            return res.text().then(function (t) {
                var m = t;
                try { m = JSON.parse(t).error.message; } catch (e) { /* texte brut */ }
                throw new Error(m || "HTTP " + res.status);
            });
        });
    }

    /** Branche les contrôles d'un conteneur (délégation d'événements, une seule fois par conteneur). */
    function wire(container, source, onMessage) {
        if (container.__alWired) return;
        container.__alWired = true;
        container.addEventListener("change", function (e) {
            var ctl = e.target.closest(".al-ctl");
            if (!ctl) return;
            if (e.target.classList.contains("al-level")) refreshSub(ctl);
            ctl.querySelector(".al-apply").disabled = !ready(ctl);
        });
        container.addEventListener("click", function (e) {
            var btn = e.target.closest(".al-apply");
            if (!btn) return;
            e.stopPropagation();
            var ctl = btn.closest(".al-ctl");
            var userId = ctl.getAttribute("data-al-user"), name = ctl.getAttribute("data-al-name");
            var level = ctl.querySelector(".al-level").value, team = ctl.querySelector(".al-team").value;
            var sub = subChoice(level);
            var what = label(LEVELS, level) + (team && sub ? " — " + label(sub, team) : "");
            if (!confirm("Passer " + name + " en « " + what + " » ?\n\nSes anciens rôles et services d'accès sont remplacés et " +
                "le changement s'applique immédiatement sur tout le portail (menus, portail d'arrivée, équipes).")) return;
            btn.disabled = true;
            apply(userId, level, team).then(function () {
                onMessage(name + " est maintenant « " + what + " ».", true);
                notify(source, userId);
            }).catch(function (err) {
                btn.disabled = false;
                onMessage(err.message, false);
            });
        });
    }

    window.RccAccessLevel = { controlHtml: controlHtml, wire: wire, notify: notify, TEAMS: TEAMS };
})();
