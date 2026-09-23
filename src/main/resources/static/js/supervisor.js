"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };

    var getJson = RccApi.getJson;
    var escapeHtml = RccApi.escapeHtml;

    var reportingCache = [];
    var qaCache = [];

    function fmtPct(v) { return v != null ? Math.round(v) + " %" : "—"; }
    function fmtScore(v) { return v != null ? v : "—"; }

    function currentMonthValue() {
        var now = new Date();
        return now.getFullYear() + "-" + String(now.getMonth() + 1).padStart(2, "0");
    }

    function todayIso() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }

    function toIsoDate(d) {
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }

    /** Format natif de <input type="week"> : "2026-W32" -> [lundi, dimanche] de cette semaine ISO. */
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

    /** Bascule le champ de saisie visible (jour/semaine/mois) selon la granularité choisie. */
    function updatePeriodInputs() {
        var type = $("svPeriodType").value;
        $("svDayInput").style.display = type === "DAY" ? "" : "none";
        $("svWeekInput").style.display = type === "WEEK" ? "" : "none";
        $("svMonthInput").style.display = type === "MONTH" ? "" : "none";
        $("svPeriodLabel").textContent = type === "DAY" ? "Jour" : (type === "WEEK" ? "Semaine" : "Mois");
    }

    /** @return {from, to, month} — month est renseigné uniquement en granularité Mois (compat endpoint existant). */
    function computeSelectedPeriod() {
        var type = $("svPeriodType").value;
        if (type === "DAY") {
            var day = $("svDayInput").value || todayIso();
            return { from: day, to: day, month: null };
        }
        if (type === "WEEK") {
            var weekValue = $("svWeekInput").value;
            if (!weekValue) { var now = new Date(); weekValue = now.getFullYear() + "-W01"; }
            var range = isoWeekToRange(weekValue);
            return { from: range[0], to: range[1], month: null };
        }
        return { from: null, to: null, month: $("svMonthInput").value || currentMonthValue() };
    }

    // ===================== ONGLETS =====================

    var TAB_DEFS = [
        { btn: "svTabReportingBtn", pane: "svPaneReporting", onShow: loadReporting },
        { btn: "svTabQaBtn", pane: "svPaneQa", onShow: loadQa },
        { btn: "svTabCrmBtn", pane: "svPaneCrm", onShow: function () { loadCrmCampaigns(); } }
    ];

    function wireTabs() {
        TAB_DEFS.forEach(function (def) {
            $(def.btn).addEventListener("click", function () { activateTab(def); });
        });
    }

    function activateTab(activeDef) {
        TAB_DEFS.forEach(function (def) {
            var isActive = def === activeDef;
            $(def.btn).classList.toggle("active", isActive);
            $(def.pane).style.display = isActive ? "" : "none";
        });
        if (activeDef.onShow) activeDef.onShow();
    }

    // ===================== REPORTING D'ÉQUIPE =====================

    var selectedTeam = null;

    function loadReporting() {
        var period = computeSelectedPeriod();
        var country = $("svCountryFilter").value;
        var url = period.month
            ? "/api/reporting/team?month=" + encodeURIComponent(period.month)
            : "/api/reporting/team?from=" + encodeURIComponent(period.from) + "&to=" + encodeURIComponent(period.to);
        if (country) url += "&countryCode=" + encodeURIComponent(country);
        getJson(url).then(function (rows) {
            reportingCache = rows || [];
            renderTeamPicker();
            renderReporting();
            updateHeaderStats();
        }).catch(function (e) {
            $("svReportingBody").innerHTML = '<tr><td colspan="9" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    /** Pastilles cliquables par équipe (r.activity) — cliquer filtre le tableau aux agents de cette équipe. */
    function renderTeamPicker() {
        var picker = $("svTeamPicker");
        var counts = {};
        var order = [];
        reportingCache.forEach(function (r) {
            var team = r.activity || "—";
            if (!counts[team]) { counts[team] = 0; order.push(team); }
            counts[team]++;
        });
        order.sort(function (a, b) { return a.localeCompare(b, "fr"); });

        if (selectedTeam && !counts[selectedTeam]) selectedTeam = null;

        if (!order.length) { picker.innerHTML = '<span class="text-muted small">Aucune équipe pour ces filtres.</span>'; return; }

        picker.innerHTML = order.map(function (team) {
            var active = team === selectedTeam;
            return '<button type="button" class="btn btn-sm ' + (active ? "btn-primary" : "btn-outline-secondary") + '" data-team-pick="' + escapeHtml(team) + '">' +
                escapeHtml(team) + ' <span class="badge ' + (active ? "bg-light text-primary" : "bg-secondary") + ' ms-1">' + counts[team] + '</span></button>';
        }).join("");

        Array.prototype.forEach.call(picker.querySelectorAll("[data-team-pick]"), function (btn) {
            btn.addEventListener("click", function () {
                var team = btn.getAttribute("data-team-pick");
                selectedTeam = team === selectedTeam ? null : team;
                renderTeamPicker();
                renderReporting();
            });
        });
    }

    function renderReporting() {
        var search = ($("svReportingSearch").value || "").trim().toLowerCase();
        var body = $("svReportingBody");
        var filtered = reportingCache.filter(function (r) {
            if (selectedTeam && (r.activity || "—") !== selectedTeam) return false;
            if (!search) return true;
            var haystack = (r.userFullName || r.username || "").toLowerCase();
            return haystack.indexOf(search) !== -1;
        });
        if (!filtered.length) {
            body.innerHTML = '<tr><td colspan="9" class="text-center text-muted">Aucun agent pour ces filtres.</td></tr>';
            return;
        }
        filtered.sort(function (a, b) { return (a.userFullName || a.username || "").localeCompare(b.userFullName || b.username || "", "fr"); });
        body.innerHTML = filtered.map(function (r) {
            var m = r.kpiMetrics || {};
            return "<tr><td>" + escapeHtml(r.userFullName || r.username) + "</td>" +
                "<td>" + escapeHtml(r.affiliateBranch || "—") + "</td>" +
                "<td>" + escapeHtml(r.serviceName || "—") + "</td>" +
                "<td>" + escapeHtml(r.activity || "—") + "</td>" +
                "<td>" + fmtScore(m.SCORE_QA) + "</td>" +
                "<td>" + (m.SCORE_EVALUATION != null ? m.SCORE_EVALUATION + " %" : "—") + "</td>" +
                "<td>" + fmtScore(m.INTERACTIONS) + "</td>" +
                "<td>" + fmtPct(r.presenceRate) + "</td>" +
                "<td>" + fmtPct(r.performanceGlobale) + "</td></tr>";
        }).join("");
    }

    function wireReporting() {
        $("svReportingApplyBtn").addEventListener("click", loadReporting);
        $("svReportingSearch").addEventListener("input", renderReporting);
        $("svPeriodType").addEventListener("change", updatePeriodInputs);
        $("svMonthInput").value = currentMonthValue();
        $("svDayInput").value = todayIso();
        updatePeriodInputs();
    }

    // ===================== ÉVALUATIONS QA =====================

    function loadQa() {
        getJson("/api/quality/evaluations").then(function (list) {
            qaCache = list || [];
            renderQa();
            updateHeaderStats();
        }).catch(function (e) {
            $("svQaBody").innerHTML = '<tr><td colspan="7" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function renderQa() {
        var search = ($("svQaSearch").value || "").trim().toLowerCase();
        var body = $("svQaBody");
        var filtered = qaCache.filter(function (e) {
            if (!search) return true;
            return ((e.agentName || e.agentMatricule || "")).toLowerCase().indexOf(search) !== -1;
        });
        if (!filtered.length) {
            body.innerHTML = '<tr><td colspan="7" class="text-center text-muted">Aucune évaluation.</td></tr>';
            return;
        }
        filtered.sort(function (a, b) { return (b.callDate || "").localeCompare(a.callDate || ""); });
        body.innerHTML = filtered.map(function (e) {
            var resultBadge = e.knockedOut
                ? '<span class="badge bg-danger">Éliminatoire</span>'
                : (e.passed ? '<span class="badge bg-success">Conforme</span>' : '<span class="badge bg-warning text-dark">Non conforme</span>');
            return "<tr><td>" + escapeHtml(e.agentName || e.agentMatricule) + "</td>" +
                "<td>" + escapeHtml(e.callDate || "—") + "</td>" +
                "<td>" + escapeHtml(e.motifLabel || "—") + "</td>" +
                "<td>" + Math.round(e.scorePercentage) + " %</td>" +
                "<td>" + resultBadge + "</td>" +
                "<td>" + escapeHtml(e.evaluatorMatricule || "—") + "</td>" +
                "<td>" + escapeHtml(e.feedbackStatus || "—") + "</td></tr>";
        }).join("");
    }

    function wireQa() {
        $("svQaSearch").addEventListener("input", renderQa);
    }

    // ===================== STATS D'EN-TÊTE =====================

    function updateHeaderStats() {
        $("svStatAgents").textContent = reportingCache.length || "—";
        var scores = reportingCache.map(function (r) { return r.kpiMetrics && r.kpiMetrics.SCORE_QA; }).filter(function (v) { return v != null; });
        $("svStatAvgQa").textContent = scores.length ? (scores.reduce(function (a, b) { return a + b; }, 0) / scores.length).toFixed(1) : "—";
        $("svStatEvaluations").textContent = qaCache.length || "—";
    }

    // ===================== INIT =====================

    // ===================== CRM OUTBOUND (Superviseur — toutes équipes) =====================

    var svCampaignDetailModal = null;
    var svCurrentCampaignId = null;

    function wireCrmSubTabs() {
        var subDefs = [
            { btn: "svCrmTabCampaignsBtn", pane: "svCrmPaneCampaigns", onShow: loadCrmCampaigns },
            { btn: "svCrmTabSalesBtn", pane: "svCrmPaneSales", onShow: loadCrmSales },
            { btn: "svCrmTabRdvBtn", pane: "svCrmPaneRdv", onShow: loadCrmRdv }
        ];
        subDefs.forEach(function (def) {
            $(def.btn).addEventListener("click", function () {
                subDefs.forEach(function (d) {
                    $(d.btn).classList.toggle("active", d === def);
                    $(d.pane).style.display = d === def ? "" : "none";
                });
                def.onShow();
            });
        });
    }

    function crmStatusBadge(status) {
        var map = {
            PENDING: '<span class="badge bg-secondary">À appeler</span>',
            GREEN: '<span class="badge bg-success">Interaction</span>',
            RED: '<span class="badge bg-danger">Pas de réponse</span>',
            YELLOW: '<span class="badge bg-warning text-dark">RDV pris</span>',
            PLANNED: '<span class="badge bg-warning text-dark">Prévu</span>',
            DONE: '<span class="badge bg-success">Honoré</span>',
            NO_SHOW: '<span class="badge bg-danger">Absent</span>',
            CANCELLED: '<span class="badge bg-secondary">Annulé</span>',
            CONFIRMED: '<span class="badge bg-success">Confirmée</span>'
        };
        return map[status] || status;
    }

    function loadCrmCampaigns() {
        var container = $("svCampaignsList");
        getJson("/api/campaigns").then(function (campaigns) {
            if (!campaigns.length) { container.innerHTML = '<p class="text-muted text-center">Aucune campagne pour l\'instant.</p>'; return; }
            container.innerHTML = campaigns.map(function (c) {
                var statusBadge = c.status === "ACTIVE" ? '<span class="badge bg-success">Active</span>' : '<span class="badge bg-secondary">Clôturée</span>';
                return '<div class="border rounded p-3 mb-2 sv-campaign-row" data-campaign-id="' + c.campaignId + '" data-campaign-name="' + escapeHtml(c.name) + '" style="cursor:pointer;">' +
                    '<div class="d-flex justify-content-between align-items-start">' +
                        '<div><strong>' + escapeHtml(c.name) + '</strong> ' + statusBadge + '</div>' +
                        '<span class="text-muted small">' + new Date(c.createdAt).toLocaleDateString("fr-FR") + '</span>' +
                    '</div>' +
                    '<div class="d-flex gap-3 mt-2 flex-wrap small">' +
                        '<span><i class="bi bi-people"></i> ' + c.totalContacts + ' contact(s)</span>' +
                        '<span><i class="bi bi-telephone"></i> ' + c.callsMade + ' appel(s)</span>' +
                        '<span><i class="bi bi-person-check"></i> ' + c.contacted + ' contacté(s)</span>' +
                        '<span><i class="bi bi-calendar-check"></i> ' + c.appointmentsTaken + ' RDV</span>' +
                    '</div></div>';
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll(".sv-campaign-row"), function (row) {
                row.addEventListener("click", function () {
                    svCurrentCampaignId = row.getAttribute("data-campaign-id");
                    $("svCampaignDetailTitle").innerHTML = '<i class="bi bi-megaphone-fill"></i> ' + escapeHtml(row.getAttribute("data-campaign-name"));
                    loadCrmCampaignContacts();
                    svCampaignDetailModal.show();
                });
            });
        }).catch(function (e) {
            container.innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function loadCrmCampaignContacts() {
        var body = $("svCampaignContactsBody");
        body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Chargement…</td></tr>';
        getJson("/api/campaigns/" + svCurrentCampaignId + "/contacts").then(function (contacts) {
            if (!contacts.length) { body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Aucun contact.</td></tr>'; return; }
            body.innerHTML = contacts.map(function (c) {
                return "<tr><td>" + escapeHtml(c.clientName) + "</td>" +
                    "<td>" + escapeHtml(c.clientPhone || "—") + "</td>" +
                    "<td>" + escapeHtml(c.maskedAccountNumber || "—") + "</td>" +
                    "<td>" + escapeHtml(c.agentName || "Non assigné") + "</td>" +
                    "<td>" + crmStatusBadge(c.callStatus) + "</td></tr>";
            }).join("");
        }).catch(function (e) {
            body.innerHTML = '<tr><td colspan="5" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function loadCrmSales() {
        var from = $("svSalesFrom").value, to = $("svSalesTo").value;
        getJson("/api/outbound/sales/team?from=" + from + "&to=" + to).then(function (sales) {
            var body = $("svSalesBody");
            if (!sales.length) { body.innerHTML = '<tr><td colspan="6" class="text-center text-muted">Aucune vente sur cette période.</td></tr>'; return; }
            body.innerHTML = sales.map(function (s) {
                return "<tr><td>" + new Date(s.saleDate).toLocaleDateString("fr-FR") + "</td>" +
                    "<td>" + escapeHtml(s.agentName) + "</td>" +
                    "<td>" + escapeHtml(s.productName) + "</td>" +
                    "<td>" + escapeHtml(s.clientName || "—") + "</td>" +
                    "<td>" + (s.amount != null ? s.amount.toLocaleString("fr-FR") + " F" : "—") + "</td>" +
                    "<td>" + crmStatusBadge(s.status) + "</td></tr>";
            }).join("");
        }).catch(function (e) {
            $("svSalesBody").innerHTML = '<tr><td colspan="6" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function loadCrmRdv() {
        var from = $("svRdvFrom").value, to = $("svRdvTo").value;
        getJson("/api/outbound/appointments/team?from=" + from + "T00:00:00&to=" + to + "T23:59:59").then(function (rows) {
            var body = $("svRdvBody");
            if (!rows.length) { body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Aucun rendez-vous sur cette période.</td></tr>'; return; }
            body.innerHTML = rows.map(function (a) {
                return "<tr><td>" + new Date(a.scheduledAt).toLocaleString("fr-FR") + "</td>" +
                    "<td>" + escapeHtml(a.agentName) + "</td>" +
                    "<td>" + escapeHtml(a.clientName) + "</td>" +
                    "<td>" + escapeHtml(a.purpose || "—") + "</td>" +
                    "<td>" + crmStatusBadge(a.status) + "</td></tr>";
            }).join("");
        }).catch(function (e) {
            $("svRdvBody").innerHTML = '<tr><td colspan="5" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function initCrmDefaults() {
        var todayIso = new Date().toISOString().slice(0, 10);
        var monthStart = todayIso.slice(0, 8) + "01";
        $("svSalesFrom").value = monthStart; $("svSalesTo").value = todayIso;
        $("svRdvFrom").value = monthStart; $("svRdvTo").value = todayIso;
        $("svSalesApplyBtn").addEventListener("click", loadCrmSales);
        $("svRdvApplyBtn").addEventListener("click", loadCrmRdv);
    }

    function init() {
        svCampaignDetailModal = new bootstrap.Modal($("svCampaignDetailModal"));
        wireTabs();
        wireReporting();
        wireQa();
        wireCrmSubTabs();
        initCrmDefaults();
        loadReporting();
    }

    document.addEventListener("DOMContentLoaded", init);
})();
