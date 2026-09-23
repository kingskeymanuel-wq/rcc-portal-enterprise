"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var WINDOW_START_MIN = 6 * 60;   // 06:00
    var WINDOW_END_MIN = 22 * 60;    // 22:00
    var shiftDistributionChart = null;

    function todayIso() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }

    function isoOf(dateObj) {
        return dateObj.getFullYear() + "-" + String(dateObj.getMonth() + 1).padStart(2, "0") + "-" + String(dateObj.getDate()).padStart(2, "0");
    }

    function shiftDay(isoDate, delta) {
        var d = new Date(isoDate + "T00:00:00");
        d.setDate(d.getDate() + delta);
        return isoOf(d);
    }

    function minutesSinceMidnight(dateObj) {
        return dateObj.getHours() * 60 + dateObj.getMinutes();
    }

    function pct(minutes) {
        var clamped = Math.min(WINDOW_END_MIN, Math.max(WINDOW_START_MIN, minutes));
        return ((clamped - WINDOW_START_MIN) / (WINDOW_END_MIN - WINDOW_START_MIN)) * 100;
    }

    /**
     * Segments d'un agent pour la journée (travail vert, pause rouge, déconnexion grise) —
     * via RccShiftTimeline (même logique que le serveur) : une déconnexion/reconnexion le même
     * jour apparaît comme une période « Déconnecté » et ne fait plus perdre le temps travaillé
     * avant la reconnexion (l'ancien calcul repartait de zéro au 2e LOGIN).
     */
    function buildSegments(events, isToday) {
        var analysis = RccShiftTimeline.analyze(events, isToday ? new Date() : null);
        return analysis.segments.map(function (s) {
            return {
                type: s.type,
                from: minutesSinceMidnight(s.from),
                to: s.to ? minutesSinceMidnight(s.to) : WINDOW_END_MIN
            };
        }).filter(function (s) { return s.to > s.from; });
    }

    var SEGMENT_LABELS = { work: "En poste", pause: "Pause", offline: "Déconnecté" };

    function renderBar(events, isToday) {
        var segments = buildSegments(events, isToday);
        return segments.map(function (s) {
            var left = pct(s.from), right = pct(s.to);
            var cls = s.type === "work" ? "shift-bar-seg-work" : s.type === "offline" ? "shift-bar-seg-offline" : "shift-bar-seg-pause";
            var width = Math.max(s.type === "work" ? 0.5 : 1, right - left);
            return '<div class="shift-bar-segment ' + cls + '" style="left:' + left + '%;width:' + width + '%;" ' +
                'title="' + SEGMENT_LABELS[s.type] + ' ' + Math.floor(s.from / 60) + 'h' + String(s.from % 60).padStart(2, "0") +
                ' → ' + Math.floor(s.to / 60) + 'h' + String(s.to % 60).padStart(2, "0") +
                ' (' + RccShiftTimeline.formatDuration(s.to - s.from) + ')"></div>';
        }).join("");
    }

    /**
     * Pastille de déconnexion à côté du nom de l'agent : « Déconnecté depuis 10:02 » s'il est
     * absent en ce moment, sinon « N déconnexion(s) · durée totale » — détail au survol.
     */
    function disconnectionBadge(events) {
        var analysis = RccShiftTimeline.analyze(events, null);
        if (!analysis.absences.length) return "";
        var details = analysis.absences.map(RccShiftTimeline.describeAbsence).join(" | ");
        var total = RccShiftTimeline.formatDuration(RccShiftTimeline.totalAbsenceMinutes(analysis.absences));
        if (analysis.state === "DISCONNECTED") {
            var current = analysis.absences[analysis.absences.length - 1];
            return ' <span class="badge shift-badge-offline" title="' + escapeHtml("Déconnexions : " + details) + '">' +
                '<i class="bi bi-plug"></i> Déconnecté depuis ' + RccShiftTimeline.hhmm(current.from) + '</span>';
        }
        return ' <span class="badge shift-badge-reconnected" title="' + escapeHtml("Déconnexions : " + details) + '">' +
            '<i class="bi bi-arrow-repeat"></i> ' + analysis.absences.length + ' déconnexion' + (analysis.absences.length > 1 ? 's' : '') +
            ' · ' + total + '</span>';
    }

    /** Tableau récapitulatif des déconnexions du jour (heure de déconnexion, reconnexion, durée). */
    function disconnectionSummaryHtml(users) {
        var rows = [];
        users.forEach(function (u) {
            var analysis = RccShiftTimeline.analyze(u.events, null);
            analysis.absences.forEach(function (a) {
                rows.push({ name: u.fullName || u.username, absence: a, resumedState: analysis.state });
            });
        });
        if (!rows.length) return "";
        rows.sort(function (a, b) { return a.absence.from - b.absence.from; });
        return '<div class="card dashboard-card shadow-sm mb-3"><div class="card-body py-2">' +
            '<div class="fw-semibold small mb-2"><i class="bi bi-plug"></i> Déconnexions en cours de shift (' + rows.length + ')</div>' +
            '<div class="table-responsive"><table class="table table-sm mb-0 small align-middle">' +
            '<thead><tr><th>Agent</th><th>Déconnecté à</th><th>Reconnecté à</th><th>Durée d\'absence</th></tr></thead><tbody>' +
            rows.map(function (r) {
                var min = Math.max(0, Math.round(((r.absence.to || new Date()) - r.absence.from) / 60000));
                return '<tr><td>' + escapeHtml(r.name) + '</td>' +
                    '<td>' + RccShiftTimeline.hhmm(r.absence.from) + '</td>' +
                    '<td>' + (r.absence.to ? RccShiftTimeline.hhmm(r.absence.to) + ' <span class="text-muted">(reprise en continuité)</span>'
                        : '<span class="badge shift-badge-offline">Toujours déconnecté</span>') + '</td>' +
                    '<td>' + RccShiftTimeline.formatDuration(min) + '</td></tr>';
            }).join("") +
            '</tbody></table></div></div></div>';
    }

    /** Regroupe une liste d'événements (potentiellement multi-jours) par date ISO locale. */
    function groupByDate(events) {
        var byDate = {};
        events.forEach(function (e) {
            var d = isoOf(new Date(e.occurredAt));
            if (!byDate[d]) byDate[d] = [];
            byDate[d].push(e);
        });
        return byDate;
    }

    /** Minutes réellement travaillées un jour donné, à partir de ses événements. */
    function workedMinutesForDay(dayEvents) {
        return buildSegments(dayEvents, false)
            .filter(function (s) { return s.type === "work"; })
            .reduce(function (sum, s) { return sum + (s.to - s.from); }, 0);
    }

    function formatMinutes(min) {
        if (min <= 0) return "—";
        var h = Math.floor(min / 60), m = min % 60;
        return h + "h" + String(m).padStart(2, "0");
    }

    function dateRangeList(fromIso, toIso) {
        var list = [];
        var cursor = fromIso;
        var guard = 0;
        while (cursor <= toIso && guard < 400) {
            list.push(cursor);
            cursor = shiftDay(cursor, 1);
            guard++;
        }
        return list;
    }

    function mondayOf(isoDate) {
        var d = new Date(isoDate + "T00:00:00");
        var day = d.getDay(); // 0 = dimanche
        var diff = day === 0 ? -6 : 1 - day;
        d.setDate(d.getDate() + diff);
        return isoOf(d);
    }

    function firstOfMonth(isoDate) {
        return isoDate.slice(0, 7) + "-01";
    }

    function lastOfMonth(isoDate) {
        var d = new Date(isoDate + "T00:00:00");
        d.setMonth(d.getMonth() + 1);
        d.setDate(0);
        return isoOf(d);
    }

    function firstOfYear(isoDate) { return isoDate.slice(0, 4) + "-01-01"; }
    function lastOfYear(isoDate) { return isoDate.slice(0, 4) + "-12-31"; }

    var REFERENCE_SHIFT_MINUTES = 8 * 60; // shift de référence pour le taux de remplissage affiché

    function pctClass(pct) {
        if (pct >= 80) return "shift-pct-high";
        if (pct >= 40) return "shift-pct-mid";
        return "shift-pct-low";
    }

    // ═══════════════════════════════════════════════════════════════════
    // État : qui (me/team) × quand (day/week/month)
    // ═══════════════════════════════════════════════════════════════════

    var currentWho = "me";
    var currentWhen = "day";

    // ═══════════════════════════════════════════════════════════════════
    // Persistance de l'état (mode/qui/quand/équipe) — sans ça, actualiser la page ou revenir
    // d'un autre onglet du portail réinitialisait toujours sur les valeurs par défaut (Suivi
    // réel/Moi-même/Jour), ce qui perdait le contexte de l'agent à chaque coup (voir demande
    // utilisateur). localStorage plutôt que l'URL : même comportement après un simple clic sur
    // un autre lien du menu, pas seulement un F5 — suit le pattern déjà en place dans
    // preferences.js (clés préfixées "rcc_").
    // ═══════════════════════════════════════════════════════════════════

    function saveShiftState() {
        try {
            localStorage.setItem("rcc_shift_who", currentWho);
            localStorage.setItem("rcc_shift_when", currentWhen);
            localStorage.setItem("rcc_shift_mode", currentMode);
            localStorage.setItem("rcc_shift_team", $("teamSelect").value || "");
        } catch (e) { /* localStorage indisponible (navigation privée...) — dégrade en douceur */ }
    }

    function loadShiftState() {
        try {
            return {
                who: localStorage.getItem("rcc_shift_who") || "me",
                when: localStorage.getItem("rcc_shift_when") || "day",
                mode: localStorage.getItem("rcc_shift_mode") || "real",
                team: localStorage.getItem("rcc_shift_team") || ""
            };
        } catch (e) {
            return { who: "me", when: "day", mode: "real", team: "" };
        }
    }

    function computeRange() {
        var anchor = $("shiftDate").value || todayIso();
        if (currentWhen === "day") return { from: anchor, to: anchor };
        if (currentWhen === "week") { var mon = mondayOf(anchor); return { from: mon, to: shiftDay(mon, 6) }; }
        if (currentWhen === "year") return { from: firstOfYear(anchor), to: lastOfYear(anchor) };
        return { from: firstOfMonth(anchor), to: lastOfMonth(anchor) };
    }

    function updatePeriodLabel() {
        var r = computeRange();
        $("periodLabel").textContent = currentWhen === "day" ? "" : (r.from + " → " + r.to);
    }

    function setWho(who) {
        currentWho = who;
        $("whoMeBtn").className = "btn btn-sm " + (who === "me" ? "btn-primary" : "btn-outline-primary");
        $("whoTeamBtn").className = "btn btn-sm " + (who === "team" ? "btn-primary" : "btn-outline-primary");
        $("teamSelect").style.display = who === "team" ? "" : "none";
        updateScheduleImportVisibility();
        saveShiftState();
        loadAll();
    }

    function setWhen(when) {
        currentWhen = when;
        ["Day", "Week", "Month", "Year"].forEach(function (w) {
            $("when" + w + "Btn").className = "btn btn-sm " + (w.toLowerCase() === when ? "btn-secondary" : "btn-outline-secondary");
        });
        updatePeriodLabel();
        saveShiftState();
        loadAll();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Rendu — vue JOUR (bande horaire, réutilise buildSegments/renderBar)
    // ═══════════════════════════════════════════════════════════════════

    function renderDayMe(events, isLeave, dateIso) {
        var container = $("shiftTree");
        var isToday = dateIso === todayIso();
        if (isLeave) {
            container.innerHTML = '<div class="card dashboard-card shadow-sm"><div class="card-body text-center text-muted">' +
                '<i class="bi bi-airplane"></i> Congé / absence ce jour-là.</div></div>';
            return;
        }
        if (!events.length) {
            container.innerHTML = '<p class="text-muted text-center">Aucun pointage pour cette date.</p>';
            return;
        }
        container.innerHTML = '<div class="card dashboard-card shadow-sm"><div class="card-body">' +
            '<div class="shift-timeline-row"><div class="shift-agent-label">Moi-même' + disconnectionBadge(events) + '</div>' +
            '<div class="shift-bar-wrap">' + renderBar(events, isToday) + '</div></div>' +
            '<div class="shift-bar-ruler"><span>06h</span><span>10h</span><span>14h</span><span>18h</span><span>22h</span></div>' +
            '</div></div>';
    }

    function renderDayTeam(events, leaveUsernames, dateIso, containerId) {
        var container = $(containerId || "shiftTree");
        var isToday = dateIso === todayIso();
        leaveUsernames = leaveUsernames || [];

        if (!events.length) {
            container.innerHTML = '<p class="text-muted text-center">Aucun événement pour cette équipe à cette date.</p>';
            return;
        }

        var byUser = {};
        events.forEach(function (e) {
            if (!byUser[e.username]) byUser[e.username] = { username: e.username, fullName: e.userFullName, events: [] };
            byUser[e.username].events.push(e);
        });

        var rows = Object.values(byUser)
            .sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || ""); })
            .map(function (u) {
                var barContent = leaveUsernames.indexOf(u.username) !== -1
                    ? '<div style="background:#e5e7eb;height:100%;display:flex;align-items:center;justify-content:center;" class="small text-muted"><i class="bi bi-airplane"></i> Congé / absence</div>'
                    : renderBar(u.events, isToday);
                return '<div class="shift-timeline-row">' +
                    '<div class="shift-agent-label">' + escapeHtml(u.fullName || u.username) + disconnectionBadge(u.events) + '</div>' +
                    '<div class="shift-bar-wrap">' + barContent + '</div>' +
                    '</div>';
            }).join("");

        container.innerHTML = disconnectionSummaryHtml(Object.values(byUser)) +
            '<div class="card dashboard-card shadow-sm"><div class="card-body">' + rows +
            '<div class="shift-bar-ruler"><span>06h</span><span>10h</span><span>14h</span><span>18h</span><span>22h</span></div>' +
            '</div></div>';
    }

    // ═══════════════════════════════════════════════════════════════════
    // Rendu — vues SEMAINE / MOIS / ANNÉE (taux de remplissage en %, clic → détail en bande)
    // ═══════════════════════════════════════════════════════════════════

    /** Modale de détail — bande horaire d'un jour précis, réutilisée depuis les vues période. */
    var shiftDayDetailModal = null;

    function openDayDetailModal(dateIso, label) {
        var team = $("teamSelect").value;
        var teamParam = team ? "&team=" + encodeURIComponent(team) : "";
        $("shiftDayDetailTitle").textContent = (label || dateIso);
        $("shiftDayDetailBody").innerHTML = '<p class="text-center text-muted">Chargement…</p>';
        shiftDayDetailModal.show();

        var request = currentWho === "me"
            ? Promise.all([
                getJson("/api/shift/me/events?date=" + dateIso),
                getJson("/api/shift/me/leave-days?from=" + dateIso + "&to=" + dateIso).catch(function () { return []; })
            ]).then(function (r) { return { events: r[0], isLeave: (r[1] || []).indexOf(dateIso) !== -1 }; })
            : Promise.all([
                getJson("/api/shift/team?date=" + dateIso + teamParam),
                getJson("/api/shift/leave-days?date=" + dateIso).catch(function () { return []; })
            ]).then(function (r) { return { events: r[0], leaveUsernames: r[1] || [] }; });

        request.then(function (data) {
            var isToday = dateIso === todayIso();
            var body = $("shiftDayDetailBody");
            if (currentWho === "me") {
                if (data.isLeave) { body.innerHTML = '<p class="text-center text-muted"><i class="bi bi-airplane"></i> Congé / absence ce jour-là.</p>'; return; }
                if (!data.events.length) { body.innerHTML = '<p class="text-center text-muted">Aucun pointage pour cette date.</p>'; return; }
                body.innerHTML = '<div class="shift-timeline-row"><div class="shift-agent-label">Moi-même</div>' +
                    '<div class="shift-bar-wrap">' + renderBar(data.events, isToday) + '</div></div>' +
                    '<div class="shift-bar-ruler"><span>06h</span><span>10h</span><span>14h</span><span>18h</span><span>22h</span></div>';
                return;
            }
            if (!data.events.length) { body.innerHTML = '<p class="text-center text-muted">Aucun événement pour cette équipe à cette date.</p>'; return; }
            var byUser = {};
            data.events.forEach(function (e) {
                if (!byUser[e.username]) byUser[e.username] = { username: e.username, fullName: e.userFullName, events: [] };
                byUser[e.username].events.push(e);
            });
            var rows = Object.values(byUser)
                .sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || ""); })
                .map(function (u) {
                    var barContent = data.leaveUsernames.indexOf(u.username) !== -1
                        ? '<div style="background:#e5e7eb;height:100%;display:flex;align-items:center;justify-content:center;" class="small text-muted"><i class="bi bi-airplane"></i> Congé / absence</div>'
                        : renderBar(u.events, isToday);
                    return '<div class="shift-timeline-row"><div class="shift-agent-label">' + escapeHtml(u.fullName || u.username) + '</div>' +
                        '<div class="shift-bar-wrap">' + barContent + '</div></div>';
                }).join("");
            body.innerHTML = rows + '<div class="shift-bar-ruler"><span>06h</span><span>10h</span><span>14h</span><span>18h</span><span>22h</span></div>';
        }).catch(function (e) {
            $("shiftDayDetailBody").innerHTML = '<p class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    /** Ouvre le mois en question dans la vue "Mois" — utilisé par le clic sur une case de la vue Année. */
    function drillIntoMonth(monthIso) {
        $("shiftDate").value = monthIso;
        setWhen("month");
    }

    function pctCellHtml(workedMinutes, hasActivity, isLeave, isFuture) {
        if (isLeave) return '<div class="shift-pct-cell shift-pct-none"><i class="bi bi-airplane"></i></div>';
        if (!hasActivity) return '<div class="shift-pct-cell shift-pct-none">' + (isFuture ? "à venir" : "—") + '</div>';
        var pct = Math.min(100, Math.round(100 * workedMinutes / REFERENCE_SHIFT_MINUTES));
        return '<div class="shift-pct-cell ' + pctClass(pct) + '">' + pct + '%</div>';
    }

    function renderRangeMe(events, leaveDates, from, to) {
        var container = $("shiftTree");
        var byDate = groupByDate(events);
        var days = dateRangeList(from, to);
        leaveDates = leaveDates || [];
        var today = todayIso();

        if (currentWhen === "year") {
            renderRangeMeByMonth(byDate, leaveDates, from, to);
            return;
        }

        var rows = days.map(function (d) {
            var isFuture = d > today;
            var isLeave = leaveDates.indexOf(d) !== -1;
            var dayEvents = byDate[d] || [];
            var hasActivity = dayEvents.length > 0;
            var minutes = workedMinutesForDay(dayEvents);
            var label = d.slice(8) + "/" + d.slice(5, 7);
            var cellHtml = pctCellHtml(minutes, hasActivity, isLeave, isFuture);
            var clickable = hasActivity && !isLeave;
            return '<div class="shift-timeline-row">' +
                '<div class="shift-agent-label">' + escapeHtml(label) + (hasActivity ? '<span class="text-muted small ms-1">' + formatMinutes(minutes) + '</span>' : '') + '</div>' +
                '<div class="shift-bar-wrap" style="width:110px;flex-grow:0;' + (clickable ? 'cursor:pointer' : '') + '" data-date="' + d + '"' + (clickable ? ' data-clickable="1"' : '') + '>' + cellHtml + '</div>' +
                '</div>';
        }).join("");

        container.innerHTML = '<div class="card dashboard-card shadow-sm"><div class="card-body">' + rows +
            '<p class="text-muted small mt-2 mb-0"><i class="bi bi-info-circle"></i> Taux calculé sur un shift de référence de 8h — cliquez un jour actif pour voir la bande détaillée.</p>' +
            '</div></div>';

        wirePctCellClicks(container, "me");
    }

    function renderRangeMeByMonth(byDate, leaveDates, from, to) {
        var container = $("shiftTree");
        var months = [];
        var cursor = firstOfMonth(from);
        while (cursor <= to) { months.push(cursor); cursor = firstOfMonth(shiftDay(lastOfMonth(cursor), 1)); }
        var monthNames = ["Jan", "Fév", "Mar", "Avr", "Mai", "Juin", "Juil", "Août", "Sep", "Oct", "Nov", "Déc"];

        var rows = months.map(function (m) {
            var days = dateRangeList(m, lastOfMonth(m));
            var totalMinutes = 0, activeDays = 0;
            days.forEach(function (d) {
                var mins = workedMinutesForDay(byDate[d] || []);
                if (mins > 0) { totalMinutes += mins; activeDays++; }
            });
            var avgPct = activeDays ? Math.min(100, Math.round(100 * (totalMinutes / activeDays) / REFERENCE_SHIFT_MINUTES)) : null;
            var label = monthNames[parseInt(m.slice(5, 7), 10) - 1];
            var cellHtml = avgPct === null
                ? '<div class="shift-pct-cell shift-pct-none">—</div>'
                : '<div class="shift-pct-cell ' + pctClass(avgPct) + '" style="cursor:pointer" data-month="' + m + '">' + avgPct + '%</div>';
            return '<div class="shift-timeline-row"><div class="shift-agent-label">' + escapeHtml(label) + '</div>' +
                '<div class="shift-bar-wrap" style="width:110px;flex-grow:0;">' + cellHtml + '</div></div>';
        }).join("");

        container.innerHTML = '<div class="card dashboard-card shadow-sm"><div class="card-body">' + rows +
            '<p class="text-muted small mt-2 mb-0"><i class="bi bi-info-circle"></i> Moyenne des jours actifs du mois — cliquez un mois pour voir le détail jour par jour.</p>' +
            '</div></div>';

        Array.prototype.forEach.call(container.querySelectorAll("[data-month]"), function (cell) {
            cell.addEventListener("click", function () { drillIntoMonth(cell.getAttribute("data-month")); });
        });
    }

    function renderRangeTeam(events, from, to) {
        var container = $("shiftTree");
        var days = dateRangeList(from, to);

        var byUser = {};
        events.forEach(function (e) {
            if (!byUser[e.username]) byUser[e.username] = { username: e.username, fullName: e.userFullName, events: [] };
            byUser[e.username].events.push(e);
        });

        var users = Object.values(byUser).sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || ""); });
        if (!users.length) {
            container.innerHTML = '<p class="text-muted text-center">Aucun événement pour cette équipe sur cette période.</p>';
            return;
        }

        if (currentWhen === "year") {
            renderRangeTeamByMonth(users, from, to);
            return;
        }

        var rows = users.map(function (u) {
            var byDate = groupByDate(u.events);
            var cells = days.map(function (d) {
                var minutes = workedMinutesForDay(byDate[d] || []);
                var hasActivity = minutes > 0;
                var cellHtml = pctCellHtml(minutes, hasActivity, false, d > todayIso());
                return '<td class="text-center p-1" style="width:64px;' + (hasActivity ? 'cursor:pointer' : '') + '" data-date="' + d + '" data-user="' + escapeHtml(u.username) + '"' + (hasActivity ? ' data-clickable="1"' : '') + '>' + cellHtml + '</td>';
            }).join("");
            return "<tr><td>" + escapeHtml(u.fullName || u.username) + "</td>" + cells + "</tr>";
        }).join("");

        container.innerHTML = '<div class="card dashboard-card shadow-sm"><div class="card-body table-responsive">' +
            '<table class="table table-sm table-hover mb-0"><thead><tr><th>Agent</th>' +
            days.map(function (d) { return "<th class=\"text-center small\">" + d.slice(5) + "</th>"; }).join("") +
            '</tr></thead><tbody>' + rows + '</tbody></table>' +
            '<p class="text-muted small mt-2 mb-0"><i class="bi bi-info-circle"></i> Taux calculé sur un shift de référence de 8h — cliquez une case pour voir la bande détaillée de ce jour.</p>' +
            '</div></div>';

        wirePctCellClicks(container, "team");
    }

    function renderRangeTeamByMonth(users, from, to) {
        var container = $("shiftTree");
        var months = [];
        var cursor = firstOfMonth(from);
        while (cursor <= to) { months.push(cursor); cursor = firstOfMonth(shiftDay(lastOfMonth(cursor), 1)); }
        var monthNames = ["Jan", "Fév", "Mar", "Avr", "Mai", "Juin", "Juil", "Août", "Sep", "Oct", "Nov", "Déc"];

        var rows = users.map(function (u) {
            var byDate = groupByDate(u.events);
            var cells = months.map(function (m) {
                var days = dateRangeList(m, lastOfMonth(m));
                var totalMinutes = 0, activeDays = 0;
                days.forEach(function (d) {
                    var mins = workedMinutesForDay(byDate[d] || []);
                    if (mins > 0) { totalMinutes += mins; activeDays++; }
                });
                var avgPct = activeDays ? Math.min(100, Math.round(100 * (totalMinutes / activeDays) / REFERENCE_SHIFT_MINUTES)) : null;
                return avgPct === null
                    ? '<td class="text-center p-1 text-muted small">—</td>'
                    : '<td class="text-center p-1" style="cursor:pointer" data-month="' + m + '"><div class="shift-pct-cell ' + pctClass(avgPct) + '" style="display:inline-flex;width:100%;">' + avgPct + '%</div></td>';
            }).join("");
            return "<tr><td>" + escapeHtml(u.fullName || u.username) + "</td>" + cells + "</tr>";
        }).join("");

        container.innerHTML = '<div class="card dashboard-card shadow-sm"><div class="card-body table-responsive">' +
            '<table class="table table-sm table-hover mb-0"><thead><tr><th>Agent</th>' +
            monthNames.map(function (m) { return "<th class=\"text-center small\">" + m + "</th>"; }).join("") +
            '</tr></thead><tbody>' + rows + '</tbody></table>' +
            '<p class="text-muted small mt-2 mb-0"><i class="bi bi-info-circle"></i> Moyenne des jours actifs du mois — cliquez un mois pour voir le détail jour par jour.</p>' +
            '</div></div>';

        Array.prototype.forEach.call(container.querySelectorAll("[data-month]"), function (cell) {
            cell.addEventListener("click", function () { drillIntoMonth(cell.getAttribute("data-month")); });
        });
    }

    function wirePctCellClicks(container, who) {
        Array.prototype.forEach.call(container.querySelectorAll('[data-clickable="1"]'), function (cell) {
            cell.addEventListener("click", function () {
                var d = cell.getAttribute("data-date");
                var label = who === "team" && cell.getAttribute("data-user")
                    ? (cell.closest("tr").querySelector("td").textContent + " — " + d)
                    : d;
                openDayDetailModal(d, label);
            });
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Chargement
    // ═══════════════════════════════════════════════════════════════════

    function loadTeams() {
        return getJson("/api/teams").then(function (teams) {
            var select = $("teamSelect");
            var current = select.value;
            select.innerHTML = '<option value="">— Mon équipe —</option>' +
                teams.map(function (t) { return '<option value="' + escapeHtml(t.code) + '">' + escapeHtml(t.label) + '</option>'; }).join("");
            select.value = current;
        }).catch(function () { /* liste d'équipes indisponible — le select reste sur "Mon équipe" */ });
    }

    function loadEvents() {
        var range = computeRange();
        var team = $("teamSelect").value;

        if (currentWho === "me") {
            if (currentWhen === "day") {
                Promise.all([
                    getJson("/api/shift/me/events?date=" + range.from),
                    getJson("/api/shift/me/leave-days?from=" + range.from + "&to=" + range.from).catch(function () { return []; })
                ]).then(function (r) {
                    renderDayMe(r[0], (r[1] || []).indexOf(range.from) !== -1, range.from);
                }).catch(function (e) { showError(e); });
            } else {
                Promise.all([
                    getJson("/api/shift/me/events/range?from=" + range.from + "&to=" + range.to),
                    getJson("/api/shift/me/leave-days?from=" + range.from + "&to=" + range.to).catch(function () { return []; })
                ]).then(function (r) {
                    renderRangeMe(r[0], r[1] || [], range.from, range.to);
                }).catch(function (e) { showError(e); });
            }
        } else {
            var teamParam = team ? "&team=" + encodeURIComponent(team) : "";
            if (currentWhen === "day") {
                Promise.all([
                    getJson("/api/shift/team?date=" + range.from + teamParam),
                    getJson("/api/shift/leave-days?date=" + range.from).catch(function () { return []; })
                ]).then(function (r) {
                    renderDayTeam(r[0], r[1] || [], range.from);
                }).catch(function (e) { showError(e); });
            } else {
                getJson("/api/shift/team/range?from=" + range.from + "&to=" + range.to + teamParam)
                    .then(function (events) { renderRangeTeam(events, range.from, range.to); })
                    .catch(function (e) { showError(e); });
            }
        }
    }

    function showError(e) {
        $("shiftTree").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
    }

    function loadLateness() {
        var date = $("shiftDate").value;
        getJson("/api/schedule/lateness?date=" + date).then(function (rows) {
            var card = $("latenessCard");
            var list = $("latenessList");
            var lateRows = rows.filter(function (r) { return r.lateMinutes !== null; });

            if (!rows.length) { card.style.display = "none"; return; }
            card.style.display = "";

            if (!lateRows.length) {
                list.innerHTML = '<p class="text-success small mb-0"><i class="bi bi-check-circle"></i> Aucun retard sur les plannings du jour.</p>';
                return;
            }

            list.innerHTML = lateRows.map(function (r) {
                var expected = r.plannedStartTime ? r.plannedStartTime.slice(0, 5) : "—";
                var actual = r.actualLoginTime ? r.actualLoginTime.slice(0, 5) : "—";
                var shift = r.shiftCode ? ' <span class="badge bg-light text-dark border">' + escapeHtml(r.shiftCode) + '</span>' : '';
                return '<div class="d-flex justify-content-between align-items-center border-bottom py-1 small">' +
                    '<span>' + escapeHtml(r.fullName || r.username) + (r.service ? ' <span class="text-muted">(' + escapeHtml(r.service) + ')</span>' : '') + shift +
                    '<span class="text-muted ms-1">— son shift : ' + expected + ', connecté à ' + actual + '</span></span>' +
                    '<span class="badge bg-danger">' + r.lateMinutes + ' min de retard</span>' +
                    '</div>';
            }).join("");
        }).catch(function () { $("latenessCard").style.display = "none"; });
    }

    function loadAttendance() {
        var date = $("shiftDate").value;
        getJson("/api/attendance?date=" + date).then(function (rows) {
            $("attendanceCard").style.display = "";
            if (!rows.length) {
                $("attendanceTable").innerHTML = '<tr><td colspan="5" class="text-muted text-center">Aucune présence importée pour cette date.</td></tr>';
                return;
            }
            $("attendanceTable").innerHTML = rows.map(function (r) {
                var statusBadge = r.status === "present"
                    ? '<span class="badge bg-success">Présent</span>'
                    : '<span class="badge bg-danger">Absent</span>';
                return "<tr><td>" + escapeHtml(r.name || r.matricule) + "</td>" +
                    "<td>" + escapeHtml(r.team || "—") + "</td>" +
                    "<td>" + statusBadge + "</td>" +
                    "<td>" + (r.arrivalTime || "—") + "</td>" +
                    "<td>" + (r.departureTime || "—") + "</td></tr>";
            }).join("");
        }).catch(function () { $("attendanceCard").style.display = "none"; });
    }

    $("attendanceImportBtn").addEventListener("click", function () {
        var file = $("attendanceImportFile").files[0];
        var date = $("shiftDate").value;
        var resultBox = $("attendanceImportResult");
        if (!file) { alert("Choisissez un fichier."); return; }

        resultBox.className = "small mb-2 text-muted";
        resultBox.textContent = "Import en cours…";

        var formData = new FormData();
        formData.append("file", file);

        fetch("/api/attendance/import?date=" + date, { method: "POST", credentials: "same-origin", body: formData })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                return res.json();
            })
            .then(function (result) {
                resultBox.className = "small mb-2 text-success";
                resultBox.textContent = result.recordsCreated + " présence(s) importée(s) sur " + result.rowsProcessed + " ligne(s)." +
                    (result.unresolvedNames.length ? " Non reconnus : " + result.unresolvedNames.join(", ") : "");
                $("attendanceImportFile").value = "";
                $("attendanceCard").style.display = "";
                loadAttendance();
            })
            .catch(function (e) {
                resultBox.className = "small mb-2 text-danger";
                resultBox.textContent = "Erreur : " + e.message;
            });
    });

    var currentMode = "real"; // "real" (pointages réels) | "planning" (programmé, importé)

    function setMode(mode) {
        currentMode = mode;
        $("modeRealBtn").className = "btn btn-sm " + (mode === "real" ? "btn-secondary" : "btn-outline-secondary");
        $("modePlanningBtn").className = "btn btn-sm " + (mode === "planning" ? "btn-secondary" : "btn-outline-secondary");
        // Le planning est une donnée programmée à l'avance — "En direct" n'a de sens que pour
        // le suivi réel (pointages), on le masque en mode Planning plutôt que de le laisser
        // cliquable sans effet.
        $("shiftLiveBtn").style.display = mode === "planning" ? "none" : "";
        updateExportVisibility();
        updateScheduleImportVisibility();
        saveShiftState();
        loadAll();
    }

    /** Export Excel du shift — réservé à ceux qui suivent une équipe (QA/RH/Superviseur/Admin/
     *  Team Leader/Excelliam) : un agent ne doit jamais pouvoir exporter des shifts (les siens
     *  ou ceux d'autrui), le bouton reste donc masqué en permanence pour le profil AGENT, quel
     *  que soit le mode (réel/planning) ou la vue (Moi-même/Équipe). */
    function updateExportVisibility() {
        var hide = currentMode === "planning" || currentProfile === "AGENT";
        $("exportExcelBtn").style.display = hide ? "none" : "";
    }

    /** Regroupement des codes de shift en 5 familles visuelles — voir CSS .planning-chip-*.
     *  Reconnaissance par mot-clé sur le libellé (pas seulement le code brut), pour rester
     *  correct même si l'outil source change ses abréviations d'un import à l'autre. */
    function planningChipClass(code, label) {
        var text = ((label || "") + " " + (code || "")).toUpperCase();
        if (!code) return "planning-chip-empty";
        if (text.indexOf("CONGE") !== -1) return "planning-chip-leave";
        if (text.indexOf("ABS") !== -1 || text.indexOf("RETARD") !== -1) return "planning-chip-absence";
        if (text.indexOf("OFF") !== -1 || text.indexOf("REPOS") !== -1 || text.indexOf("MALADIE") !== -1
            || text.indexOf("PERMISSION") !== -1 || text.indexOf("PERMUT") !== -1) return "planning-chip-off";
        if (/\d{1,2}H\d{0,2}\s*-\s*\d{1,2}H/.test(text)) return "planning-chip-work";
        return "planning-chip-other";
    }

    var currentPlanningRange = null;

    function loadPlanning() {
        var range = computeRange();
        var container = $("shiftTree");

        if (currentWhen === "year") {
            container.innerHTML = '<p class="text-muted text-center py-4"><i class="bi bi-info-circle"></i> Le planning détaillé se consulte par jour, semaine ou mois — choisissez une de ces vues pour l\'année ' + range.from.slice(0, 4) + '.</p>';
            $("tlApprovalPanel").style.display = "none";
            $("shiftDistributionCard").style.display = "none";
            return;
        }

        container.innerHTML = '<p class="text-center text-muted">Chargement…</p>';
        currentPlanningRange = range;

        if (currentWho === "me") {
            $("tlApprovalPanel").style.display = "none";
            getJson("/api/schedule/me?from=" + range.from + "&to=" + range.to)
                .then(function (rows) { renderPlanningMe(rows, range.from, range.to); })
                .catch(function (e) { showError(e); });
        } else {
            var team = $("teamSelect").value;
            var url = "/api/schedule/team?from=" + range.from + "&to=" + range.to + (team ? "&team=" + encodeURIComponent(team) : "");
            getJson(url)
                .then(function (rows) { renderPlanningTeam(rows, range.from, range.to); })
                .catch(function (e) { showError(e); });
        }
    }

    /** Affiche le code de shift ET l'heure de début→fin telles qu'elles ressortent du fichier
     *  (visible directement dans la cellule, pas seulement en infobulle) — pour les codes sans
     *  horaire (OFF/ABS/RM/P/PU/Congés), seul le code/libellé s'affiche, jamais d'heure inventée.
     *  Une entrée PENDING/REJECTED (planning Excelliam pas encore validé par le Team Leader) est
     *  surlignée distinctement — visible uniquement par Team Leader/Excelliam/Admin/RH/Superviseur,
     *  jamais par un Agent (déjà filtré côté serveur, voir ScheduleService.planningForUser/Team). */
    function planningCellHtml(entry) {
        if (!entry) return '<div class="planning-chip planning-chip-empty">—</div>';
        var hasHours = !!(entry.startTime && entry.endTime);
        var hoursText = hasHours
            ? entry.startTime.slice(0, 5) + (entry.overnightCrossesMidnight ? "→" : "–") + entry.endTime.slice(0, 5)
            : "";
        var statusClass = entry.approvalStatus === "PENDING" ? " planning-chip-pending"
            : entry.approvalStatus === "REJECTED" ? " planning-chip-rejected" : "";
        var title = (entry.shiftLabel || hoursText || entry.shiftCode || "") +
            (entry.approvalStatus === "PENDING" ? " — en attente de validation TL" : "") +
            (entry.approvalStatus === "REJECTED" ? " — refusé : " + (entry.rejectionReason || "") : "");
        var codeLine = escapeHtml(entry.shiftCode || (hasHours ? entry.startTime.slice(0, 5) : (entry.shiftLabel || "—")));
        return '<div class="planning-chip ' + planningChipClass(entry.shiftCode, entry.shiftLabel) + statusClass + '" title="' + escapeHtml(title) + '">' +
            '<span>' + codeLine + '</span>' +
            (hasHours ? '<span class="planning-chip-hours">' + escapeHtml(hoursText) + '</span>' : '') +
            '</div>';
    }

    function renderPlanningMe(rows, from, to) {
        var container = $("shiftTree");
        $("shiftDistributionCard").style.display = "none"; // graphique réservé à la vue Équipe (un seul agent n'a rien à répartir)
        var byDate = {};
        rows.forEach(function (r) { byDate[r.workDate] = r; });
        var days = dateRangeList(from, to);

        var body = days.map(function (d) {
            var entry = byDate[d];
            var label = d.slice(8) + "/" + d.slice(5, 7);
            return '<div class="shift-timeline-row"><div class="shift-agent-label">' + escapeHtml(label) + '</div>' +
                '<div style="width:160px;">' + planningCellHtml(entry) + '</div>' +
                '<div class="text-muted small">' + (entry && entry.shiftLabel ? escapeHtml(entry.shiftLabel) : "") + '</div></div>';
        }).join("");

        container.innerHTML = '<div class="card dashboard-card shadow-sm"><div class="card-body">' + body +
            '<p class="text-muted small mt-2 mb-0"><i class="bi bi-info-circle"></i> Planning programmé (importé) — distinct de vos pointages réels (onglet "Suivi réel").</p>' +
            '</div></div>';

        container.classList.remove("shift-fade-in");
        void container.offsetWidth;
        container.classList.add("shift-fade-in");
    }

    var SHIFT_CHART_COLORS = { M: "#0057B8", M2: "#3B82C4", A: "#00A651", N: "#7C3AED", OFF: "#94A3B8", ABS: "#DC3545", C: "#F59E0B" };

    /** Anneau — répartition des codes de shift (M/M2/A/N/OFF...) sur les lignes actuellement
     *  affichées (rows = celles déjà reçues par renderPlanningTeam, pas de second appel API).
     *  Masqué si aucune ligne n'a de code exploitable plutôt que d'afficher un graphique vide. */
    function renderShiftDistributionChart(rows, from, to) {
        var counts = {};
        rows.forEach(function (r) {
            if (!r.shiftCode) return;
            counts[r.shiftCode] = (counts[r.shiftCode] || 0) + 1;
        });
        var codes = Object.keys(counts).sort(function (a, b) { return counts[b] - counts[a]; });

        if (!codes.length) { $("shiftDistributionCard").style.display = "none"; return; }
        $("shiftDistributionCard").style.display = "";
        $("shiftDistributionPeriod").textContent = from === to ? from : from + " → " + to;

        if (shiftDistributionChart) shiftDistributionChart.destroy();
        shiftDistributionChart = new Chart($("shiftDistributionChart"), {
            type: "doughnut",
            data: {
                labels: codes,
                datasets: [{
                    data: codes.map(function (c) { return counts[c]; }),
                    backgroundColor: codes.map(function (c) { return SHIFT_CHART_COLORS[c] || "#CBD5E1"; }),
                    borderWidth: 0
                }]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: { legend: { position: "bottom", labels: { boxWidth: 10, font: { size: 11 } } } }
            }
        });
    }

    function renderPlanningTeam(rows, from, to) {
        var container = $("shiftTree");
        var days = dateRangeList(from, to);

        var byUser = {};
        rows.forEach(function (r) {
            if (!byUser[r.username]) byUser[r.username] = { fullName: r.fullName, byDate: {} };
            byUser[r.username].byDate[r.workDate] = r;
        });

        var usernames = Object.keys(byUser).sort(function (a, b) {
            return (byUser[a].fullName || "").localeCompare(byUser[b].fullName || "", "fr");
        });

        updateTeamLeaderApprovalPanel(rows);
        renderShiftDistributionChart(rows, from, to);

        if (!usernames.length) {
            container.innerHTML = '<p class="text-muted text-center">Aucun planning importé pour cette période/équipe.</p>';
            return;
        }

        var rowsHtml = usernames.map(function (u) {
            var cells = days.map(function (d) {
                return '<td class="text-center p-1">' + planningCellHtml(byUser[u].byDate[d]) + '</td>';
            }).join("");
            return "<tr><td>" + escapeHtml(byUser[u].fullName || u) + "</td>" + cells + "</tr>";
        }).join("");

        container.innerHTML = '<div class="card dashboard-card shadow-sm"><div class="card-body table-responsive">' +
            '<table class="table table-sm table-hover mb-0"><thead><tr><th>Agent</th>' +
            days.map(function (d) { return "<th class=\"text-center small\">" + d.slice(8) + "/" + d.slice(5, 7) + "</th>"; }).join("") +
            '</tr></thead><tbody>' + rowsHtml + '</tbody></table>' +
            '<p class="text-muted small mt-2 mb-0"><i class="bi bi-info-circle"></i> Planning programmé (importé) — distinct des pointages réels.</p>' +
            '</div></div>';

        container.classList.remove("shift-fade-in");
        void container.offsetWidth; // force le reflow pour rejouer l'animation à chaque changement de période/équipe
        container.classList.add("shift-fade-in");
    }

    /** Pilote le panneau de validation (#tlApprovalPanel) — visible uniquement pour un Team
     *  Leader, uniquement s'il y a au moins une entrée PENDING dans les lignes déjà chargées
     *  (pas d'appel API séparé : on réutilise les données déjà reçues par renderPlanningTeam). */
    function updateTeamLeaderApprovalPanel(rows) {
        if (currentProfile !== "TEAM_LEADER") { $("tlApprovalPanel").style.display = "none"; return; }
        var pendingCount = rows.filter(function (r) { return r.approvalStatus === "PENDING"; }).length;
        if (!pendingCount) { $("tlApprovalPanel").style.display = "none"; return; }
        $("tlApprovalPanel").style.display = "";
        $("tlApprovalCount").textContent = pendingCount;
        $("tlRejectForm").style.display = "none";
        $("tlApprovalResult").textContent = "";
    }


    function loadAll() {
        updatePeriodLabel();
        if (currentMode === "planning") {
            loadPlanning();
            $("latenessCard").style.display = "none";
            $("attendanceCard").style.display = "none";
            return;
        }
        $("shiftDistributionCard").style.display = "none"; // graphique réservé au mode Planning
        loadEvents();
        if (currentWhen === "day") { loadLateness(); loadAttendance(); }
        else { $("latenessCard").style.display = "none"; $("attendanceCard").style.display = "none"; }
    }

    $("modeRealBtn").addEventListener("click", function () { setMode("real"); });
    $("modePlanningBtn").addEventListener("click", function () { setMode("planning"); });

    $("whoMeBtn").addEventListener("click", function () { setWho("me"); });
    $("whoTeamBtn").addEventListener("click", function () { setWho("team"); });
    $("whenDayBtn").addEventListener("click", function () { setWhen("day"); });
    $("whenWeekBtn").addEventListener("click", function () { setWhen("week"); });
    $("whenMonthBtn").addEventListener("click", function () { setWhen("month"); });
    $("whenYearBtn").addEventListener("click", function () { setWhen("year"); });
    $("teamSelect").addEventListener("change", function () { saveShiftState(); updateScheduleImportVisibility(); loadAll(); });

    $("shiftDate").value = todayIso();
    $("shiftDate").addEventListener("change", loadAll);
    function shiftYear(isoDate, deltaYears) {
        var d = new Date(isoDate + "T00:00:00");
        d.setFullYear(d.getFullYear() + deltaYears);
        return isoOf(d);
    }

    $("prevPeriodBtn").addEventListener("click", function () {
        if (currentWhen === "year") { $("shiftDate").value = shiftYear($("shiftDate").value, -1); loadAll(); return; }
        var delta = currentWhen === "day" ? -1 : currentWhen === "week" ? -7 : -30;
        $("shiftDate").value = shiftDay($("shiftDate").value, delta);
        loadAll();
    });
    $("nextPeriodBtn").addEventListener("click", function () {
        if (currentWhen === "year") { $("shiftDate").value = shiftYear($("shiftDate").value, 1); loadAll(); return; }
        var delta = currentWhen === "day" ? 1 : currentWhen === "week" ? 7 : 30;
        $("shiftDate").value = shiftDay($("shiftDate").value, delta);
        loadAll();
    });

    $("exportExcelBtn").addEventListener("click", function () {
        var range = computeRange();
        var btn = this;
        var params = "?from=" + range.from + "&to=" + range.to + "&team=" + (currentWho === "team");
        if (currentWho === "team" && $("teamSelect").value) params += "&teamCode=" + encodeURIComponent($("teamSelect").value);

        btn.disabled = true;
        var original = btn.innerHTML;
        btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Export…';

        fetch("/api/shift/export" + params, { credentials: "same-origin" })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                return res.blob();
            })
            .then(function (blob) {
                var url = window.URL.createObjectURL(blob);
                var a = document.createElement("a");
                a.href = url;
                a.download = "suivi-shift-" + range.from + "_" + range.to + ".xlsx";
                document.body.appendChild(a);
                a.click();
                a.remove();
                window.URL.revokeObjectURL(url);
            })
            .catch(function (e) { alert("Erreur export : " + e.message); })
            .finally(function () { btn.disabled = false; btn.innerHTML = original; });
    });

    // ===================== STATUT EN DIRECT =====================

    var SHIFT_STATE_BADGES = {
        WORKING: '<span class="badge bg-success">En poste</span>',
        NOT_STARTED: '<span class="badge bg-secondary">Non démarré</span>',
        ON_PAUSE: '<span class="badge bg-danger">En pause</span>',
        ON_LUNCH: '<span class="badge bg-danger">Pause déjeuner</span>',
        ON_TRAINING: '<span class="badge" style="background:#0057B8;">En formation</span>',
        ON_MEETING: '<span class="badge" style="background:#F5A623;color:#000;">En réunion</span>',
        SHIFT_ENDED: '<span class="badge bg-dark">Shift terminé</span>',
        DISCONNECTED: '<span class="badge shift-badge-offline">Déconnecté</span>'
    };
    var TEAM_LABELS_SHIFT = { INBOUND_VOICE: "Inbound Voix", INBOUND_MAIL: "Inbound Mail / Rafiki", CIB: "CIB", OUTBOUND: "Outbound", OTHER: "Non classée" };

    /** "En direct" réutilise la même frise horaire que Suivi réel > Équipe (voir renderDayTeam),
     *  sur l'équipe active (teamSelect / celle du Team Leader) plutôt qu'un tableau texte
     *  multi-équipes — voir demande utilisateur et capture de référence. La barre verte
     *  s'arrête à l'heure actuelle (isToday dans buildSegments), donnant l'effet "en direct". */
    function loadShiftLive() {
        var container = $("shiftLiveTree");
        container.innerHTML = '<p class="text-center text-muted">Chargement…</p>';

        var today = todayIso();
        var team = $("teamSelect").value;
        var teamParam = team ? "&team=" + encodeURIComponent(team) : "";

        Promise.all([
            getJson("/api/shift/team?date=" + today + teamParam),
            getJson("/api/shift/leave-days?date=" + today).catch(function () { return []; })
        ]).then(function (r) {
            renderDayTeam(r[0], r[1] || [], today, "shiftLiveTree");
        }).catch(function (e) {
            container.innerHTML = '<p class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    $("shiftLiveBtn").addEventListener("click", function () {
        var section = $("shiftLiveSection");
        var showing = section.style.display !== "none";
        section.style.display = showing ? "none" : "";
        this.classList.toggle("btn-danger", !showing);
        this.classList.toggle("btn-outline-danger", showing);
        if (!showing) loadShiftLive();
    });

    // ═══════════════════════════════════════════════════════════════════
    // Permutations de shift entre agents — voir ShiftSwapService pour le
    // workflow complet (agent visé, puis Team Leader).
    // ═══════════════════════════════════════════════════════════════════

    var SWAP_STATUS_LABELS = { PENDING: "En attente", ACCEPTED: "Accepté", REJECTED: "Refusé", APPROVED: "Validé", NOT_SUBMITTED: "—" };

    function swapStatusPill(status) {
        var cls = status === "APPROVED" || status === "ACCEPTED" ? "status-approved"
            : status === "REJECTED" ? "status-rejected" : "status-pending";
        return '<span class="status-pill ' + cls + '">' + (SWAP_STATUS_LABELS[status] || status) + '</span>';
    }

    function swapRowHtml(s) {
        var title = escapeHtml(s.requesterName || s.requesterUsername) + " (" + s.requesterDate + (s.requesterShiftCode ? ", " + s.requesterShiftCode : "") + ")"
            + ' <i class="bi bi-arrow-left-right mx-1"></i> '
            + escapeHtml(s.targetName || s.targetUsername) + " (" + s.targetDate + (s.targetShiftCode ? ", " + s.targetShiftCode : "") + ")";
        var sub = "Pair : " + swapStatusPill(s.peerStatus) + " &nbsp; Team Leader : " + swapStatusPill(s.teamLeaderStatus);
        if (s.teamLeaderComment) sub += '<br><span class="text-muted">Motif : ' + escapeHtml(s.teamLeaderComment) + "</span>";
        return { title: title, sub: sub };
    }

    function renderSwapList(container, swaps, emptyText) {
        if (!swaps.length) { container.innerHTML = '<p class="text-muted small mb-0">' + emptyText + '</p>'; return; }
        container.innerHTML = swaps.map(function (s) {
            var r = swapRowHtml(s);
            return '<div class="border-bottom py-2 small"><div>' + r.title + '</div><div class="mt-1">' + r.sub + '</div></div>';
        }).join("");
    }

    function loadSwapMine() {
        getJson("/api/shift-swaps/mine").then(function (swaps) {
            renderSwapList($("swapMineList"), swaps, "Aucune permutation pour le moment.");
        }).catch(function () {});
    }

    var swapPeerPendingCount = 0;
    var swapTlPendingCount = 0;

    function refreshSwapBadgeFromCounts() {
        updateSwapBadge(swapPeerPendingCount + swapTlPendingCount);
    }

    function loadSwapPeer() {
        getJson("/api/shift-swaps/pending-for-me").then(function (swaps) {
            $("swapPeerCard").style.display = swaps.length ? "" : "none";
            swapPeerPendingCount = swaps.length;
            refreshSwapBadgeFromCounts();
            if (!swaps.length) { $("swapPeerList").innerHTML = ""; return; }
            $("swapPeerList").innerHTML = swaps.map(function (s) {
                var r = swapRowHtml(s);
                return '<div class="border-bottom py-2 small"><div>' + r.title + '</div>' +
                    (s.requesterMessage ? '<div class="text-muted mt-1">« ' + escapeHtml(s.requesterMessage) + ' »</div>' : '') +
                    '<div class="mt-2 d-flex gap-2">' +
                    '<button class="btn btn-sm btn-success swap-peer-accept" data-id="' + s.swapRequestId + '">Accepter</button>' +
                    '<button class="btn btn-sm btn-outline-danger swap-peer-reject" data-id="' + s.swapRequestId + '">Refuser</button>' +
                    '</div></div>';
            }).join("");
            Array.prototype.forEach.call($("swapPeerList").querySelectorAll(".swap-peer-accept"), function (btn) {
                btn.addEventListener("click", function () { decideSwapPeer(btn.getAttribute("data-id"), true); });
            });
            Array.prototype.forEach.call($("swapPeerList").querySelectorAll(".swap-peer-reject"), function (btn) {
                btn.addEventListener("click", function () { decideSwapPeer(btn.getAttribute("data-id"), false); });
            });
        }).catch(function () {});
    }

    function decideSwapPeer(id, approve) {
        sendJson("/api/shift-swaps/" + id + "/peer-decide", "POST", { approve: approve, comment: null })
            .then(function () { loadSwapPeer(); loadSwapMine(); })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    function loadSwapTeamLeader() {
        if (currentProfile !== "TEAM_LEADER") { $("swapTeamLeaderCard").style.display = "none"; return; }
        getJson("/api/shift-swaps/pending-for-team-leader").then(function (swaps) {
            swapTlPendingCount = swaps.length;
            $("swapTeamLeaderCard").style.display = swaps.length ? "" : "none";
            refreshSwapBadgeFromCounts();
            if (!swaps.length) { $("swapTeamLeaderList").innerHTML = ""; return; }
            $("swapTeamLeaderList").innerHTML = swaps.map(function (s) {
                var r = swapRowHtml(s);
                return '<div class="border-bottom py-2 small" data-swap-row="' + s.swapRequestId + '"><div>' + r.title + '</div>' +
                    (s.requesterMessage ? '<div class="text-muted mt-1">« ' + escapeHtml(s.requesterMessage) + ' »</div>' : '') +
                    '<div class="mt-2 d-flex gap-2 align-items-start">' +
                    '<button class="btn btn-sm btn-success swap-tl-approve" data-id="' + s.swapRequestId + '">Valider l\'échange</button>' +
                    '<button class="btn btn-sm btn-outline-danger swap-tl-reject-toggle" data-id="' + s.swapRequestId + '">Refuser</button>' +
                    '</div>' +
                    '<div class="mt-2" style="display:none;" id="swapTlRejectForm_' + s.swapRequestId + '">' +
                    '<textarea class="form-control form-control-sm" placeholder="Motif du refus" id="swapTlReason_' + s.swapRequestId + '"></textarea>' +
                    '<button class="btn btn-sm btn-danger mt-1 swap-tl-reject-confirm" data-id="' + s.swapRequestId + '">Confirmer le refus</button>' +
                    '</div></div>';
            }).join("");
            Array.prototype.forEach.call($("swapTeamLeaderList").querySelectorAll(".swap-tl-approve"), function (btn) {
                btn.addEventListener("click", function () { decideSwapTeamLeader(btn.getAttribute("data-id"), true, null); });
            });
            Array.prototype.forEach.call($("swapTeamLeaderList").querySelectorAll(".swap-tl-reject-toggle"), function (btn) {
                btn.addEventListener("click", function () {
                    var form = $("swapTlRejectForm_" + btn.getAttribute("data-id"));
                    form.style.display = form.style.display === "none" ? "" : "none";
                });
            });
            Array.prototype.forEach.call($("swapTeamLeaderList").querySelectorAll(".swap-tl-reject-confirm"), function (btn) {
                btn.addEventListener("click", function () {
                    var id = btn.getAttribute("data-id");
                    var reason = $("swapTlReason_" + id).value.trim();
                    if (!reason) { alert("Le motif est requis."); return; }
                    decideSwapTeamLeader(id, false, reason);
                });
            });
        }).catch(function () {});
    }

    function decideSwapTeamLeader(id, approve, comment) {
        sendJson("/api/shift-swaps/" + id + "/team-leader-decide", "POST", { approve: approve, comment: comment })
            .then(function () { loadSwapTeamLeader(); loadSwapMine(); })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    function updateSwapBadge(count) {
        var badge = $("swapBadge");
        badge.style.display = count > 0 ? "" : "none";
        badge.textContent = count;
    }

    var currentUsername = null;

    function loadSwapColleagues() {
        getJson("/api/users/directory").then(function (users) {
            $("swapTargetUser").innerHTML = '<option value="">— Choisir un collègue —</option>' +
                users.filter(function (u) { return (u.role === "AGENT" || !u.role) && u.username !== currentUsername; })
                    .map(function (u) { return '<option value="' + escapeHtml(u.username) + '">' + escapeHtml(u.fullName || u.username) + '</option>'; })
                    .join("");
        }).catch(function () {});
    }

    function wireShiftSwap() {
        $("shiftSwapBtn").addEventListener("click", function () {
            var section = $("shiftSwapSection");
            var showing = section.style.display !== "none";
            section.style.display = showing ? "none" : "";
            this.classList.toggle("btn-primary", !showing);
            this.classList.toggle("btn-outline-primary", showing);
            if (!showing) {
                loadSwapColleagues();
                loadSwapMine();
                loadSwapPeer();
                loadSwapTeamLeader();
            }
        });

        $("swapSubmitBtn").addEventListener("click", function () {
            var resultBox = $("swapSubmitResult");
            var payload = {
                requesterDate: $("swapRequesterDate").value,
                targetUsername: $("swapTargetUser").value,
                targetDate: $("swapTargetDate").value,
                message: $("swapMessage").value.trim() || null
            };
            if (!payload.requesterDate || !payload.targetUsername || !payload.targetDate) {
                resultBox.className = "small mt-2 text-danger";
                resultBox.textContent = "Remplissez les deux dates et choisissez un collègue.";
                return;
            }
            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Envoi en cours…";
            sendJson("/api/shift-swaps", "POST", payload)
                .then(function () {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = "Demande envoyée — en attente de la réponse de votre collègue.";
                    $("swapMessage").value = "";
                    loadSwapMine();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Congés & absences — soumis par l'agent, validés uniquement par SON Team
    // Leader (voir WorkflowService.decide : la demande est assignée nommément
    // au Team Leader résolu à la soumission, pas à "l'équipe Team Leader" en
    // général). Stockées comme WorkflowRequest(type=LEAVE, periodType=DAY) ;
    // periodFrom/periodTo pilotent déjà l'affichage congé du planning
    // (ShiftService.approvedLeaveDates / usersOnApprovedLeave).
    // ═══════════════════════════════════════════════════════════════════

    var LEAVE_STATUS_LABELS = { PENDING: "En attente", APPROVED: "Approuvé", REJECTED: "Refusé" };

    function leaveStatusPill(status) {
        var cls = status === "APPROVED" ? "status-approved" : status === "REJECTED" ? "status-rejected" : "status-pending";
        return '<span class="status-pill ' + cls + '">' + (LEAVE_STATUS_LABELS[status] || status) + '</span>';
    }

    function leavePeriodLabel(r) {
        return r.periodFrom === r.periodTo ? r.periodFrom : (r.periodFrom + " → " + r.periodTo);
    }

    function loadLeaveMine() {
        getJson("/api/workflow/requests/mine").then(function (all) {
            var leaves = (all || []).filter(function (r) { return r.type === "LEAVE"; });
            var container = $("leaveMineList");
            if (!leaves.length) { container.innerHTML = '<p class="text-muted small mb-0">Aucune demande de congé pour le moment.</p>'; return; }
            container.innerHTML = leaves.map(function (r) {
                var sub = leaveStatusPill(r.status);
                if (r.status === "REJECTED" && r.decisionComment) sub += '<br><span class="text-muted">Motif : ' + escapeHtml(r.decisionComment) + "</span>";
                return '<div class="border-bottom py-2 small"><div>' + escapeHtml(r.title) + " — " + escapeHtml(leavePeriodLabel(r)) + '</div><div class="mt-1">' + sub + '</div></div>';
            }).join("");
        }).catch(function () {});
    }

    function loadLeaveTeamLeader() {
        if (currentProfile !== "TEAM_LEADER") { $("leaveTeamLeaderCard").style.display = "none"; return; }
        getJson("/api/workflow/requests/hr/leave").then(function (all) {
            var pending = (all || []).filter(function (r) { return r.status === "PENDING"; });
            leaveTlPendingCount = pending.length;
            updateLeaveBadge();
            $("leaveTeamLeaderCard").style.display = pending.length ? "" : "none";
            if (!pending.length) { $("leaveTeamLeaderList").innerHTML = ""; return; }
            $("leaveTeamLeaderList").innerHTML = pending.map(function (r) {
                return '<div class="border-bottom py-2 small" data-leave-row="' + r.requestId + '">' +
                    '<div>' + escapeHtml(r.requestedByName || r.requestedByUsername) + " — " + escapeHtml(r.title) + " (" + escapeHtml(leavePeriodLabel(r)) + ')</div>' +
                    (r.details ? '<div class="text-muted mt-1">« ' + escapeHtml(r.details) + ' »</div>' : '') +
                    '<div class="mt-2 d-flex gap-2 align-items-start">' +
                    '<button class="btn btn-sm btn-success leave-tl-approve" data-id="' + r.requestId + '">Valider</button>' +
                    '<button class="btn btn-sm btn-outline-danger leave-tl-reject-toggle" data-id="' + r.requestId + '">Refuser</button>' +
                    '</div>' +
                    '<div class="mt-2" style="display:none;" id="leaveTlRejectForm_' + r.requestId + '">' +
                    '<textarea class="form-control form-control-sm" placeholder="Motif du refus" id="leaveTlReason_' + r.requestId + '"></textarea>' +
                    '<button class="btn btn-sm btn-danger mt-1 leave-tl-reject-confirm" data-id="' + r.requestId + '">Confirmer le refus</button>' +
                    '</div></div>';
            }).join("");
            Array.prototype.forEach.call($("leaveTeamLeaderList").querySelectorAll(".leave-tl-approve"), function (btn) {
                btn.addEventListener("click", function () { decideLeave(btn.getAttribute("data-id"), true, null); });
            });
            Array.prototype.forEach.call($("leaveTeamLeaderList").querySelectorAll(".leave-tl-reject-toggle"), function (btn) {
                btn.addEventListener("click", function () {
                    var form = $("leaveTlRejectForm_" + btn.getAttribute("data-id"));
                    form.style.display = form.style.display === "none" ? "" : "none";
                });
            });
            Array.prototype.forEach.call($("leaveTeamLeaderList").querySelectorAll(".leave-tl-reject-confirm"), function (btn) {
                btn.addEventListener("click", function () {
                    var id = btn.getAttribute("data-id");
                    var reason = $("leaveTlReason_" + id).value.trim();
                    if (!reason) { alert("Le motif est requis."); return; }
                    decideLeave(id, false, reason);
                });
            });
        }).catch(function () {});
    }

    function decideLeave(id, approve, comment) {
        var url = "/api/workflow/requests/" + id + "/" + (approve ? "approve" : "reject");
        sendJson(url, "POST", { comment: comment })
            .then(function () { loadLeaveTeamLeader(); loadLeaveMine(); })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    var leaveTlPendingCount = 0;

    function updateLeaveBadge() {
        var badge = $("leaveBadge");
        badge.style.display = leaveTlPendingCount > 0 ? "" : "none";
        badge.textContent = leaveTlPendingCount;
    }

    // ---- Solde de congés (repris de l'ancien onglet Workflow > Solde de congés) ----

    function currentYear() { return new Date().getFullYear(); }

    function loadMyLeaveBalance() {
        $("wfLeaveYearLabel").textContent = currentYear();
        getJson("/api/leave-balance/me?year=" + currentYear()).then(function (b) {
            var pct = b.allocatedDays > 0 ? Math.round((b.usedDays / b.allocatedDays) * 100) : 0;
            var barColor = pct >= 90 ? "bg-danger" : (pct >= 70 ? "bg-warning" : "bg-success");
            $("wfMyLeaveBalance").innerHTML =
                '<div class="fs-3 fw-bold mb-1">' + b.remainingDays + ' <span class="fs-6 text-muted fw-normal">jours restants</span></div>' +
                '<div class="progress mb-2" style="height:8px;"><div class="progress-bar ' + barColor + '" style="width:' + Math.min(100, pct) + '%"></div></div>' +
                '<div class="text-muted small">' + b.usedDays + ' utilisés sur ' + b.allocatedDays + ' alloués (' + b.year + ')</div>';
        }).catch(function (e) {
            $("wfMyLeaveBalance").innerHTML = '<p class="text-danger mb-0">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    /** Soldes de l'équipe — visible seulement pour un Team Leader (le backend filtre déjà sur
     *  SA propre équipe via User.ledTeam, voir LeaveBalanceController.requireReviewer). Lecture
     *  seule ici : modifier les jours alloués reste réservé à RH/QA/Admin, pas ce Team Leader. */
    function loadLeaveTeamBalances() {
        if (currentProfile !== "TEAM_LEADER") { $("leaveTeamBalanceCard").style.display = "none"; return; }
        $("leaveTeamBalanceCard").style.display = "";
        getJson("/api/leave-balance?year=" + currentYear()).then(function (balances) {
            var body = $("leaveTeamBalanceBody");
            if (!balances || !balances.length) {
                body.innerHTML = '<tr><td colspan="4" class="text-center text-muted">Aucun agent dans l\'équipe.</td></tr>';
                return;
            }
            body.innerHTML = balances
                .sort(function (a, b) { return (a.name || a.username).localeCompare(b.name || b.username, "fr"); })
                .map(function (b) {
                    var lowBadge = b.remainingDays <= 2 ? ' <span class="badge bg-danger">Faible</span>' : "";
                    return "<tr><td>" + escapeHtml(b.name || b.username) + "</td><td>" + b.allocatedDays +
                        "</td><td>" + b.usedDays + "</td><td>" + b.remainingDays + lowBadge + "</td></tr>";
                }).join("");
        }).catch(function (e) {
            $("leaveTeamBalanceBody").innerHTML = '<tr><td colspan="4" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function wireLeaveSection() {
        $("leaveToggleBtn").addEventListener("click", function () {
            var section = $("leaveSection");
            var showing = section.style.display !== "none";
            section.style.display = showing ? "none" : "";
            this.classList.toggle("btn-primary", !showing);
            this.classList.toggle("btn-outline-primary", showing);
            if (!showing) {
                loadMyLeaveBalance();
                loadLeaveMine();
                loadLeaveTeamLeader();
                loadLeaveTeamBalances();
            }
        });

        $("leaveSubmitBtn").addEventListener("click", function () {
            var resultBox = $("leaveSubmitResult");
            var from = $("leaveFromDate").value;
            var to = $("leaveToDate").value || from;
            var title = $("leaveTitle").value.trim();
            if (!from || !title) {
                resultBox.className = "small mt-2 text-danger";
                resultBox.textContent = "Indiquez au moins une date de début et un motif.";
                return;
            }
            if (to < from) {
                resultBox.className = "small mt-2 text-danger";
                resultBox.textContent = "La date de fin ne peut pas précéder la date de début.";
                return;
            }
            var payload = {
                type: "LEAVE",
                title: title,
                details: $("leaveDetails").value.trim() || null,
                periodType: "DAY",
                periodFrom: from,
                periodTo: to
            };
            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Envoi en cours…";
            sendJson("/api/workflow/requests", "POST", payload)
                .then(function () {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = "Demande envoyée — en attente de la validation de votre Team Leader.";
                    $("leaveTitle").value = "";
                    $("leaveDetails").value = "";
                    loadLeaveMine();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    var currentProfile = null;
    var scheduleImportToast = null;

    /** Panneau d'import visible seulement pour QA/RH/Superviseur/Admin, et seulement en mode
     *  Planning + vue Équipe (une équipe précise doit être sélectionnée). */
    function updateScheduleImportVisibility() {
        var panel = $("scheduleImportPanel");
        var broadAccess = ["ADMIN", "RH", "SUPERVISOR", "QA", "QA_SUPERVISOR", "EXCELLIAM"].indexOf(currentProfile) !== -1;
        var show = currentMode === "planning" && currentWho === "team" && broadAccess;
        panel.style.display = show ? "" : "none";
        if (show) {
            var teamCode = $("teamSelect").value;
            var teamLabel = teamCode ? $("teamSelect").options[$("teamSelect").selectedIndex].textContent : "toutes les équipes";
            $("scheduleImportTeamLabel").textContent = teamLabel;
        }
    }

    function wireScheduleImport() {
        getJson("/api/kb/countries").then(function (countries) {
            $("scheduleImportCountry").innerHTML = '<option value="">— Filiale —</option>' +
                countries.map(function (c) { return '<option value="' + c.countryCode + '">' + escapeHtml(c.label) + '</option>'; }).join("");
        }).catch(function () {});
        getJson("/api/procedures/services").then(function (services) {
            $("scheduleImportService").innerHTML = '<option value="">— Service —</option>' +
                services.map(function (s) { return '<option value="' + s.code + '">' + escapeHtml(s.name) + '</option>'; }).join("");
        }).catch(function () {});
        $("scheduleImportMonth").value = todayIso().slice(0, 7);

        $("scheduleImportToggleBtn").addEventListener("click", function () {
            var form = $("scheduleImportForm");
            form.style.display = form.style.display === "none" ? "" : "none";
        });

        $("scheduleImportBtn").addEventListener("click", function () {
            var fileInput = $("scheduleImportFile");
            var resultBox = $("scheduleImportResult");
            var file = fileInput.files[0];
            if (!file) { resultBox.textContent = "Choisissez un fichier."; resultBox.className = "small mt-2 text-danger"; return; }

            var formData = new FormData();
            formData.append("file", file);
            resultBox.textContent = "Import en cours…";
            resultBox.className = "small mt-2 text-muted";

            var teamCode = $("teamSelect").value;
            fetch("/api/schedule/import" +
                "?serviceCode=" + encodeURIComponent($("scheduleImportService").value) +
                "&countryCode=" + encodeURIComponent($("scheduleImportCountry").value) +
                "&team=" + encodeURIComponent(teamCode) +
                "&month=" + encodeURIComponent($("scheduleImportMonth").value),
                { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (result) {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = result.entriesCreated + " case(s) de planning importée(s) sur " + result.rowsProcessed + " agent(s)." +
                        (result.usersAutoCreated ? " " + result.usersAutoCreated + " compte(s) agent créé(s) automatiquement." : "");
                    if (result.aiReview) {
                        var reviewBox = document.createElement("div");
                        reviewBox.className = "small mt-2 alert alert-warning";
                        reviewBox.textContent = "À vérifier : " + result.aiReview;
                        resultBox.after(reviewBox);
                    }
                    fileInput.value = "";

                    // Confirmation visible + rafraîchissement immédiat de la vue Planning (et du
                    // Suivi de shift si on y revient) — jamais besoin de recharger la page pour
                    // voir apparaître ce qu'on vient de charger.
                    $("scheduleImportToastBody").innerHTML = '<i class="bi bi-check-circle-fill"></i> Planning importé — '
                        + result.entriesCreated + " case(s) sur " + result.rowsProcessed + " agent(s).";
                    scheduleImportToast.show();
                    if (currentMode === "planning") loadAll();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    function wireTeamLeaderApproval() {
        $("tlApproveBtn").addEventListener("click", function () {
            submitTeamPlanningDecision(true, null);
        });

        $("tlRejectToggleBtn").addEventListener("click", function () {
            var form = $("tlRejectForm");
            form.style.display = form.style.display === "none" ? "" : "none";
        });

        $("tlRejectConfirmBtn").addEventListener("click", function () {
            var reason = $("tlRejectReason").value.trim();
            if (!reason) { $("tlApprovalResult").className = "small mt-2 text-danger"; $("tlApprovalResult").textContent = "Le motif est requis."; return; }
            submitTeamPlanningDecision(false, reason);
        });
    }

    function submitTeamPlanningDecision(approve, reason) {
        if (!currentPlanningRange) return;
        var resultBox = $("tlApprovalResult");
        resultBox.className = "small mt-2 text-muted";
        resultBox.textContent = approve ? "Validation en cours…" : "Refus en cours…";

        sendJson("/api/schedule/team/decide", "POST", {
            from: currentPlanningRange.from, to: currentPlanningRange.to, approve: approve, reason: reason
        }).then(function (result) {
            resultBox.className = "small mt-2 text-success";
            resultBox.textContent = (approve ? "Planning validé — " : "Planning refusé — ") + result.entriesDecided + " entrée(s) mise(s) à jour.";
            loadPlanning();
        }).catch(function (e) {
            resultBox.className = "small mt-2 text-danger";
            resultBox.textContent = "Erreur : " + e.message;
        });
    }
// Profils qui ne pointent pas comme un agent : ils gèrent des équipes, pas leur propre
// shift. Pour eux, le bouton "Moi-même" est retiré et la vue "Équipe" est forcée.
    var TEAM_ONLY_PROFILES = ["TEAM_LEADER", "ADMIN", "EXCELLIAM", "RH", "QA_SUPERVISOR", "SUPERVISOR"];

    window.RccSession.init().then(function (session) {
        currentProfile = session ? session.profile : null;
        currentUsername = session && session.user ? session.user.username : null;

        var saved = loadShiftState();
        var isTeamLeader = currentProfile === "TEAM_LEADER";

        if (TEAM_ONLY_PROFILES.indexOf(currentProfile) !== -1) {
            $("whoMeBtn").style.display = "none";
            saved.who = "team";
        }

        // Applique l'état restauré (ou par défaut) sans passer par setWho/setWhen/setMode —
        // chacune de ces fonctions déclenche son propre loadAll(), ce qui produirait 3 appels
        // réseau redondants au chargement pour un seul état cible.
        currentWho = saved.who;
        currentWhen = saved.when;
        currentMode = saved.mode;
        $("whoMeBtn").className = "btn btn-sm " + (currentWho === "me" ? "btn-primary" : "btn-outline-primary");
        $("whoTeamBtn").className = "btn btn-sm " + (currentWho === "team" ? "btn-primary" : "btn-outline-primary");
        $("teamSelect").style.display = currentWho === "team" ? "" : "none";
        ["Day", "Week", "Month", "Year"].forEach(function (w) {
            $("when" + w + "Btn").className = "btn btn-sm " + (w.toLowerCase() === currentWhen ? "btn-secondary" : "btn-outline-secondary");
        });
        $("modeRealBtn").className = "btn btn-sm " + (currentMode === "real" ? "btn-secondary" : "btn-outline-secondary");
        $("modePlanningBtn").className = "btn btn-sm " + (currentMode === "planning" ? "btn-secondary" : "btn-outline-secondary");
        $("shiftLiveBtn").style.display = currentMode === "planning" ? "none" : "";
        updatePeriodLabel();
        updateExportVisibility();
        updateScheduleImportVisibility();

        if (isTeamLeader) {
            // Un Team Leader voit directement SA propre équipe, sans sélecteur — seuls RH,
            // Excelliam, Superviseur, Admin et QA gardent le choix (voir demande utilisateur).
            // L'équipe éventuellement mémorisée en localStorage est ignorée pour ce profil :
            // elle n'a plus de sens à choisir, la sienne est fixe.
            getJson("/api/shift/me/led-team").then(function (r) {
                $("teamSelect").innerHTML = r.ledTeam
                    ? '<option value="' + escapeHtml(r.ledTeam) + '">' + escapeHtml(r.ledTeam) + '</option>'
                    : '<option value="">— Mon équipe —</option>';
                $("teamSelect").value = r.ledTeam || "";
                $("teamSelect").disabled = true;
                loadAll();
            }).catch(function () { loadAll(); });
        } else {
            loadTeams().then(function () {
                // teamSelect n'a d'options qu'une fois loadTeams() résolu — restaurer avant
                // écrirait sur un <select> encore vide (voir bug initial : le choix d'équipe ne
                // survivait jamais à un rechargement).
                if (saved.team) $("teamSelect").value = saved.team;
                loadAll();
            });
        }

        loadSwapPeer();
        loadSwapTeamLeader();
        loadLeaveTeamLeader();

        // Ouverture directe d'une section depuis un lien externe (voir le portail Team Leader,
        // dont les boutons Congés/Permutations/En direct renvoient ici avec ?open=... plutôt que
        // de dupliquer cette page — un seul code à maintenir). Simule le clic déjà câblé plus
        // bas, donc rien de nouveau à tester : c'est exactement le même chemin qu'un clic
        // manuel. Déclenché seulement une fois la session résolue (currentProfile/badges prêts).
        var openParam = new URLSearchParams(window.location.search).get("open");
        if (openParam === "leave") $("leaveToggleBtn").click();
        else if (openParam === "swap") $("shiftSwapBtn").click();
        else if (openParam === "live") $("shiftLiveBtn").click();
    });
    wireScheduleImport();
    wireTeamLeaderApproval();
    wireShiftSwap();
    wireLeaveSection();
    scheduleImportToast = new bootstrap.Toast($("scheduleImportToast"));
    shiftDayDetailModal = new bootstrap.Modal($("shiftDayDetailModal"));
})();
