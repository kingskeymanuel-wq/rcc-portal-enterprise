"use strict";

/**
 * Demandes d'aide agent (accès, outils, matériel, autre difficulté) — affichage et actions
 * partagés par le Workflow (agent), le portail Team Leader et le portail Superviseur.
 *
 * Circuit : l'agent envoie → son Team Leader reçoit, prend en charge puis valide une fois la
 * situation réglée → sans résolution après le délai (72 h), escalade automatique au Superviseur.
 */
window.RccSupport = (function () {

    var TYPES = {
        ACCESS: { label: "Accès", icon: "bi-key-fill", color: "#0057B8", bg: "#eaf2ff", hint: "Compte, habilitation, application, mot de passe…" },
        TOOL: { label: "Outils", icon: "bi-tools", color: "#7b2ff7", bg: "#f3eaff", hint: "Logiciel, CRM, téléphonie, extension…" },
        EQUIPMENT: { label: "Matériel", icon: "bi-headset", color: "#0a8a3e", bg: "#e6f7ec", hint: "PC, casque, écran, poste, clavier…" },
        DIFFICULTY: { label: "Autre difficulté", icon: "bi-life-preserver", color: "#c77700", bg: "#fff4e0", hint: "Tout ce qui vous empêche de bien travailler" }
    };
    var PRIORITIES = {
        NORMAL: '<span class="sr-chip">Normale</span>',
        URGENT: '<span class="sr-chip sr-chip-warn"><i class="bi bi-lightning-charge-fill"></i> Urgente</span>',
        BLOQUANT: '<span class="sr-chip sr-chip-danger"><i class="bi bi-sign-stop-fill"></i> Bloquante</span>'
    };

    function esc(s) { return window.RccApi ? RccApi.escapeHtml(s) : String(s == null ? "" : s); }

    function fmt(iso) {
        if (!iso) return "";
        var d = new Date(iso);
        return isNaN(d) ? "" : d.toLocaleDateString("fr-FR", { day: "2-digit", month: "short" }) + " " + d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
    }

    function remaining(iso) {
        var ms = new Date(iso).getTime() - Date.now();
        if (isNaN(ms)) return "";
        if (ms <= 0) return "escalade imminente";
        var h = Math.floor(ms / 3600000), d = Math.floor(h / 24);
        return "escalade auto dans " + (d ? d + " j " + (h % 24) + " h" : h ? h + " h" : Math.max(1, Math.round(ms / 60000)) + " min");
    }

    function isSupport(r) { return !!TYPES[r.type]; }

    function stateOf(r) {
        if (r.status === "APPROVED") return "resolved";
        if (r.status === "REJECTED") return "rejected";
        if (r.escalatedAt) return "escalated";
        if (r.acknowledgedAt) return "progress";
        return "sent";
    }

    var STATE_CHIP = {
        sent: '<span class="sr-chip sr-chip-info"><i class="bi bi-send"></i> Envoyée au Team Leader</span>',
        progress: '<span class="sr-chip sr-chip-info"><i class="bi bi-hourglass-split"></i> En cours de résolution</span>',
        escalated: '<span class="sr-chip sr-chip-danger"><i class="bi bi-arrow-up-circle-fill"></i> Escaladée au Superviseur</span>',
        resolved: '<span class="sr-chip sr-chip-success"><i class="bi bi-check-circle-fill"></i> Résolue</span>',
        rejected: '<span class="sr-chip sr-chip-muted"><i class="bi bi-x-circle"></i> Refusée</span>'
    };

    function timeline(r) {
        var st = stateOf(r);
        var steps = [
            { done: true, label: "Envoyée", sub: fmt(r.createdAt) },
            { done: !!r.acknowledgedAt, label: "Prise en charge", sub: r.acknowledgedAt ? (r.acknowledgedBy || "") + " · " + fmt(r.acknowledgedAt) : (r.assignedToName || "Team Leader") },
        ];
        if (r.escalatedAt) steps.push({ done: true, danger: true, label: "Escaladée", sub: "Superviseur · " + fmt(r.escalatedAt) });
        steps.push({ done: st === "resolved" || st === "rejected", danger: st === "rejected", label: st === "rejected" ? "Refusée" : "Résolue",
            sub: r.decidedAt ? (r.decidedByName || "") + " · " + fmt(r.decidedAt) : "" });
        return '<ol class="sr-timeline">' + steps.map(function (s) {
            return '<li class="' + (s.done ? "done" : "") + (s.danger ? " danger" : "") + '"><span class="dot"></span><b>' + esc(s.label) + "</b><small>" + esc(s.sub || "") + "</small></li>";
        }).join("") + "</ol>";
    }

    /**
     * mode : "agent" (ses demandes), "tl" (demandes de son équipe), "supervisor" (escaladées).
     * onChange : rappel après une action (rechargement de la liste).
     */
    function render(container, requests, mode, onChange) {
        var list = (requests || []).filter(isSupport);
        if (!list.length) {
            container.innerHTML = '<div class="sr-empty"><i class="bi bi-emoji-smile"></i>' +
                (mode === "agent" ? "Aucune demande d'aide pour l'instant. Un souci d'accès, d'outil ou de matériel ? Signalez-le à gauche."
                    : mode === "tl" ? "Aucune demande d'aide en attente dans votre équipe."
                        : "Aucune demande escaladée — les Team Leaders traitent dans les délais.") + "</div>";
            return;
        }
        container.innerHTML = list.map(function (r, i) {
            var t = TYPES[r.type], st = stateOf(r), open = r.status === "PENDING";
            var actions = "";
            if (open && (mode === "tl" || mode === "supervisor")) {
                if (!r.acknowledgedAt) actions += '<button type="button" class="btn btn-sm btn-outline-primary" data-sr="ack" data-id="' + r.requestId + '"><i class="bi bi-hand-thumbs-up"></i> Prendre en charge</button>';
                actions += '<button type="button" class="btn btn-sm btn-success" data-sr="resolve" data-id="' + r.requestId + '"><i class="bi bi-check2-circle"></i> Situation réglée — valider</button>';
                actions += '<button type="button" class="btn btn-sm btn-outline-danger" data-sr="reject" data-id="' + r.requestId + '"><i class="bi bi-x"></i> Refuser</button>';
            }
            if (open && mode === "agent") {
                actions += '<button type="button" class="btn btn-sm btn-outline-secondary" data-sr="delete" data-id="' + r.requestId + '"><i class="bi bi-trash"></i> Retirer</button>';
            }
            var due = open && !r.escalatedAt && r.escalationDueAt ? '<span class="sr-due"><i class="bi bi-alarm"></i> ' + remaining(r.escalationDueAt) + "</span>" : "";
            var who = mode === "agent"
                ? '<i class="bi bi-person-badge"></i> ' + esc(r.assignedToName || (r.assignedTeam === "SUPERVISOR" ? "Supervision" : "Team Leader"))
                : '<i class="bi bi-person"></i> <b>' + esc(r.requestedByName || r.requestedByUsername) + "</b>" + (r.requestedByActivity ? " · " + esc(r.requestedByActivity) : "") +
                  (mode === "supervisor" && r.assignedToName ? ' · <i class="bi bi-person-badge"></i> TL : ' + esc(r.assignedToName) : "");
            return '<div class="sr-card sr-' + st + '" style="animation-delay:' + Math.min(i * 40, 300) + 'ms">' +
                '<div class="sr-card-head"><span class="sr-type" style="background:' + t.bg + ";color:" + t.color + '"><i class="bi ' + t.icon + '"></i></span>' +
                '<div class="sr-card-title"><div class="sr-kicker">' + esc(t.label) + " " + (PRIORITIES[r.priority] || "") + "</div><h6>" + esc(r.title) + "</h6></div>" +
                STATE_CHIP[st] + "</div>" +
                (r.details ? '<p class="sr-details">' + esc(r.details) + "</p>" : "") +
                (r.acknowledgementNote ? '<div class="sr-note"><i class="bi bi-chat-left-text"></i> ' + esc(r.acknowledgementNote) + "</div>" : "") +
                (r.decisionComment ? '<div class="sr-note sr-note-final"><i class="bi bi-chat-square-quote"></i> ' + esc(r.decisionComment) + "</div>" : "") +
                timeline(r) +
                '<div class="sr-foot"><span class="sr-who">' + who + "</span>" + due + '<div class="sr-actions">' + actions + "</div></div></div>";
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll("[data-sr]"), function (b) {
            b.addEventListener("click", function () {
                var id = b.getAttribute("data-id"), action = b.getAttribute("data-sr");
                var req;
                if (action === "delete") {
                    if (!confirm("Retirer cette demande ?")) return;
                    req = fetch("/api/workflow/requests/" + id, { method: "DELETE", credentials: "same-origin" })
                        .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); });
                } else {
                    var text = action === "ack" ? prompt("Message à l'agent (optionnel) — ex. « ticket IT ouvert, retour sous 24 h » :", "")
                        : action === "resolve" ? prompt("Comment la situation a-t-elle été réglée ? (visible par l'agent)", "")
                            : prompt("Motif du refus (visible par l'agent) :", "");
                    if (text === null) return;
                    if (action === "reject" && !text.trim()) { alert("Indiquez le motif du refus."); return; }
                    var url = "/api/workflow/requests/" + id + (action === "ack" ? "/acknowledge" : action === "resolve" ? "/approve" : "/reject");
                    req = RccApi.sendJson(url, "POST", { comment: text.trim() || null });
                }
                b.disabled = true;
                req.then(function () { if (onChange) onChange(); })
                    .catch(function (e) { b.disabled = false; alert("Erreur : " + e.message); });
            });
        });
    }

    /**
     * Carte autonome pour les portails : <div data-sr-mount="tl|supervisor"> contenant
     * [data-sr-list], [data-sr-count] et des boutons [data-sr-filter]. Rafraîchie toutes les 2 min.
     */
    function mountPanel(el) {
        var mode = el.getAttribute("data-sr-mount");
        var url = mode === "supervisor" ? "/api/workflow/requests/escalated" : "/api/workflow/requests";
        var filter = "open", cache = [];
        function draw() {
            var support = cache.filter(isSupport);
            var open = support.filter(function (r) { return r.status === "PENDING"; });
            var count = el.querySelector("[data-sr-count]");
            if (count) { count.textContent = open.length; count.style.display = open.length ? "" : "none"; }
            var shown = support.filter(function (r) { return filter === "all" || (filter === "open" ? r.status === "PENDING" : r.status !== "PENDING"); });
            // Ouvertes : les plus anciennes (proches de l'escalade) et bloquantes d'abord.
            var rank = { BLOQUANT: 0, URGENT: 1, NORMAL: 2 };
            shown.sort(function (a, b) {
                if (a.status === "PENDING" && b.status === "PENDING") {
                    var ra = rank[a.priority] !== undefined ? rank[a.priority] : 2;
                    var rb = rank[b.priority] !== undefined ? rank[b.priority] : 2;
                    var p = ra - rb;
                    return p || new Date(a.createdAt) - new Date(b.createdAt);
                }
                return new Date(b.decidedAt || b.createdAt) - new Date(a.decidedAt || a.createdAt);
            });
            render(el.querySelector("[data-sr-list]"), shown, mode, load);
        }
        function load() {
            return RccApi.getJson(url).then(function (list) { cache = list || []; draw(); })
                .catch(function (e) { el.querySelector("[data-sr-list]").innerHTML = '<div class="sr-empty text-danger">' + esc(e.message) + "</div>"; });
        }
        Array.prototype.forEach.call(el.querySelectorAll("[data-sr-filter]"), function (b) {
            b.addEventListener("click", function () {
                filter = b.getAttribute("data-sr-filter");
                Array.prototype.forEach.call(el.querySelectorAll("[data-sr-filter]"), function (x) { x.classList.toggle("active", x === b); });
                draw();
            });
        });
        load();
        setInterval(function () { if (!document.hidden) load(); }, 120000);
    }

    document.addEventListener("DOMContentLoaded", function () {
        Array.prototype.forEach.call(document.querySelectorAll("[data-sr-mount]"), mountPanel);
    });

    return { TYPES: TYPES, render: render, isSupport: isSupport, stateOf: stateOf };
})();
