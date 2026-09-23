"use strict";

(function () {
    var getJson = RccApi.getJson;
    var escapeHtml = RccApi.escapeHtml;

    var TEAM_COLORS = ["#0057B8", "#00A651", "#F5A623", "#7B2FF7", "#F72585", "#17a2b8", "#dc3545", "#6c757d"];
    var lastBreakdown = [];
    var lastGlobal = { avgPresenceRate: null, avgQualityScore: null, avgPerformanceGlobale: null };
    var lastPeriodQuery = "month=" + currentMonthValue(); // reconstruit à chaque generate() — réutilisé pour agents/timeline
    var daTeamDetailModal = null;

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
        var donut = '<div class="da-gauge" style="width:110px;height:110px;background:conic-gradient(' + gradientParts.join(",") + ');">' +
            '<div class="da-gauge-hole" style="width:70px;height:70px;"><span class="val">' + total + '</span><span class="lbl">agents</span></div></div>';
        var legend = breakdown.map(function (s, idx) {
            return '<span class="small me-3"><span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:' +
                TEAM_COLORS[idx % TEAM_COLORS.length] + ';margin-right:4px;"></span>' + escapeHtml(s.serviceName) + ' (' + s.agentCount + ')</span>';
        }).join("");
        container.innerHTML = donut + '<div>' + legend + '</div>';
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

    function openTeamDetail(teamRow) {
        document.getElementById("daTeamDetailTitle").innerHTML = '<i class="bi bi-diagram-3"></i> ' + escapeHtml(teamRow.serviceName);

        document.getElementById("daTeamGauges").innerHTML =
            gaugeHtml(teamRow.avgPresenceRate, "Présence", "#0057B8") +
            gaugeHtml(teamRow.avgQualityScore, "Score qualité", "#00A651") +
            gaugeHtml(teamRow.avgPerformanceGlobale, "Performance", "#F5A623");

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
                    '<div class="da-timeline-bar" style="height:' + Math.max(4, v) + '%;"></div>' +
                    '<div class="da-timeline-label">' + monthLabel + '</div>' +
                '</div>';
            }).join("");
        }).catch(function () {
            timelineEl.innerHTML = '<p class="text-danger small mb-0">Erreur de chargement de la frise chronologique.</p>';
        });

        var agentsBody = document.getElementById("daAgentsBody");
        agentsBody.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Chargement…</td></tr>';
        getJson("/api/data-analysis/agents?" + lastPeriodQuery + "&team=" + encodeURIComponent(teamRow.teamCode || teamRow.serviceName))
            .then(function (agents) {
                if (!agents.length) { agentsBody.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Aucun agent avec données.</td></tr>'; return; }
                agentsBody.innerHTML = agents.map(function (a, idx) {
                    // "A marché" = performance globale connue et >= 70 % ; sinon "à surveiller" (pas assez de données ou sous le seuil).
                    var worked = a.performanceGlobale != null && a.performanceGlobale >= 70;
                    var resultBadge = a.performanceGlobale == null
                        ? '<span class="badge bg-secondary">Données incomplètes</span>'
                        : (worked ? '<span class="badge bg-success"><i class="bi bi-check-lg"></i> A marché</span>' : '<span class="badge bg-danger"><i class="bi bi-x-lg"></i> À surveiller</span>');
                    return '<tr class="da-agent-row" data-agent-idx="' + idx + '" style="cursor:pointer;"><td>' + escapeHtml(a.userFullName || a.username) + "</td>" +
                        "<td>" + fmtPct(Math.round(a.presenceRate)) + "</td>" +
                        "<td>" + (a.avgQualityScore != null ? fmtPct(Math.round(a.avgQualityScore)) : "—") + "</td>" +
                        "<td>" + (a.performanceGlobale != null ? fmtPct(Math.round(a.performanceGlobale)) : "—") + "</td>" +
                        "<td>" + resultBadge + "</td></tr>";
                }).join("");
                Array.prototype.forEach.call(agentsBody.querySelectorAll(".da-agent-row"), function (row) {
                    row.addEventListener("click", function () {
                        openAgentDetail(agents[Number(row.getAttribute("data-agent-idx"))]);
                    });
                });
            }).catch(function (e) {
                agentsBody.innerHTML = '<tr><td colspan="5" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
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
        document.getElementById("daAgentDetailTitle").innerHTML = '<i class="bi bi-person-fill"></i> ' + escapeHtml(agent.userFullName || agent.username);

        if (agent.presenceRate !== undefined) {
            var kpiRows = "";
            if (agent.kpiMetrics) {
                Object.keys(agent.kpiMetrics).sort().forEach(function (code) {
                    kpiRows += "<tr><td>" + escapeHtml(code) + "</td><td>" + agent.kpiMetrics[code] + "</td></tr>";
                });
            }
            document.getElementById("daAgentDetailBody").innerHTML =
                '<table class="table table-sm mb-0">' +
                    '<tr><td>Présence</td><td>' + fmtPct(Math.round(agent.presenceRate)) + '</td></tr>' +
                    '<tr><td>Score qualité moyen</td><td>' + (agent.avgQualityScore != null ? fmtPct(Math.round(agent.avgQualityScore)) : "—") + '</td></tr>' +
                    '<tr><td>Performance globale</td><td>' + (agent.performanceGlobale != null ? fmtPct(Math.round(agent.performanceGlobale)) : "—") + '</td></tr>' +
                    '<tr><td>Évaluations QA (total)</td><td>' + (agent.evaluationCount || 0) + '</td></tr>' +
                    '<tr><td>Filiale</td><td>' + escapeHtml(agent.affiliateBranch || "—") + '</td></tr>' +
                    '<tr><td>Équipe (activité)</td><td>' + escapeHtml(agent.activity || "—") + '</td></tr>' +
                    kpiRows +
                '</table>';
        } else {
            document.getElementById("daAgentDetailBody").innerHTML = "";
        }

        var historyEl = document.getElementById("daAgentAlertHistory");
        historyEl.innerHTML = '<p class="text-muted small">Chargement…</p>';
        if (agent.userId) {
            getJson("/api/data-analysis/alerts/agent/" + agent.userId).then(function (history) {
                if (!history.length) { historyEl.innerHTML = '<p class="text-muted small mb-0">Aucune alerte enregistrée pour cet agent — historique propre depuis le début du suivi.</p>'; return; }
                historyEl.innerHTML = '<table class="table table-sm mb-0"><thead><tr><th>Mois</th><th>Type</th><th>Sévérité</th><th>Message</th></tr></thead><tbody>' +
                    history.map(function (h) {
                        return "<tr><td>" + h.periodMonth + "</td><td>" + escapeHtml(ALERT_TYPE_LABELS[h.alertType] || h.alertType) + "</td>" +
                            "<td>" + alertSeverityBadge(h.severity) + "</td><td>" + escapeHtml(h.message) + "</td></tr>";
                    }).join("") + "</tbody></table>";
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
        document.getElementById("daSummaryCards").innerHTML = [
            { label: "Agents avec données", value: result.totalAgents },
            { label: "Présence moyenne", value: fmtPct(result.avgPresenceRate) },
            { label: "Score qualité moyen", value: fmtPct(result.avgQualityScore) },
            { label: "Performance moyenne", value: fmtPct(result.avgPerformanceGlobale) }
        ].map(function (s) {
            return '<div class="col-md-3"><div class="da-stat-card"><div class="value">' + s.value + '</div><div class="label">' + s.label + '</div></div></div>';
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
                renderServiceTable(result.serviceBreakdown);
                lastBreakdown = result.serviceBreakdown || [];
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

    /** Regroupe les alertes par équipe, montre un donut (répartition critique/attention) + le
     *  pourcentage d'agents de l'équipe concernés par au moins une alerte. */
    function renderAlertsTeamCards() {
        var cardsContainer = document.getElementById("daAlertsTeamCards");
        if (!lastAlertsCache.length) {
            cardsContainer.innerHTML = '<p class="text-muted text-center">Aucune alerte pour ce mois — tout est dans les seuils attendus, ou la détection n\'a pas encore tourné.</p>';
            return;
        }
        var byTeam = {};
        lastAlertsCache.forEach(function (a) {
            var key = a.team || "OTHER";
            if (!byTeam[key]) byTeam[key] = { CRITIQUE: 0, ATTENTION: 0, agents: {} };
            byTeam[key][a.severity] = (byTeam[key][a.severity] || 0) + 1;
            byTeam[key].agents[a.userId] = true;
        });

        var teamKeys = Object.keys(byTeam).sort();
        cardsContainer.innerHTML = teamKeys.map(function (key) {
            var t = byTeam[key];
            var total = t.CRITIQUE + t.ATTENTION;
            var critPct = total ? (t.CRITIQUE / total) * 100 : 0;
            var gradient = "conic-gradient(" + ALERT_SEVERITY_COLORS.CRITIQUE + " 0% " + critPct + "%, " +
                ALERT_SEVERITY_COLORS.ATTENTION + " " + critPct + "% 100%)";
            var agentCount = Object.keys(t.agents).length;
            return '<div class="col-md-3 col-sm-6">' +
                '<div class="border rounded p-3 text-center da-alert-team-card" data-team="' + key + '" style="cursor:pointer;">' +
                    '<div class="da-gauge mx-auto mb-2" style="width:80px;height:80px;background:' + gradient + ';">' +
                        '<div class="da-gauge-hole" style="width:52px;height:52px;"><span class="val" style="font-size:.9rem;">' + total + '</span></div>' +
                    '</div>' +
                    '<div class="fw-semibold">' + escapeHtml(TEAM_LABELS_DA[key] || key) + '</div>' +
                    '<div class="small text-muted">' + agentCount + ' agent(s) concerné(s)</div>' +
                    '<div class="small mt-1"><span class="text-danger">● ' + t.CRITIQUE + ' critique</span> · <span style="color:#F5A623;">● ' + t.ATTENTION + ' attention</span></div>' +
                '</div></div>';
        }).join("");

        Array.prototype.forEach.call(cardsContainer.querySelectorAll(".da-alert-team-card"), function (card) {
            card.addEventListener("click", function () { renderAlertsForTeam(card.getAttribute("data-team")); });
        });
    }

    function renderAlertsForTeam(teamKey) {
        var section = document.getElementById("daAlertsAgentsSection");
        var body = document.getElementById("daAlertsBody");
        document.getElementById("daAlertsAgentsTitle").textContent = "Agents concernés — " + (TEAM_LABELS_DA[teamKey] || teamKey);
        var teamAlerts = lastAlertsCache.filter(function (a) { return (a.team || "OTHER") === teamKey; });

        body.innerHTML = teamAlerts.map(function (a) {
            var statusBadge = a.acknowledged
                ? '<span class="badge bg-success"><i class="bi bi-check-lg"></i> Traitée</span>'
                : '<span class="badge bg-secondary">À traiter</span>';
            var ackBtn = a.acknowledged ? "" : '<button class="btn btn-sm btn-outline-success" data-ack-id="' + a.alertId + '">Marquer traitée</button>';
            return '<tr><td class="da-alert-agent-link" data-user-id="' + a.userId + '" data-user-name="' + escapeHtml(a.userFullName) + '" style="cursor:pointer;color:#0057B8;text-decoration:underline;">' + escapeHtml(a.userFullName) + "</td>" +
                "<td>" + escapeHtml(ALERT_TYPE_LABELS[a.alertType] || a.alertType) + "</td>" +
                "<td>" + alertSeverityBadge(a.severity) + "</td>" +
                "<td>" + escapeHtml(a.message) + "</td>" +
                "<td>" + statusBadge + "</td>" +
                "<td>" + ackBtn + "</td></tr>";
        }).join("");

        Array.prototype.forEach.call(body.querySelectorAll("[data-ack-id]"), function (btn) {
            btn.addEventListener("click", function () {
                fetch("/api/data-analysis/alerts/" + btn.getAttribute("data-ack-id") + "/acknowledge",
                    { method: "POST", credentials: "same-origin" }).then(loadAlerts);
            });
        });
        Array.prototype.forEach.call(body.querySelectorAll(".da-alert-agent-link"), function (cell) {
            cell.addEventListener("click", function () {
                openAgentDetail({ userId: Number(cell.getAttribute("data-user-id")), userFullName: cell.getAttribute("data-user-name") });
            });
        });

        section.style.display = "";
        section.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    function init() {
        daTeamDetailModal = new bootstrap.Modal(document.getElementById("daTeamDetailModal"));
        daAgentDetailModal = new bootstrap.Modal(document.getElementById("daAgentDetailModal"));
        wireGranularity();
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
