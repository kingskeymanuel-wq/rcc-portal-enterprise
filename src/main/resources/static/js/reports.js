"use strict";

(function () {

    var escapeHtml = RccApi.escapeHtml;

    var getJson = RccApi.getJson;

    var currentProfile = null;

    function currentMonthValue() {
        var now = new Date();
        return now.getFullYear() + "-" + String(now.getMonth() + 1).padStart(2, "0");
    }

    // Ces "services" sont en réalité des sous-équipes du Rcc — on les rebascule
    // pour qu'elles apparaissent comme des équipes SOUS Rcc plutôt que comme
    // des services séparés au même niveau.
    var RCC_SUB_TEAMS = ["CMB CIB", "INBOUND", "OUTBOUND", "RESOLUTION"];

    function regroupRccTeams(service, team) {
        if (RCC_SUB_TEAMS.indexOf((service || "").toUpperCase()) !== -1) {
            return { service: "Rcc", team: service };
        }
        return { service: service, team: team };
    }
    function groupRows(rows) {
        var tree = {};
        rows.forEach(function (r) {
            var branch = r.affiliateBranch || ""; // "" = pas de filiale — jamais de faux libellé "Sans filiale"
            var regrouped = regroupRccTeams(r.serviceName || "", r.activity || null); // "" = pas de service
            tree[branch] = tree[branch] || {};
            tree[branch][regrouped.service] = tree[branch][regrouped.service] || { teams: {}, direct: [] };
            var svc = tree[branch][regrouped.service];
            if (regrouped.team) {
                svc.teams[regrouped.team] = svc.teams[regrouped.team] || [];
                svc.teams[regrouped.team].push(r);
            } else {
                // Pas d'équipe assignée — affiché directement sous le service, sans
                // créer de faux niveau "Sans équipe".
                svc.direct.push(r);
            }
        });
        return tree;
    }

    function fmtPct(v) { return v != null ? v + " %" : "—"; }

    function renderTeam(rows) {
        var container = document.getElementById("reportingTree");
        if (!rows.length) {
            container.innerHTML = '<p class="text-center text-muted">Aucune donnée pour ce mois.</p>';
            return;
        }
        var tree = groupRows(rows);

        function agentRowHtml(a, serviceKey) {
            var needsFix = currentProfile === "ADMIN" && (!serviceKey || !a.activity);
            var m = a.kpiMetrics || {};
            var fixBtn = needsFix && a.userId
                ? '<button class="btn btn-sm btn-outline-warning fix-org-btn" data-user-id="' + a.userId + '" data-username="' + escapeHtml(a.username) + '" data-name="' + escapeHtml(a.userFullName || a.username) + '" title="Corriger équipe/service"><i class="bi bi-pencil"></i></button>'
                : "";
            return '<tr class="agent-row" style="cursor:pointer;" data-username="' + escapeHtml(a.username) + '">' +
                '<td>' + escapeHtml(a.userFullName || a.username) + '</td>' +
                '<td>' + (m.SCORE_QA != null ? m.SCORE_QA : "—") + '</td>' +
                '<td>' + (m.SCORE_EVALUATION != null ? m.SCORE_EVALUATION + " %" : "—") + '</td>' +
                '<td>' + (m.INTERACTIONS != null ? m.INTERACTIONS : "—") + '</td>' +
                '<td>' + (m.PRODUCTIVITE != null ? m.PRODUCTIVITE : "—") + '</td>' +
                '<td>' + (m.NOTE_QA != null ? m.NOTE_QA : "—") + '</td>' +
                '<td>' + (m.TARGET != null ? m.TARGET : "—") + '</td>' +
                '<td>' + fmtPct(a.presenceRate) + '</td>' +
                '<td>' + fmtPct(a.performanceGlobale) + '</td>' +
                '<td onclick="event.stopPropagation();">' + fixBtn + '</td>' +
                '</tr>';
        }

        function agentsTableHtml(agents, serviceKey) {
            return '<table class="table table-sm table-hover mb-0">' +
                '<thead><tr><th>Agent</th><th>Score QA</th><th>Score évaluation</th><th>Interactions</th><th>Productivité</th><th>Note QA</th><th>Target</th><th>Présence</th><th>Performance</th><th></th></tr></thead>' +
                '<tbody>' + agents.map(function (a) { return agentRowHtml(a, serviceKey); }).join("") + '</tbody></table>';
        }

        function teamBlockHtml(team, agents, serviceKey) {
            // Pas de filtre de rôle ici — cette page entière est déjà réservée à
            // QA/RH/Superviseur/Admin côté serveur (voir ReportingController.requireReviewer).
            var trendLink = '<a href="/performance?teamHint=' + encodeURIComponent(team) + '#teamKpiTrendCard" class="small ms-2" ' +
                  'onclick="event.stopPropagation();" title="Voir la tendance du pôle importée (si disponible)">' +
                  '<i class="bi bi-graph-up"></i> Tendance du pôle</a>';
            return '<div class="rpt-team">' +
                '<div class="rpt-team-header"><span><i class="bi bi-chevron-right rpt-chevron"></i> ' + escapeHtml(team) + trendLink + '</span>' +
                '<span class="badge bg-light text-dark">' + agents.length + '</span></div>' +
                '<div class="rpt-team-body">' + agentsTableHtml(agents, serviceKey) + '</div></div>';
        }

        function serviceContentHtml(svc, serviceKey) {
            var teamHtml = Object.keys(svc.teams).sort().map(function (team) {
                return teamBlockHtml(team, svc.teams[team], serviceKey);
            }).join("");
            var directHtml = svc.direct.length ? '<div class="rpt-team-body">' + agentsTableHtml(svc.direct, serviceKey) + '</div>' : "";
            return teamHtml + directHtml;
        }

        container.innerHTML = Object.keys(tree).sort().map(function (branch) {
            var services = tree[branch];
            var branchCount = Object.values(services).reduce(function (n, svc) {
                var teamsCount = Object.values(svc.teams).reduce(function (m, agents) { return m + agents.length; }, 0);
                return n + teamsCount + svc.direct.length;
            }, 0);

            var serviceHtml = Object.keys(services).sort().map(function (service) {
                var svc = services[service];
                var serviceCount = Object.values(svc.teams).reduce(function (m, agents) { return m + agents.length; }, 0) + svc.direct.length;
                var content = serviceContentHtml(svc, service);

                // Pas de service — pas de faux niveau "Sans service", contenu affiché directement.
                if (!service) return content;

                return '<div class="rpt-service">' +
                    '<div class="rpt-service-header"><span><i class="bi bi-chevron-right rpt-chevron"></i> ' + escapeHtml(service) + '</span>' +
                    '<span class="badge bg-secondary">' + serviceCount + '</span></div>' +
                    '<div class="rpt-service-body">' + content + '</div></div>';
            }).join("");

            // Pas de filiale — pas de faux niveau "Sans filiale", contenu affiché directement.
            if (!branch) return serviceHtml;

            return '<div class="rpt-branch">' +
                '<div class="rpt-branch-header"><span><i class="bi bi-chevron-right rpt-chevron"></i> <i class="bi bi-building"></i> ' + escapeHtml(branch) + '</span>' +
                '<span class="badge bg-primary">' + branchCount + ' agent(s)</span></div>' +
                '<div class="rpt-branch-body">' + serviceHtml + '</div></div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".rpt-branch-header"), function (h) {
            h.addEventListener("click", function () { h.parentElement.classList.toggle("open"); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".rpt-service-header"), function (h) {
            h.addEventListener("click", function (evt) { evt.stopPropagation(); h.parentElement.classList.toggle("open"); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".rpt-team-header"), function (h) {
            h.addEventListener("click", function (evt) { evt.stopPropagation(); h.parentElement.classList.toggle("open"); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".agent-row"), function (row) {
            row.addEventListener("click", function (evt) {
                evt.stopPropagation();
                var agent = rows.filter(function (r) { return r.username === row.getAttribute("data-username"); })[0];
                if (agent) openAgentDetail(agent);
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".fix-org-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                openFixOrgModal(btn.getAttribute("data-user-id"), btn.getAttribute("data-name"));
            });
        });
    }

    var fixOrgServicesLoaded = false;
    function openFixOrgModal(userId, name) {
        document.getElementById("fixOrgName").textContent = name;
        document.getElementById("fixOrgUserId").value = userId;
        document.getElementById("fixOrgActivity").value = "";
        document.getElementById("fixOrgResult").textContent = "";
        var select = document.getElementById("fixOrgService");

        if (!fixOrgServicesLoaded) {
            getJson("/api/admin/services").then(function (services) {
                fixOrgServicesLoaded = true;
                services.forEach(function (s) {
                    var opt = document.createElement("option");
                    opt.value = s.id;
                    opt.textContent = s.name;
                    select.appendChild(opt);
                });
            }).catch(function (e) { console.error(e); });
        }

        new bootstrap.Modal(document.getElementById("fixOrgModal")).show();
    }

    function wireFixOrgModal() {
        var saveBtn = document.getElementById("fixOrgSaveBtn");
        if (!saveBtn) return;
        saveBtn.addEventListener("click", function () {
            var userId = document.getElementById("fixOrgUserId").value;
            var activity = document.getElementById("fixOrgActivity").value.trim();
            var serviceId = document.getElementById("fixOrgService").value;
            var resultBox = document.getElementById("fixOrgResult");

            var requests = [];
            if (activity) {
                requests.push(fetch("/api/users/" + userId, {
                    method: "PUT", credentials: "same-origin",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ activity: activity })
                }).then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status + " (équipe)"); }));
            }
            if (serviceId) {
                requests.push(fetch("/api/admin/users/" + userId + "/services", {
                    method: "POST", credentials: "same-origin",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ serviceId: Number(serviceId) })
                }).then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status + " (service)"); }));
            }
            if (!requests.length) { resultBox.innerHTML = '<span class="text-danger">Renseignez au moins un champ.</span>'; return; }

            Promise.all(requests)
                .then(function () {
                    bootstrap.Modal.getInstance(document.getElementById("fixOrgModal")).hide();
                    load();
                })
                .catch(function (e) { resultBox.innerHTML = '<span class="text-danger">Erreur : ' + e.message + '</span>'; });
        });
    }

    function openAgentDetail(agent) {
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

    function currentMonth() {
        return document.getElementById("monthInput").value || currentMonthValue();
    }

    function currentCountry() {
        return document.getElementById("countryFilterInput").value;
    }

    function updateExportLink() {
        document.getElementById("exportBtn").href = "/api/reporting/team/export?month=" + encodeURIComponent(currentMonth());
        var country = currentCountry();
        var excelBtn = document.getElementById("exportExcelBtn");
        if (excelBtn) {
            excelBtn.href = "/api/reporting/team/export-excel?month=" + encodeURIComponent(currentMonth()) +
                (country ? "&countryCode=" + encodeURIComponent(country) : "");
        }
        var wordBtn = document.getElementById("exportWordBtn");
        if (wordBtn) {
            wordBtn.href = "/api/reporting/team/export-word?month=" + encodeURIComponent(currentMonth()) +
                (country ? "&countryCode=" + encodeURIComponent(country) : "");
        }
        var pptBtn = document.getElementById("exportPptBtn");
        if (pptBtn) {
            pptBtn.href = "/api/reporting/team/export-powerpoint?month=" + encodeURIComponent(currentMonth()) +
                (country ? "&countryCode=" + encodeURIComponent(country) : "");
        }
    }

    function load() {
        var errorBox = document.getElementById("loadError");
        errorBox.style.display = "none";
        updateExportLink();

        var country = currentCountry();
        getJson("/api/reporting/team?month=" + encodeURIComponent(currentMonth()) +
                (country ? "&countryCode=" + encodeURIComponent(country) : ""))
            .then(renderTeam)
            .catch(function (e) {
                errorBox.textContent = "Erreur de chargement : " + e.message;
                errorBox.style.display = "";
            });
    }

    function init() {
        document.getElementById("monthInput").value = currentMonthValue();
        document.getElementById("monthForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            load();
        });
        wireFixOrgModal();
        fetch("/api/auth/me", { credentials: "same-origin" })
            .then(function (res) { return res.json(); })
            .then(function (user) {
                currentProfile = (user.role && user.role.toUpperCase() === "ADMIN") ? "ADMIN" : "OTHER";
                load(); // recharge une fois le profil connu — sinon le bouton "Corriger" (Admin uniquement) n'apparaît jamais au premier affichage
            })
            .catch(function () { load(); });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
