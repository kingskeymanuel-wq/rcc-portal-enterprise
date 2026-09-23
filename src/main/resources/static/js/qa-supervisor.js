"use strict";

(function () {
    var getJson = RccApi.getJson;
    var escapeHtml = RccApi.escapeHtml;
    var evaluations = [];
    var formations = [];
    var currentFormationStats = [];
    var formationDetailModal = null;

    function el(id) { return document.getElementById(id); }
    function val(v, fallback) { return v === null || v === undefined || v === "" ? (fallback || "—") : v; }
    function formatDate(v) {
        if (!v) return "—";
        try { return new Date(v + (String(v).length === 10 ? "T00:00:00" : "")).toLocaleDateString("fr-FR"); } catch (e) { return v; }
    }

    function api(url) {
        return getJson(url).catch(function (e) { return { __error: e.message || "Erreur API" }; });
    }

    function showError(message) {
        var box = el("qasError");
        box.textContent = message;
        box.classList.remove("d-none");
    }

    function renderKpis() {
        el("kpiEvalCount").textContent = evaluations.length;
        var agentSet = {};
        evaluations.forEach(function (e) { if (e.agentMatricule) agentSet[e.agentMatricule] = true; });
        el("kpiAgentsEvaluated").textContent = Object.keys(agentSet).length;
        var passed = evaluations.filter(function (e) { return e.passed; }).length;
        el("kpiPassRate").textContent = evaluations.length ? ((passed / evaluations.length) * 100).toFixed(1) + "%" : "—";
        var active = formations.filter(function (f) { return !f.status || !["ARCHIVED", "CLOSED", "TERMINEE"].includes(String(f.status).toUpperCase()); });
        el("kpiFormationsActive").textContent = active.length;
    }

    function renderEvaluations() {
        var q = (el("evalSearch").value || "").trim().toLowerCase();
        var filtered = evaluations.filter(function (e) {
            var hay = [e.agentName, e.agentMatricule, e.evaluatorMatricule, e.motifLabel].filter(Boolean).join(" ").toLowerCase();
            return !q || hay.includes(q);
        });
        el("evalBody").innerHTML = filtered.slice(0, 200).map(function (e) {
            var resultCls = e.knockedOut ? "status-rejected" : (e.passed ? "status-approved" : "status-pending");
            var resultLabel = e.knockedOut ? "Éliminatoire" : (e.passed ? "Réussi" : "Insuffisant");
            return '<tr><td>' + escapeHtml(val(e.agentName, e.agentMatricule)) + '</td>' +
                '<td>' + escapeHtml(val(e.evaluatorMatricule)) + '</td>' +
                '<td>' + escapeHtml(formatDate(e.evaluationDate)) + '</td>' +
                '<td>' + escapeHtml(val(e.motifLabel)) + '</td>' +
                '<td>' + (typeof e.scorePercentage === "number" ? e.scorePercentage.toFixed(1) + "%" : "—") + '</td>' +
                '<td><span class="status-pill ' + resultCls + '">' + resultLabel + '</span></td></tr>';
        }).join("") || '<tr><td colspan="6" class="text-center text-muted">Aucune évaluation trouvée.</td></tr>';
    }

    function renderFormationsGrid() {
        el("formationsGrid").innerHTML = formations.length ? formations.map(function (f) {
            return '<div class="team-tile" data-id="' + f.formationId + '" data-title="' + escapeHtml(f.title) + '" role="button" tabindex="0">' +
                '<i class="bi bi-mortarboard-fill"></i><div><b>' + escapeHtml(f.title) + '</b><span>' + escapeHtml(val(f.category, "Formation")) + (f.mandatory ? " · Obligatoire" : "") + '</span></div></div>';
        }).join("") : '<div class="text-muted small">Aucune formation.</div>';
        Array.prototype.forEach.call(el("formationsGrid").querySelectorAll(".team-tile"), function (tile) {
            tile.addEventListener("click", function () { openFormationDetail(tile.getAttribute("data-id"), tile.getAttribute("data-title")); });
            tile.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); tile.click(); } });
        });
    }

    function openFormationDetail(formationId, title) {
        el("formationDetailTitle").textContent = title;
        el("formationDetailBody").innerHTML = '<tr><td colspan="4" class="text-center text-muted"><span class="spinner-border spinner-border-sm"></span> Chargement…</td></tr>';
        formationDetailModal.show();
        getJson("/api/training/formations/" + formationId + "/stats/agents").then(function (stats) {
            currentFormationStats = Array.isArray(stats) ? stats : [];
            el("tabTrainedBtn").classList.add("active");
            el("tabNotTrainedBtn").classList.remove("active");
            renderFormationDetail("trained");
        }).catch(function (e) {
            el("formationDetailBody").innerHTML = '<tr><td colspan="4" class="text-center text-muted">Impossible de charger les statistiques : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function isTrained(stat) { return stat.fullyCompleted === true; }

    function renderFormationDetail(which) {
        var rows = currentFormationStats.filter(function (s) { return which === "trained" ? isTrained(s) : !isTrained(s); });
        el("formationDetailBody").innerHTML = rows.length ? rows.map(function (s) {
            return '<tr><td>' + escapeHtml(val(s.name, s.username)) + '</td><td>' + escapeHtml(val(s.team)) + '</td>' +
                '<td>' + (typeof s.overallPercent === "number" ? s.overallPercent.toFixed(0) + "%" : "—") + ' (' + s.lessonsCompleted + '/' + s.totalLessons + ')</td>' +
                '<td>' + escapeHtml(val(s.status)) + '</td></tr>';
        }).join("") : '<tr><td colspan="4" class="text-center text-muted">Aucun agent dans cette catégorie.</td></tr>';
    }

    async function loadAll() {
        var results = await Promise.all([
            api("/api/quality/evaluations"),
            api("/api/training/formations")
        ]);
        evaluations = Array.isArray(results[0]) ? results[0] : [];
        formations = Array.isArray(results[1]) ? results[1] : [];
        if (results[0] && results[0].__error) showError("Impossible de charger les évaluations : " + results[0].__error);
        renderKpis(); renderEvaluations(); renderFormationsGrid();
    }

    function init() {
        formationDetailModal = new bootstrap.Modal(el("formationDetailModal"));
        el("refreshBtn").addEventListener("click", loadAll);
        el("evalSearch").addEventListener("input", renderEvaluations);
        el("tabTrainedBtn").addEventListener("click", function () {
            el("tabTrainedBtn").classList.add("active"); el("tabNotTrainedBtn").classList.remove("active");
            renderFormationDetail("trained");
        });
        el("tabNotTrainedBtn").addEventListener("click", function () {
            el("tabNotTrainedBtn").classList.add("active"); el("tabTrainedBtn").classList.remove("active");
            renderFormationDetail("not-trained");
        });

        window.RccSession.init().then(function (session) {
            if (!session || (session.profile !== "QA_SUPERVISOR" && session.profile !== "ADMIN")) {
                showError("Cette interface est réservée au Superviseur Qualité Assurance et à l'administrateur.");
                return;
            }
            loadAll();
        }).catch(function (e) { showError(e.message || "Session indisponible."); });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
