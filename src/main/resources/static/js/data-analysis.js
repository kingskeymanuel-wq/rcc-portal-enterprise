"use strict";

(function () {
    var getJson = RccApi.getJson;
    var escapeHtml = RccApi.escapeHtml;

    var TEAM_COLORS = ["#0057B8", "#00A651", "#F5A623", "#7B2FF7", "#F72585", "#17a2b8", "#dc3545", "#6c757d"];
    var lastBreakdown = [];
    var lastGlobal = { avgPresenceRate: null, avgQualityScore: null, avgPerformanceGlobale: null };
    var lastPeriodQuery = "month=" + currentMonthValue(); // reconstruit à chaque generate() — réutilisé pour agents/timeline
    var daTeamDetailModal = null;

    // ===== Photos d'agents (Mon profil) — initiales colorées tant qu'aucune photo n'est connue =====
    var photoCache = {};      // userId → url ("" = pas de photo)
    var AVATAR_COLORS = ["#0057B8", "#00A651", "#7B2FF7", "#F5A623", "#F72585", "#17a2b8", "#dc3545", "#495057"];

    function initialsOf(name) {
        return String(name || "?").split(/\s+/).filter(Boolean).slice(0, 2).map(function (w) { return w[0]; }).join("").toUpperCase() || "?";
    }

    function avatarHtml(userId, name, size) {
        size = size || 40;
        var url = userId != null ? photoCache[userId] : null;
        var h = 0, str = String(name || userId || "");
        for (var i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) >>> 0;
        var inner = url ? '<img src="' + escapeHtml(url) + '" alt="">' : escapeHtml(initialsOf(name));
        return '<span class="da-avatar" data-avatar-uid="' + (userId != null ? userId : "") + '" data-avatar-name="' + escapeHtml(name || "") + '" ' +
            'style="width:' + size + 'px;height:' + size + 'px;font-size:' + Math.round(size * 0.36) + 'px;background:' + AVATAR_COLORS[h % AVATAR_COLORS.length] + ';">' + inner + '</span>';
    }

    /** Charge les photos manquantes puis remplace les initiales déjà affichées. */
    function loadPhotos(userIds) {
        var missing = (userIds || []).filter(function (id) { return id != null && photoCache[id] === undefined; });
        if (!missing.length) return Promise.resolve();
        missing.forEach(function (id) { photoCache[id] = ""; });
        return getJson("/api/data-analysis/photos?ids=" + missing.join(",")).then(function (map) {
            Object.keys(map || {}).forEach(function (id) { photoCache[id] = map[id]; });
            Array.prototype.forEach.call(document.querySelectorAll("[data-avatar-uid]"), function (el) {
                var url = photoCache[el.getAttribute("data-avatar-uid")];
                if (url && !el.querySelector("img")) el.innerHTML = '<img src="' + escapeHtml(url) + '" alt="">';
            });
        }).catch(function () {});
    }

    function teamColor(teamRow) {
        var idx = lastBreakdown.indexOf(teamRow);
        return TEAM_COLORS[(idx >= 0 ? idx : 0) % TEAM_COLORS.length];
    }

    /** Couleur selon seuils métier : vert (bon), orange (à surveiller), rouge (sous le seuil). */
    function levelColor(value, good, warn) {
        if (value == null) return "#cfd6e2";
        return value >= good ? "#00A651" : value >= warn ? "#F5A623" : "#dc3545";
    }

    function miniGauge(value, label, good, warn) {
        var pct = value != null ? Math.max(0, Math.min(100, value)) : 0;
        var color = levelColor(value, good, warn);
        return '<div class="da-mini-gauge"><div class="ring" style="background:conic-gradient(' + color + ' ' + (pct * 3.6) + 'deg,#eef0f4 0)"><span>' +
            (value != null ? Math.round(value) + "%" : "—") + '</span></div>' + label + '</div>';
    }

    /** Cercle chromatique (donut CSS conic-gradient) — composition par équipe, sans librairie externe. */
    function renderGlobalDonut(breakdown) {
        var container = document.getElementById("daGlobalDonutRow");
        if (!breakdown || !breakdown.length) { container.innerHTML = '<p class="text-muted mb-0">Aucune donnée pour cette période.</p>'; return; }
        var total = breakdown.reduce(function (sum, s) { return sum + s.agentCount; }, 0);
        var gradientParts = [];
        var cursor = 0;
        breakdown.forEach(function (s, idx) {
            var start = (cursor / total) * 360;
            cursor += s.agentCount;
            var end = (cursor / total) * 360;
            gradientParts.push(TEAM_COLORS[idx % TEAM_COLORS.length] + " " + start + "deg " + end + "deg");
        });
        var donut = '<div class="da-gauge" style="width:130px;height:130px;background:conic-gradient(' + gradientParts.join(",") + ');">' +
            '<div class="da-gauge-hole" style="width:86px;height:86px;"><span class="val" style="font-size:1.4rem;">' + total + '</span><span class="lbl">agents</span></div></div>';
        var legend = breakdown.map(function (s, idx) {
            var pct = total ? Math.round(s.agentCount * 100 / total) : 0;
            return '<button type="button" class="da-legend-pill" data-legend-idx="' + idx + '"><i style="background:' + TEAM_COLORS[idx % TEAM_COLORS.length] + ';"></i>' +
                escapeHtml(s.serviceName) + ' <b>' + s.agentCount + '</b> <span class="text-muted">(' + pct + ' %)</span></button>';
        }).join("");
        container.innerHTML = donut + '<div>' + legend + '</div>';
        Array.prototype.forEach.call(container.querySelectorAll("[data-legend-idx]"), function (b) {
            b.addEventListener("click", function () { openTeamDetail(breakdown[Number(b.getAttribute("data-legend-idx"))]); });
        });
    }

    /** Cartes « équipe » : jauges colorées selon les seuils, chiffres clés, photos des agents. */
    function renderTeamCards(breakdown) {
        var grid = document.getElementById("daTeamCards");
        if (!breakdown || !breakdown.length) { grid.innerHTML = '<p class="text-muted">Aucune donnée pour cette période.</p>'; return; }
        grid.innerHTML = breakdown.map(function (s, idx) {
            var color = TEAM_COLORS[idx % TEAM_COLORS.length];
            return '<div class="da-team-card" data-team-idx="' + idx + '" style="animation-delay:' + (idx * 60) + 'ms">' +
                '<div class="da-team-band" style="background:linear-gradient(120deg,' + color + ',' + color + 'cc);"><h6>' + escapeHtml(s.serviceName) + '</h6><span>' + s.agentCount + ' agent(s)</span></div>' +
                '<div class="da-team-gauges">' + miniGauge(s.avgPresenceRate, "Présence", 90, 85) + miniGauge(s.avgQualityScore, "Qualité", 80, 70) + miniGauge(s.avgPerformanceGlobale, "Performance", 70, 60) + '</div>' +
                '<div class="da-team-stats">' +
                    '<span><i class="bi bi-telephone"></i> Interactions moy. <b>' + fmtNum(s.avgInteractions) + '</b></span>' +
                    '<span><i class="bi bi-calendar-x"></i> Absences <b>' + (s.totalAbsenceDays || 0) + ' j</b></span>' +
                    '<span><i class="bi bi-cup-hot"></i> Pauses dépassées <b>' + (s.totalPauseOverruns || 0) + '</b></span>' +
                    '<span><i class="bi bi-bullseye"></i> Sous target <b>' + fmtPct(s.pctBelowTarget) + '</b></span>' +
                '</div>' +
                '<div class="da-team-foot"><span class="da-avatars" data-team-avatars="' + idx + '"></span><span>Voir le détail <i class="bi bi-arrow-right"></i></span></div>' +
            '</div>';
        }).join("");
        Array.prototype.forEach.call(grid.querySelectorAll(".da-team-card"), function (card) {
            card.addEventListener("click", function () { openTeamDetail(breakdown[Number(card.getAttribute("data-team-idx"))]); });
        });
        // Aperçu des agents (photos) par équipe — chargé en arrière-plan.
        breakdown.forEach(function (s, idx) {
            getJson("/api/data-analysis/agents?" + lastPeriodQuery + "&team=" + encodeURIComponent(s.teamCode || s.serviceName)).then(function (agents) {
                var box = grid.querySelector('[data-team-avatars="' + idx + '"]');
                if (!box) return;
                var watch = agents.filter(function (a) { return a.performanceGlobale != null && a.performanceGlobale < 70; }).length;
                box.innerHTML = agents.slice(0, 5).map(function (a) { return avatarHtml(a.userId, a.userFullName || a.username, 28); }).join("") +
                    (agents.length > 5 ? '<span class="da-avatar" style="width:28px;height:28px;font-size:10px;background:#e3e8f0;color:#4a5568;">+' + (agents.length - 5) + '</span>' : "") +
                    (watch ? '<span class="ms-2 small text-danger fw-bold">' + watch + ' à surveiller</span>' : "");
                loadPhotos(agents.slice(0, 5).map(function (a) { return a.userId; }));
            }).catch(function () {});
        });
    }

    /** Jauge radiale simple — un pourcentage rempli d'une couleur, le reste en gris clair. */
    function gaugeHtml(value, label, color) {
        var pct = value != null ? Math.max(0, Math.min(100, value)) : 0;
        var bg = value != null
            ? "conic-gradient(" + color + " " + (pct * 3.6) + "deg, #eef0f4 " + (pct * 3.6) + "deg 360deg)"
            : "#eef0f4";
        return '<div class="text-center">' +
            '<div class="da-gauge mx-auto" style="width:90px;height:90px;background:' + bg + ';">' +
                '<div class="da-gauge-hole" style="width:60px;height:60px;"><span class="val">' + (value != null ? Math.round(value) + "%" : "—") + '</span></div>' +
            '</div>' +
            '<div class="small text-muted mt-1">' + label + '</div>' +
        '</div>';
    }

    function barRowHtml(label, teamValue, globalValue, color) {
        var pct = teamValue != null ? Math.max(0, Math.min(100, teamValue)) : 0;
        return '<div class="da-bar-row">' +
            '<div class="da-bar-label">' + label + '</div>' +
            '<div class="da-bar-track"><div class="da-bar-fill" style="width:' + pct + '%;background:' + color + ';"></div></div>' +
            '<div class="da-bar-value">' + (teamValue != null ? Math.round(teamValue) + "%" : "—") + '</div>' +
            '<div class="small text-muted" style="width:110px;">RCC : ' + (globalValue != null ? Math.round(globalValue) + "%" : "—") + '</div>' +
        '</div>';
    }

    var teamAgentsCache = [];
    var agentFilter = "all";

    function agentClass(a) {
        if (a.performanceGlobale == null) return "na";
        return a.performanceGlobale >= 70 ? "good" : "bad";
    }

    function metricRow(label, value, good, warn) {
        var pct = value != null ? Math.max(0, Math.min(100, value)) : 0;
        return '<div class="da-metric"><span>' + label + '</span><span class="da-metric-track"><i style="width:' + pct + '%;background:' + levelColor(value, good, warn) + ';"></i></span><b>' +
            (value != null ? Math.round(value) + "%" : "—") + '</b></div>';
    }

    function renderTeamAgents() {
        var grid = document.getElementById("daAgentsGrid");
        var list = teamAgentsCache.filter(function (a) { return agentFilter === "all" || agentClass(a) === agentFilter; });
        if (!teamAgentsCache.length) { grid.innerHTML = '<p class="text-muted small">Aucun agent avec données sur cette période.</p>'; return; }
        if (!list.length) { grid.innerHTML = '<p class="text-muted small">Aucun agent dans ce filtre.</p>'; return; }
        var BADGE = {
            good: '<span class="badge bg-success"><i class="bi bi-check-lg"></i> A marché</span>',
            bad: '<span class="badge bg-danger"><i class="bi bi-exclamation-lg"></i> À surveiller</span>',
            na: '<span class="badge bg-secondary">Données incomplètes</span>'
        };
        grid.innerHTML = list.map(function (a, i) {
            var cls = agentClass(a);
            return '<div class="da-agent-card ' + cls + '" data-agent-idx="' + teamAgentsCache.indexOf(a) + '" style="animation-delay:' + Math.min(i * 30, 300) + 'ms">' +
                '<div class="da-agent-head">' + avatarHtml(a.userId, a.userFullName || a.username, 44) +
                    '<div><b>' + escapeHtml(a.userFullName || a.username) + '</b><small>' + escapeHtml(a.username || "") + '</small></div></div>' +
                metricRow("Présence", a.presenceRate, 90, 85) +
                metricRow("Qualité", a.avgQualityScore, 80, 70) +
                metricRow("Performance", a.performanceGlobale, 70, 60) +
                '<div class="mt-2">' + BADGE[cls] + '</div>' +
            '</div>';
        }).join("");
        Array.prototype.forEach.call(grid.querySelectorAll(".da-agent-card"), function (card) {
            card.addEventListener("click", function () { openAgentDetail(teamAgentsCache[Number(card.getAttribute("data-agent-idx"))]); });
        });
        loadPhotos(list.map(function (a) { return a.userId; }));
    }

    function openTeamDetail(teamRow) {
        var color = teamColor(teamRow);
        document.getElementById("daTeamHero").style.background = "linear-gradient(120deg," + color + ",#0d1b3e)";
        document.getElementById("daTeamDetailTitle").innerHTML = '<i class="bi bi-diagram-3"></i> ' + escapeHtml(teamRow.serviceName);
        document.getElementById("daTeamHeroSub").textContent = teamRow.agentCount + " agent(s) · absences " + (teamRow.totalAbsenceDays || 0) +
            " j · pauses dépassées " + (teamRow.totalPauseOverruns || 0) + (teamRow.pctBelowTarget != null ? " · " + teamRow.pctBelowTarget + " % sous target" : "");

        document.getElementById("daTeamGauges").innerHTML =
            gaugeHtml(teamRow.avgPresenceRate, "Présence", levelColor(teamRow.avgPresenceRate, 90, 85)) +
            gaugeHtml(teamRow.avgQualityScore, "Score qualité", levelColor(teamRow.avgQualityScore, 80, 70)) +
            gaugeHtml(teamRow.avgPerformanceGlobale, "Performance", levelColor(teamRow.avgPerformanceGlobale, 70, 60));

        document.getElementById("daTeamBars").innerHTML =
            barRowHtml("Présence", teamRow.avgPresenceRate, lastGlobal.avgPresenceRate, "#0057B8") +
            barRowHtml("Score qualité", teamRow.avgQualityScore, lastGlobal.avgQualityScore, "#00A651") +
            barRowHtml("Performance", teamRow.avgPerformanceGlobale, lastGlobal.avgPerformanceGlobale, "#F5A623");

        var timelineEl = document.getElementById("daTeamTimeline");
        timelineEl.innerHTML = '<p class="text-muted small mb-0">Chargement…</p>';
        getJson("/api/data-analysis/timeline?months=6&team=" + encodeURIComponent(teamRow.teamCode || teamRow.serviceName)).then(function (snapshots) {
            if (!snapshots.length) { timelineEl.innerHTML = '<p class="text-muted small mb-0">Aucun historique — générez l\'analyse plusieurs mois de suite pour voir l\'évolution.</p>'; return; }
            timelineEl.innerHTML = snapshots.map(function (s) {
                var v = s.avgPerformanceGlobale != null ? Math.round(s.avgPerformanceGlobale) : 0;
                var monthLabel = s.periodMonth ? s.periodMonth.slice(5, 7) + "/" + s.periodMonth.slice(2, 4) : "—";
                return '<div class="da-timeline-col">' +
                    '<div class="da-timeline-value">' + (s.avgPerformanceGlobale != null ? v + "%" : "—") + '</div>' +
                    '<div class="da-timeline-bar" style="height:' + Math.max(4, v) + '%;background:linear-gradient(180deg,' + color + ',' + color + '88);"></div>' +
                    '<div class="da-timeline-label">' + monthLabel + '</div>' +
                '</div>';
            }).join("");
        }).catch(function () {
            timelineEl.innerHTML = '<p class="text-danger small mb-0">Erreur de chargement de la frise chronologique.</p>';
        });

        teamAgentsCache = [];
        agentFilter = "all";
        Array.prototype.forEach.call(document.querySelectorAll("#daAgentFilter button"), function (b) { b.classList.toggle("active", b.getAttribute("data-filter") === "all"); });
        document.getElementById("daAgentsGrid").innerHTML = '<p class="text-muted small">Chargement…</p>';
        getJson("/api/data-analysis/agents?" + lastPeriodQuery + "&team=" + encodeURIComponent(teamRow.teamCode || teamRow.serviceName))
            .then(function (agents) {
                // Les agents à surveiller d'abord, puis données incomplètes, puis ceux qui ont marché.
                var order = { bad: 0, na: 1, good: 2 };
                teamAgentsCache = agents.slice().sort(function (a, b) {
                    return order[agentClass(a)] - order[agentClass(b)] || (a.performanceGlobale || 0) - (b.performanceGlobale || 0);
                });
                renderTeamAgents();
            }).catch(function (e) {
                document.getElementById("daAgentsGrid").innerHTML = '<p class="text-danger small">Erreur : ' + escapeHtml(e.message) + '</p>';
            });

        daTeamDetailModal.show();
    }

    var currentGranularity = "MONTH";

    function toIsoDate(d) { return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }

    function isoWeekToRange(weekStr) {
        var parts = weekStr.split("-W");
        var year = Number(parts[0]);
        var week = Number(parts[1]);
        var simple = new Date(year, 0, 1 + (week - 1) * 7);
        var dayOfWeek = simple.getDay();
        var monday = new Date(simple);
        var diff = dayOfWeek <= 4 ? dayOfWeek - 1 : dayOfWeek - 8;
        monday.setDate(simple.getDate() - diff);
        var sunday = new Date(monday);
        sunday.setDate(monday.getDate() + 6);
        return [toIsoDate(monday), toIsoDate(sunday)];
    }

    /** Renvoie soit {month: "2026-08"} soit {from, to} selon la granularité choisie. */
    function computeSelectedPeriod() {
        if (currentGranularity === "DAY") {
            var day = document.getElementById("daDay").value || toIsoDate(new Date());
            return { from: day, to: day };
        }
        if (currentGranularity === "WEEK") {
            var weekValue = document.getElementById("daWeek").value;
            if (!weekValue) { var now = new Date(); weekValue = now.getFullYear() + "-W01"; }
            var range = isoWeekToRange(weekValue);
            return { from: range[0], to: range[1] };
        }
        return { month: document.getElementById("daMonth").value || currentMonthValue() };
    }

    function wireGranularity() {
        var todayIso = toIsoDate(new Date());
        document.getElementById("daDay").value = todayIso;
        Array.prototype.forEach.call(document.querySelectorAll("#daGranularity button"), function (btn) {
            btn.addEventListener("click", function () {
                currentGranularity = btn.getAttribute("data-gran");
                Array.prototype.forEach.call(document.querySelectorAll("#daGranularity button"), function (b) {
                    b.classList.toggle("btn-primary", b === btn);
                    b.classList.toggle("btn-outline-primary", b !== btn);
                });
                document.getElementById("daMonth").style.display = currentGranularity === "MONTH" ? "" : "none";
                document.getElementById("daWeek").style.display = currentGranularity === "WEEK" ? "" : "none";
                document.getElementById("daDay").style.display = currentGranularity === "DAY" ? "" : "none";
            });
        });
    }

    function fmtPct(v) { return v != null ? v + " %" : "—"; }
    var daAgentDetailModal = null;

    function openAgentDetail(agent) {
        document.getElementById("daAgentDetailTitle").innerHTML = avatarHtml(agent.userId, agent.userFullName || agent.username, 72) +
            '<div><h5 class="mb-1">' + escapeHtml(agent.userFullName || agent.username || "Agent") + '</h5>' +
            '<small>' + escapeHtml([agent.username, TEAM_LABELS_DA[agent.activity] || agent.activity, agent.affiliateBranch].filter(Boolean).join(" · ")) + '</small></div>';
        loadPhotos([agent.userId]);

        if (agent.presenceRate !== undefined) {
            var chips = "";
            if (agent.kpiMetrics) {
                chips = Object.keys(agent.kpiMetrics).sort().map(function (code) {
                    return '<span class="da-chip">' + escapeHtml(code.replace(/_/g, " ").toLowerCase()) + ' <b>' + agent.kpiMetrics[code] + '</b></span>';
                }).join("");
            }
            document.getElementById("daAgentDetailBody").innerHTML =
                '<div class="da-kpi-tiles">' +
                    '<div class="da-kpi-tile"><b style="color:' + levelColor(agent.presenceRate, 90, 85) + '">' + fmtPct(Math.round(agent.presenceRate)) + '</b><small>Présence</small></div>' +
                    '<div class="da-kpi-tile"><b style="color:' + levelColor(agent.avgQualityScore, 80, 70) + '">' + (agent.avgQualityScore != null ? fmtPct(Math.round(agent.avgQualityScore)) : "—") + '</b><small>Score qualité</small></div>' +
                    '<div class="da-kpi-tile"><b style="color:' + levelColor(agent.performanceGlobale, 70, 60) + '">' + (agent.performanceGlobale != null ? fmtPct(Math.round(agent.performanceGlobale)) : "—") + '</b><small>Performance globale</small></div>' +
                '</div>' +
                '<div class="small text-muted mb-2"><i class="bi bi-clipboard-check"></i> ' + (agent.evaluationCount || 0) + ' évaluation(s) QA</div>' +
                (chips ? '<h6 class="mb-2"><i class="bi bi-speedometer2"></i> Indicateurs importés (KPI)</h6><div>' + chips + '</div>' : "");
        } else {
            document.getElementById("daAgentDetailBody").innerHTML = "";
        }

        var historyEl = document.getElementById("daAgentAlertHistory");
        historyEl.innerHTML = '<p class="text-muted small">Chargement…</p>';
        if (agent.userId) {
            getJson("/api/data-analysis/alerts/agent/" + agent.userId).then(function (history) {
                if (!history.length) { historyEl.innerHTML = '<p class="text-success small mb-0"><i class="bi bi-check-circle"></i> Aucune alerte enregistrée — historique propre depuis le début du suivi.</p>'; return; }
                historyEl.innerHTML = '<ul class="da-history">' + history.map(function (h) {
                    return '<li class="' + (h.severity === "CRITIQUE" ? "crit" : "") + '"><b>' + escapeHtml(h.periodMonth) + '</b> · ' +
                        escapeHtml(ALERT_TYPE_LABELS[h.alertType] || h.alertType) + ' ' + alertSeverityBadge(h.severity) +
                        '<div class="text-muted">' + escapeHtml(h.message) + '</div></li>';
                }).join("") + '</ul>';
            }).catch(function () {
                historyEl.innerHTML = '<p class="text-danger small mb-0">Erreur de chargement de l\'historique.</p>';
            });
        } else {
            historyEl.innerHTML = '<p class="text-muted small mb-0">Identifiant agent indisponible.</p>';
        }

        daAgentDetailModal.show();
    }

    function fmtNum(v) { return v != null ? v : "—"; }

    function renderSummaryCards(result) {
        var cards = [
            { label: "Agents avec données", value: result.totalAgents, icon: "bi-people-fill", color: "#0057B8", bg: "#eaf2ff" },
            { label: "Présence moyenne", value: fmtPct(result.avgPresenceRate), icon: "bi-person-check-fill", raw: result.avgPresenceRate, good: 90, warn: 85, bg: "#e6f7ec" },
            { label: "Score qualité moyen", value: fmtPct(result.avgQualityScore), icon: "bi-award-fill", raw: result.avgQualityScore, good: 80, warn: 70, bg: "#f3eaff" },
            { label: "Performance moyenne", value: fmtPct(result.avgPerformanceGlobale), icon: "bi-graph-up-arrow", raw: result.avgPerformanceGlobale, good: 70, warn: 60, bg: "#fff4e0" }
        ];
        document.getElementById("daSummaryCards").innerHTML = cards.map(function (c, i) {
            var color = c.color || levelColor(c.raw, c.good, c.warn);
            var hint = c.good == null ? "" : c.raw == null ? '<span class="da-kpi-hint text-muted">pas encore de données</span>'
                : '<span class="da-kpi-hint" style="color:' + color + '">' + (c.raw >= c.good ? "dans l'objectif" : c.raw >= c.warn ? "à surveiller" : "sous le seuil (" + c.warn + " %)") + '</span>';
            return '<div class="col-md-3 col-6"><div class="da-kpi" style="animation-delay:' + (i * 60) + 'ms"><span class="da-kpi-icon" style="background:' + c.bg + ';color:' + color + ';"><i class="bi ' + c.icon + '"></i></span>' +
                '<div><b>' + c.value + '</b><small>' + c.label + '</small>' + hint + '</div></div></div>';
        }).join("");
    }

    function renderServiceTable(breakdown) {
        var body = document.getElementById("daServiceBody");
        if (!breakdown || !breakdown.length) {
            body.innerHTML = '<tr><td colspan="9" class="text-center text-muted">Aucune donnée.</td></tr>';
            return;
        }
        body.innerHTML = breakdown.map(function (s) {
            return "<tr class=\"team-row\" style=\"cursor:pointer;\" data-team=\"" + escapeHtml(s.serviceName) + "\">" +
                "<td>" + escapeHtml(s.serviceName) + "</td>" +
                "<td>" + s.agentCount + "</td>" +
                "<td>" + fmtPct(s.avgPresenceRate) + "</td>" +
                "<td>" + fmtPct(s.avgQualityScore) + "</td>" +
                "<td>" + fmtPct(s.avgPerformanceGlobale) + "</td>" +
                "<td>" + fmtNum(s.avgInteractions) + "</td>" +
                "<td>" + s.totalAbsenceDays + "</td>" +
                "<td>" + s.totalPauseOverruns + "</td>" +
                "<td>" + fmtPct(s.pctBelowTarget) + "</td></tr>";
        }).join("");

        Array.prototype.forEach.call(body.querySelectorAll(".team-row"), function (row) {
            row.addEventListener("click", function () {
                var teamData = lastBreakdown.find(function (s) { return s.serviceName === row.getAttribute("data-team"); });
                if (teamData) openTeamDetail(teamData);
            });
        });
    }

    function loadTeams() {
        getJson("/api/data-analysis/teams?month=" + document.getElementById("daMonth").value)
            .then(function (teams) {
                var select = document.getElementById("daTeamSelect");
                var current = select.value;
                select.innerHTML = '<option value="">— Toutes équipes —</option>' +
                    teams.map(function (t) { return '<option value="' + escapeHtml(t) + '">' + escapeHtml(TEAM_LABELS_DA[t] || t) + '</option>'; }).join("");
                select.value = current;
            }).catch(function () { /* liste d'équipes indisponible, le sélecteur reste sur "Toutes équipes" */ });
    }

    /** Rendu markdown minimal et sûr : échappe le HTML d'abord, puis interprète **gras**,
     *  les puces "- " et les sauts de ligne — le narratif (IA ou moteur de règles) utilise ce format. */
    function renderNarrative(text) {
        var escaped = escapeHtml(text || "");
        var html = escaped
            .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
            .split(/\n{2,}/).map(function (block) {
                var lines = block.split("\n").filter(function (l) { return l.trim() !== ""; });
                if (lines.length && lines.every(function (l) { return l.trim().indexOf("- ") === 0; })) {
                    return "<ul>" + lines.map(function (l) { return "<li>" + l.trim().slice(2) + "</li>"; }).join("") + "</ul>";
                }
                return "<p>" + lines.join("<br>") + "</p>";
            }).join("");
        return html;
    }

    function generate() {
        var btn = document.getElementById("daGenerateBtn");
        var narrativeBox = document.getElementById("daNarrative");
        var team = document.getElementById("daTeamSelect").value;
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Génération…';
        narrativeBox.textContent = "Analyse en cours…";

        var period = computeSelectedPeriod();
        var periodQuery = period.month ? "month=" + period.month : "from=" + period.from + "&to=" + period.to;
        lastPeriodQuery = periodQuery;
        var url = "/api/data-analysis?" + periodQuery + (team ? "&team=" + encodeURIComponent(team) : "");

        getJson(url)
            .then(function (result) {
                narrativeBox.innerHTML = renderNarrative(result.narrative);
                renderSummaryCards(result);
                lastBreakdown = result.serviceBreakdown || [];
                renderServiceTable(result.serviceBreakdown);
                renderTeamCards(lastBreakdown);
                // La moyenne globale ne sert de référence de comparaison que sur la vue "Toutes équipes" —
                // sinon comparer une équipe à elle-même n'aurait pas de sens.
                if (!team) {
                    lastGlobal = { avgPresenceRate: result.avgPresenceRate, avgQualityScore: result.avgQualityScore, avgPerformanceGlobale: result.avgPerformanceGlobale };
                    renderGlobalDonut(lastBreakdown);
                }
            })
            .catch(function (e) {
                narrativeBox.textContent = "Erreur : " + e.message;
            })
            .finally(function () {
                btn.disabled = false;
                btn.innerHTML = '<i class="bi bi-magic"></i> Générer l\'analyse';
            });
    }

    function currentMonthValue() {
        var now = new Date();
        return now.getFullYear() + "-" + String(now.getMonth() + 1).padStart(2, "0");
    }

    function alertSeverityBadge(sev) {
        return { CRITIQUE: '<span class="badge bg-danger">Critique</span>',
            ATTENTION: '<span class="badge bg-warning text-dark">Attention</span>',
            INFO: '<span class="badge bg-info text-dark">Info</span>' }[sev] || sev;
    }

    var ALERT_TYPE_LABELS = {
        PRESENCE_FAIBLE: "Présence faible",
        SCORE_QA_FAIBLE: "Score QA faible",
        EVALUATION_FAIBLE: "Évaluation faible",
        PERFORMANCE_EN_BAISSE: "Performance en baisse",
        CHUTE_PRESENCE: "Chute de présence",
        CHUTE_SCORE_QA: "Chute du score QA",
        CHUTE_PERFORMANCE: "Chute de performance"
    };

    var TEAM_LABELS_DA = { INBOUND_VOICE: "Inbound Voix", INBOUND_MAIL: "Inbound Mail / Rafiki", CIB: "CIB", OUTBOUND: "Outbound" };

    var lastAlertsCache = [];
    var lastAlertsCard = null;

    function loadAlerts() {
        var cardsContainer = document.getElementById("daAlertsTeamCards");
        lastAlertsCard = cardsContainer.closest(".card");
        var month = document.getElementById("daMonth").value;
        getJson("/api/data-analysis/alerts?month=" + month).then(function (alerts) {
            lastAlertsCache = alerts;
            document.getElementById("daAlertsAgentsSection").style.display = "none";
            renderAlertsTeamCards();
        }).catch(function () {
            if (lastAlertsCard) lastAlertsCard.style.display = "none"; // pas d'accès — on masque plutôt que d'afficher une erreur
        });
    }

    var ALERT_SEVERITY_COLORS = { CRITIQUE: "#dc3545", ATTENTION: "#F5A623" };

    /** Regroupe les alertes par équipe : anneau critique/attention, photos des agents concernés. */
    function renderAlertsTeamCards() {
        var cardsContainer = document.getElementById("daAlertsTeamCards");
        if (!lastAlertsCache.length) {
            cardsContainer.innerHTML = '<p class="text-muted text-center">Aucune alerte pour ce mois — tout est dans les seuils attendus, ou la détection n\'a pas encore tourné.</p>';
            return;
        }
        var byTeam = {};
        lastAlertsCache.forEach(function (a) {
            var key = a.team || "OTHER";
            if (!byTeam[key]) byTeam[key] = { CRITIQUE: 0, ATTENTION: 0, agents: {}, open: 0 };
            byTeam[key][a.severity] = (byTeam[key][a.severity] || 0) + 1;
            byTeam[key].agents[a.userId] = a.userFullName;
            if (!a.acknowledged) byTeam[key].open++;
        });

        var teamKeys = Object.keys(byTeam).sort(function (a, b) { return byTeam[b].CRITIQUE - byTeam[a].CRITIQUE; });
        cardsContainer.innerHTML = teamKeys.map(function (key, i) {
            var t = byTeam[key];
            var total = t.CRITIQUE + t.ATTENTION;
            var critPct = total ? (t.CRITIQUE / total) * 100 : 0;
            var gradient = "conic-gradient(" + ALERT_SEVERITY_COLORS.CRITIQUE + " 0% " + critPct + "%, " +
                ALERT_SEVERITY_COLORS.ATTENTION + " " + critPct + "% 100%)";
            var ids = Object.keys(t.agents);
            return '<div class="col-xl-3 col-md-4 col-sm-6">' +
                '<div class="da-alert-card" data-team="' + escapeHtml(key) + '" style="animation-delay:' + (i * 50) + 'ms">' +
                    '<div class="da-gauge mx-auto mb-2" style="width:86px;height:86px;background:' + gradient + ';">' +
                        '<div class="da-gauge-hole" style="width:58px;height:58px;"><span class="val" style="font-size:1rem;">' + total + '</span><span class="lbl">alertes</span></div>' +
                    '</div>' +
                    '<div class="fw-bold">' + escapeHtml(TEAM_LABELS_DA[key] || (key === "OTHER" ? "Non classée" : key)) + '</div>' +
                    '<div class="small text-muted mb-2">' + ids.length + ' agent(s) concerné(s) · ' + t.open + ' à traiter</div>' +
                    '<div class="da-avatars justify-content-center mb-2">' + ids.slice(0, 6).map(function (id) { return avatarHtml(Number(id), t.agents[id], 30); }).join("") +
                        (ids.length > 6 ? '<span class="da-avatar" style="width:30px;height:30px;font-size:10px;background:#e3e8f0;color:#4a5568;">+' + (ids.length - 6) + '</span>' : "") + '</div>' +
                    '<div class="small"><span class="text-danger">● ' + t.CRITIQUE + ' critique</span> · <span style="color:#F5A623;">● ' + t.ATTENTION + ' attention</span></div>' +
                '</div></div>';
        }).join("");
        loadPhotos([].concat.apply([], teamKeys.map(function (k) { return Object.keys(byTeam[k].agents).slice(0, 6).map(Number); })));

        Array.prototype.forEach.call(cardsContainer.querySelectorAll(".da-alert-card"), function (card) {
            card.addEventListener("click", function () { renderAlertsForTeam(card.getAttribute("data-team")); });
        });
    }

    var daAlertsModal = null;

    /** Fenêtre « agents concernés » : une fiche par agent (photo, alertes, action). */
    function renderAlertsForTeam(teamKey) {
        var teamAlerts = lastAlertsCache.filter(function (a) { return (a.team || "OTHER") === teamKey; });
        var byAgent = {};
        var order = [];
        teamAlerts.forEach(function (a) {
            if (!byAgent[a.userId]) { byAgent[a.userId] = { name: a.userFullName, userId: a.userId, alerts: [] }; order.push(a.userId); }
            byAgent[a.userId].alerts.push(a);
        });
        order.sort(function (x, y) {
            var cx = byAgent[x].alerts.filter(function (a) { return a.severity === "CRITIQUE"; }).length;
            var cy = byAgent[y].alerts.filter(function (a) { return a.severity === "CRITIQUE"; }).length;
            return cy - cx;
        });
        document.getElementById("daAlertsModalTitle").innerHTML = '<i class="bi bi-exclamation-triangle-fill"></i> Agents concernés — ' + escapeHtml(TEAM_LABELS_DA[teamKey] || (teamKey === "OTHER" ? "Non classée" : teamKey));
        document.getElementById("daAlertsModalSub").textContent = order.length + " agent(s) · " + teamAlerts.length + " alerte(s) · cliquez sur un agent pour voir sa fiche";
        var body = document.getElementById("daAlertsModalBody");
        body.innerHTML = order.map(function (uid) {
            var g = byAgent[uid];
            var allDone = g.alerts.every(function (a) { return a.acknowledged; });
            return '<div class="da-alert-person' + (allDone ? " done" : "") + '">' +
                '<span class="da-alert-agent-link" data-user-id="' + uid + '" data-user-name="' + escapeHtml(g.name) + '" style="cursor:pointer;">' + avatarHtml(uid, g.name, 52) + '</span>' +
                '<div class="flex-grow-1"><b class="da-alert-agent-link" data-user-id="' + uid + '" data-user-name="' + escapeHtml(g.name) + '" style="cursor:pointer;color:#0057B8;">' + escapeHtml(g.name) + '</b>' +
                g.alerts.map(function (a) {
                    return '<div class="small mt-1 d-flex align-items-center gap-2 flex-wrap">' + alertSeverityBadge(a.severity) +
                        '<b>' + escapeHtml(ALERT_TYPE_LABELS[a.alertType] || a.alertType) + '</b><span class="text-muted">' + escapeHtml(a.message) + '</span>' +
                        (a.acknowledged ? '<span class="badge bg-success"><i class="bi bi-check-lg"></i> Traitée</span>'
                            : '<button class="btn btn-sm btn-outline-success py-0" data-ack-id="' + a.alertId + '">Marquer traitée</button>') + '</div>';
                }).join("") + '</div></div>';
        }).join("");
        loadPhotos(order);

        Array.prototype.forEach.call(body.querySelectorAll("[data-ack-id]"), function (btn) {
            btn.addEventListener("click", function () {
                btn.disabled = true;
                fetch("/api/data-analysis/alerts/" + btn.getAttribute("data-ack-id") + "/acknowledge", { method: "POST", credentials: "same-origin" })
                    .then(function () {
                        var alert = lastAlertsCache.filter(function (a) { return String(a.alertId) === btn.getAttribute("data-ack-id"); })[0];
                        if (alert) alert.acknowledged = true;
                        renderAlertsForTeam(teamKey);
                        renderAlertsTeamCards();
                    });
            });
        });
        Array.prototype.forEach.call(body.querySelectorAll(".da-alert-agent-link"), function (cell) {
            cell.addEventListener("click", function () {
                openAgentDetail({ userId: Number(cell.getAttribute("data-user-id")), userFullName: cell.getAttribute("data-user-name") });
            });
        });
        if (!daAlertsModal) daAlertsModal = new bootstrap.Modal(document.getElementById("daAlertsModal"));
        daAlertsModal.show();
    }

    function init() {
        daTeamDetailModal = new bootstrap.Modal(document.getElementById("daTeamDetailModal"));
        daAgentDetailModal = new bootstrap.Modal(document.getElementById("daAgentDetailModal"));
        wireGranularity();
        Array.prototype.forEach.call(document.querySelectorAll("#daAgentFilter button"), function (b) {
            b.addEventListener("click", function () {
                agentFilter = b.getAttribute("data-filter");
                Array.prototype.forEach.call(document.querySelectorAll("#daAgentFilter button"), function (x) { x.classList.toggle("active", x === b); });
                renderTeamAgents();
            });
        });
        document.getElementById("daMonth").value = currentMonthValue();
        document.getElementById("daGenerateBtn").addEventListener("click", generate);
        document.getElementById("daMonth").addEventListener("change", function () { loadTeams(); loadAlerts(); });
        document.getElementById("daRunAlertsBtn").addEventListener("click", function () {
            var btn = document.getElementById("daRunAlertsBtn");
            btn.disabled = true;
            fetch("/api/data-analysis/alerts/run?month=" + document.getElementById("daMonth").value,
                { method: "POST", credentials: "same-origin" })
                .then(function (res) { return res.json(); })
                .then(function (result) {
                    alert(result.created + " nouvelle(s) alerte(s) détectée(s).");
                    loadAlerts();
                })
                .finally(function () { btn.disabled = false; });
        });
        window.RccSession.init();
        loadTeams();
        loadAlerts();
        generate();
    }

    document.addEventListener("DOMContentLoaded", init);
})();
