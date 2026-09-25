"use strict";

/**
 * Activité de l'équipe Quality Assurance, agent QA par agent QA — Portail Head QA et Portail
 * Superviseur. API : GET /api/qa-team/activity, GET /api/qa-team/members/{username}/activity.
 * Usage : RccQaTeam.mount(document.getElementById("…")).
 */
window.RccQaTeam = (function () {
    var esc = RccApi.escapeHtml;

    var ICONS = { VOICE: "bi-headset", WRITTEN: "bi-envelope-paper", COACHING: "bi-chat-heart", FORMATION: "bi-mortarboard",
        QUIZ: "bi-patch-question", CONTENT: "bi-journal-richtext" };
    var COLORS = { VOICE: "#0057B8", WRITTEN: "#7B2FF7", COACHING: "#E0435B", FORMATION: "#0F9D6C", QUIZ: "#F59E0B", CONTENT: "#00838F" };

    function iso(d) { return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }
    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }
    function fmtTime(sec) {
        if (!sec) return "—";
        var h = Math.floor(sec / 3600), m = Math.round((sec % 3600) / 60);
        return h ? h + " h " + String(m).padStart(2, "0") : m + " min";
    }
    function fmtDate(v) {
        if (!v) return "—";
        var d = new Date(v);
        return isNaN(d) ? v : d.toLocaleDateString("fr-FR", { day: "2-digit", month: "short" });
    }

    function periodRange(key) {
        var end = new Date(), start = new Date();
        if (key === "month") start = new Date(end.getFullYear(), end.getMonth(), 1);
        else start.setDate(end.getDate() - (Number(key) - 1));
        return { from: iso(start), to: iso(end) };
    }

    function mount(container) {
        var state = { period: "30", data: null, q: "" };
        container.innerHTML =
            '<div class="qt-toolbar">' +
            '<div><h5 class="mb-0"><i class="bi bi-people-fill"></i> Équipe Quality Assurance</h5>' +
            '<small class="text-muted">Écoutes, évaluations écrites, feedbacks, coachings, formations et contenus — agent QA par agent QA.</small></div>' +
            '<div class="qt-tools"><input type="search" class="form-control form-control-sm" placeholder="Rechercher un agent QA…" data-qt-search>' +
            '<div class="qt-period">' + [["7", "7 j"], ["30", "30 j"], ["month", "Mois en cours"], ["90", "3 mois"]].map(function (p) {
                return '<button type="button" data-qt-period="' + p[0] + '"' + (p[0] === state.period ? ' class="on"' : "") + '>' + p[1] + '</button>';
            }).join("") + '</div></div></div>' +
            '<div data-qt-body><div class="qt-loading">Chargement…</div></div>';
        container.querySelectorAll("[data-qt-period]").forEach(function (b) {
            b.addEventListener("click", function () {
                state.period = b.getAttribute("data-qt-period");
                container.querySelectorAll("[data-qt-period]").forEach(function (x) { x.classList.toggle("on", x === b); });
                load();
            });
        });
        container.querySelector("[data-qt-search]").addEventListener("input", function () { state.q = this.value.toLowerCase(); render(); });

        function load() {
            var r = periodRange(state.period);
            container.querySelector("[data-qt-body]").innerHTML = '<div class="qt-loading">Chargement…</div>';
            RccApi.getJson("/api/qa-team/activity?from=" + r.from + "&to=" + r.to).then(function (d) { state.data = d; render(); })
                .catch(function (e) { container.querySelector("[data-qt-body]").innerHTML = '<p class="text-danger small">Activité QA indisponible : ' + esc(e.message) + '</p>'; });
        }

        function render() {
            var d = state.data;
            if (!d) return;
            var t = d.totals || {};
            var kpis = [
                ["bi-people", "#0057B8", t.members, "Membres QA"],
                ["bi-headset", "#0057B8", t.voiceEvaluations, "Écoutes"],
                ["bi-envelope-paper", "#7B2FF7", t.writtenEvaluations, "Évaluations écrites"],
                ["bi-hourglass-split", "#E0435B", t.feedbacksPending, "Feedbacks à rendre"],
                ["bi-chat-heart", "#E0435B", t.coachings, "Coachings"],
                ["bi-mortarboard", "#0F9D6C", t.formations, "Formations & cours créés"],
                ["bi-patch-question", "#F59E0B", t.quizQuestions, "Questions d'évaluation"],
                ["bi-journal-richtext", "#00838F", t.contents, "Contenus publiés"]
            ].map(function (k) {
                return '<div class="qt-kpi"><span style="background:' + k[1] + '1A;color:' + k[1] + '"><i class="bi ' + k[0] + '"></i></span><div><b>' + (k[2] || 0) + '</b><small>' + k[3] + '</small></div></div>';
            }).join("");

            var max = 1;
            d.members.forEach(function (m) { max = Math.max(max, m.voiceEvaluations + m.writtenEvaluations); });
            var members = d.members.filter(function (m) { return !state.q || String(m.name || m.username).toLowerCase().indexOf(state.q) >= 0; });
            var cards = members.map(function (m) {
                var evals = m.voiceEvaluations + m.writtenEvaluations;
                var chip = function (icon, color, value, label) {
                    return '<span class="qt-chip' + (value ? "" : " zero") + '" style="--c:' + color + '"><i class="bi ' + icon + '"></i><b>' + value + '</b> ' + label + '</span>';
                };
                return '<button type="button" class="qt-card" data-qt-user="' + esc(m.username) + '">' +
                    '<div class="qt-card-head"><span class="qt-av">' + esc(initials(m.name || m.username)) + '</span>' +
                    '<div class="qt-card-who"><b>' + esc(m.name || m.username) + '</b><small>' + esc(m.role) + ' · dernière activité ' + fmtDate(m.lastActivity) + '</small></div>' +
                    '<div class="qt-card-score">' + (m.avgScore != null ? '<b>' + Math.round(m.avgScore) + ' %</b><small>score moyen</small>' : '<small class="text-muted">aucune écoute</small>') + '</div></div>' +
                    '<div class="qt-bar" title="' + evals + ' évaluation(s)"><span style="width:' + (m.voiceEvaluations * 100 / max) + '%;background:#0057B8"></span><span style="width:' + (m.writtenEvaluations * 100 / max) + '%;background:#7B2FF7"></span></div>' +
                    '<div class="qt-chips">' +
                    chip("bi-headset", "#0057B8", m.voiceEvaluations, "écoutes") +
                    chip("bi-envelope-paper", "#7B2FF7", m.writtenEvaluations, "écrits") +
                    chip("bi-person-check", "#0F9D6C", m.agentsEvaluated, "agents évalués") +
                    chip("bi-hourglass-split", "#E0435B", m.feedbacksPending, "feedbacks à rendre") +
                    chip("bi-chat-heart", "#E0435B", m.coachings, "coachings") +
                    chip("bi-mortarboard", "#0F9D6C", m.formations + m.courses, "formations") +
                    chip("bi-patch-question", "#F59E0B", m.quizQuestions, "questions") +
                    chip("bi-journal-richtext", "#00838F", m.contents, "contenus") +
                    '</div>' +
                    '<div class="qt-card-foot"><span><i class="bi bi-check2-circle"></i> Réussite ' + (m.passRate != null ? Math.round(m.passRate) + " %" : "—") + '</span>' +
                    '<span><i class="bi bi-clock-history"></i> Temps portail ' + fmtTime(m.portalSeconds) + '</span><span class="qt-more">Détail <i class="bi bi-chevron-right"></i></span></div>' +
                    '</button>';
            }).join("");

            container.querySelector("[data-qt-body]").innerHTML =
                '<div class="qt-kpis">' + kpis + '</div>' +
                '<div class="qt-chart-card"><div class="d-flex justify-content-between align-items-center"><h6 class="mb-0"><i class="bi bi-bar-chart"></i> Écoutes et évaluations écrites par jour</h6>' +
                '<small class="text-muted"><span class="qt-dot" style="background:#0057B8"></span> écoutes <span class="qt-dot" style="background:#7B2FF7"></span> écrits</small></div>' + chart(d.daily) + '</div>' +
                (cards ? '<div class="qt-grid">' + cards + '</div>' : '<p class="text-muted">Aucun membre QA ' + (state.q ? "ne correspond à la recherche." : "(service Quality Assurance / Superviseur QA / Formateur).") + '</p>');
            container.querySelectorAll("[data-qt-user]").forEach(function (b) {
                b.addEventListener("click", function () { openMember(b.getAttribute("data-qt-user")); });
            });
        }

        function chart(days) {
            if (!days || !days.length) return "";
            var W = 900, H = 120, pad = 18, max = 1;
            days.forEach(function (x) { max = Math.max(max, x.voice + x.written); });
            var bw = (W - pad * 2) / days.length;
            return '<svg class="qt-chart" viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none">' + days.map(function (x, i) {
                var hv = (H - pad * 2) * x.voice / max, hw = (H - pad * 2) * x.written / max, xx = pad + i * bw + bw * 0.15, w = bw * 0.7;
                var label = new Date(x.day + "T00:00:00").toLocaleDateString("fr-FR", { day: "2-digit", month: "2-digit" });
                return '<rect x="' + xx.toFixed(1) + '" y="' + (H - pad - hv).toFixed(1) + '" width="' + w.toFixed(1) + '" height="' + hv.toFixed(1) + '" fill="#0057B8" rx="1.5"><title>' + label + ' : ' + x.voice + ' écoute(s)</title></rect>' +
                    '<rect x="' + xx.toFixed(1) + '" y="' + (H - pad - hv - hw).toFixed(1) + '" width="' + w.toFixed(1) + '" height="' + hw.toFixed(1) + '" fill="#7B2FF7" rx="1.5"><title>' + label + ' : ' + x.written + ' écrit(s)</title></rect>' +
                    ((days.length <= 16 || i % Math.ceil(days.length / 10) === 0) ? '<text x="' + (xx + w / 2).toFixed(1) + '" y="' + (H - 4) + '" font-size="10" text-anchor="middle" fill="#6B7A90">' + label + '</text>' : "");
            }).join("") + '</svg>';
        }

        var modal = null;
        function openMember(username) {
            var m = state.data.members.filter(function (x) { return x.username === username; })[0];
            var r = periodRange(state.period);
            var el = document.getElementById("qtMemberModal");
            if (!el) {
                el = document.createElement("div");
                el.id = "qtMemberModal";
                el.className = "modal fade qt-modal";
                el.tabIndex = -1;
                el.innerHTML = '<div class="modal-dialog modal-lg modal-dialog-scrollable"><div class="modal-content">' +
                    '<div class="qt-modal-head"><span class="qt-av lg"></span><div><h5 class="mb-0"></h5><small></small></div>' +
                    '<button type="button" class="btn-close btn-close-white ms-auto" data-bs-dismiss="modal"></button></div><div class="modal-body"></div></div></div>';
                document.body.appendChild(el);
            }
            if (!modal) modal = new bootstrap.Modal(el);
            el.querySelector(".qt-av").textContent = initials(m.name || m.username);
            el.querySelector("h5").textContent = m.name || m.username;
            el.querySelector("small").textContent = m.role + " · du " + new Date(r.from + "T00:00:00").toLocaleDateString("fr-FR") + " au " + new Date(r.to + "T00:00:00").toLocaleDateString("fr-FR");
            el.querySelector(".modal-body").innerHTML = '<p class="text-muted small">Chargement de l\'activité…</p>';
            modal.show();
            RccApi.getJson("/api/qa-team/members/" + encodeURIComponent(username) + "/activity?from=" + r.from + "&to=" + r.to).then(function (items) {
                var byType = {};
                items.forEach(function (i) { byType[i.type] = (byType[i.type] || 0) + 1; });
                var labels = { VOICE: "Écoutes", WRITTEN: "Évaluations écrites", COACHING: "Coachings", FORMATION: "Formations & cours", QUIZ: "Questions d'évaluation", CONTENT: "Contenus" };
                el.querySelector(".modal-body").innerHTML =
                    '<div class="qt-mini">' + Object.keys(labels).map(function (k) {
                        return '<div style="--c:' + COLORS[k] + '"><i class="bi ' + ICONS[k] + '"></i><b>' + (byType[k] || 0) + '</b><small>' + labels[k] + '</small></div>';
                    }).join("") + '</div>' +
                    (items.length ? '<ul class="qt-timeline">' + items.map(function (i) {
                        return '<li style="--c:' + (COLORS[i.type] || "#6B7A90") + '"><span class="qt-tl-ico"><i class="bi ' + (ICONS[i.type] || "bi-dot") + '"></i></span>' +
                            '<div><b>' + esc(i.label) + '</b>' + (i.detail ? '<small>' + esc(i.detail) + '</small>' : "") + '</div>' +
                            '<time>' + new Date(i.at).toLocaleDateString("fr-FR", { day: "2-digit", month: "short", year: "numeric" }) + '</time></li>';
                    }).join("") + '</ul>' : '<p class="text-muted">Aucune activité enregistrée sur la période.</p>');
            }).catch(function (e) { el.querySelector(".modal-body").innerHTML = '<p class="text-danger">' + esc(e.message) + '</p>'; });
        }

        load();
        return { reload: load };
    }

    return { mount: mount };
})();
