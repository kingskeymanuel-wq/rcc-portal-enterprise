"use strict";

/**
 * Portail RH — onglet « Plannings en direct » : plannings et shifts de toutes les équipes de la filiale choisie,
 * avec le statut réel de chaque collaborateur (pointeuse) rafraîchi toutes les minutes. Données : /api/hr/live-planning.
 */
window.RccHrLive = (function () {
    var esc = RccApi.escapeHtml;
    var STATUS = {
        EN_POSTE: ["En poste", "bi-broadcast", "ok"],
        EN_PAUSE: ["En pause", "bi-cup-hot", "pause"],
        DECONNECTE: ["Déconnecté", "bi-wifi-off", "off"],
        EN_RETARD: ["En retard", "bi-alarm", "late"],
        A_VENIR: ["À venir", "bi-hourglass-split", "next"],
        TERMINE: ["Shift terminé", "bi-flag", "done"],
        NON_POINTE: ["Non pointé", "bi-person-x", "miss"],
        PLANIFIE: ["Planifié", "bi-calendar-check", "next"],
        CONGE: ["En congé", "bi-airplane", "leave"],
        ABSENT: ["Absent", "bi-person-dash", "miss"],
        REPOS: ["Repos", "bi-moon-stars", "rest"],
        NON_PLANIFIE: ["Sans planning", "bi-question-circle", "rest"]
    };
    var DAYS = ["Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"];
    var state = { data: null, status: "", team: "", q: "", date: null, timer: null, loading: false };
    var root = null;

    function $(sel) { return root.querySelector(sel); }
    function iso(d) { return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }
    function hhmm(t) { return t ? String(t).slice(0, 5) : ""; }
    function country() { return (window.RccHr && window.RccHr.country) || "CI"; }
    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }

    function codeClass(code) {
        if (!code) return "empty";
        if (/^(OFF|RM|R|REPOS|RH|RE|RC)$/.test(code)) return "rest";
        if (/^(C|CP|CONGE|CONGES|CA)$/.test(code)) return "leave";
        if (/^(ABS|AB|ABSENT|MAL|MALADIE|AM)$/.test(code)) return "abs";
        if (/^N/.test(code)) return "night";
        if (/^A/.test(code)) return "aft";
        return "morning";
    }

    function mount(el) {
        root = el;
        root.innerHTML =
            '<div class="hl-toolbar">' +
            '  <div class="hl-date"><button type="button" class="hl-nav" data-shift="-1" aria-label="Jour précédent"><i class="bi bi-chevron-left"></i></button>' +
            '    <input type="date" id="hlDate"><button type="button" class="hl-nav" data-shift="1" aria-label="Jour suivant"><i class="bi bi-chevron-right"></i></button>' +
            '    <button type="button" class="hl-today" id="hlToday">Aujourd\'hui</button></div>' +
            '  <div class="hl-live" id="hlLive"><span class="hl-dot"></span> <span id="hlLiveText">…</span></div>' +
            '  <div class="hl-search"><i class="bi bi-search"></i><input id="hlSearch" type="search" placeholder="Rechercher un collaborateur…"></div>' +
            '</div>' +
            '<div class="hl-summary" id="hlSummary"></div>' +
            '<div class="hl-shifts" id="hlShifts"></div>' +
            '<div class="hl-teamchips" id="hlTeams"></div>' +
            '<div id="hlBody"><div class="hl-empty">Chargement des plannings…</div></div>';
        state.date = iso(new Date());
        $("#hlDate").value = state.date;
        $("#hlDate").addEventListener("change", function () { state.date = this.value || iso(new Date()); load(); });
        $("#hlToday").addEventListener("click", function () { state.date = iso(new Date()); $("#hlDate").value = state.date; load(); });
        root.querySelectorAll(".hl-nav").forEach(function (b) {
            b.addEventListener("click", function () {
                var d = new Date(state.date + "T00:00:00");
                d.setDate(d.getDate() + Number(b.getAttribute("data-shift")));
                state.date = iso(d); $("#hlDate").value = state.date; load();
            });
        });
        $("#hlSearch").addEventListener("input", function () { state.q = this.value.trim().toLowerCase(); render(); });
        $("#hlSummary").addEventListener("click", function (e) {
            var b = e.target.closest("[data-status]");
            if (!b) return;
            state.status = state.status === b.getAttribute("data-status") ? "" : b.getAttribute("data-status");
            render();
        });
        $("#hlTeams").addEventListener("click", function (e) {
            var b = e.target.closest("[data-team]");
            if (!b) return;
            state.team = b.getAttribute("data-team");
            render();
        });
        state.timer = setInterval(function () {
            if (!document.hidden && root.offsetParent !== null && state.date === iso(new Date())) load(true);
        }, 60000);
    }

    function load(silent) {
        if (!root || state.loading) return Promise.resolve();
        state.loading = true;
        if (!silent) $("#hlBody").innerHTML = '<div class="hl-empty"><span class="spinner-border spinner-border-sm"></span> Chargement des plannings…</div>';
        return RccApi.getJson("/api/hr/live-planning?country=" + country() + "&date=" + state.date).then(function (d) {
            state.data = d;
            render();
        }).catch(function (e) {
            $("#hlBody").innerHTML = '<div class="hl-empty text-danger">' + esc(e.message) + '</div>';
        }).finally(function () { state.loading = false; });
    }

    function personMatches(p) {
        if (state.status && p.status !== state.status) return false;
        if (state.q && [p.name, p.username, p.shiftCode, p.role].join(" ").toLowerCase().indexOf(state.q) === -1) return false;
        return true;
    }

    function render() {
        var d = state.data;
        if (!d) return;
        var live = $("#hlLive");
        live.classList.toggle("on", d.today);
        $("#hlLiveText").textContent = d.today ? "En direct · mis à jour à " + new Date(d.generatedAt).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })
            : "Planning du " + new Date(d.date + "T00:00:00").toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long" });

        var keys = Object.keys(STATUS).filter(function (k) { return (d.summary[k] || 0) > 0; });
        $("#hlSummary").innerHTML = keys.map(function (k) {
            var s = STATUS[k];
            return '<button type="button" class="hl-stat ' + s[2] + (state.status === k ? " on" : "") + '" data-status="' + k + '"><i class="bi ' + s[1] + '"></i><b>' + d.summary[k] + '</b><span>' + s[0] + '</span></button>';
        }).join("") || '<div class="hl-empty">Aucun collaborateur actif dans cette filiale.</div>';

        $("#hlShifts").innerHTML = d.shifts.length ? d.shifts.map(function (s) {
            var pct = s.planned ? Math.round(s.present * 100 / s.planned) : 0;
            return '<div class="hl-shift ' + codeClass(s.code) + '"><div class="hl-shift-top"><span class="hl-code ' + codeClass(s.code) + '">' + esc(s.code) + '</span>' +
                '<small>' + hhmm(s.start) + ' – ' + hhmm(s.end) + '</small></div>' +
                '<b>' + (d.today ? s.present + ' / ' + s.planned : s.planned) + '</b><span>' + (d.today ? "présents / planifiés" : "planifiés") + '</span>' +
                (d.today ? '<div class="hl-bar"><i style="width:' + pct + '%"></i></div>' : '') + '</div>';
        }).join("") : "";

        var teams = d.teams;
        $("#hlTeams").innerHTML = '<button type="button" data-team=""' + (state.team ? "" : ' class="on"') + '>Toutes les équipes</button>' +
            teams.map(function (t) {
                var key = (t.population || "") + "|" + (t.team || "");
                return '<button type="button" data-team="' + esc(key) + '"' + (state.team === key ? ' class="on"' : "") + '>' + esc(t.label) + ' <span>' + t.people.length + '</span></button>';
            }).join("");

        var todayIdx = d.weekDays.indexOf(d.date);
        var html = teams.filter(function (t) { return !state.team || state.team === (t.population || "") + "|" + (t.team || ""); }).map(function (t) {
            var people = t.people.filter(personMatches);
            if (!people.length) return "";
            var on = (t.counts.EN_POSTE || 0), pause = (t.counts.EN_PAUSE || 0), late = (t.counts.EN_RETARD || 0) + (t.counts.NON_POINTE || 0);
            return '<section class="hl-team"><header><div><h6>' + esc(t.label) + '</h6><small>' + t.people.length + ' collaborateur(s)</small></div>' +
                '<div class="hl-team-stats">' + (d.today ? '<span class="ok"><i class="bi bi-broadcast"></i> ' + on + '</span><span class="pause"><i class="bi bi-cup-hot"></i> ' + pause + '</span>' +
                '<span class="late"><i class="bi bi-alarm"></i> ' + late + '</span>' : '') + '<span class="leave"><i class="bi bi-airplane"></i> ' + (t.counts.CONGE || 0) + '</span></div></header>' +
                '<div class="hl-table"><div class="hl-row hl-head"><span>Collaborateur</span><span>Shift</span><span>Statut</span><span class="hl-week">' +
                d.weekDays.map(function (w, i) { return '<i' + (i === todayIdx ? ' class="today"' : '') + '>' + DAYS[i] + ' ' + Number(w.slice(8)) + '</i>'; }).join("") + '</span></div>' +
                people.map(function (p) {
                    var s = STATUS[p.status] || [p.status, "bi-dot", "rest"];
                    var since = p.statusSince && d.today && ["EN_POSTE", "EN_PAUSE", "DECONNECTE", "TERMINE"].indexOf(p.status) !== -1
                        ? '<small>depuis ' + new Date(p.statusSince).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" }) + '</small>' : "";
                    return '<div class="hl-row"><span class="hl-who"><span class="hl-av">' + esc(initials(p.name)) + '</span><span><b>' + esc(p.name) + '</b><small>' + esc(p.username || "") + '</small></span></span>' +
                        '<span class="hl-shiftcell">' + (p.shiftCode ? '<span class="hl-code ' + codeClass(p.shiftCode) + '">' + esc(p.shiftCode) + '</span>' +
                        (p.start ? '<small>' + hhmm(p.start) + ' – ' + hhmm(p.end) + '</small>' : '') : '<small class="text-muted">—</small>') + '</span>' +
                        '<span><span class="hl-pill ' + s[2] + '"><i class="bi ' + s[1] + '"></i> ' + s[0] + '</span>' + since + '</span>' +
                        '<span class="hl-week">' + p.week.map(function (c, i) {
                            return '<i class="hl-mini ' + codeClass(c) + (i === todayIdx ? " today" : "") + '" title="' + esc(DAYS[i] + " : " + (c || "non planifié")) + '">' + esc(c || "·") + '</i>';
                        }).join("") + '</span></div>';
                }).join("") + '</div></section>';
        }).join("");
        $("#hlBody").innerHTML = html || '<div class="hl-empty"><i class="bi bi-calendar-x"></i> Aucun collaborateur pour ces filtres.</div>';
    }

    return {
        mount: mount,
        load: load,
        summary: function () { return state.data ? state.data.summary : null; }
    };
})();
