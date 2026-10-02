"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };

    var getJson = RccApi.getJson;
    var escapeHtml = RccApi.escapeHtml;

    var qaCache = [];

    // ===================== ONGLETS =====================
    // Le portail Superviseur reprend le portail RH en lecture (hr-parcours.js gère les onglets) : ses
    // onglets propres (Supervision, Qualité, CRM) se chargent à leur première ouverture (événement « hr:tab »).

    var mounted = {};

    /** Incidents de shift non encore justifiés par les Team Leaders : pastille de l'onglet « Alertes de shift ». */
    function incidentCount(n) {
        var b = $("svIncidentCount");
        if (b) b.textContent = n > 0 ? n : "";
    }
    var ON_SHOW = {
        "sv-supervision": function () {
            if (!mounted.workflow && window.RccSupervision) { mounted.workflow = true; RccSupervision.mountManager($("spManager"), { scope: "RCC" }); }
        },
        "sv-quality": function () {
            loadQa();
            if (!mounted.qaTeam && window.RccQaTeam) { mounted.qaTeam = true; RccQaTeam.mount($("svQaTeamMount")); }
        },
        "sv-alerts": function () {
            if (!mounted.alerts && window.RccShiftIncidents) {
                mounted.alerts = true;
                RccShiftIncidents.mount($("svShiftIncidents"), { canJustify: false, teamFilter: true, onCount: incidentCount });
            }
        },
        "sv-tools": function () {
            if (!mounted.tools && window.RccExternalTools) { mounted.tools = true; RccExternalTools.render("svToolsGrid"); }
        },
        "sv-crm": function () { if (!mounted.crm) { mounted.crm = true; loadCrmCampaigns(); } }
    };

    // ===================== ÉVALUATIONS QA =====================

    function loadQa() {
        getJson("/api/quality/evaluations").then(function (list) {
            qaCache = list || [];
            renderQa();
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
        if (!$("svCampaignDetailModal")) return;
        svCampaignDetailModal = new bootstrap.Modal($("svCampaignDetailModal"));
        wireQa();
        wireCrmSubTabs();
        initCrmDefaults();
        window.addEventListener("hr:tab", function (e) { var f = ON_SHOW[e.detail]; if (f) f(); });
        if (window.RccShiftIncidents) RccShiftIncidents.count().then(incidentCount);
        // Nombre de demandes escaladées (support-requests.js) reporté sur l'onglet « Supervision ».
        var src = document.querySelector("[data-sr-mount='supervisor'] [data-sr-count]"), dst = $("svEscalatedCount");
        if (src && dst && window.MutationObserver) {
            var sync = function () { dst.textContent = src.style.display === "none" ? "" : src.textContent; };
            new MutationObserver(sync).observe(src, { childList: true, attributes: true, characterData: true, subtree: true });
            sync();
        }
        var current = (location.hash || "").replace("#", "");
        if (ON_SHOW[current]) ON_SHOW[current]();
    }

    document.addEventListener("DOMContentLoaded", init);
})();
