"use strict";

(function () {

    var METRIC_LABELS = {
        INTERACTIONS: "Interactions",
        PRODUCTIVITE: "Productivité",
        SCORE_QA: "Score QA",
        NOTE_QA: "Note QA",
        TARGET: "Target",
        SCORE: "Score",
        SCORE_EVALUATION: "Score évaluation",
        CUMUL_INTERACTIONS: "Interactions (cumul)",
        CUMUL_PRODUCTIVITE: "Productivité (cumul)",
        CUMUL_SCORE_QA: "Score QA (cumul)"
    };

    var METRIC_ICONS = {
        INTERACTIONS: "bi-chat-dots",
        PRODUCTIVITE: "bi-speedometer2",
        SCORE_QA: "bi-patch-check",
        NOTE_QA: "bi-star",
        TARGET: "bi-bullseye",
        SCORE: "bi-award",
        SCORE_EVALUATION: "bi-clipboard-check",
        CUMUL_INTERACTIONS: "bi-chat-dots",
        CUMUL_PRODUCTIVITE: "bi-speedometer2",
        CUMUL_SCORE_QA: "bi-patch-check"
    };

    /** Tableau compact des chiffres réellement importés — évite de n'afficher que des noms/compteurs. */
    /** Tableau plat classique (utilisé pour le planning : agent/date/heure — pas de sens à pivoter). */
    function renderImportPreview(afterEl, preview, truncated, headers, rowMapper) {
        if (!preview || !preview.length) return;
        var table = document.createElement("table");
        table.className = "table table-sm table-hover mt-2 mb-0";
        table.innerHTML = "<thead><tr>" + headers.map(function (h) { return "<th>" + escapeHtml(h) + "</th>"; }).join("") + "</tr></thead>" +
            "<tbody>" + preview.map(function (p) {
                var cells = rowMapper(p);
                return "<tr>" + cells.map(function (c) { return "<td>" + escapeHtml(String(c == null ? "—" : c)) + "</td>"; }).join("") + "</tr>";
            }).join("") + "</tbody>";
        afterEl.after(table);
        if (truncated) {
            var note = document.createElement("div");
            note.className = "small text-muted mt-1";
            note.textContent = "Aperçu limité aux " + preview.length + " premières valeurs importées.";
            table.after(note);
        }
        return table;
    }

    /** Tableau large façon fichier Excel source — un agent par ligne, une rubrique par colonne, valeurs réelles. */
    function renderImportPreviewWide(afterEl, preview, truncated) {
        if (!preview || !preview.length) return;

        var byAgent = {};
        var codesSeen = [];
        preview.forEach(function (p) {
            if (!byAgent[p.agentName]) byAgent[p.agentName] = {};
            byAgent[p.agentName][p.metricCode] = p.value;
            if (codesSeen.indexOf(p.metricCode) === -1) codesSeen.push(p.metricCode);
        });
        // Ordre lisible : les rubriques connues d'abord, puis le reste tel que rencontré.
        var priority = ["SCORE_QA", "INTERACTIONS", "PRODUCTIVITE", "NOTE_QA", "TARGET", "SCORE"];
        codesSeen.sort(function (a, b) {
            var ia = priority.indexOf(a), ib = priority.indexOf(b);
            if (ia === -1 && ib === -1) return a.localeCompare(b);
            if (ia === -1) return 1;
            if (ib === -1) return -1;
            return ia - ib;
        });

        var table = document.createElement("table");
        table.className = "table table-sm table-hover mt-2 mb-0";
        table.innerHTML = "<thead><tr><th>Agent</th>" +
            codesSeen.map(function (c) { return "<th>" + escapeHtml(METRIC_LABELS[c] || c) + "</th>"; }).join("") +
            "</tr></thead><tbody>" +
            Object.keys(byAgent).sort().map(function (agent) {
                var row = byAgent[agent];
                return "<tr><td>" + escapeHtml(agent) + "</td>" +
                    codesSeen.map(function (c) { return "<td>" + (row[c] != null ? row[c] : "—") + "</td>"; }).join("") +
                    "</tr>";
            }).join("") + "</tbody>";
        afterEl.after(table);
        if (truncated) {
            var note = document.createElement("div");
            note.className = "small text-muted mt-1";
            note.textContent = "Aperçu limité aux " + preview.length + " premières valeurs importées (certains agents peuvent être incomplets ci-dessus).";
            table.after(note);
        }
        return table;
    }

    /** Bouton "Supprimer cet import" — annule d'un coup toutes les valeurs créées par ce fichier. */
    function renderDeleteImportButton(afterEl, batchId, onDeleted) {
        if (!batchId) return;
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn btn-sm btn-outline-danger mt-2";
        btn.innerHTML = '<i class="bi bi-trash"></i> Supprimer cet import';
        btn.addEventListener("click", function () {
            if (!confirm("Supprimer toutes les valeurs importées par ce fichier ? Cette action est irréversible.")) return;
            fetch("/api/kpi/manual-entries/imports/" + encodeURIComponent(batchId), { method: "DELETE", credentials: "same-origin" })
                .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
                .then(function (result) {
                    btn.remove();
                    if (onDeleted) onDeleted(result.deletedCount);
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
        afterEl.after(btn);
    }

    var escapeHtml = RccApi.escapeHtml;

    var getJson = RccApi.getJson;

    function currentMonthValue() {
        var now = new Date();
        return now.getFullYear() + "-" + String(now.getMonth() + 1).padStart(2, "0");
    }

    /** Couleur d'accent par métrique — reprend la palette de marque déjà utilisée ailleurs
     *  (bleu Ecobank, vert, orange, violet), cycle pour les codes non prévus. */
    var METRIC_COLORS = {
        INTERACTIONS: "#0057B8", PRODUCTIVITE: "#00A651", SCORE_QA: "#F5A623", NOTE_QA: "#7C3AED",
        TARGET: "#0EA5E9", SCORE: "#EF4444", SCORE_EVALUATION: "#14B8A6",
        CUMUL_INTERACTIONS: "#0057B8", CUMUL_PRODUCTIVITE: "#00A651", CUMUL_SCORE_QA: "#F5A623"
    };
    var METRIC_COLOR_CYCLE = ["#0057B8", "#00A651", "#F5A623", "#7C3AED", "#0EA5E9", "#EF4444", "#14B8A6", "#EC4899"];
    var metricColorAssignCache = {};
    function metricColor(code) {
        if (METRIC_COLORS[code]) return METRIC_COLORS[code];
        if (!metricColorAssignCache[code]) {
            var idx = Object.keys(metricColorAssignCache).length % METRIC_COLOR_CYCLE.length;
            metricColorAssignCache[code] = METRIC_COLOR_CYCLE[idx];
        }
        return metricColorAssignCache[code];
    }

    function renderKpi(kpiMetrics) {
        var grid = document.getElementById("kpiCardsGrid");
        var codes = Object.keys(kpiMetrics || {});
        if (!codes.length) {
            grid.innerHTML = '<div class="text-center text-muted">Aucun KPI saisi pour ce mois.</div>';
            return;
        }
        grid.innerHTML = codes.sort().map(function (code) {
            var icon = METRIC_ICONS[code] || "bi-graph-up";
            var value = kpiMetrics[code];
            var color = metricColor(code);
            var displayValue = (code === "SCORE_EVALUATION") ? value + " %" : value;
            var numeric = Number(value);
            var isPercentLike = !isNaN(numeric) && numeric >= 0 && numeric <= 100;
            var barHtml = isPercentLike
                ? '<div class="pf-mini-bar-track"><div class="pf-mini-bar-fill" style="width:' + numeric + '%;"></div></div>'
                : "";
            return '<div class="pf-mini-card" style="--pf-accent:' + color + ';">' +
                    '<div class="d-flex align-items-center gap-2 mb-2">' +
                        '<div class="pf-mini-icon"><i class="bi ' + icon + '"></i></div>' +
                        '<div class="pf-mini-label">' + escapeHtml(METRIC_LABELS[code] || code) + '</div>' +
                    '</div>' +
                    '<div class="pf-mini-value">' + escapeHtml(String(displayValue)) + '</div>' +
                    barHtml +
                '</div>';
        }).join("");
    }

    /** Statut qualitatif affiché dans le bandeau de synthèse — seuils indicatifs, jamais
     *  affichés si performanceGlobale est null (voir computeFor() côté backend : reste null
     *  tant que SCORE_QA n'a pas été importé, jamais une approximation silencieuse). */
    function heroStatus(value) {
        if (value >= 85) return { label: "Excellent", icon: "bi-trophy-fill" };
        if (value >= 70) return { label: "Bien", icon: "bi-hand-thumbs-up-fill" };
        if (value >= 50) return { label: "À améliorer", icon: "bi-graph-up-arrow" };
        return { label: "Attention requise", icon: "bi-exclamation-triangle-fill" };
    }

    function renderHero(data) {
        var monthLabel = document.getElementById("monthInput").value || "";
        try {
            if (monthLabel) {
                var d = new Date(monthLabel + "-01T00:00:00");
                monthLabel = d.toLocaleDateString("fr-FR", { month: "long", year: "numeric" });
            }
        } catch (e) {}
        document.getElementById("heroMonthLabel").textContent = monthLabel;

        var ring = document.getElementById("heroRing");
        var badge = document.getElementById("heroBadge");
        if (data.performanceGlobale != null) {
            var pct = Math.max(0, Math.min(100, data.performanceGlobale));
            document.getElementById("heroGlobalValue").textContent = pct + " %";
            document.getElementById("heroRingValue").textContent = Math.round(pct) + "%";
            ring.style.background = "conic-gradient(#fff " + (pct * 3.6) + "deg, rgba(255,255,255,.18) " + (pct * 3.6) + "deg 360deg)";
            var status = heroStatus(pct);
            badge.innerHTML = '<i class="bi ' + status.icon + '"></i> ' + status.label;
        } else {
            document.getElementById("heroGlobalValue").textContent = "—";
            document.getElementById("heroRingValue").textContent = "—";
            ring.style.background = "rgba(255,255,255,.15)";
            badge.innerHTML = '<i class="bi bi-hourglass-split"></i> En attente de données (Score QA du mois pas encore importé)';
        }
    }

    function render(data) {
        renderHero(data);

        document.getElementById("presenceValue").textContent =
            data.presenceRate != null ? data.presenceRate + " %" : "—";

        if (data.avgQualityScore != null) {
            document.getElementById("qualityValue").textContent = data.avgQualityScore + " %";
            document.getElementById("qualityDetail").textContent = data.evaluationCount + " évaluation(s)";
        } else {
            document.getElementById("qualityValue").textContent = "—";
            document.getElementById("qualityDetail").textContent = "Aucune évaluation ce mois-ci";
        }

        document.getElementById("evalCountValue").textContent = data.evaluationCount;

        if (data.performanceGlobale != null) {
            document.getElementById("globalValue").textContent = data.performanceGlobale + " %";
            document.getElementById("globalDetail").textContent = "";
        } else {
            document.getElementById("globalValue").textContent = "—";
            document.getElementById("globalDetail").textContent = "Toutes les métriques requises ne sont pas encore saisies";
        }

        renderKpi(data.kpiMetrics);
    }

    function loadManualKpiHistory() {
        fetch("/api/kpi/manual-entries/me", { credentials: "same-origin" })
            .then(function (res) { return res.ok ? res.json() : []; })
            .then(function (entries) {
                var body = document.getElementById("manualKpiHistoryBody");
                if (!entries.length) {
                    body.innerHTML = '<tr><td colspan="4" class="text-muted text-center">Aucune donnée.</td></tr>';
                    return;
                }
                body.innerHTML = entries.map(function (e) {
                    return "<tr><td>" + e.metricCode + "</td><td>" + e.metricValue + "</td><td>" + e.periodDate + "</td><td>" + (e.enteredByMatricule || "—") + "</td></tr>";
                }).join("");
            }).catch(function () {});
    }

    function load() {
        var errorBox = document.getElementById("loadError");
        errorBox.style.display = "none";
        var month = document.getElementById("monthInput").value;
        var url = "/api/performance/me" + (month ? "?month=" + encodeURIComponent(month) : "");

        var exportBtn = document.getElementById("exportMyReportBtn");
        if (exportBtn) {
            exportBtn.href = "/api/performance/me/export-word" + (month ? "?month=" + encodeURIComponent(month) : "");
        }
        var exportPptBtn = document.getElementById("exportMyReportPptBtn");
        if (exportPptBtn) {
            exportPptBtn.href = "/api/performance/me/export-powerpoint" + (month ? "?month=" + encodeURIComponent(month) : "");
        }

        getJson(url)
            .then(function (data) {
                document.getElementById("monthInput").value = data.periodMonth;
                render(data);
            })
            .catch(function (e) {
                errorBox.textContent = "Erreur de chargement : " + e.message;
                errorBox.style.display = "";
            });
    }

    function fmtPct(v) { return v != null ? v + " %" : "—"; }

    function avgOf(agents, field) {
        var values = agents.map(function (a) { return a[field]; }).filter(function (v) { return v != null; });
        if (!values.length) return null;
        return Math.round((values.reduce(function (s, v) { return s + v; }, 0) / values.length) * 100) / 100;
    }

    function summaryCardsHtml(agents) {
        var totalEval = agents.reduce(function (s, a) { return s + (a.evaluationCount || 0); }, 0);
        return '<div class="tr-summary-cards">' +
            '<div class="tr-summary-card"><div class="value">' + fmtPct(avgOf(agents, "presenceRate")) + '</div><div class="label">Présence</div></div>' +
            '<div class="tr-summary-card"><div class="value">' + fmtPct(avgOf(agents, "avgQualityScore")) + '</div><div class="label">Score qualité</div></div>' +
            '<div class="tr-summary-card"><div class="value">' + fmtPct(avgOf(agents, "performanceGlobale")) + '</div><div class="label">Performance globale</div></div>' +
            '<div class="tr-summary-card"><div class="value">' + totalEval + '</div><div class="label">Évaluations qualité</div></div>' +
            '</div>';
    }

    function agentRowsHtml(agents) {
        return '<table class="table table-sm table-hover mb-0">' +
            '<thead><tr><th>Agent</th><th>Score QA</th><th>Interactions</th><th>Productivité</th><th>Note QA</th><th>Target</th><th>Présence</th><th>Performance</th></tr></thead>' +
            '<tbody>' + agents.map(function (a) {
                var m = a.kpiMetrics || {};
                return '<tr class="tr-agent-row" style="cursor:pointer;" data-username="' + escapeHtml(a.username) + '">' +
                    "<td>" + escapeHtml(a.userFullName || a.username) + "</td>" +
                    "<td>" + (m.SCORE_QA != null ? m.SCORE_QA : "—") + "</td>" +
                    "<td>" + (m.INTERACTIONS != null ? m.INTERACTIONS : "—") + "</td>" +
                    "<td>" + (m.PRODUCTIVITE != null ? m.PRODUCTIVITE : "—") + "</td>" +
                    "<td>" + (m.NOTE_QA != null ? m.NOTE_QA : "—") + "</td>" +
                    "<td>" + (m.TARGET != null ? m.TARGET : "—") + "</td>" +
                    "<td>" + fmtPct(a.presenceRate) + "</td>" +
                    "<td>" + fmtPct(a.performanceGlobale) + "</td></tr>";
            }).join("") + '</tbody></table>';
    }

    /** Détail complet d'un agent — toutes les rubriques importées, réutilisé au clic sur une ligne. */
    function openTeamResultAgentDetail(agent) {
        document.getElementById("agentDetailName").textContent = agent.userFullName || agent.username;
        var kpiRows = Object.keys(agent.kpiMetrics || {}).map(function (k) {
            return "<tr><td>" + escapeHtml(k) + "</td><td>" + agent.kpiMetrics[k] + "</td></tr>";
        }).join("");
        document.getElementById("agentDetailBody").innerHTML =
            "<tr><td>Filiale</td><td>" + escapeHtml(agent.affiliateBranch || "—") + "</td></tr>" +
            "<tr><td>Service</td><td>" + escapeHtml(agent.serviceName || "—") + "</td></tr>" +
            "<tr><td>Équipe</td><td>" + escapeHtml(agent.activity || "—") + "</td></tr>" +
            "<tr><td>Présence</td><td>" + fmtPct(agent.presenceRate) + "</td></tr>" +
            "<tr><td>Score qualité</td><td>" + fmtPct(agent.avgQualityScore) + "</td></tr>" +
            "<tr><td>Performance globale</td><td>" + fmtPct(agent.performanceGlobale) + "</td></tr>" +
            kpiRows;
        new bootstrap.Modal(document.getElementById("agentDetailModal")).show();
    }

    var RCC_SUB_TEAMS = ["CMB CIB", "INBOUND", "OUTBOUND", "RESOLUTION"];

    function regroupRccTeams(service, team) {
        if (RCC_SUB_TEAMS.indexOf((service || "").toUpperCase()) !== -1) {
            return { service: "Rcc", team: service };
        }
        return { service: service, team: team };
    }

    function renderTeamResultsTree(filtered) {
        var container = document.getElementById("teamResultsTree");
        if (!filtered.length) { container.innerHTML = '<p class="text-center text-muted">Aucun résultat pour cette sélection.</p>'; return; }

        var tree = {};
        filtered.forEach(function (r) {
            var regrouped = regroupRccTeams(r.serviceName || "", r.activity || null); // "" = pas de service
            tree[regrouped.service] = tree[regrouped.service] || { teams: {}, direct: [] };
            var svc = tree[regrouped.service];
            if (regrouped.team) {
                svc.teams[regrouped.team] = svc.teams[regrouped.team] || [];
                svc.teams[regrouped.team].push(r);
            } else {
                svc.direct.push(r);
            }
        });

        container.innerHTML = summaryCardsHtml(filtered) +
            Object.keys(tree).sort().map(function (service) {
                var svc = tree[service];
                var teams = svc.teams;
                var serviceAgents = Object.values(teams).reduce(function (acc, a) { return acc.concat(a); }, []).concat(svc.direct);
                var teamHtml = Object.keys(teams).sort().map(function (team) {
                    var agents = teams[team];
                    return '<div class="tr-team"><div class="tr-team-header"><span><i class="bi bi-chevron-right tr-chevron"></i> ' + escapeHtml(team) + '</span>' +
                        '<span class="badge bg-light text-dark">' + agents.length + '</span></div>' +
                        '<div class="tr-team-body">' + summaryCardsHtml(agents) + agentRowsHtml(agents) + '</div></div>';
                }).join("");
                // Agents sans équipe assignée — affichés directement, sans faux niveau "Sans équipe".
                var directHtml = svc.direct.length ? agentRowsHtml(svc.direct) : "";
                var content = teamHtml + directHtml;

                // Pas de service — pas de faux niveau "Sans service", contenu affiché directement.
                if (!service) return content;

                return '<div class="tr-branch"><div class="tr-branch-header"><span><i class="bi bi-chevron-right tr-chevron"></i> ' + escapeHtml(service) + '</span>' +
                    '<span class="badge bg-primary">' + serviceAgents.length + ' agent(s)</span></div>' +
                    '<div class="tr-branch-body">' + content + '</div></div>';
            }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".tr-branch-header"), function (h) {
            h.addEventListener("click", function () { h.parentElement.classList.toggle("open"); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".tr-team-header"), function (h) {
            h.addEventListener("click", function (evt) { evt.stopPropagation(); h.parentElement.classList.toggle("open"); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".tr-agent-row"), function (row) {
            row.addEventListener("click", function (evt) {
                evt.stopPropagation();
                var agent = filtered.filter(function (a) { return a.username === row.getAttribute("data-username"); })[0];
                if (agent) openTeamResultAgentDetail(agent);
            });
        });
    }

    function loadTeamResults() {
        var countryCode = document.getElementById("kpiImportCountry").value;
        var serviceCode = document.getElementById("kpiImportService").value;
        var countrySelect = document.getElementById("kpiImportCountry");
        var serviceSelect = document.getElementById("kpiImportService");
        var countryLabel = countrySelect.options[countrySelect.selectedIndex] ? countrySelect.options[countrySelect.selectedIndex].text : "";
        var serviceLabel = serviceSelect.options[serviceSelect.selectedIndex] ? serviceSelect.options[serviceSelect.selectedIndex].text : "";

        document.getElementById("teamResultsLabel").textContent =
            [countryLabel, serviceLabel].filter(function (s) { return s && s.indexOf("—") !== 0; }).join(" / ") || "toutes équipes";
        document.getElementById("teamResultsCard").style.display = "";

        getJson("/api/reporting/team?month=" + encodeURIComponent(document.getElementById("kpiImportMonth").value))
            .then(function (rows) {
                var filtered = rows.filter(function (r) {
                    var matchCountry = !countryCode || r.affiliateBranch === countryCode;
                    var matchService = !serviceCode || (r.serviceName && serviceLabel && r.serviceName === serviceLabel);
                    return matchCountry && matchService;
                });
                renderTeamResultsTree(filtered);
            })
            .catch(function (e) {
                document.getElementById("teamResultsTree").innerHTML =
                    '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
            });
    }

    function loadImportOptions() {
        getJson("/api/kb/countries").then(function (countries) {
            var options = '<option value="">— Filiale —</option>' +
                countries.map(function (c) { return '<option value="' + c.countryCode + '">' + escapeHtml(c.label) + '</option>'; }).join("");
            document.getElementById("kpiImportCountry").innerHTML = options;
        }).catch(function () {});
        getJson("/api/procedures/services").then(function (services) {
            var options = '<option value="">— Service —</option>' +
                services.map(function (s) { return '<option value="' + s.code + '">' + escapeHtml(s.name) + '</option>'; }).join("");
            document.getElementById("kpiImportService").innerHTML = options;
        }).catch(function () {});
    }

    var teamKpiImportTeam = "";

    function wireTeamKpiImport() {
        Array.prototype.forEach.call(document.querySelectorAll(".team-kpi-team-btn"), function (btn) {
            btn.addEventListener("click", function () {
                teamKpiImportTeam = btn.getAttribute("data-team");
                Array.prototype.forEach.call(document.querySelectorAll(".team-kpi-team-btn"), function (b) {
                    b.classList.toggle("active", b === btn);
                });
            });
        });

        document.getElementById("teamKpiImportBtn").addEventListener("click", function () {
            var fileInput = document.getElementById("teamKpiImportFile");
            var resultBox = document.getElementById("teamKpiImportResult");
            var file = fileInput.files[0];
            if (!file) { resultBox.textContent = "Choisissez un fichier."; resultBox.className = "small mt-2 text-danger"; return; }
            if (!teamKpiImportTeam) { resultBox.textContent = "Choisissez l'équipe/le pôle concerné."; resultBox.className = "small mt-2 text-danger"; return; }

            var formData = new FormData();
            formData.append("file", file);
            resultBox.textContent = "Import en cours…";
            resultBox.className = "small mt-2 text-muted";

            fetch("/api/team-kpi/import?team=" + encodeURIComponent(teamKpiImportTeam),
                { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (result) {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = result.valuesCreated + " valeur(s) importée(s) sur " + result.metricsProcessed + " métrique(s), " +
                        "équipe " + result.team + "." +
                        (result.targetsSkippedIntentionally ? " " + result.targetsSkippedIntentionally + " objectif(s) (TARGET) hors périmètre chronologique." : "");

                    var auditBox = document.createElement("div");
                    var total = result.numericCellsDetected || 0;
                    var pct = total > 0 ? Math.round((result.valuesCreated / total) * 100) : 100;
                    if (result.skippedValues && result.skippedValues.length > 0) {
                        auditBox.className = "small mt-2 alert alert-warning";
                        auditBox.innerHTML = '<strong>Audit de complétude : ' + pct + ' % capturé</strong> (' +
                            result.valuesCreated + ' / ' + total + ' valeurs numériques détectées).<br>' +
                            '<strong>' + result.skippedValues.length + ' valeur(s) NON capturée(s)</strong> :' +
                            '<ul class="mb-0 mt-1">' + result.skippedValues.map(function (s) {
                                return '<li>' + s.metricLabel + ' — ' + s.columnLabel + ' = « ' + s.rawValue + ' » : ' + s.reason + '</li>';
                            }).join("") + '</ul>';
                    } else {
                        auditBox.className = "small mt-2 text-success";
                        auditBox.innerHTML = '<i class="bi bi-check-circle-fill"></i> <strong>Audit de complétude : 100 % capturé</strong> (' +
                            total + ' / ' + total + ' valeurs) — aucune valeur perdue.';
                    }
                    resultBox.after(auditBox);

                    fileInput.value = "";
                    refreshTeamKpiTrendMetrics();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    var TEAM_LABELS_PERF = { INBOUND_VOICE: "Inbound Voix", INBOUND_MAIL: "Inbound Mail / Rafiki", CIB: "CIB", OUTBOUND: "Outbound" };

    function initTeamKpiTrend(profile) {
        var teamSelect = document.getElementById("teamKpiTrendTeam");
        var metricSelect = document.getElementById("teamKpiTrendMetric");

        if (profile === "TEAM_LEADER") {
            // Restreint à sa propre équipe — récupérée via l'endpoint déjà existant.
            getJson("/api/team-leader/my-team").then(function (result) {
                teamSelect.innerHTML = '<option value="' + result.team + '">' + (TEAM_LABELS_PERF[result.team] || result.team) + '</option>';
                refreshTeamKpiTrendMetrics();
            }).catch(function () {});
        } else {
            teamSelect.innerHTML = Object.keys(TEAM_LABELS_PERF).map(function (t) {
                return '<option value="' + t + '">' + TEAM_LABELS_PERF[t] + '</option>';
            }).join("");
            // Pré-sélection depuis le lien "Tendance du pôle" de la page Reporting (?teamHint=INBOUND) —
            // correspondance approchée uniquement, jamais aveugle : l'utilisateur voit la sélection
            // faite et peut la corriger via le menu si le libellé ne correspondait pas exactement
            // (ex. "INBOUND" — provenant du service affiché — peut désigner Voix ou Mail/Rafiki).
            var params = new URLSearchParams(window.location.search);
            var hint = params.get("teamHint");
            if (hint) {
                var hintNorm = hint.toUpperCase();
                var bestMatch = Object.keys(TEAM_LABELS_PERF).find(function (code) {
                    return code.indexOf(hintNorm) !== -1 || hintNorm.indexOf(code.split("_")[0]) !== -1;
                });
                if (bestMatch) teamSelect.value = bestMatch;
            }
            refreshTeamKpiTrendMetrics();
        }

        teamSelect.addEventListener("change", refreshTeamKpiTrendMetrics);
        metricSelect.addEventListener("change", renderTeamKpiTrendChart);
    }

    function refreshTeamKpiTrendMetrics() {
        var teamSelect = document.getElementById("teamKpiTrendTeam");
        var metricSelect = document.getElementById("teamKpiTrendMetric");
        var team = teamSelect.value;
        if (!team) return;

        getJson("/api/team-kpi/metrics?team=" + encodeURIComponent(team)).then(function (metrics) {
            if (!metrics.length) {
                metricSelect.innerHTML = "";
                document.getElementById("teamKpiTrendChart").innerHTML = '<p class="text-muted small">Aucune donnée importée pour cette équipe pour l\'instant.</p>';
                return;
            }
            metricSelect.innerHTML = metrics.map(function (m) { return '<option value="' + m + '">' + m + '</option>'; }).join("");
            renderTeamKpiTrendChart();
        }).catch(function () {});
    }

    function renderTeamKpiTrendChart() {
        var team = document.getElementById("teamKpiTrendTeam").value;
        var metric = document.getElementById("teamKpiTrendMetric").value;
        var container = document.getElementById("teamKpiTrendChart");
        if (!team || !metric) return;

        getJson("/api/team-kpi/series?team=" + encodeURIComponent(team) + "&metricCode=" + encodeURIComponent(metric))
            .then(function (series) {
                if (!series.points.length) { container.innerHTML = '<p class="text-muted small">Aucune donnée.</p>'; return; }
                var maxVal = Math.max.apply(null, series.points.map(function (p) { return Number(p.value); }));
                container.innerHTML = '<div class="d-flex align-items-end gap-2" style="height:160px;">' +
                    series.points.map(function (p) {
                        var pct = maxVal > 0 ? Math.max(4, Math.round((Number(p.value) / maxVal) * 100)) : 4;
                        return '<div class="text-center flex-fill">' +
                            '<div class="small">' + p.value + '</div>' +
                            '<div style="height:' + pct + '%;background:#0057B8;border-radius:4px 4px 0 0;min-height:4px;"></div>' +
                            '<div class="small text-muted mt-1">' + p.month + '</div>' +
                        '</div>';
                    }).join("") + '</div>';
            }).catch(function () {
                container.innerHTML = '<p class="text-danger small">Erreur de chargement.</p>';
            });
    }

    function wireImports() {
        document.getElementById("kpiImportMonth").value = currentMonthValue();
        loadImportOptions();

        var kpiImportTeam = "";

        Array.prototype.forEach.call(document.querySelectorAll(".kpi-team-btn"), function (btn) {
            btn.addEventListener("click", function () {
                kpiImportTeam = btn.getAttribute("data-team");
                Array.prototype.forEach.call(document.querySelectorAll(".kpi-team-btn"), function (b) {
                    b.classList.toggle("active", b === btn);
                });
            });
        });

        function runKpiImport(url, formData, resultBox, fileInput) {
            resultBox.textContent = "Import en cours…";
            resultBox.className = "small mt-2 text-muted";

            fetch(url, { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (result) {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = result.entriesCreated + " valeur(s) importée(s) sur " + result.rowsProcessed + " ligne(s)." +
                        (result.usersAutoCreated ? " " + result.usersAutoCreated + " compte(s) agent créé(s) automatiquement." : "") +
                        (result.usersLinkedToTeam ? " " + result.usersLinkedToTeam + " agent(s) rattaché(s) à la filiale/service/équipe choisie." : "") +
                        (result.unknownMatricules.length ? " Non reconnus : " + result.unknownMatricules.join(", ") : "");

                    // Audit de complétude — exact, jamais une approximation : chaque valeur
                    // numérique détectée dans le fichier/la capture est soit capturée, soit
                    // listée ici avec sa raison précise.
                    var auditBox = document.createElement("div");
                    var total = result.numericCellsDetected || 0;
                    var pct = total > 0 ? Math.round((result.entriesCreated / total) * 100) : 100;
                    if (result.skippedValues && result.skippedValues.length > 0) {
                        auditBox.className = "small mt-2 alert alert-warning";
                        auditBox.innerHTML = '<strong>Audit de complétude : ' + pct + ' % capturé</strong> (' +
                            result.entriesCreated + ' / ' + total + ' valeurs numériques détectées).<br>' +
                            '<strong>' + result.skippedValues.length + ' valeur(s) NON capturée(s)</strong> :' +
                            '<ul class="mb-0 mt-1">' + result.skippedValues.slice(0, 30).map(function (s) {
                                return '<li>' + s.rowLabel + ' — ' + s.columnLabel + ' = « ' + s.rawValue + ' » : ' + s.reason + '</li>';
                            }).join("") + '</ul>' +
                            (result.skippedValues.length > 30 ? '<div class="mt-1">… et ' + (result.skippedValues.length - 30) + ' autre(s).</div>' : '');
                    } else {
                        auditBox.className = "small mt-2 text-success";
                        auditBox.innerHTML = '<i class="bi bi-check-circle-fill"></i> <strong>Audit de complétude : 100 % capturé</strong> (' +
                            total + ' / ' + total + ' valeurs numériques détectées) — aucune valeur perdue.';
                    }
                    resultBox.after(auditBox);

                    var previewTable = renderImportPreviewWide(resultBox, result.preview, result.previewTruncated);
                    renderDeleteImportButton(previewTable || resultBox, result.importBatchId, function () {
                        resultBox.textContent = "Import supprimé.";
                        if (previewTable) previewTable.remove();
                        auditBox.remove();
                        load();
                        loadTeamResults();
                    });
                    if (result.aiReview) {
                        var reviewBox = document.createElement("div");
                        reviewBox.className = "small mt-2 alert alert-warning";
                        reviewBox.textContent = "À vérifier (analyse Copilot Studio) : " + result.aiReview;
                        resultBox.after(reviewBox);
                    }
                    if (fileInput) fileInput.value = "";
                    load();
                    loadTeamResults();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        }

        document.getElementById("kpiImportBtn").addEventListener("click", function () {
            var fileInput = document.getElementById("kpiImportFile");
            var resultBox = document.getElementById("kpiImportResult");
            var file = fileInput.files[0];
            if (!file) { resultBox.textContent = "Choisissez un fichier."; resultBox.className = "small mt-2 text-danger"; return; }

            var formData = new FormData();
            formData.append("file", file);

            var url = "/api/kpi/manual-entries/import?period=" + document.getElementById("kpiImportMonth").value +
                "&serviceCode=" + encodeURIComponent(document.getElementById("kpiImportService").value) +
                "&countryCode=" + encodeURIComponent(document.getElementById("kpiImportCountry").value) +
                "&team=" + encodeURIComponent(kpiImportTeam);

            runKpiImport(url, formData, resultBox, fileInput);
        });

        // ===== Import KPI par capture d'écran (vision Claude) — mêmes filiale/service/équipe que l'import Excel =====
        var screenshotFileInput = document.getElementById("kpiScreenshotFile");
        var screenshotPickBtn = document.getElementById("kpiScreenshotPickBtn");
        var screenshotUpdateBtn = document.getElementById("kpiScreenshotUpdateBtn");
        var screenshotFileName = document.getElementById("kpiScreenshotFileName");

        screenshotPickBtn.addEventListener("click", function () { screenshotFileInput.click(); });
        screenshotFileInput.addEventListener("change", function () {
            var file = screenshotFileInput.files[0];
            screenshotFileName.textContent = file ? file.name : "";
            screenshotUpdateBtn.style.display = file ? "" : "none";
        });

        screenshotUpdateBtn.addEventListener("click", function () {
            var file = screenshotFileInput.files[0];
            var resultBox = document.getElementById("kpiImportResult");
            if (!file) { resultBox.textContent = "Choisissez une capture d'écran."; resultBox.className = "small mt-2 text-danger"; return; }

            var formData = new FormData();
            formData.append("file", file);

            var url = "/api/kpi/manual-entries/import-screenshot?period=" + document.getElementById("kpiImportMonth").value +
                "&serviceCode=" + encodeURIComponent(document.getElementById("kpiImportService").value) +
                "&countryCode=" + encodeURIComponent(document.getElementById("kpiImportCountry").value) +
                "&team=" + encodeURIComponent(kpiImportTeam);

            runKpiImport(url, formData, resultBox, screenshotFileInput);
            screenshotFileName.textContent = "";
            screenshotUpdateBtn.style.display = "none";
        });
    }

    function wireManualEntry() {
        document.getElementById("manualKpiSaveBtn").addEventListener("click", function () {
            var resultBox = document.getElementById("manualKpiResult");
            var username = document.getElementById("manualKpiUsername").value.trim();
            var metric = document.getElementById("manualKpiMetric").value.trim();
            var value = document.getElementById("manualKpiValue").value;
            var date = document.getElementById("manualKpiDate").value;
            if (!username || !metric || value === "" || !date) {
                resultBox.className = "small mt-2 text-danger";
                resultBox.textContent = "Tous les champs sont obligatoires.";
                return;
            }
            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Enregistrement…";
            RccApi.sendJson("/api/kpi/manual-entries", "POST", {
                subjectMatricule: username,
                metricCode: metric.toUpperCase(),
                metricValue: Number(value),
                periodDate: date
            }).then(function () {
                resultBox.className = "small mt-2 text-success";
                resultBox.textContent = "Valeur enregistrée.";
                document.getElementById("manualKpiValue").value = "";
                load();
                loadManualKpiHistory();
            }).catch(function (e) {
                resultBox.className = "small mt-2 text-danger";
                resultBox.textContent = "Erreur : " + e.message;
            });
        });

        document.getElementById("manualShiftSaveBtn").addEventListener("click", function () {
            var resultBox = document.getElementById("manualShiftResult");
            var username = document.getElementById("manualShiftUsername").value.trim();
            var eventType = document.getElementById("manualShiftType").value;
            var dateTime = document.getElementById("manualShiftDateTime").value;
            if (!username || !dateTime) {
                resultBox.className = "small mt-2 text-danger";
                resultBox.textContent = "Identifiant et date/heure sont obligatoires.";
                return;
            }
            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Enregistrement…";
            RccApi.sendJson("/api/shift/manual", "POST", {
                username: username,
                eventType: eventType,
                occurredAt: dateTime
            }).then(function () {
                resultBox.className = "small mt-2 text-success";
                resultBox.textContent = "Événement enregistré.";
                document.getElementById("manualShiftDateTime").value = "";
                if (myShiftLoaded) loadMyShiftForCurrentView();
            }).catch(function (e) {
                resultBox.className = "small mt-2 text-danger";
                resultBox.textContent = "Erreur : " + e.message;
            });
        });
    }

    var myShiftLoaded = false;

    function todayIso() {
        var d = new Date();
        var tz = d.getTimezoneOffset() * 60000;
        return new Date(d.getTime() - tz).toISOString().slice(0, 10);
    }

    function loadMyShift() {
        var dateStr = document.getElementById("myShiftDate").value;
        Promise.all([
            getJson("/api/shift/me/events?date=" + dateStr),
            getJson("/api/shift/me/leave-days?from=" + dateStr + "&to=" + dateStr).catch(function () { return []; })
        ]).then(function (results) {
            var isOnLeave = results[1] && results[1].indexOf(dateStr) !== -1;
            renderMyShiftBand(results[0], isOnLeave);
        }).catch(function (e) {
            document.getElementById("myShiftSummary").textContent = "Erreur : " + e.message;
        });
    }

    /** Reconstruit des segments travail/pause à partir des événements bruts (LOGIN, PAUSE_START/END, LUNCH_START/END, SHIFT_END). */
    var DAY_START_MIN = 6 * 60, DAY_END_MIN = 22 * 60; // fenêtre affichée : 06h00 - 22h00

    function minutesOfDay(iso) {
        var d = new Date(iso);
        return d.getHours() * 60 + d.getMinutes();
    }

    /**
     * Segments travail/pause/déconnexion d'une journée — via RccShiftTimeline (même logique que
     * le serveur et le Suivi de shift) : une reconnexion le même jour ne remet plus le compteur
     * à zéro et l'absence apparaît comme une période « Déconnecté ».
     */
    function computeDaySegments(events, isToday) {
        var totalMin = DAY_END_MIN - DAY_START_MIN;
        var openEnd = isToday ? new Date() : null;
        var analysis = RccShiftTimeline.analyze(events, openEnd);
        var segments = [];
        var totalWorkMin = 0, totalPauseMin = 0, totalOfflineMin = 0;
        analysis.segments.forEach(function (s) {
            var start = minutesOfDay(s.from.toISOString());
            // Segment encore ouvert : jusqu'à maintenant pour aujourd'hui, fin de fenêtre sinon.
            var end = s.to ? minutesOfDay(s.to.toISOString()) : (isToday ? Math.min(minutesOfDay(new Date().toISOString()), DAY_END_MIN) : DAY_END_MIN);
            if (end <= start) return;
            segments.push({ start: start, end: end, type: s.type });
            if (s.type === "work") totalWorkMin += end - start;
            else if (s.type === "pause") totalPauseMin += end - start;
            else totalOfflineMin += end - start;
        });
        return { segments: segments, totalWorkMin: totalWorkMin, totalPauseMin: totalPauseMin,
            totalOfflineMin: totalOfflineMin, absences: analysis.absences, totalMin: totalMin };
    }

    function fmtMin(m) {
        var h = Math.floor(m / 60), mm = m % 60;
        return h + "h" + String(mm).padStart(2, "0");
    }

    function renderMyShiftBand(events, isOnLeave) {
        var wrap = document.getElementById("myShiftBarWrap");
        var ruler = document.getElementById("myShiftRuler");
        var summary = document.getElementById("myShiftSummary");
        wrap.innerHTML = "";
        ruler.innerHTML = "";

        if (isOnLeave) {
            wrap.style.background = "#e5e7eb";
            wrap.innerHTML = '<div class="d-flex align-items-center justify-content-center h-100 text-muted small"><i class="bi bi-airplane"></i>&nbsp;Congé / absence approuvé(e)</div>';
            summary.textContent = "Journée de congé approuvée — aucun pointage attendu.";
            for (var h = DAY_START_MIN; h <= DAY_END_MIN; h += 120) {
                var label = document.createElement("span");
                label.textContent = String(Math.floor(h / 60)).padStart(2, "0") + "h";
                ruler.appendChild(label);
            }
            return;
        }
        wrap.style.background = "";

        if (!events.length) {
            summary.textContent = "Aucun événement enregistré ce jour-là.";
            return;
        }

        var todayStr = new Date().toISOString().slice(0, 10);
        var isToday = document.getElementById("myShiftDate").value === todayStr;
        var computed = computeDaySegments(events, isToday);

        function pct(min) {
            return Math.max(0, Math.min(100, ((min - DAY_START_MIN) / computed.totalMin) * 100));
        }

        computed.segments.forEach(function (seg) {
            var el = document.createElement("div");
            el.className = "my-shift-bar-segment my-shift-bar-seg-" + seg.type;
            el.style.left = pct(seg.start) + "%";
            el.style.width = (pct(seg.end) - pct(seg.start)) + "%";
            el.title = seg.type === "work" ? "Travail" : seg.type === "offline" ? "Déconnecté" : "Pause";
            wrap.appendChild(el);
        });

        for (var h = DAY_START_MIN; h <= DAY_END_MIN; h += 120) {
            var label = document.createElement("span");
            label.textContent = String(Math.floor(h / 60)).padStart(2, "0") + "h";
            ruler.appendChild(label);
        }

        summary.textContent = "Temps de travail : " + fmtMin(computed.totalWorkMin) + " — Pauses : " + fmtMin(computed.totalPauseMin) +
            (computed.absences.length
                ? " — Déconnexions : " + computed.absences.map(RccShiftTimeline.describeAbsence).join(", ")
                : "");
    }

    var myShiftView = "day";

    function startOfWeek(dateStr) {
        var d = new Date(dateStr);
        var day = d.getDay(); // 0 = dimanche
        var diff = day === 0 ? -6 : 1 - day; // lundi comme premier jour
        d.setDate(d.getDate() + diff);
        return d;
    }

    function fmtDayLabel(d) {
        return d.toLocaleDateString("fr-FR", { weekday: "short", day: "2-digit", month: "2-digit" });
    }

    function renderRangeRows(eventsByDay, todayStr, leaveDates) {
        var container = document.getElementById("myShiftRangeRows");
        var days = Object.keys(eventsByDay).sort();
        if (!days.length) { container.innerHTML = '<p class="text-muted text-center">Aucun événement sur cette période.</p>'; return; }

        var grandWorkMin = 0, grandPauseMin = 0;

        container.innerHTML = days.map(function (dayStr) {
            var label = fmtDayLabel(new Date(dayStr));

            if (leaveDates && leaveDates.indexOf(dayStr) !== -1) {
                return '<div class="shift-timeline-row">' +
                    '<div class="shift-agent-label">' + label + '</div>' +
                    '<div class="shift-bar-wrap" style="background:#e5e7eb;display:flex;align-items:center;justify-content:center;">' +
                    '<span class="small text-muted"><i class="bi bi-airplane"></i> Congé / absence</span></div>' +
                    '</div>';
            }

            var computed = computeDaySegments(eventsByDay[dayStr], dayStr === todayStr);
            grandWorkMin += computed.totalWorkMin;
            grandPauseMin += computed.totalPauseMin;

            function pct(min) { return Math.max(0, Math.min(100, ((min - DAY_START_MIN) / computed.totalMin) * 100)); }
            var segmentsHtml = computed.segments.map(function (seg) {
                return '<div class="shift-bar-segment shift-bar-seg-' + seg.type + '" style="left:' + pct(seg.start) + '%;width:' + (pct(seg.end) - pct(seg.start)) + '%;" title="' + (seg.type === "work" ? "Travail" : seg.type === "offline" ? "Déconnecté" : "Pause") + '"></div>';
            }).join("");

            var rowLabel = label +
                (computed.totalWorkMin || computed.totalPauseMin ? " — " + fmtMin(computed.totalWorkMin) + " travail" : "");

            return '<div class="shift-timeline-row">' +
                '<div class="shift-agent-label">' + rowLabel + '</div>' +
                '<div class="shift-bar-wrap">' + segmentsHtml + '</div>' +
                '</div>';
        }).join("");

        document.getElementById("myShiftRangeSummary").textContent =
            "Total sur la période : " + fmtMin(grandWorkMin) + " de travail, " + fmtMin(grandPauseMin) + " de pause.";
    }

    function loadMyShiftRange(from, to) {
        var todayStr = todayIso();
        Promise.all([
            getJson("/api/shift/me/events/range?from=" + from + "&to=" + to),
            getJson("/api/shift/me/leave-days?from=" + from + "&to=" + to).catch(function () { return []; })
        ]).then(function (results) {
            var events = results[0];
            var leaveDates = results[1] || [];
            var eventsByDay = {};
            // Toujours afficher chaque jour de la plage, même sans événement
            var cursor = new Date(from);
            var end = new Date(to);
            while (cursor <= end) {
                eventsByDay[cursor.toISOString().slice(0, 10)] = [];
                cursor.setDate(cursor.getDate() + 1);
            }
            events.forEach(function (e) {
                var day = e.occurredAt.slice(0, 10);
                if (!eventsByDay[day]) eventsByDay[day] = [];
                eventsByDay[day].push(e);
            });
            renderRangeRows(eventsByDay, todayStr, leaveDates);
        }).catch(function (e) {
            document.getElementById("myShiftRangeRows").innerHTML = '<p class="text-danger text-center">Erreur : ' + e.message + '</p>';
        });
    }

    function loadMyShiftForCurrentView() {
        var dateStr = document.getElementById("myShiftDate").value;
        if (myShiftView === "day") {
            loadMyShift();
            return;
        }
        if (myShiftView === "team") {
            loadMyShiftTeam(dateStr);
            return;
        }
        var from, to;
        if (myShiftView === "week") {
            var weekStart = startOfWeek(dateStr);
            var weekEnd = new Date(weekStart);
            weekEnd.setDate(weekEnd.getDate() + 6);
            from = weekStart.toISOString().slice(0, 10);
            to = weekEnd.toISOString().slice(0, 10);
        } else { // month
            var d = new Date(dateStr);
            var monthStart = new Date(d.getFullYear(), d.getMonth(), 1);
            var monthEnd = new Date(d.getFullYear(), d.getMonth() + 1, 0);
            from = monthStart.toISOString().slice(0, 10);
            to = monthEnd.toISOString().slice(0, 10);
        }
        loadMyShiftRange(from, to);
    }

    function loadMyShiftTeam(dateStr) {
        var team = document.getElementById("myShiftTeamSelect").value;
        var container = document.getElementById("myShiftTeamRows");
        var url = "/api/shift/team?date=" + dateStr + (team ? "&team=" + encodeURIComponent(team) : "");
        var isToday = dateStr === todayIso();

        getJson(url).then(function (events) {
            if (!events.length) {
                container.innerHTML = '<p class="text-muted text-center">Aucun événement pour cette équipe ce jour-là' +
                    (team ? "" : " (ou aucune équipe renseignée sur votre profil)") + '.</p>';
                return;
            }
            var byAgent = {};
            events.forEach(function (e) {
                byAgent[e.username] = byAgent[e.username] || { label: e.userFullName || e.username, events: [] };
                byAgent[e.username].events.push(e);
            });

            container.innerHTML = Object.keys(byAgent).sort(function (a, b) {
                return byAgent[a].label.localeCompare(byAgent[b].label);
            }).map(function (username) {
                var agent = byAgent[username];
                var computed = computeDaySegments(agent.events, isToday);
                function pct(min) { return Math.max(0, Math.min(100, ((min - DAY_START_MIN) / computed.totalMin) * 100)); }
                var segmentsHtml = computed.segments.map(function (seg) {
                    return '<div class="shift-bar-segment shift-bar-seg-' + seg.type + '" style="left:' + pct(seg.start) + '%;width:' + (pct(seg.end) - pct(seg.start)) + '%;"></div>';
                }).join("");
                return '<div class="shift-timeline-row">' +
                    '<div class="shift-agent-label">' + escapeHtml(agent.label) + '</div>' +
                    '<div class="shift-bar-wrap">' + segmentsHtml + '</div>' +
                    '</div>';
            }).join("");
        }).catch(function (e) {
            container.innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function wireMyShiftTab() {
        var dateInput = document.getElementById("myShiftDate");
        dateInput.value = todayIso();

        document.getElementById("myShiftPrevDayBtn").addEventListener("click", function () {
            var d = new Date(dateInput.value);
            var step = myShiftView === "month" ? 30 : myShiftView === "week" ? 7 : 1;
            d.setDate(d.getDate() - step);
            dateInput.value = d.toISOString().slice(0, 10);
            loadMyShiftForCurrentView();
        });
        document.getElementById("myShiftNextDayBtn").addEventListener("click", function () {
            var d = new Date(dateInput.value);
            var step = myShiftView === "month" ? 30 : myShiftView === "week" ? 7 : 1;
            d.setDate(d.getDate() + step);
            dateInput.value = d.toISOString().slice(0, 10);
            loadMyShiftForCurrentView();
        });
        dateInput.addEventListener("change", loadMyShiftForCurrentView);

        Array.prototype.forEach.call(document.querySelectorAll("#myShiftViewToggle button"), function (btn) {
            btn.addEventListener("click", function () {
                Array.prototype.forEach.call(document.querySelectorAll("#myShiftViewToggle button"), function (b) {
                    b.classList.toggle("active", b === btn);
                    b.classList.toggle("btn-secondary", b === btn);
                    b.classList.toggle("btn-outline-secondary", b !== btn);
                });
                myShiftView = btn.getAttribute("data-view");
                document.getElementById("myShiftDayView").style.display = myShiftView === "day" ? "" : "none";
                document.getElementById("myShiftRangeView").style.display = (myShiftView === "week" || myShiftView === "month") ? "" : "none";
                document.getElementById("myShiftTeamView").style.display = myShiftView === "team" ? "" : "none";
                document.getElementById("myShiftTeamSelect").classList.toggle("d-none", myShiftView !== "team");
                loadMyShiftForCurrentView();
            });
        });

        document.getElementById("shiftTabBtn").addEventListener("click", function () {
            if (!myShiftLoaded) { myShiftLoaded = true; loadMyShiftForCurrentView(); }
        });
    }

    function init() {
        document.getElementById("monthInput").value = currentMonthValue();
        document.getElementById("monthForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            load();
        });
        wireImports();
        wireTeamKpiImport();
        wireManualEntry();
        load();
        loadManualKpiHistory();

        window.RccSession.init().then(function (session) {
            var isSupervisor = session && (session.profile === "QA" || session.profile === "ADMIN");
            document.getElementById("importCard").style.display = isSupervisor ? "" : "none";

            // Import KPI par pôle — QA/RH/Superviseur/Admin (même accès que requireReviewer côté backend).
            var canImportTeamKpi = session && ["QA", "ADMIN", "RH", "SUPERVISOR"].indexOf(session.profile) !== -1;
            document.getElementById("teamKpiImportCard").style.display = canImportTeamKpi ? "" : "none";
            // Tendance pôle — accès élargi au Team Leader, restreint à sa propre équipe côté backend.
            var canViewTeamKpiTrend = session && ["QA", "ADMIN", "RH", "SUPERVISOR", "TEAM_LEADER"].indexOf(session.profile) !== -1;
            document.getElementById("teamKpiTrendCard").style.display = canViewTeamKpiTrend ? "" : "none";
            if (canViewTeamKpiTrend) initTeamKpiTrend(session.profile);

            var canCorrect = session && (session.profile === "QA" || session.profile === "ADMIN" || session.profile === "RH");
            document.getElementById("manualEntryCard").style.display = canCorrect ? "" : "none";
            document.getElementById("manualKpiColumn").style.display = (session && (session.profile === "QA" || session.profile === "ADMIN")) ? "" : "none";

            var canDrillTeam = session && (session.profile === "QA" || session.profile === "ADMIN" || session.profile === "RH");
            if (canDrillTeam) {
                ["presenceCard", "qualityCard", "globalCard", "evalCard"].forEach(function (id) {
                    var card = document.getElementById(id);
                    card.classList.add("clickable-active");
                    card.addEventListener("click", function () {
                        loadTeamResults();
                        document.getElementById("teamResultsCard").scrollIntoView({ behavior: "smooth" });
                    });
                });

                getJson("/api/reporting/team?month=" + currentMonthValue()).then(function (rows) {
                    var teams = Array.from(new Set(rows.map(function (r) { return r.activity; }).filter(Boolean))).sort();
                    var select = document.getElementById("myShiftTeamSelect");
                    select.innerHTML = '<option value="">— Choisir une équipe —</option>' +
                        teams.map(function (t) { return '<option value="' + escapeHtml(t) + '">' + escapeHtml(t) + '</option>'; }).join("");
                    select.addEventListener("change", function () { loadMyShiftForCurrentView(); });
                }).catch(function () {});
            }
        });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
