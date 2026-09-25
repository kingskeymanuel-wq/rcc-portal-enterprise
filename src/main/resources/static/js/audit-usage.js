"use strict";

/**
 * Audit → Utilisation des onglets : répartition du temps passé par onglet, en pourcentage,
 * organisée par équipe (2 mois glissants). Un clic sur un utilisateur ouvre son cercle
 * d'utilisation détaillé. API : GET /api/audit/usage?days=N.
 */
(function () {
    var esc = RccApi.escapeHtml;

    var PAGES = {
        "/dashboard": ["Accueil", "bi-house-door", "#0057B8"],
        "/performance": ["Ma Performance", "bi-speedometer2", "#0F9D6C"],
        "/rh": ["Portail RH", "bi-person-vcard", "#7B2FF7"],
        "/excelliam": ["Portail Excelliam", "bi-building", "#8D6E63"],
        "/agence": ["Portail Agence", "bi-bank", "#455A64"],
        "/mon-rcc": ["MON RCC", "bi-stars", "#E0435B"],
        "/translator": ["Traducteur & correcteur", "bi-translate", "#00A3A3"],
        "/procedures": ["Procédures", "bi-diagram-3", "#F59E0B"],
        "/mail-templates": ["Masques de mail", "bi-envelope-paper", "#3F51B5"],
        "/knowledge": ["Knowledge Base", "bi-book", "#2E7D32"],
        "/training": ["Formation", "bi-mortarboard", "#C2185B"],
        "/games": ["Évaluation", "bi-controller", "#FF7043"],
        "/workflow": ["Workflow", "bi-diagram-2", "#1565C0"],
        "/qa": ["Quality Assurance", "bi-clipboard-check", "#6A1B9A"],
        "/qa-supervisor": ["Superviseur QA", "bi-shield-check", "#4A148C"],
        "/reports": ["Reporting", "bi-bar-chart", "#00897B"],
        "/supervisor": ["Portail Superviseur", "bi-binoculars", "#283593"],
        "/team-leader": ["Portail Team Leader", "bi-people", "#EF6C00"],
        "/outbound-dashboard": ["Ventes & RDV", "bi-graph-up-arrow", "#AD1457"],
        "/shift": ["Suivi de shift", "bi-clock-history", "#5D4037"],
        "/audit": ["Audit", "bi-shield-lock", "#37474F"],
        "/administration": ["Administration", "bi-gear", "#546E7A"],
        "/data-analysis": ["Analyse de données", "bi-pie-chart", "#0277BD"],
        "/notifications": ["Notifications", "bi-bell", "#9E9D24"],
        "/settings": ["Paramètres", "bi-sliders", "#78909C"]
    };
    var EXTRA = ["#8E24AA", "#43A047", "#FB8C00", "#1E88E5", "#D81B60", "#00ACC1", "#6D4C41", "#7CB342"];
    function meta(page) {
        if (PAGES[page]) return PAGES[page];
        var h = 0; for (var i = 0; i < page.length; i++) h = (h * 31 + page.charCodeAt(i)) >>> 0;
        return [page.replace(/^\//, "").replace(/-/g, " ") || page, "bi-window", EXTRA[h % EXTRA.length]];
    }
    var TEAM_ICON = { INBOUND_VOICE: "bi-headset", INBOUND_MAIL: "bi-envelope-at", CIB: "bi-briefcase", OUTBOUND: "bi-telephone-outbound", OTHER: "bi-question-circle" };

    var state = { days: 60, data: null, team: "ALL", q: "" };

    function fmtTime(sec) {
        sec = Number(sec) || 0;
        if (sec < 60) return sec + " s";
        var h = Math.floor(sec / 3600), m = Math.round((sec % 3600) / 60);
        if (m === 60) { h++; m = 0; }
        return h ? h + " h " + String(m).padStart(2, "0") : m + " min";
    }
    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }
    /** Pourcentages par temps actif ; si aucun temps (visites très courtes), par visites. */
    function shares(pages) {
        var byTime = pages.reduce(function (a, p) { return a + p.seconds; }, 0);
        var total = byTime || pages.reduce(function (a, p) { return a + p.visits; }, 0);
        return pages.map(function (p) {
            var v = byTime ? p.seconds : p.visits;
            return { page: p.page, seconds: p.seconds, visits: p.visits, pct: total ? v * 100 / total : 0 };
        });
    }
    function pctLabel(p) { return p >= 10 || p === 0 ? Math.round(p) + " %" : p.toFixed(1).replace(".", ",") + " %"; }

    /** Cercle d'utilisation (SVG) — segments animés, pourcentage principal au centre. */
    function donut(pages, size, center, sub) {
        var sh = shares(pages).filter(function (p) { return p.pct > 0; });
        var r = 15.915, c = 100, offset = 25, segs = "";
        if (!sh.length) segs = '<circle cx="21" cy="21" r="' + r + '" fill="none" stroke="#EEF1F5" stroke-width="5"></circle>';
        sh.forEach(function (p, i) {
            var len = Math.max(p.pct - (sh.length > 1 ? 0.6 : 0), 0.3);
            segs += '<circle class="us-seg" cx="21" cy="21" r="' + r + '" fill="none" stroke="' + meta(p.page)[2] + '" stroke-width="5" ' +
                'stroke-dasharray="' + len.toFixed(2) + ' ' + (c - len).toFixed(2) + '" stroke-dashoffset="' + offset.toFixed(2) + '" ' +
                'style="animation-delay:' + (i * 60) + 'ms" data-page="' + esc(p.page) + '"><title>' + esc(meta(p.page)[0]) + ' — ' + pctLabel(p.pct) + '</title></circle>';
            offset -= p.pct;
        });
        return '<div class="us-donut" style="width:' + size + 'px;height:' + size + 'px">' +
            '<svg viewBox="0 0 42 42">' + '<circle cx="21" cy="21" r="' + r + '" fill="none" stroke="#F1F4F9" stroke-width="5"></circle>' + segs + '</svg>' +
            '<div class="us-donut-center"><b>' + center + '</b><small>' + sub + '</small></div></div>';
    }
    function legend(pages, limit) {
        var sh = shares(pages);
        var shown = limit ? sh.slice(0, limit) : sh;
        var rest = limit ? sh.slice(limit) : [];
        var html = shown.map(function (p) {
            var m = meta(p.page);
            return '<div class="us-leg"><span class="us-leg-ico" style="background:' + m[2] + '1A;color:' + m[2] + '"><i class="bi ' + m[1] + '"></i></span>' +
                '<div class="us-leg-main"><div class="us-leg-top"><span>' + esc(m[0]) + '</span><b>' + pctLabel(p.pct) + '</b></div>' +
                '<div class="us-bar"><span style="width:' + p.pct.toFixed(1) + '%;background:' + m[2] + '"></span></div>' +
                '<small>' + fmtTime(p.seconds) + ' · ' + p.visits + ' visite' + (p.visits > 1 ? "s" : "") + '</small></div></div>';
        }).join("");
        if (rest.length) {
            var pct = rest.reduce(function (a, p) { return a + p.pct; }, 0);
            html += '<div class="us-leg us-leg-rest"><span class="us-leg-ico"><i class="bi bi-three-dots"></i></span><div class="us-leg-main"><div class="us-leg-top"><span>' +
                rest.length + ' autre(s) onglet(s)</span><b>' + pctLabel(pct) + '</b></div></div></div>';
        }
        return html || '<p class="text-muted small mb-0">Aucune donnée.</p>';
    }
    function stack(pages) {
        return '<div class="us-stack">' + shares(pages).map(function (p) {
            return '<span style="width:' + p.pct.toFixed(2) + '%;background:' + meta(p.page)[2] + '" title="' + esc(meta(p.page)[0]) + ' — ' + pctLabel(p.pct) + '"></span>';
        }).join("") + '</div>';
    }

    function load() {
        var box = document.getElementById("usBody");
        box.innerHTML = '<div class="us-loading"><span></span><span></span><span></span></div>';
        RccApi.getJson("/api/audit/usage?days=" + state.days).then(function (data) {
            state.data = data;
            if (state.team !== "ALL" && !data.teams.some(function (t) { return t.team === state.team; })) state.team = "ALL";
            render();
        }).catch(function (e) {
            box.innerHTML = '<p class="text-danger">Utilisation indisponible : ' + esc(e.message) + '</p>';
        });
    }

    function render() {
        var d = state.data, box = document.getElementById("usBody");
        document.getElementById("usPeriod").textContent = "du " + new Date(d.from + "T00:00:00").toLocaleDateString("fr-FR") +
            " au " + new Date(d.to + "T00:00:00").toLocaleDateString("fr-FR");
        if (!d.teams.length) {
            box.innerHTML = '<div class="us-empty"><span><i class="bi bi-pie-chart"></i></span><b>Pas encore de mesure sur cette période</b>' +
                '<small>La mesure démarre dès la prochaine ouverture du portail par les utilisateurs (temps actif par onglet, conservé 2 mois).</small></div>';
            return;
        }
        var top = d.pages[0];
        var kpis = '<div class="us-kpis">' +
            kpi("bi-people", "blue", d.activeUsers, "Utilisateurs actifs") +
            kpi("bi-hourglass-split", "green", fmtTime(d.seconds), "Temps actif total") +
            kpi("bi-box-arrow-in-right", "violet", d.visits, "Ouvertures d'onglets") +
            kpi(top ? meta(top.page)[1] : "bi-star", "orange", top ? esc(meta(top.page)[0]) : "—", "Onglet le plus utilisé") + '</div>';

        var chips = '<div class="us-teams">' + [{ team: "ALL", label: "Toutes les équipes", users: [].concat.apply([], d.teams.map(function (t) { return t.users; })) }].concat(d.teams).map(function (t) {
            return '<button type="button" class="' + (state.team === t.team ? "on" : "") + '" data-team="' + esc(t.team) + '"><i class="bi ' +
                (t.team === "ALL" ? "bi-grid" : (TEAM_ICON[t.team] || "bi-diagram-3")) + '"></i> ' + esc(t.label) + ' <span>' + t.users.length + '</span></button>';
        }).join("") + '</div>';

        var teams = state.team === "ALL" ? d.teams : d.teams.filter(function (t) { return t.team === state.team; });
        var overviewPages = state.team === "ALL" ? d.pages : teams[0].pages;
        var overview = '<div class="us-overview"><div class="us-card us-global">' +
            '<h6><i class="bi bi-pie-chart-fill"></i> ' + (state.team === "ALL" ? "Tout le portail" : esc(teams[0].label)) + '</h6>' +
            '<div class="us-global-body">' + donut(overviewPages, 190, overviewPages.length, "onglets utilisés") +
            '<div class="us-legend">' + legend(overviewPages, 7) + '</div></div></div></div>';

        var q = state.q.trim().toLowerCase();
        var sections = teams.map(function (t, ti) {
            var users = t.users.filter(function (u) {
                return !q || String(u.name || "").toLowerCase().indexOf(q) >= 0 || String(u.username).toLowerCase().indexOf(q) >= 0;
            });
            if (!users.length) return "";
            return '<section class="us-card us-team" style="animation-delay:' + ti * 50 + 'ms">' +
                '<div class="us-team-head">' + donut(t.pages, 74, t.users.length, "pers.") +
                '<div class="us-team-title"><h6><i class="bi ' + (TEAM_ICON[t.team] || "bi-diagram-3") + '"></i> ' + esc(t.label) + '</h6>' +
                '<small>' + t.users.length + ' utilisateur(s) · ' + fmtTime(t.seconds) + ' · ' + t.visits + ' ouvertures</small>' +
                '<div class="us-team-top">' + shares(t.pages).slice(0, 4).map(function (p) {
                    return '<span style="--c:' + meta(p.page)[2] + '"><i class="bi ' + meta(p.page)[1] + '"></i> ' + esc(meta(p.page)[0]) + ' <b>' + pctLabel(p.pct) + '</b></span>';
                }).join("") + '</div></div></div>' +
                '<div class="us-users">' + users.map(function (u) {
                    var first = shares(u.pages)[0];
                    return '<button type="button" class="us-user" data-team="' + esc(t.team) + '" data-user="' + u.id + '">' +
                        '<span class="us-av">' + esc(initials(u.name || u.username)) + '</span>' +
                        '<span class="us-user-main"><b>' + esc(u.name || u.username) + '</b><small>' + esc(u.service || u.username) + '</small>' + stack(u.pages) + '</span>' +
                        '<span class="us-user-side"><b>' + fmtTime(u.seconds) + '</b><small>' + (first ? esc(meta(first.page)[0]) + ' ' + pctLabel(first.pct) : "") + '</small></span>' +
                        '<i class="bi bi-chevron-right"></i></button>';
                }).join("") + '</div></section>';
        }).join("");

        box.innerHTML = kpis + chips + overview + (sections || '<p class="text-muted">Aucun utilisateur ne correspond à la recherche.</p>');
        box.querySelectorAll("[data-team]").forEach(function (b) {
            if (b.classList.contains("us-user")) return;
            b.addEventListener("click", function () { state.team = b.getAttribute("data-team"); render(); });
        });
        box.querySelectorAll(".us-user").forEach(function (b) {
            b.addEventListener("click", function () {
                var t = d.teams.filter(function (x) { return x.team === b.getAttribute("data-team"); })[0];
                openUser(t, t.users.filter(function (u) { return String(u.id) === b.getAttribute("data-user"); })[0]);
            });
        });
    }

    function kpi(icon, tone, value, label) {
        return '<div class="us-kpi"><span class="us-kpi-ico ' + tone + '"><i class="bi ' + icon + '"></i></span><div><b>' + value + '</b><small>' + label + '</small></div></div>';
    }

    var modal;
    function openUser(team, u) {
        var el = document.getElementById("usUserModal");
        if (!modal) modal = new bootstrap.Modal(el);
        var first = shares(u.pages)[0];
        el.querySelector(".us-modal-name").textContent = u.name || u.username;
        el.querySelector(".us-modal-sub").textContent = team.label + (u.service ? " · " + u.service : "") + " · " + u.username;
        el.querySelector(".us-modal-av").textContent = initials(u.name || u.username);
        el.querySelector(".modal-body").innerHTML =
            '<div class="us-detail">' + donut(u.pages, 230, first ? pctLabel(first.pct) : "—", first ? esc(meta(first.page)[0]) : "") +
            '<div class="us-detail-facts">' +
            '<div><i class="bi bi-hourglass-split"></i><b>' + fmtTime(u.seconds) + '</b><small>temps actif</small></div>' +
            '<div><i class="bi bi-box-arrow-in-right"></i><b>' + u.visits + '</b><small>ouvertures</small></div>' +
            '<div><i class="bi bi-window-stack"></i><b>' + u.pages.length + '</b><small>onglets utilisés</small></div>' +
            '<div><i class="bi bi-clock"></i><b>' + (u.lastSeen ? new Date(u.lastSeen).toLocaleString("fr-FR", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) : "—") + '</b><small>dernière activité</small></div>' +
            '</div></div><div class="us-legend us-legend-full">' + legend(u.pages) + '</div>' +
            '<p class="text-muted small mt-3 mb-0"><i class="bi bi-info-circle"></i> Pourcentage du temps actif passé sur chaque onglet (' + state.days + ' derniers jours).</p>';
        modal.show();
    }

    function init() {
        if (!document.getElementById("usBody")) return;
        document.querySelectorAll("#usDays [data-days]").forEach(function (b) {
            b.addEventListener("click", function () {
                state.days = Number(b.getAttribute("data-days"));
                document.querySelectorAll("#usDays [data-days]").forEach(function (x) { x.classList.toggle("on", x === b); });
                load();
            });
        });
        document.getElementById("usSearch").addEventListener("input", function () { state.q = this.value; if (state.data && state.data.teams.length) render(); });
        load();
    }

    window.RccAuditUsage = { init: init, reload: load };
})();
