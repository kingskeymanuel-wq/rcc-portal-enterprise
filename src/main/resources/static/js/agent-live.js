"use strict";

/**
 * « En direct » : dès sa connexion, chaque agent (Inbound Voix, Mail, CIB, Outbound, Tchat, Rafiki) voit SON
 * planning et SES performances, tenus à jour sans recharger la page :
 *  - statut du moment (en poste, prochain shift, repos) recalculé toutes les 30 s ;
 *  - planning des 7 jours, indicateurs du mois et dernier rapport hebdo relus toutes les 60 s,
 *    et dès que l'onglet redevient visible (retour sur la page).
 * RccAgentLive.mount(container, { onToday: function (entry) {} }).
 */
window.RccAgentLive = (function () {
    var esc = RccApi.escapeHtml, getJson = RccApi.getJson;
    var DATA_REFRESH_MS = 60 * 1000, CLOCK_REFRESH_MS = 30 * 1000;

    function iso(d) { return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }
    function hm(t) { return t ? String(t).slice(0, 5) : ""; }
    function at(dateIso, time) { return new Date(dateIso + "T" + String(time).slice(0, 8)); }
    function duration(ms) {
        var m = Math.max(0, Math.round(ms / 60000)), h = Math.floor(m / 60);
        return h ? h + " h " + String(m % 60).padStart(2, "0") : m + " min";
    }
    function pct(v) { return v == null ? "—" : (Math.round(v * 10) / 10).toString().replace(".", ",") + " %"; }

    /** Début / fin réels d'un shift (un shift de nuit se termine le lendemain). */
    function window_(e) {
        if (!e || !e.startTime || !e.endTime) return null;
        var start = at(e.workDate, e.startTime), end = at(e.workDate, e.endTime);
        if (end <= start) end = new Date(end.getTime() + 24 * 3600 * 1000);
        return { start: start, end: end };
    }

    /** Statut du moment d'après le planning : en poste, prochain shift ou repos. */
    function status(entries, now) {
        for (var i = 0; i < entries.length; i++) {
            var w = window_(entries[i]);
            if (w && now >= w.start && now < w.end) {
                return { kind: "on", entry: entries[i], w: w, progress: (now - w.start) / (w.end - w.start) };
            }
        }
        var next = entries.map(function (e) { return { e: e, w: window_(e) }; })
            .filter(function (x) { return x.w && x.w.start > now; })
            .sort(function (a, b) { return a.w.start - b.w.start; })[0];
        var today = entries.filter(function (e) { return e.workDate === iso(now); })[0];
        return { kind: next ? "next" : "none", entry: next ? next.e : null, w: next ? next.w : null, today: today };
    }

    function mount(root, opts) {
        opts = opts || {};
        var state = { entries: [], perf: null, weekly: null, updatedAt: null };
        root.innerHTML = '<section class="al-card">' +
            '<div class="al-head"><h5><i class="bi bi-broadcast"></i> Mon direct — planning &amp; performances</h5>' +
            '<span class="al-live"><span class="al-dot"></span> En direct · <span data-updated>…</span></span></div>' +
            '<div class="al-body"><div class="al-now" data-now></div><div class="al-week" data-week></div></div>' +
            '<div class="al-kpis" data-kpis></div></section>';
        function q(s) { return root.querySelector(s); }

        function renderNow() {
            var now = new Date(), st = status(state.entries, now), box = q("[data-now]");
            if (st.kind === "on") {
                box.className = "al-now on";
                box.innerHTML = '<small>En poste maintenant</small><b>' + esc(st.entry.shiftLabel || st.entry.shiftCode) + '</b>' +
                    '<span>' + hm(st.entry.startTime) + ' – ' + hm(st.entry.endTime) + ' · fin dans ' + duration(st.w.end - now) + '</span>' +
                    '<div class="al-progress"><span style="width:' + Math.round(st.progress * 100) + '%"></span></div>';
            } else if (st.kind === "next") {
                var sameDay = iso(st.w.start) === iso(now);
                box.className = "al-now next";
                box.innerHTML = '<small>' + (st.today && !st.today.startTime ? esc(st.today.shiftLabel || st.today.shiftCode) + " aujourd'hui · " : "") + 'Prochain shift</small>' +
                    '<b>' + (sameDay ? "Aujourd'hui" : st.w.start.toLocaleDateString("fr-FR", { weekday: "long", day: "2-digit", month: "short" })) + '</b>' +
                    '<span>' + esc(st.entry.shiftCode) + ' · ' + hm(st.entry.startTime) + ' – ' + hm(st.entry.endTime) + ' · dans ' + duration(st.w.start - now) + '</span>';
            } else {
                box.className = "al-now none";
                box.innerHTML = '<small>Planning</small><b>Aucun shift à venir</b><span>Votre planning n\'est pas encore publié pour les prochains jours.</span>';
            }
            if (opts.onToday) opts.onToday(st);
        }

        function renderWeek() {
            var byDate = {};
            state.entries.forEach(function (e) { byDate[e.workDate] = e; });
            var html = "";
            for (var i = 0; i < 7; i++) {
                var d = new Date(); d.setDate(d.getDate() + i);
                var e = byDate[iso(d)], off = !e || !e.startTime;
                html += '<div class="al-day' + (i === 0 ? " today" : "") + (off ? " off" : "") + '" title="' + esc(e ? (e.shiftLabel || e.shiftCode) : "Non planifié") + '">' +
                    '<span>' + d.toLocaleDateString("fr-FR", { weekday: "short", day: "2-digit" }) + '</span><b>' + esc(e ? e.shiftCode : "—") + '</b>' +
                    '<small>' + (e && e.startTime ? hm(e.startTime) + "–" + hm(e.endTime) : (e ? esc(e.shiftLabel || "") : "")) + '</small></div>';
            }
            q("[data-week]").innerHTML = html;
        }

        function renderKpis() {
            var p = state.perf || {}, w = state.weekly;
            var tiles = [
                ["bi-calendar-check", "Présence du mois", pct(p.presenceRate), ""],
                ["bi-patch-check", "Score qualité", pct(p.avgQualityScore), (p.evaluationCount || 0) + " évaluation(s)"],
                ["bi-graph-up", "Performance globale", pct(p.performanceGlobale), p.periodMonth ? "mois " + p.periodMonth : ""]
            ];
            if (w) {
                var prod = w.values && w.values.productivity;
                tiles.push(["bi-speedometer2", "Rapport hebdo", pct(prod), (w.rank ? w.rank + (w.rank === 1 ? "er" : "e") + " / " + w.teamSize + " · " : "") +
                    "sem. du " + new Date(w.from + "T00:00:00").toLocaleDateString("fr-FR", { day: "2-digit", month: "short" }), w.level]);
            }
            q("[data-kpis]").innerHTML = tiles.map(function (t) {
                var tone = t[4] === "GOOD" ? " good" : t[4] === "WARN" ? " warn" : t[4] === "BAD" ? " bad" : "";
                return '<a class="al-kpi' + tone + '" href="/performance"><i class="bi ' + t[0] + '"></i><span>' + t[1] + '</span><b>' + t[2] + '</b>' +
                    (t[3] ? '<small>' + esc(t[3]) + '</small>' : "") + '</a>';
            }).join("");
        }

        function load() {
            var from = new Date(); from.setDate(from.getDate() - 1);   // un shift de nuit commencé hier peut être en cours
            var to = new Date(); to.setDate(to.getDate() + 13);
            return Promise.all([
                getJson("/api/schedule/me?from=" + iso(from) + "&to=" + iso(to)).catch(function () { return state.entries; }),
                getJson("/api/performance/me").catch(function () { return state.perf; }),
                getJson("/api/team-perf-files/me").catch(function () { return null; })
            ]).then(function (r) {
                state.entries = r[0] || [];
                state.perf = r[1];
                state.weekly = r[2] && r[2].weeks && r[2].weeks.length ? r[2].weeks[0] : null;
                state.updatedAt = new Date();
                q("[data-updated]").textContent = "mis à jour à " + state.updatedAt.toLocaleTimeString("fr-FR");
                renderNow(); renderWeek(); renderKpis();
            });
        }

        load();
        setInterval(function () { if (!document.hidden) load(); }, DATA_REFRESH_MS);
        setInterval(function () { if (!document.hidden) renderNow(); }, CLOCK_REFRESH_MS);
        document.addEventListener("visibilitychange", function () { if (!document.hidden) load(); });
        return { reload: load };
    }

    return { mount: mount, status: status };
})();
