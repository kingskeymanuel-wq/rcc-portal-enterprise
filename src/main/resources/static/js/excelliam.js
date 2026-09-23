"use strict";

(function () {
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var TEAM_LABELS = { INBOUND_VOICE: "Inbound Voix", INBOUND_MAIL: "Inbound Mail / Rafiki", CIB: "CIB", OUTBOUND: "Outbound", OTHER: "Non classée" };
    var SHIFT_LABELS = { M: "07h–16h", M2: "08h–17h", A: "12h–21h", N: "21h–06h", OFF: "Repos (OFF)" };

    var employees = [];
    var leaves = [];
    var planifyModal = null;
    var currentTeam = null;
    var teamChart = null;
    var leaveChart = null;

    function el(id) { return document.getElementById(id); }

    /** Incrémentation douce d'un compteur KPI — ~500ms, easing simple (pas de rebond). Sans
     *  effet si prefers-reduced-motion est actif (accessibilité), le chiffre final s'affiche
     *  directement. Ne perturbe jamais la valeur en cas d'appels successifs rapprochés : chaque
     *  appel repart proprement de la valeur actuellement affichée. */
    function animateCounter(elementId, targetValue) {
        var node = el(elementId);
        if (!node) return;
        var reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        var start = parseInt(node.textContent, 10);
        if (isNaN(start) || reduceMotion) { node.textContent = targetValue; return; }
        if (start === targetValue) return;

        var duration = 500;
        var startTime = performance.now();
        function tick(now) {
            var progress = Math.min(1, (now - startTime) / duration);
            var eased = 1 - Math.pow(1 - progress, 2); // ease-out quadratique — pas de dépassement/rebond
            var current = Math.round(start + (targetValue - start) * eased);
            node.textContent = current;
            if (progress < 1) requestAnimationFrame(tick);
        }
        requestAnimationFrame(tick);
    }

    function api(url) {
        return getJson(url).catch(function (e) {
            return { __error: e.message || "Erreur API" };
        });
    }

    function showError(message) {
        el("excError").textContent = message;
        el("excError").classList.remove("d-none");
    }

    function teamKey(u) { return u.activity || "OTHER"; }
    function teamLabel(code) { return TEAM_LABELS[code] || code || "Non classée"; }

    function todayIso() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }

    function toIsoDateLocal(d) {
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }

    function load() {
        el("excLoading").classList.remove("d-none");
        el("excApp").classList.add("d-none");
        el("excError").classList.add("d-none");

        Promise.all([
            api("/api/users/directory"),
            api("/api/workflow/requests/hr/leave")
        ]).then(function (results) {
            if (results[0] && results[0].__error) { showError(results[0].__error); return; }
            employees = Array.isArray(results[0]) ? results[0].filter(function (u) { return u.role === "AGENT" || !u.role; }) : [];
            leaves = (results[1] && !results[1].__error && Array.isArray(results[1])) ? results[1] : [];

            renderKpis();
            renderTeamChart();
            renderLeaveChart();
            renderTeamGrid();
            renderLeaveList();

            el("excLoading").classList.add("d-none");
            el("excApp").classList.remove("d-none");
            el("excApp").classList.remove("exc-fade-in");
            void el("excApp").offsetWidth; // force le reflow pour rejouer l'animation à chaque refresh
            el("excApp").classList.add("exc-fade-in");
            el("excLastRefresh").textContent = "Actualisé à " + new Date().toLocaleTimeString("fr-FR");
        }).catch(function (e) {
            el("excLoading").classList.add("d-none");
            showError(e.message || "Erreur de chargement.");
        });
    }

    function renderKpis() {
        var counts = {};
        employees.forEach(function (u) { var t = teamKey(u); counts[t] = (counts[t] || 0) + 1; });
        animateCounter("excKpiTeams", Object.keys(counts).filter(function (t) { return t !== "OTHER"; }).length);

        var pendingApproved = leaves.filter(function (l) { return l.status === "APPROVED"; });
        var todayStr = todayIso();
        var onLeaveToday = pendingApproved.filter(function (l) {
            return l.periodFrom <= todayStr && l.periodTo >= todayStr;
        });
        animateCounter("excKpiOnLeave", onLeaveToday.length);

        animateCounter("excKpiPlanified", employees.length);
        el("excKpiPlanifiedSub").textContent = "Effectif total sous contrat";
    }

    var EXC_CHART_COLORS = ["#7C3AED", "#00A651", "#0057B8", "#F59E0B", "#DC3545"];

    /** Anneau — répartition des agents Excelliam par équipe (mêmes couleurs que la palette
     *  violette du portail, une teinte par équipe). Détruit le graphique précédent avant d'en
     *  recréer un — évite une fuite mémoire Chart.js si load() est rappelée (bouton Actualiser). */
    function renderTeamChart() {
        var counts = {};
        employees.forEach(function (u) { var t = teamKey(u); counts[t] = (counts[t] || 0) + 1; });
        var teams = Object.keys(counts).filter(function (t) { return t !== "OTHER"; })
            .sort(function (a, b) { return counts[b] - counts[a]; });

        if (teamChart) teamChart.destroy();
        if (!teams.length) return;
        teamChart = new Chart(el("excTeamChart"), {
            type: "doughnut",
            data: {
                labels: teams.map(teamLabel),
                datasets: [{ data: teams.map(function (t) { return counts[t]; }), backgroundColor: EXC_CHART_COLORS, borderWidth: 0 }]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: { legend: { position: "bottom", labels: { boxWidth: 10, font: { size: 11 } } } }
            }
        });
    }

    /** Barres — demandes de congé des 30 derniers jours par statut (Approuvé/En attente/Refusé). */
    function renderLeaveChart() {
        var cutoff = new Date();
        cutoff.setDate(cutoff.getDate() - 30);
        var cutoffStr = cutoff.toISOString().slice(0, 10);
        var recent = leaves.filter(function (l) { return (l.periodFrom || "") >= cutoffStr; });

        var approved = recent.filter(function (l) { return l.status === "APPROVED"; }).length;
        var pending = recent.filter(function (l) { return l.status === "PENDING"; }).length;
        var rejected = recent.filter(function (l) { return l.status === "REJECTED"; }).length;

        if (leaveChart) leaveChart.destroy();
        leaveChart = new Chart(el("excLeaveChart"), {
            type: "bar",
            data: {
                labels: ["Approuvé", "En attente", "Refusé"],
                datasets: [{ data: [approved, pending, rejected], backgroundColor: ["#00A651", "#F59E0B", "#DC3545"], borderRadius: 6, maxBarThickness: 56 }]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: { legend: { display: false } },
                scales: { y: { beginAtZero: true, ticks: { precision: 0 } } }
            }
        });
    }

    function renderTeamGrid() {
        var counts = {};
        employees.forEach(function (u) { var t = teamKey(u); counts[t] = (counts[t] || 0) + 1; });
        var teams = Object.keys(counts).filter(function (t) { return t !== "OTHER"; })
            .sort(function (a, b) { return counts[b] - counts[a]; });

        el("excTeamGrid").innerHTML = teams.length ? teams.map(function (t) {
            return '<div class="team-tile" data-team="' + escapeHtml(t) + '" role="button" tabindex="0">' +
                '<i class="bi bi-people-fill"></i><div><b>' + escapeHtml(teamLabel(t)) + '</b><span>' + counts[t] + ' agent' + (counts[t] > 1 ? "s" : "") + '</span></div></div>';
        }).join("") : '<div class="text-muted small">Aucune équipe.</div>';

        Array.prototype.forEach.call(el("excTeamGrid").querySelectorAll(".team-tile"), function (tile) {
            tile.addEventListener("click", function () { openPlanifyModal(tile.getAttribute("data-team")); });
            tile.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); tile.click(); } });
        });
    }

    function renderLeaveList() {
        var box = el("excLeaveList");
        var recent = leaves.slice(0, 12);
        if (!recent.length) {
            box.innerHTML = '<p class="text-muted small mb-0">Aucune demande de congé pour le moment.</p>';
            return;
        }
        box.innerHTML = recent.map(function (l) {
            var statusClass = l.status === "APPROVED" ? "status-approved" : l.status === "REJECTED" ? "status-rejected" : "status-pending";
            var statusLabel = l.status === "APPROVED" ? "Approuvé" : l.status === "REJECTED" ? "Rejeté" : "En attente";
            return '<div class="activity-item"><div class="activity-icon"><i class="bi bi-calendar2-x"></i></div>' +
                '<div class="flex-grow-1"><div class="training-title">' + escapeHtml(l.requestedByName || l.requestedByUsername || "—") + '</div>' +
                '<div class="activity-meta">' + escapeHtml(l.periodFrom || "") + ' → ' + escapeHtml(l.periodTo || "") +
                (l.assignedToName ? ' · TL : ' + escapeHtml(l.assignedToName) : '') + '</div></div>' +
                '<span class="status-pill ' + statusClass + '">' + statusLabel + '</span></div>';
        }).join("");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Onglet Planning — équipes planifiées du mois -> clic équipe -> agents
    // dispatchés par shift (planning Excelliam construit par mois).
    // ═══════════════════════════════════════════════════════════════════

    var planningEntries = [];
    var selectedPlanningTeam = null;
    var planningLoadedOnce = false;

    function currentMonthStr() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0");
    }

    function monthBounds(monthStr) {
        var parts = monthStr.split("-");
        var y = parseInt(parts[0], 10), m = parseInt(parts[1], 10);
        var lastDay = new Date(y, m, 0).getDate();
        return { from: monthStr + "-01", to: monthStr + "-" + String(lastDay).padStart(2, "0") };
    }

    function loadPlanningTab() {
        var monthInput = el("excPlanningMonth");
        var month = monthInput.value || currentMonthStr();
        monthInput.value = month;
        var range = monthBounds(month);

        selectedPlanningTeam = null;
        el("excPlanningDetailCard").classList.add("d-none");
        el("excPlanningTeamGrid").innerHTML = '<p class="text-muted small mb-0">Chargement…</p>';

        api("/api/schedule/team?from=" + range.from + "&to=" + range.to).then(function (rows) {
            if (rows && rows.__error) {
                el("excPlanningTeamGrid").innerHTML = '<p class="text-danger small mb-0">' + escapeHtml(rows.__error) + '</p>';
                return;
            }
            planningEntries = Array.isArray(rows) ? rows : [];
            renderPlannedTeamGrid(range);
        });
    }

    function renderPlannedTeamGrid(range) {
        var byTeam = {};
        planningEntries.forEach(function (r) {
            var t = r.team || "OTHER";
            if (!byTeam[t]) byTeam[t] = {};
            byTeam[t][r.username] = true;
        });
        var teams = Object.keys(byTeam).sort(function (a, b) { return Object.keys(byTeam[b]).length - Object.keys(byTeam[a]).length; });

        if (!teams.length) {
            el("excPlanningTeamGrid").innerHTML = '<p class="text-muted small mb-0">Aucune équipe planifiée pour ce mois.</p>';
            return;
        }

        el("excPlanningTeamGrid").innerHTML = teams.map(function (t) {
            var count = Object.keys(byTeam[t]).length;
            var activeClass = t === selectedPlanningTeam ? " team-tile-active" : "";
            return '<div class="team-tile' + activeClass + '" data-planning-team="' + escapeHtml(t) + '" role="button" tabindex="0">' +
                '<i class="bi bi-people-fill"></i><div><b>' + escapeHtml(teamLabel(t)) + '</b><span>' + count + ' agent' + (count > 1 ? "s" : "") + ' planifié' + (count > 1 ? "s" : "") + '</span></div></div>';
        }).join("");

        Array.prototype.forEach.call(el("excPlanningTeamGrid").querySelectorAll("[data-planning-team]"), function (tile) {
            tile.addEventListener("click", function () { showPlanningTeamDetail(tile.getAttribute("data-planning-team"), range); });
            tile.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); tile.click(); } });
        });
    }

    /** Regroupe les agents d'une équipe par shift dominant sur le mois — un agent planifié
     *  avec le même shift chaque jour (cas normal d'une planification Excelliam mensuelle)
     *  apparaît une seule fois ; un agent dont le shift varie dans le mois est marqué "mixte"
     *  (icône) plutôt que caché ou dupliqué silencieusement. */
    function showPlanningTeamDetail(team, range) {
        selectedPlanningTeam = team;
        renderPlannedTeamGrid(range);

        var entries = planningEntries.filter(function (r) { return (r.team || "OTHER") === team; });

        var byUser = {};
        entries.forEach(function (r) {
            if (!byUser[r.username]) byUser[r.username] = { fullName: r.fullName, counts: {}, labels: {} };
            var code = r.shiftCode || "—";
            byUser[r.username].counts[code] = (byUser[r.username].counts[code] || 0) + 1;
            byUser[r.username].labels[code] = r.shiftLabel || code;
        });

        var buckets = {};
        Object.keys(byUser).forEach(function (u) {
            var info = byUser[u];
            var codes = Object.keys(info.counts);
            var dominant = codes.sort(function (a, b) { return info.counts[b] - info.counts[a]; })[0];
            if (!buckets[dominant]) buckets[dominant] = [];
            buckets[dominant].push({ username: u, fullName: info.fullName, mixed: codes.length > 1, label: info.labels[dominant] });
        });

        var order = ["M", "M2", "A", "N", "OFF"];
        var codes = Object.keys(buckets).sort(function (a, b) {
            var ia = order.indexOf(a), ib = order.indexOf(b);
            if (ia === -1 && ib === -1) return a.localeCompare(b);
            if (ia === -1) return 1;
            if (ib === -1) return -1;
            return ia - ib;
        });

        el("excPlanningDetailTeam").textContent = teamLabel(team);
        el("excPlanningDetailPeriod").textContent = "Période : " + range.from + " au " + range.to;

        el("excPlanningShiftGroups").innerHTML = codes.length ? codes.map(function (code) {
            var members = buckets[code].sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || "", "fr"); });
            var shiftTitle = SHIFT_LABELS[code] || members[0].label || code;
            return '<div class="exc-shift-group">' +
                '<div class="exc-shift-group-head"><span class="exc-shift-chip"><span class="dot"></span> ' + escapeHtml(shiftTitle) + '</span>' +
                '<span class="text-muted small">' + members.length + ' agent' + (members.length > 1 ? "s" : "") + '</span></div>' +
                '<div class="exc-shift-agent-list">' + members.map(function (m) {
                    return '<span class="exc-shift-agent-chip' + (m.mixed ? ' mixed' : '') + '"' +
                        (m.mixed ? ' title="Shift variable sur le mois"' : '') + '>' +
                        escapeHtml(m.fullName || m.username) + (m.mixed ? ' <i class="bi bi-shuffle"></i>' : '') + '</span>';
                }).join("") + '</div></div>';
        }).join("") : '<p class="text-muted small mb-0">Aucun agent dispatché pour cette équipe sur la période.</p>';

        el("excPlanningDetailCard").classList.remove("d-none");
    }

    function wirePlanningTab() {
        el("excPlanningMonth").value = currentMonthStr();
        el("excPlanningMonth").addEventListener("change", loadPlanningTab);
        el("excPlanningTabBtn").addEventListener("shown.bs.tab", function () {
            if (!planningLoadedOnce) { planningLoadedOnce = true; loadPlanningTab(); }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Plannings soumis par les Team Leaders (flux symétrique) — un TL a construit lui-même
    // le planning de son équipe (voir team-leader.js "Envoyer à Excelliam") ; ici on liste
    // les entrées PENDING d'origine TEAM_LEADER, groupées par équipe + période, avec
    // Valider/Refuser (voir ScheduleController.decideExcelliamValidation). Une validation ne
    // rend rien visible immédiatement : le Team Leader doit encore cliquer "Mise à jour" de
    // son côté — c'est fait exprès (voir la demande métier).
    // ═══════════════════════════════════════════════════════════════════

    var excTlRejectModal = null;
    var pendingTlRejectGroup = null; // { team, from, to }

    function loadTlSubmissions() {
        el("excTlSubmissionsList").innerHTML = '<p class="text-muted small mb-0">Chargement…</p>';
        // Fenêtre large (mois en cours ± 2 mois) pour ne rater aucune soumission, quelle que
        // soit la période choisie par le Team Leader — le volume reste faible (une ligne par
        // agent/jour PENDING), donc une seule requête suffit.
        var now = new Date();
        var from = new Date(now.getFullYear(), now.getMonth() - 2, 1);
        var to = new Date(now.getFullYear(), now.getMonth() + 3, 0);
        var fromStr = toIsoDateLocal(from), toStr = toIsoDateLocal(to);

        api("/api/schedule/team?from=" + fromStr + "&to=" + toStr).then(function (rows) {
            if (rows && rows.__error) {
                el("excTlSubmissionsList").innerHTML = '<p class="text-danger small mb-0">' + escapeHtml(rows.__error) + '</p>';
                return;
            }
            var pending = (Array.isArray(rows) ? rows : []).filter(function (r) {
                return r.origin === "TEAM_LEADER" && r.approvalStatus === "PENDING";
            });
            if (!pending.length) {
                el("excTlSubmissionsList").innerHTML = '<p class="text-muted small mb-0">Aucun planning en attente de validation.</p>';
                return;
            }

            // Groupé par équipe — la période affichée est la plage min/max des entrées de ce
            // groupe, et c'est cette plage exacte qui est renvoyée à /decide pour ne traiter
            // QUE cette soumission (pas une soumission plus ancienne ou future de la même équipe).
            var byTeam = {};
            pending.forEach(function (r) {
                var g = byTeam[r.team] = byTeam[r.team] || { agents: {}, from: r.workDate, to: r.workDate };
                g.agents[r.username] = (g.agents[r.username] || 0) + 1;
                if (r.workDate < g.from) g.from = r.workDate;
                if (r.workDate > g.to) g.to = r.workDate;
            });

            el("excTlSubmissionsList").innerHTML = Object.keys(byTeam).map(function (team) {
                var g = byTeam[team];
                var agentCount = Object.keys(g.agents).length;
                return '<div class="d-flex justify-content-between align-items-center border rounded p-3 mb-2" data-team="' + escapeHtml(team) + '" data-from="' + g.from + '" data-to="' + g.to + '">' +
                    '<div><strong>' + escapeHtml(teamLabel(team)) + '</strong>' +
                    '<div class="small text-muted">' + agentCount + ' agent(s) — du ' + g.from + ' au ' + g.to + '</div></div>' +
                    '<div class="d-flex gap-2">' +
                    '<button type="button" class="btn btn-sm btn-outline-danger exc-tl-reject-btn"><i class="bi bi-x-circle"></i> Refuser</button>' +
                    '<button type="button" class="btn btn-sm btn-success exc-tl-approve-btn"><i class="bi bi-check2-circle"></i> Valider</button>' +
                    '</div></div>';
            }).join("");

            Array.prototype.forEach.call(el("excTlSubmissionsList").querySelectorAll("[data-team]"), function (row) {
                var group = { team: row.getAttribute("data-team"), from: row.getAttribute("data-from"), to: row.getAttribute("data-to") };
                row.querySelector(".exc-tl-approve-btn").addEventListener("click", function () { decideTlSubmission(group, true, null); });
                row.querySelector(".exc-tl-reject-btn").addEventListener("click", function () {
                    pendingTlRejectGroup = group;
                    el("excTlRejectReason").value = "";
                    excTlRejectModal.show();
                });
            });
        });
    }

    function decideTlSubmission(group, approve, reason) {
        sendJson("/api/schedule/excelliam/decide", "POST", { team: group.team, from: group.from, to: group.to, approve: approve, reason: reason })
            .then(function () { loadTlSubmissions(); })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    function wireTlSubmissions() {
        excTlRejectModal = new bootstrap.Modal(el("excTlRejectModal"));
        el("excTlSubmissionsRefreshBtn").addEventListener("click", loadTlSubmissions);
        el("excTlRejectConfirmBtn").addEventListener("click", function () {
            var reason = el("excTlRejectReason").value.trim();
            if (!reason) { alert("Le motif est obligatoire."); return; }
            excTlRejectModal.hide();
            decideTlSubmission(pendingTlRejectGroup, false, reason);
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Modale Planifier — clic équipe -> agents cochés -> shift par agent
    // ═══════════════════════════════════════════════════════════════════

    function openPlanifyModal(team) {
        currentTeam = team;
        el("planifyTeamName").textContent = teamLabel(team);
        el("planifyFrom").value = todayIso();
        el("planifyTo").value = todayIso();
        el("planifyResult").textContent = "";
        el("planifyCheckAll").checked = false;

        var members = employees.filter(function (u) { return teamKey(u) === team; })
            .sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || "", "fr"); });

        el("planifyAgentList").innerHTML = members.length ? members.map(function (u) {
            var id = "planifyAgent_" + u.username;
            return '<div class="exc-agent-row" data-username="' + escapeHtml(u.username) + '">' +
                '<input type="checkbox" class="form-check-input planify-check" id="' + id + '">' +
                '<div class="exc-agent-name">' + escapeHtml(u.fullName || u.username) + '<small>' + escapeHtml(u.username) + '</small></div>' +
                '<select class="form-select form-select-sm exc-shift-select planify-shift" disabled>' +
                '<option value="M">07h–16h (M)</option>' +
                '<option value="M2">08h–17h (M2)</option>' +
                '<option value="A">12h–21h (A)</option>' +
                '<option value="N">21h–06h (N)</option>' +
                '<option value="OFF">Repos (OFF)</option>' +
                '</select></div>';
        }).join("") : '<p class="text-muted text-center small">Aucun agent dans cette équipe.</p>';

        Array.prototype.forEach.call(el("planifyAgentList").querySelectorAll(".exc-agent-row"), function (row) {
            var checkbox = row.querySelector(".planify-check");
            var select = row.querySelector(".planify-shift");
            checkbox.addEventListener("change", function () {
                select.disabled = !checkbox.checked;
                row.classList.toggle("checked", checkbox.checked);
                updateCheckedCount();
            });
        });

        updateCheckedCount();
        planifyModal.show();
    }

    function updateCheckedCount() {
        var checked = el("planifyAgentList").querySelectorAll(".planify-check:checked").length;
        el("planifyCheckedCount").textContent = checked;
    }

    function wireModalControls() {
        el("planifyCheckAll").addEventListener("change", function () {
            var checkAll = el("planifyCheckAll").checked;
            Array.prototype.forEach.call(el("planifyAgentList").querySelectorAll(".planify-check"), function (cb) {
                if (cb.checked !== checkAll) { cb.checked = checkAll; cb.dispatchEvent(new Event("change")); }
            });
        });

        el("planifySaveBtn").addEventListener("click", function () {
            var from = el("planifyFrom").value;
            var to = el("planifyTo").value;
            var resultBox = el("planifyResult");
            if (!from || !to) { resultBox.className = "text-danger"; resultBox.textContent = "Choisissez une période."; return; }

            var assignments = [];
            Array.prototype.forEach.call(el("planifyAgentList").querySelectorAll(".exc-agent-row"), function (row) {
                var checkbox = row.querySelector(".planify-check");
                if (!checkbox.checked) return;
                assignments.push({ username: row.getAttribute("data-username"), shiftCode: row.querySelector(".planify-shift").value });
            });

            if (!assignments.length) { resultBox.className = "text-danger"; resultBox.textContent = "Cochez au moins un agent."; return; }

            resultBox.className = "text-muted";
            resultBox.textContent = "Enregistrement…";

            sendJson("/api/schedule/planify", "POST", { periodFrom: from, periodTo: to, assignments: assignments })
                .then(function (result) {
                    resultBox.className = "text-success";
                    resultBox.textContent = result.agentsPlanified + " agent(s) planifié(s) (" + result.entriesCreated + " jour(s) au total).";
                    if (result.unknownUsernames && result.unknownUsernames.length) {
                        resultBox.textContent += " Non reconnus : " + result.unknownUsernames.join(", ") + ".";
                    }
                    load();
                })
                .catch(function (e) {
                    resultBox.className = "text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });

        el("refreshExcBtn").addEventListener("click", load);
    }

    document.addEventListener("DOMContentLoaded", function () {
        planifyModal = new bootstrap.Modal(el("planifyModal"));
        wireModalControls();
        wirePlanningTab();
        wireTlSubmissions();
        loadTlSubmissions();
        load();
    });
})();
