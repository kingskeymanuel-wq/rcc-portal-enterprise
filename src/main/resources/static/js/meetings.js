"use strict";

/**
 * Meetings de performance tête-à-tête (Team Leader ↔ agent) — module partagé :
 * Portail Team Leader (bouton « Meeting » + onglet Meetings) et Workflow (tâches
 * « Compte rendu » / « Lire et approuver », onglet Meetings). API : /api/meetings.
 */
window.RccMeetings = (function () {

    var getJson = RccApi.getJson, sendJson = RccApi.sendJson, esc = RccApi.escapeHtml;

    var STATUS = {
        PLANIFIE: { cls: "planned", icon: "bi-calendar-event", label: "Planifié" },
        COMPTE_RENDU: { cls: "reported", icon: "bi-hourglass-split", label: "En attente de l'agent" },
        APPROUVE: { cls: "approved", icon: "bi-patch-check-fill", label: "Lu et approuvé" },
        ANNULE: { cls: "cancelled", icon: "bi-x-circle", label: "Annulé" }
    };
    var OBJECTIVES = [
        "Améliorer le score qualité (QA)",
        "Améliorer la présence et la ponctualité",
        "Atteindre les objectifs de vente",
        "Réduire le temps de traitement / améliorer le FCR",
        "Suivi du plan d'action précédent",
        "Comportement / relation client",
        "Félicitations et motivation"
    ];

    var listeners = [];
    function changed() { listeners.forEach(function (f) { try { f(); } catch (e) { /* ignore */ } }); }

    function fmt(dt) {
        if (!dt) return "";
        var d = new Date(dt);
        return isNaN(d) ? String(dt) : d.toLocaleString("fr-FR", { weekday: "short", day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
    }
    function fmtDate(d) { return d ? new Date(d + "T00:00:00").toLocaleDateString("fr-FR") : ""; }
    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }
    function name(p) { return p ? (p.name || p.username) : "—"; }
    function chip(status) {
        var s = STATUS[status] || { cls: "planned", icon: "bi-circle", label: status };
        return '<span class="mt-chip ' + s.cls + '"><i class="bi ' + s.icon + '"></i> ' + s.label + '</span>';
    }

    // ── Modale générique ───────────────────────────────────────────────────
    var modalEl = null, modal = null;
    function openModal(title, icon, bodyHtml, footerHtml) {
        if (!modalEl) {
            modalEl = document.createElement("div");
            modalEl.className = "modal fade mt-modal";
            modalEl.tabIndex = -1;
            modalEl.innerHTML = '<div class="modal-dialog modal-lg modal-dialog-centered modal-dialog-scrollable"><div class="modal-content">' +
                '<div class="mt-modal-head"><span class="mt-modal-ico"><i class="bi"></i></span><h5 class="mb-0"></h5>' +
                '<button type="button" class="btn-close btn-close-white ms-auto" data-bs-dismiss="modal" aria-label="Fermer"></button></div>' +
                '<div class="modal-body"></div><div class="modal-footer"></div></div></div>';
            document.body.appendChild(modalEl);
            modal = new bootstrap.Modal(modalEl);
        }
        modalEl.querySelector(".mt-modal-ico i").className = "bi " + icon;
        modalEl.querySelector("h5").textContent = title;
        modalEl.querySelector(".modal-body").innerHTML = bodyHtml;
        modalEl.querySelector(".modal-footer").innerHTML = footerHtml;
        modal.show();
        return modalEl;
    }
    function err(el, message) {
        var box = el.querySelector("[data-err]");
        if (box) { box.textContent = message; box.hidden = !message; }
    }

    // ── Planifier (Team Leader) ────────────────────────────────────────────
    function openSchedule(preselectAgent) {
        var tomorrow = new Date(); tomorrow.setDate(tomorrow.getDate() + 1); tomorrow.setHours(10, 0, 0, 0);
        var local = new Date(tomorrow.getTime() - tomorrow.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
        var el = openModal("Planifier un meeting tête-à-tête", "bi-people-fill",
            '<p class="text-muted small mb-3">Objectif : améliorer la performance de l\'agent. Après le meeting, le compte rendu se remplit dans <b>Workflow → Tâches &amp; notes</b> ; l\'agent le lit et l\'approuve, puis il remonte au <b>Head QA</b> et au <b>Head RCC</b>.</p>' +
            '<div class="row g-3">' +
            '<div class="col-md-6"><label class="form-label mt-label">Agent</label><select class="form-select" id="mtAgent"><option value="">Chargement…</option></select></div>' +
            '<div class="col-md-6"><label class="form-label mt-label">Date et heure</label><input type="datetime-local" class="form-control" id="mtWhen" value="' + local + '"></div>' +
            '<div class="col-md-6"><label class="form-label mt-label">Durée</label><select class="form-select" id="mtDuration"><option value="15">15 min</option><option value="30" selected>30 min</option><option value="45">45 min</option><option value="60">1 h</option><option value="90">1 h 30</option></select></div>' +
            '<div class="col-md-6"><label class="form-label mt-label">Lieu</label><input class="form-control" id="mtLocation" list="mtLocations" placeholder="Salle de réunion, bureau TL, Teams…"><datalist id="mtLocations"><option value="Bureau du Team Leader"><option value="Salle de réunion"><option value="Microsoft Teams"><option value="Sur le plateau"></datalist></div>' +
            '<div class="col-12"><label class="form-label mt-label">Objectif du meeting</label><div class="mt-objectives" id="mtObjectives">' +
            OBJECTIVES.map(function (o) { return '<button type="button" data-o="' + esc(o) + '">' + esc(o) + '</button>'; }).join("") +
            '</div><input class="form-control mt-2" id="mtObjective" maxlength="300" placeholder="Ou précisez l\'objectif…"></div>' +
            '<div class="col-12"><label class="form-label mt-label">Points à aborder (facultatif)</label><textarea class="form-control" id="mtAgenda" rows="3" maxlength="2000" placeholder="Constats chiffrés, écoutes QA, retards, objectifs…"></textarea></div>' +
            '<div class="col-12"><label class="form-label mt-label">Responsables en copie</label><div class="mt-cc" id="mtCc"><span class="text-muted small">Chargement…</span></div></div>' +
            '</div><div class="alert alert-danger mt-3 mb-0 py-2 small" data-err hidden></div>',
            '<button type="button" class="btn btn-light" data-bs-dismiss="modal">Annuler</button>' +
            '<button type="button" class="btn btn-primary mt-primary" id="mtScheduleBtn"><i class="bi bi-calendar-check"></i> Planifier et inviter</button>');

        getJson("/api/meetings/agents").then(function (agents) {
            el.querySelector("#mtAgent").innerHTML = '<option value="">Choisir l\'agent…</option>' + (agents || []).map(function (a) {
                return '<option value="' + esc(a.username) + '"' + (a.username === preselectAgent ? " selected" : "") + '>' + esc(a.fullName || a.username) + '</option>';
            }).join("");
        }).catch(function (e) { err(el, "Agents indisponibles : " + e.message); });

        getJson("/api/meetings/cc-candidates").then(function (people) {
            var box = el.querySelector("#mtCc");
            if (!people.length) { box.innerHTML = '<span class="text-muted small">Aucun responsable disponible.</span>'; return; }
            box.innerHTML = people.map(function (p) {
                var head = p.label === "Head QA" || p.label === "Head RCC";
                return '<label class="mt-cc-item' + (head ? " head" : "") + '"><input type="checkbox" value="' + esc(p.username) + '"' + (head ? " checked" : "") + '>' +
                    '<span class="mt-av">' + esc(initials(p.name || p.username)) + '</span><span><b>' + esc(p.name || p.username) + '</b><small>' + esc(p.label || "") + '</small></span></label>';
            }).join("");
        }).catch(function () { el.querySelector("#mtCc").innerHTML = '<span class="text-muted small">Liste indisponible.</span>'; });

        el.querySelectorAll("#mtObjectives [data-o]").forEach(function (b) {
            b.addEventListener("click", function () {
                el.querySelectorAll("#mtObjectives [data-o]").forEach(function (x) { x.classList.toggle("on", x === b); });
                el.querySelector("#mtObjective").value = b.getAttribute("data-o");
            });
        });

        el.querySelector("#mtScheduleBtn").addEventListener("click", function () {
            var btn = this;
            var body = {
                agentUsername: el.querySelector("#mtAgent").value,
                scheduledAt: el.querySelector("#mtWhen").value ? el.querySelector("#mtWhen").value + ":00" : null,
                durationMinutes: Number(el.querySelector("#mtDuration").value),
                location: el.querySelector("#mtLocation").value,
                objective: el.querySelector("#mtObjective").value,
                agenda: el.querySelector("#mtAgenda").value,
                ccUsernames: Array.prototype.map.call(el.querySelectorAll("#mtCc input:checked"), function (i) { return i.value; })
            };
            if (!body.agentUsername) { err(el, "Choisissez l'agent."); return; }
            if (!body.objective.trim()) { err(el, "Indiquez l'objectif du meeting."); return; }
            btn.disabled = true;
            sendJson("/api/meetings", "POST", body).then(function () {
                modal.hide();
                toast("Meeting planifié : l'agent et les responsables en copie sont notifiés.");
                changed();
            }).catch(function (e) { err(el, e.message); btn.disabled = false; });
        });
    }

    // ── Vue détaillée / compte rendu / approbation ─────────────────────────
    function summaryHtml(m) {
        return '<div class="mt-summary">' +
            '<div class="mt-people"><div><span class="mt-av lg">' + esc(initials(name(m.teamLeader))) + '</span><small>Team Leader</small><b>' + esc(name(m.teamLeader)) + '</b></div>' +
            '<i class="bi bi-arrow-left-right"></i><div><span class="mt-av lg agent">' + esc(initials(name(m.agent))) + '</span><small>Agent</small><b>' + esc(name(m.agent)) + '</b></div></div>' +
            '<div class="mt-facts"><div><i class="bi bi-calendar-event"></i> ' + esc(fmt(m.scheduledAt)) + (m.durationMinutes ? " · " + m.durationMinutes + " min" : "") + '</div>' +
            (m.location ? '<div><i class="bi bi-geo-alt"></i> ' + esc(m.location) + '</div>' : "") +
            '<div><i class="bi bi-bullseye"></i> <b>' + esc(m.objective) + '</b></div>' +
            (m.cc && m.cc.length ? '<div><i class="bi bi-people"></i> Copie : ' + m.cc.map(function (p) { return esc(name(p)); }).join(", ") + '</div>' : "") +
            '<div>' + chip(m.status) + '</div></div>' +
            (m.agenda ? '<div class="mt-block"><h6><i class="bi bi-list-ul"></i> Points à aborder</h6><p>' + esc(m.agenda) + '</p></div>' : "") +
            '</div>';
    }

    function reportReadHtml(m) {
        if (!m.reportedAt) return "";
        function block(icon, title, text, tone) {
            return text ? '<div class="mt-block ' + (tone || "") + '"><h6><i class="bi ' + icon + '"></i> ' + title + '</h6><p>' + esc(text) + '</p></div>' : "";
        }
        return '<div class="mt-report">' +
            block("bi-search", "Raisons du meeting / constat", m.reasons) +
            block("bi-hand-thumbs-up", "Points forts", m.strengths, "good") +
            block("bi-graph-up-arrow", "Axes d'amélioration", m.improvements, "warn") +
            block("bi-list-check", "Plan d'action", m.actionPlan, "plan") +
            (m.followUpDate ? '<div class="mt-follow"><i class="bi bi-calendar-check"></i> Point de suivi prévu le <b>' + esc(fmtDate(m.followUpDate)) + '</b></div>' : "") +
            (m.acknowledgedAt ? '<div class="mt-ack-done"><i class="bi bi-patch-check-fill"></i> Lu et approuvé par l\'agent le ' + esc(fmt(m.acknowledgedAt)) +
                (m.agentComment ? '<div class="mt-agent-comment">« ' + esc(m.agentComment) + ' »</div>' : "") + '</div>' : "") +
            '</div>';
    }

    function openMeeting(id) {
        return getJson("/api/meetings/" + id).then(function (m) {
            if (m.canReport) return openReport(m);
            if (m.canAcknowledge) return openAck(m);
            var cancel = m.canCancel ? '<button type="button" class="btn btn-outline-danger me-auto" id="mtCancelBtn"><i class="bi bi-x-circle"></i> Annuler le meeting</button>' : "";
            var el = openModal("Meeting de performance", "bi-people-fill", summaryHtml(m) + (reportReadHtml(m) ||
                '<p class="text-muted small mt-3 mb-0"><i class="bi bi-info-circle"></i> Le compte rendu sera disponible après le meeting.</p>'),
                cancel + '<button type="button" class="btn btn-primary mt-primary" data-bs-dismiss="modal">Fermer</button>');
            wireCancel(el, m);
        }).catch(function (e) { alert("Meeting indisponible : " + e.message); });
    }

    function wireCancel(el, m) {
        var c = el.querySelector("#mtCancelBtn");
        if (!c) return;
        c.addEventListener("click", function () {
            if (!confirm("Annuler ce meeting ? L'agent et les personnes en copie seront prévenus.")) return;
            sendJson("/api/meetings/" + m.id + "/cancel", "POST", {}).then(function () { modal.hide(); toast("Meeting annulé."); changed(); })
                .catch(function (e) { err(el, e.message); });
        });
    }

    function openReport(m) {
        var cancel = m.canCancel ? '<button type="button" class="btn btn-outline-danger me-auto" id="mtCancelBtn"><i class="bi bi-x-circle"></i> Annuler le meeting</button>' : "";
        var el = openModal("Compte rendu du meeting", "bi-journal-text", summaryHtml(m) +
            '<div class="mt-form">' +
            '<label class="form-label mt-label">Raisons du meeting / constat <span class="text-danger">*</span></label>' +
            '<textarea class="form-control" id="mtReasons" rows="3" maxlength="2000" placeholder="Ex. score QA de 62 % sur septembre (accueil et reformulation), 3 retards…">' + esc(m.reasons || "") + '</textarea>' +
            '<label class="form-label mt-label mt-3">Points forts</label>' +
            '<textarea class="form-control" id="mtStrengths" rows="2" maxlength="2000" placeholder="Ce que l\'agent fait bien…">' + esc(m.strengths || "") + '</textarea>' +
            '<label class="form-label mt-label mt-3">Axes d\'amélioration <span class="text-danger">*</span></label>' +
            '<textarea class="form-control" id="mtImprovements" rows="3" maxlength="2000" placeholder="Ce qui doit progresser, de façon concrète…">' + esc(m.improvements || "") + '</textarea>' +
            '<label class="form-label mt-label mt-3">Plan d\'action et engagements <span class="text-danger">*</span></label>' +
            '<textarea class="form-control" id="mtPlan" rows="3" maxlength="2000" placeholder="Actions, objectifs mesurables, accompagnement prévu (double écoute, formation…)">' + esc(m.actionPlan || "") + '</textarea>' +
            '<div class="row g-3 mt-1"><div class="col-md-6"><label class="form-label mt-label">Point de suivi</label><input type="date" class="form-control" id="mtFollow" value="' + esc(m.followUpDate || "") + '"></div></div>' +
            '</div><div class="alert alert-danger mt-3 mb-0 py-2 small" data-err hidden></div>',
            cancel + '<button type="button" class="btn btn-light" data-bs-dismiss="modal">Plus tard</button>' +
            '<button type="button" class="btn btn-primary mt-primary" id="mtReportBtn"><i class="bi bi-send-check"></i> Envoyer à l\'agent pour approbation</button>');
        wireCancel(el, m);
        el.querySelector("#mtReportBtn").addEventListener("click", function () {
            var btn = this;
            var body = { reasons: el.querySelector("#mtReasons").value, strengths: el.querySelector("#mtStrengths").value,
                improvements: el.querySelector("#mtImprovements").value, actionPlan: el.querySelector("#mtPlan").value,
                followUpDate: el.querySelector("#mtFollow").value || null };
            if (!body.reasons.trim() || !body.improvements.trim() || !body.actionPlan.trim()) { err(el, "Raisons, axes d'amélioration et plan d'action sont obligatoires."); return; }
            btn.disabled = true;
            sendJson("/api/meetings/" + m.id + "/report", "PUT", body).then(function () {
                modal.hide(); toast("Compte rendu envoyé : l'agent doit le lire et l'approuver."); changed();
            }).catch(function (e) { err(el, e.message); btn.disabled = false; });
        });
    }

    function openAck(m) {
        var el = openModal("Compte rendu de votre meeting", "bi-patch-check", summaryHtml(m) + reportReadHtml(m) +
            '<div class="mt-ack-box"><label class="mt-ack-check"><input type="checkbox" id="mtAckChk"> <span><b>J\'ai lu et j\'approuve</b> ce compte rendu et le plan d\'action.</span></label>' +
            '<textarea class="form-control mt-2" id="mtAckComment" rows="2" maxlength="1000" placeholder="Votre commentaire (facultatif)"></textarea></div>' +
            '<div class="alert alert-danger mt-3 mb-0 py-2 small" data-err hidden></div>',
            '<button type="button" class="btn btn-light" data-bs-dismiss="modal">Plus tard</button>' +
            '<button type="button" class="btn btn-success" id="mtAckBtn" disabled><i class="bi bi-check2-circle"></i> Valider</button>');
        el.querySelector("#mtAckChk").addEventListener("change", function () { el.querySelector("#mtAckBtn").disabled = !this.checked; });
        el.querySelector("#mtAckBtn").addEventListener("click", function () {
            var btn = this; btn.disabled = true;
            sendJson("/api/meetings/" + m.id + "/acknowledge", "POST", { approved: true, comment: el.querySelector("#mtAckComment").value })
                .then(function () { modal.hide(); toast("Merci ! Le compte rendu est approuvé et transmis au Head QA et au Head RCC."); changed(); })
                .catch(function (e) { err(el, e.message); btn.disabled = false; });
        });
    }

    // ── Liste ──────────────────────────────────────────────────────────────
    function renderList(container, opts) {
        opts = opts || {};
        container.innerHTML = '<div class="mt-loading"><span></span><span></span><span></span></div>';
        return getJson("/api/meetings").then(function (list) {
            var filter = opts.filter || "all";
            var counts = { all: list.length, todo: 0, PLANIFIE: 0, COMPTE_RENDU: 0, APPROUVE: 0 };
            list.forEach(function (m) { counts[m.status] = (counts[m.status] || 0) + 1; if (m.canReport || m.canAcknowledge) counts.todo++; });
            var shown = list.filter(function (m) {
                return filter === "all" || (filter === "todo" ? (m.canReport || m.canAcknowledge) : m.status === filter);
            });
            var bar = '<div class="mt-filters">' + [["all", "Tous"], ["todo", "À faire"], ["PLANIFIE", "Planifiés"], ["COMPTE_RENDU", "En attente de l'agent"], ["APPROUVE", "Approuvés"]].map(function (f) {
                return '<button type="button" class="' + (filter === f[0] ? "on" : "") + '" data-f="' + f[0] + '">' + f[1] + ' <span>' + (counts[f[0]] || 0) + '</span></button>';
            }).join("") + (opts.canSchedule ? '<button type="button" class="mt-new" data-new><i class="bi bi-plus-lg"></i> Nouveau meeting</button>' : "") + '</div>';
            var body = shown.length ? '<div class="mt-list">' + shown.map(function (m, i) {
                var action = m.canReport ? '<button type="button" class="btn btn-sm btn-primary" data-open="' + m.id + '">' + (m.status === "PLANIFIE" ? "Remplir le compte rendu" : "Modifier le compte rendu") + '</button>'
                    : m.canAcknowledge ? '<button type="button" class="btn btn-sm btn-success" data-open="' + m.id + '">Lire et approuver</button>'
                    : '<button type="button" class="btn btn-sm btn-outline-primary" data-open="' + m.id + '">Voir</button>';
                return '<div class="mt-card ' + ((STATUS[m.status] || {}).cls || "") + '" style="animation-delay:' + Math.min(i, 12) * 40 + 'ms">' +
                    '<span class="mt-av">' + esc(initials(name(m.agent))) + '</span>' +
                    '<div class="mt-card-main"><div class="mt-card-title">' + esc(name(m.agent)) + ' <small>avec ' + esc(name(m.teamLeader)) + '</small></div>' +
                    '<div class="mt-card-sub"><i class="bi bi-bullseye"></i> ' + esc(m.objective) + '</div>' +
                    '<div class="mt-card-meta"><span><i class="bi bi-calendar-event"></i> ' + esc(fmt(m.scheduledAt)) + '</span>' +
                    (m.location ? '<span><i class="bi bi-geo-alt"></i> ' + esc(m.location) + '</span>' : "") + chip(m.status) + '</div></div>' +
                    '<div class="mt-card-action">' + action + '</div></div>';
            }).join("") + '</div>'
                : '<div class="mt-empty"><span><i class="bi bi-people"></i></span><b>Aucun meeting ' + (filter === "all" ? "" : "dans ce filtre") + '</b>' +
                  '<small>' + (opts.canSchedule ? "Planifiez un tête-à-tête avec un agent pour faire progresser sa performance." : "Les meetings de performance vous concernant apparaîtront ici.") + '</small></div>';
            container.innerHTML = bar + body;
            container.querySelectorAll("[data-f]").forEach(function (b) {
                b.addEventListener("click", function () { opts.filter = b.getAttribute("data-f"); renderList(container, opts); });
            });
            container.querySelectorAll("[data-open]").forEach(function (b) {
                b.addEventListener("click", function () { openMeeting(Number(b.getAttribute("data-open"))); });
            });
            var nb = container.querySelector("[data-new]");
            if (nb) nb.addEventListener("click", function () { openSchedule(); });
            if (opts.onCounts) opts.onCounts(counts);
            return list;
        }).catch(function (e) {
            container.innerHTML = '<p class="text-danger small">Meetings indisponibles : ' + esc(e.message) + '</p>';
        });
    }

    function toast(text) {
        var t = document.createElement("div");
        t.className = "mt-toast";
        t.innerHTML = '<i class="bi bi-check-circle-fill"></i> ' + esc(text);
        document.body.appendChild(t);
        setTimeout(function () { t.remove(); }, 3200);
    }

    return {
        openSchedule: openSchedule,
        openMeeting: openMeeting,
        renderList: renderList,
        onChange: function (f) { listeners.push(f); }
    };
})();
