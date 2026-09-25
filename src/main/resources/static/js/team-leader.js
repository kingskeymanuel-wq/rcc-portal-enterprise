"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var myTeam = null; // "INBOUND_VOICE" | "INBOUND_MAIL" | "CIB" | "OUTBOUND"
    var reportingCache = [];
    var salesByAgentCache = {}; // agentName -> count (mois du reporting en cours), Outbound uniquement
    var agentModal;

    var TABS = ["Reporting", "Members", "Planning", "Qa", "Sales", "Rdv", "Campaigns", "Alerts", "Competitions", "Meetings"];

    function switchTab(tab) {
        TABS.forEach(function (t) {
            var btn = $("tlTab" + t + "Btn");
            var pane = $("tlPane" + t);
            if (!btn || !pane) return;
            btn.classList.toggle("active", t.toLowerCase() === tab);
            pane.style.display = t.toLowerCase() === tab ? "" : "none";
        });
        if (tab === "reporting") loadReporting();
        if (tab === "members") loadMembers();
        if (tab === "planning") loadPlanningTab();
        if (tab === "qa") loadQa();
        if (tab === "sales") loadSales();
        if (tab === "rdv") loadRdv();
        if (tab === "campaigns") loadCampaigns();
        if (tab === "alerts") loadAlerts();
        if (tab === "competitions") loadTeamLeaderCompetitions();
        if (tab === "meetings") loadMeetings();
    }

    /** Meetings tête-à-tête (module partagé meetings.js) : liste + compteur « à compléter ». */
    function loadMeetings() {
        RccMeetings.renderList($("tlMeetingsList"), { canSchedule: true, onCounts: showMeetingCounts });
    }
    function refreshMeetingCounts() {
        getJson("/api/meetings").then(function (list) {
            var c = { todo: list.filter(function (m) { return m.canReport; }).length };
            showMeetingCounts(c);
        }).catch(function () { /* compteur facultatif */ });
    }
    function showMeetingCounts(counts) {
        $("tlStatMeetings").textContent = counts.todo;
        var badge = $("tlMeetingsTabCount");
        badge.textContent = counts.todo;
        badge.style.display = counts.todo ? "" : "none";
    }

    function currentMonthValue() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0");
    }
    function todayIso() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }
    function toIsoDateLocal(d) {
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }
    function fmtPct(v) { return v == null ? "—" : Math.round(v) + " %"; }
    function fmtScore(v) { return v == null ? "—" : v; }
    function fmtDate(iso) { return iso ? new Date(iso).toLocaleDateString("fr-FR") : "—"; }
    function fmtDateTime(iso) { return iso ? new Date(iso).toLocaleDateString("fr-FR") + " " + new Date(iso).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" }) : "—"; }
    function statusLabel(s) {
        return { PENDING: "En cours", CONFIRMED: "Confirmée", CANCELLED: "Annulée",
            PLANNED: "Prévu", DONE: "Honoré", NO_SHOW: "Absent" }[s] || s || "—";
    }

    // ===================== REPORTING ÉQUIPE =====================

    function loadReporting() {
        var month = $("tlReportingMonth").value || currentMonthValue();
        getJson("/api/team-leader/reporting?month=" + encodeURIComponent(month)).then(function (rows) {
            reportingCache = rows || [];
            renderReporting();
            updateHeaderStats();
        }).catch(function (e) {
            $("tlReportingBody").innerHTML = '<tr><td colspan="6" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });

        getJson("/api/mon-rcc/notifications/me").then(function (notifs) {
            agentsWithNewEvaluation = {};
            (notifs || []).forEach(function (n) {
                if (!n.isRead && n.actionType === "QA_EVALUATION_REVIEW" && n.actionTarget) {
                    agentsWithNewEvaluation[n.actionTarget] = n.id; // garde l'id de notif pour la marquer lue au clic
                }
            });
            renderReporting();
        }).catch(function () {});

        if (myTeam === "OUTBOUND") {
            var from = month + "-01";
            var to = month + "-" + String(new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate()).padStart(2, "0");
            getJson("/api/outbound/sales/team?from=" + from + "&to=" + to).then(function (sales) {
                salesByAgentCache = {};
                (sales || []).forEach(function (s) {
                    if (s.status === "CANCELLED") return;
                    salesByAgentCache[s.agentName] = (salesByAgentCache[s.agentName] || 0) + 1;
                });
                $("tlReportingSalesHeader").style.display = "";
                renderReporting();
            }).catch(function () {});
        } else {
            $("tlReportingSalesHeader").style.display = "none";
        }
    }

    var agentsWithNewEvaluation = {}; // { username: true } — construit depuis les notifications non lues

    function renderReporting() {
        var body = $("tlReportingBody");
        if (!reportingCache.length) { body.innerHTML = '<tr><td colspan="6" class="text-center text-muted">Aucun agent pour ce mois.</td></tr>'; return; }
        var showSales = myTeam === "OUTBOUND";
        body.innerHTML = reportingCache.map(function (r) {
            var m = r.kpiMetrics || {};
            var name = r.userFullName || r.username;
            var salesCell = showSales ? "<td>" + (salesByAgentCache[name] || 0) + "</td>" : "";
            var newEvalBadge = agentsWithNewEvaluation[r.username]
                ? ' <span class="badge bg-danger" title="Nouvelle écoute QA à consulter"><i class="bi bi-headset"></i></span>' : "";
            return '<tr class="tl-agent-row" data-agent-idx="' + reportingCache.indexOf(r) + '">' +
                "<td>" + escapeHtml(name) + newEvalBadge + "</td>" +
                "<td>" + fmtScore(m.SCORE_QA) + "</td>" +
                "<td>" + (m.SCORE_EVALUATION != null ? m.SCORE_EVALUATION + " %" : "—") + "</td>" +
                "<td>" + fmtScore(m.INTERACTIONS) + "</td>" +
                salesCell +
                "<td>" + fmtPct(r.presenceRate) + "</td>" +
                "<td>" + fmtPct(r.performanceGlobale) + "</td></tr>";
        }).join("");
        Array.prototype.forEach.call(body.querySelectorAll(".tl-agent-row"), function (row) {
            row.addEventListener("click", function () {
                var r = reportingCache[Number(row.getAttribute("data-agent-idx"))];
                if (agentsWithNewEvaluation[r.username]) {
                    var notifId = agentsWithNewEvaluation[r.username];
                    delete agentsWithNewEvaluation[r.username];
                    sendJson("/api/mon-rcc/notifications/" + notifId + "/read", "POST").catch(function () {});
                    renderReporting();
                }
                openAgentDetail(r);
            });
        });
    }

    function openAgentDetail(r) {
        var m = r.kpiMetrics || {};
        var name = r.userFullName || r.username;
        var username = r.username;

        $("tlAgentHrSection").style.display = "none"; // section propre au mode Membre — jamais ici
        $("tlAgentAlertHistorySection").style.display = "";
        $("tlAgentNameBadge").textContent = name;
        $("tlAgentTeamLabel").textContent = teamLabel(myTeam);
        $("tlAgentWhoTitle").textContent = "Qui est " + name;
        $("tlAgentPhotoWrap").innerHTML = '<i class="bi bi-person-fill" style="font-size:4rem;color:#adb5bd;"></i>';
        if (username) {
            getJson("/api/users/" + encodeURIComponent(username) + "/team-info").then(function (info) {
                if (info.photoUrl) {
                    $("tlAgentPhotoWrap").innerHTML = '<img src="' + escapeHtml(info.photoUrl) + '" style="width:100%;height:100%;object-fit:cover;">';
                }
            }).catch(function () {});
        }

        var salesLine = myTeam === "OUTBOUND"
            ? '<tr><td>Ventes ce mois</td><td><strong>' + (salesByAgentCache[name] || 0) + '</strong></td></tr>' : "";
        $("tlAgentModalBody").innerHTML =
            '<table class="table table-sm mb-0">' +
                '<tr><td>Score QA</td><td>' + fmtScore(m.SCORE_QA) + '</td></tr>' +
                '<tr><td>Score évaluation</td><td>' + (m.SCORE_EVALUATION != null ? m.SCORE_EVALUATION + " %" : "—") + '</td></tr>' +
                '<tr><td>Interactions</td><td>' + fmtScore(m.INTERACTIONS) + '</td></tr>' +
                '<tr><td>Évaluations QA (total)</td><td>' + (r.evaluationCount || 0) + '</td></tr>' +
                '<tr><td>Score qualité moyen</td><td>' + (r.avgQualityScore != null ? r.avgQualityScore + " %" : "—") + '</td></tr>' +
                '<tr><td>Présence</td><td>' + fmtPct(r.presenceRate) + '</td></tr>' +
                '<tr><td>Performance globale</td><td>' + fmtPct(r.performanceGlobale) + '</td></tr>' +
                salesLine +
            '</table>';
        agentModal.show();

        loadAgentLatestEvaluation(username, name);

        var historyEl = $("tlAgentAlertHistory");
        historyEl.innerHTML = '<p class="text-muted small">Chargement…</p>';
        if (r.userId) {
            getJson("/api/data-analysis/alerts/agent/" + r.userId).then(function (history) {
                if (!history.length) { historyEl.innerHTML = '<p class="text-muted small mb-0">Aucune alerte enregistrée pour cet agent.</p>'; return; }
                historyEl.innerHTML = '<table class="table table-sm mb-0"><thead><tr><th>Mois</th><th>Type</th><th>Sévérité</th></tr></thead><tbody>' +
                    history.map(function (h) {
                        var sevBadge = { CRITIQUE: '<span class="badge bg-danger">Critique</span>',
                            ATTENTION: '<span class="badge bg-warning text-dark">Attention</span>' }[h.severity] || h.severity;
                        return "<tr><td>" + h.periodMonth + "</td><td>" + escapeHtml(ALERT_TYPE_LABELS_TL[h.alertType] || h.alertType) + "</td><td>" + sevBadge + "</td></tr>";
                    }).join("") + "</tbody></table>";
            }).catch(function () {
                historyEl.innerHTML = '<p class="text-danger small mb-0">Erreur de chargement.</p>';
            });
        } else {
            historyEl.innerHTML = '<p class="text-muted small mb-0">Identifiant agent indisponible.</p>';
        }
    }

    /** Barres de critères + résumé (points forts/axes d'amélioration) de la dernière évaluation
     *  QA de cet agent — même présentation que le clic sur "Note QA" depuis Évaluations QA. */
    function loadAgentLatestEvaluation(username, name) {
        var barsSection = $("tlAgentQaBarsSection");
        var summarySection = $("tlAgentQaSummary");
        barsSection.style.display = "none";
        summarySection.style.display = "none";
        if (!username) return;

        getJson("/api/team-leader/quality-evaluations").then(function (evals) {
            var matches = evals.filter(function (e) { return e.agentMatricule === username; })
                .sort(function (a, b) { return new Date(b.evaluationDate) - new Date(a.evaluationDate); });
            if (!matches.length) return;
            var latest = matches[0];

            if (latest.scorePercentage != null) {
                var resultBadge = latest.knockedOut ? '<span class="badge bg-danger">Éliminatoire</span>'
                    : (latest.passed ? '<span class="badge bg-success">Validé</span>' : '<span class="badge bg-warning text-dark">Non validé</span>');
                $("tlAgentQaBars").innerHTML =
                    '<div class="mb-2">' +
                        '<div class="d-flex justify-content-between small mb-1"><span>Score global — ' + fmtDate(latest.evaluationDate) + '</span><span>' + Math.round(latest.scorePercentage) + ' %</span></div>' +
                        '<div class="rounded-pill" style="height:10px;background:#eef0f4;overflow:hidden;"><div style="height:100%;width:' + Math.round(latest.scorePercentage) + '%;background:#F5A623;"></div></div>' +
                    '</div>' +
                    '<div class="mt-1">' + resultBadge + (latest.motifLabel ? ' <span class="text-muted small ms-2">' + escapeHtml(latest.motifLabel) + '</span>' : '') + '</div>';
                barsSection.style.display = "";
            }

            if (latest.strengths || latest.improvements) {
                var html = "";
                if (latest.strengths) html += '<p class="mb-2"><strong>Points forts :</strong> ' + escapeHtml(latest.strengths) + '</p>';
                if (latest.improvements) html += '<p class="mb-0"><strong>Axes d\'amélioration :</strong> ' + escapeHtml(latest.improvements) + '</p>';
                $("tlAgentQaSummaryBody").innerHTML = html;
                summarySection.style.display = "";
            } else {
                $("tlAgentQaSummaryBody").innerHTML = '<p class="text-muted mb-0">Aucun commentaire détaillé sur cette évaluation.</p>';
                summarySection.style.display = "";
            }

            // Boucle la consultation sur un TODO d'engagement — pré-rempli avec les axes
            // d'amélioration de CETTE évaluation précise, à valider par l'agent après entretien.
            $("tlAgentCreateTodoBtn").onclick = function () {
                openQaCoachingModal(username, latest.evaluationDate);
                if (latest.improvements) {
                    $("tlQaCoachingNotes").value = "Suite à la note QA du " + fmtDate(latest.evaluationDate) +
                        " (" + Math.round(latest.scorePercentage) + " %) — axes d'amélioration discutés : " + latest.improvements;
                }
            };
        }).catch(function () {});
    }

    function updateHeaderStats() {
        $("tlStatMembers").textContent = reportingCache.length;
        var scores = reportingCache.map(function (r) { return r.kpiMetrics && r.kpiMetrics.SCORE_QA; }).filter(function (v) { return v != null; });
        $("tlStatQaScore").textContent = scores.length ? Math.round(scores.reduce(function (a, b) { return a + b; }, 0) / scores.length) + " %" : "—";
        var totalEval = reportingCache.reduce(function (sum, r) { return sum + (r.evaluationCount || 0); }, 0);
        $("tlStatEval").textContent = totalEval;
    }

    // ===================== MEMBRES =====================

    function loadMembers() {
        getJson("/api/team-leader/members/full").then(function (members) {
            var container = $("tlMembersList");
            if (!members.length) { container.innerHTML = '<p class="text-muted text-center">Aucun agent dans cette équipe.</p>'; return; }
            container.innerHTML = members.map(function (u, idx) {
                var statusBadge = u.active ? '<span class="badge bg-success">Actif</span>' : '<span class="badge bg-secondary">Inactif</span>';
                return '<div class="border rounded p-3 mb-2 tl-member-row" data-member-idx="' + idx + '" style="cursor:pointer;">' +
                    '<div class="d-flex justify-content-between align-items-center">' +
                        '<div><strong>' + escapeHtml(u.fullName || u.username) + '</strong> ' + statusBadge +
                            '<div class="small text-muted">' + escapeHtml(u.email || "—") + ' · ' + escapeHtml(u.affiliateBranch || "—") + '</div></div>' +
                        '<div class="d-flex align-items-center gap-2">' +
                            '<button type="button" class="btn btn-sm btn-outline-primary tl-meet-member-btn" data-username="' + escapeHtml(u.username) + '" title="Planifier un meeting tête-à-tête"><i class="bi bi-people-fill"></i> Meeting</button>' +
                            '<button type="button" class="btn btn-sm btn-outline-danger tl-remove-member-btn" data-id="' + u.id + '" data-name="' + escapeHtml(u.fullName || u.username) + '" title="Retirer de mon équipe"><i class="bi bi-person-dash-fill"></i></button>' +
                            '<i class="bi bi-chevron-right text-muted"></i>' +
                        '</div>' +
                    '</div></div>';
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll(".tl-member-row"), function (row) {
                row.addEventListener("click", function () { openMemberDetail(members[Number(row.getAttribute("data-member-idx"))]); });
            });
            Array.prototype.forEach.call(container.querySelectorAll(".tl-meet-member-btn"), function (btn) {
                btn.addEventListener("click", function (e) {
                    e.stopPropagation();
                    RccMeetings.openSchedule(btn.getAttribute("data-username"));
                });
            });
            Array.prototype.forEach.call(container.querySelectorAll(".tl-remove-member-btn"), function (btn) {
                btn.addEventListener("click", function (e) {
                    e.stopPropagation();
                    removeMemberFlow(btn.getAttribute("data-id"), btn.getAttribute("data-name"));
                });
            });
        }).catch(function (e) {
            $("tlMembersList").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    /** Retrait d'un agent — demande d'abord confirmation, puis distingue démission (désactive
     *  aussi le compte, visible immédiatement RH/Superviseur/Reporting) d'un simple changement
     *  d'équipe (le compte reste actif, juste sorti de ma liste). */
    function removeMemberFlow(userId, name) {
        if (!confirm("Retirer " + name + " de votre équipe ?")) return;
        var resigned = confirm("Est-ce une démission ? (le compte sera aussi désactivé — OK = oui, Annuler = non, simple changement d'équipe)");
        fetch("/api/team-leader/members/" + userId + "?resigned=" + resigned, { method: "DELETE", credentials: "same-origin" })
            .then(function (res) {
                if (!res.ok && res.status !== 204) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                loadMembers();
            })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    var addMemberModal = null, addMemberSearchTimer = null;

    // ═══════════════════════════════════════════════════════════════════
    // PLANNING (onglet Team Leader) — même principe que la modale "Planifier" d'Excelliam :
    // on coche des agents de SA PROPRE équipe et on choisit un shift chacun sur une période,
    // puis "Envoyer à Excelliam" (statut PENDING/origin TEAM_LEADER). Une fois qu'Excelliam
    // valide (statut VALIDATED), le bouton "Mise à jour" apparaît pour rendre le planning
    // effectif (statut APPROVED) — voir ScheduleService.submitTeamPlanning/publishTeamPlanning.
    // ═══════════════════════════════════════════════════════════════════

    var planStatusFromCache = null, planStatusToCache = null;

    function loadPlanningTab() {
        $("tlPlanFrom").value = todayIso();
        $("tlPlanTo").value = todayIso();
        $("tlPlanResult").textContent = "";
        $("tlPlanCheckAll").checked = false;
        $("tlPlanStatusList").innerHTML = '<p class="text-muted small">Choisissez une période puis cliquez "Actualiser".</p>';
        $("tlPlanPublishBtn").classList.add("d-none");

        getJson("/api/team-leader/members/full").then(function (members) {
            var active = members.filter(function (u) { return u.active; })
                .sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || "", "fr"); });

            $("tlPlanAgentList").innerHTML = active.length ? active.map(function (u) {
                return '<div class="exc-agent-row" data-username="' + escapeHtml(u.username) + '">' +
                    '<input type="checkbox" class="form-check-input tl-plan-check">' +
                    '<div class="exc-agent-name">' + escapeHtml(u.fullName || u.username) + '<small>' + escapeHtml(u.username) + '</small></div>' +
                    '<select class="form-select form-select-sm exc-shift-select tl-plan-shift" disabled>' +
                    '<option value="M">07h–16h (M)</option>' +
                    '<option value="M2">08h–17h (M2)</option>' +
                    '<option value="A">12h–21h (A)</option>' +
                    '<option value="N">21h–06h (N)</option>' +
                    '<option value="OFF">Repos (OFF)</option>' +
                    '</select></div>';
            }).join("") : '<p class="text-muted text-center small">Aucun agent actif dans votre équipe.</p>';

            Array.prototype.forEach.call($("tlPlanAgentList").querySelectorAll(".exc-agent-row"), function (row) {
                var checkbox = row.querySelector(".tl-plan-check");
                var select = row.querySelector(".tl-plan-shift");
                checkbox.addEventListener("change", function () {
                    select.disabled = !checkbox.checked;
                    row.classList.toggle("checked", checkbox.checked);
                    updatePlanCheckedCount();
                });
            });
            updatePlanCheckedCount();
        }).catch(function (e) {
            $("tlPlanAgentList").innerHTML = '<p class="text-danger text-center small">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function updatePlanCheckedCount() {
        $("tlPlanCheckedCount").textContent = $("tlPlanAgentList").querySelectorAll(".tl-plan-check:checked").length;
    }

    var PLAN_STATUS_LABELS = {
        PENDING: '<span class="badge bg-warning text-dark">Envoyé — en attente d\'Excelliam</span>',
        VALIDATED: '<span class="badge bg-info text-dark">Validé par Excelliam — prêt pour mise à jour</span>',
        REJECTED: '<span class="badge bg-danger">Refusé par Excelliam</span>',
        APPROVED: '<span class="badge bg-success">En ligne</span>'
    };

    function loadPlanningStatus() {
        var from = $("tlPlanFrom").value, to = $("tlPlanTo").value;
        if (!from || !to) { alert("Choisissez une période."); return; }
        planStatusFromCache = from; planStatusToCache = to;
        $("tlPlanStatusList").innerHTML = '<p class="text-muted small">Chargement…</p>';
        getJson("/api/schedule/team?from=" + from + "&to=" + to).then(function (entries) {
            var mine = entries.filter(function (e) { return e.origin === "TEAM_LEADER"; });
            if (!mine.length) {
                $("tlPlanStatusList").innerHTML = '<p class="text-muted small">Aucun planning envoyé par vous sur cette période.</p>';
                $("tlPlanPublishBtn").classList.add("d-none");
                return;
            }
            var hasValidated = mine.some(function (e) { return e.approvalStatus === "VALIDATED"; });
            $("tlPlanPublishBtn").classList.toggle("d-none", !hasValidated);

            var byStatus = {};
            mine.forEach(function (e) { (byStatus[e.approvalStatus] = byStatus[e.approvalStatus] || []).push(e); });
            $("tlPlanStatusList").innerHTML = Object.keys(byStatus).map(function (status) {
                var list = byStatus[status];
                var reason = list.find(function (e) { return e.rejectionReason; });
                return '<div class="mb-2">' + (PLAN_STATUS_LABELS[status] || status) +
                    ' — ' + list.length + ' entrée(s)' +
                    (reason ? '<div class="small text-danger mt-1">Motif : ' + escapeHtml(reason.rejectionReason) + '</div>' : '') +
                    '</div>';
            }).join("");
        }).catch(function (e) {
            $("tlPlanStatusList").innerHTML = '<p class="text-danger small">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function wirePlanningTab() {
        $("tlPlanCheckAll").addEventListener("change", function () {
            var checkAll = $("tlPlanCheckAll").checked;
            Array.prototype.forEach.call($("tlPlanAgentList").querySelectorAll(".tl-plan-check"), function (cb) {
                if (cb.checked !== checkAll) { cb.checked = checkAll; cb.dispatchEvent(new Event("change")); }
            });
        });

        $("tlPlanSubmitBtn").addEventListener("click", function () {
            var from = $("tlPlanFrom").value, to = $("tlPlanTo").value;
            var resultBox = $("tlPlanResult");
            if (!from || !to) { resultBox.className = "text-danger"; resultBox.textContent = "Choisissez une période."; return; }

            var assignments = [];
            Array.prototype.forEach.call($("tlPlanAgentList").querySelectorAll(".exc-agent-row"), function (row) {
                var checkbox = row.querySelector(".tl-plan-check");
                if (!checkbox.checked) return;
                assignments.push({ username: row.getAttribute("data-username"), shiftCode: row.querySelector(".tl-plan-shift").value });
            });
            if (!assignments.length) { resultBox.className = "text-danger"; resultBox.textContent = "Cochez au moins un agent."; return; }

            resultBox.className = "text-muted";
            resultBox.textContent = "Envoi à Excelliam…";
            fetch("/api/schedule/team/submit", {
                method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ periodFrom: from, periodTo: to, assignments: assignments })
            })
                .then(readImportResponse)
                .then(function (result) {
                    resultBox.className = "text-success";
                    resultBox.textContent = result.agentsPlanified + " agent(s) envoyé(s) à Excelliam (" + result.entriesCreated + " jour(s) au total).";
                    loadPlanningStatus();
                })
                .catch(function (e) {
                    resultBox.className = "text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });

        $("tlPlanRefreshStatusBtn").addEventListener("click", loadPlanningStatus);

        $("tlPlanPublishBtn").addEventListener("click", function () {
            if (!planStatusFromCache || !planStatusToCache) return;
            if (!confirm("Confirmer la mise à jour globale ? Le planning validé par Excelliam deviendra immédiatement visible pour toute l'équipe.")) return;
            fetch("/api/schedule/team/publish?from=" + planStatusFromCache + "&to=" + planStatusToCache, { method: "POST", credentials: "same-origin" })
                .then(readImportResponse)
                .then(function (result) {
                    alert(result.entriesPublished + " entrée(s) mise(s) à jour et désormais en ligne.");
                    loadPlanningStatus();
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    function openAddMemberModal() {
        $("tlAddMemberSearch").value = "";
        $("tlAddMemberResults").innerHTML = "";
        addMemberModal.show();
        searchAddableAgents("");
    }

    function searchAddableAgents(q) {
        getJson("/api/team-leader/members/candidates?q=" + encodeURIComponent(q)).then(function (list) {
            var box = $("tlAddMemberResults");
            if (!list.length) { box.innerHTML = '<p class="text-muted small text-center mb-0">Aucun agent trouvé.</p>'; return; }
            box.innerHTML = list.map(function (u) {
                return '<div class="d-flex justify-content-between align-items-center border rounded p-2">' +
                    '<div><div>' + escapeHtml(u.fullName || u.username) + '</div><div class="small text-muted">' + escapeHtml(u.activity || "Non classée") + ' · ' + escapeHtml(u.affiliateBranch || "—") + '</div></div>' +
                    '<button type="button" class="btn btn-sm btn-primary tl-add-candidate-btn" data-id="' + u.id + '">Ajouter</button>' +
                    '</div>';
            }).join("");
            Array.prototype.forEach.call(box.querySelectorAll(".tl-add-candidate-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    fetch("/api/team-leader/members/" + btn.getAttribute("data-id"), { method: "POST", credentials: "same-origin" })
                        .then(function (res) {
                            if (!res.ok && res.status !== 204) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                            addMemberModal.hide();
                            loadMembers();
                        })
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function (e) {
            $("tlAddMemberResults").innerHTML = '<p class="text-danger small mb-0">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    /** Fiche agent en mode Membre — mêmes photo/badge que openAgentDetail(), mais avec la
     *  section RH éditable (contrat, résidence) au lieu des KPI de performance. */
    function openMemberDetail(u) {
        var name = u.fullName || u.username;
        $("tlAgentNameBadge").textContent = name;
        $("tlAgentTeamLabel").textContent = teamLabel(myTeam);
        $("tlAgentWhoTitle").textContent = "Qui est " + name;
        $("tlAgentPhotoWrap").innerHTML = u.photoUrl
            ? '<img src="' + escapeHtml(u.photoUrl) + '" style="width:100%;height:100%;object-fit:cover;">'
            : '<i class="bi bi-person-fill" style="font-size:4rem;color:#adb5bd;"></i>';

        $("tlAgentModalBody").innerHTML =
            '<table class="table table-sm mb-0">' +
                '<tr><td>Email</td><td>' + escapeHtml(u.email || "—") + '</td></tr>' +
                '<tr><td>Filiale</td><td>' + escapeHtml(u.affiliateBranch || "—") + '</td></tr>' +
                '<tr><td>Statut</td><td>' + (u.active ? "Actif" : "Inactif") + '</td></tr>' +
            '</table>';

        $("tlAgentQaBarsSection").style.display = "none";
        $("tlAgentQaSummary").style.display = "none";
        $("tlAgentAlertHistorySection").style.display = "none"; // pas d'historique d'alertes en mode Membre — hors sujet ici

        $("tlAgentContractType").value = u.contractType || "";
        $("tlAgentContractStatus").value = u.contractStatus || "";
        $("tlAgentContractStart").value = u.contractStartDate || "";
        $("tlAgentContractEnd").value = u.contractEndDate || "";
        $("tlAgentResidence").value = u.residencePlace || "";
        $("tlAgentSaveHrStatus").classList.add("d-none");
        $("tlAgentHrSection").style.display = "";

        $("tlAgentSaveHrBtn").onclick = function () {
            sendJson("/api/users/" + u.id + "/hr-fields", "PUT", {
                contractType: $("tlAgentContractType").value || null,
                contractStatus: $("tlAgentContractStatus").value || null,
                contractStartDate: $("tlAgentContractStart").value || null,
                contractEndDate: $("tlAgentContractEnd").value || null,
                residencePlace: $("tlAgentResidence").value.trim() || null
            }).then(function () {
                $("tlAgentSaveHrStatus").classList.remove("d-none");
                loadMembers();
            }).catch(function (e) { alert("Erreur : " + e.message); });
        };

        agentModal.show();
    }

    // ===================== ÉVALUATIONS QA =====================

    function loadQa() {
        getJson("/api/team-leader/quality-evaluations").then(function (evals) {
            var body = $("tlQaBody");
            if (!evals.length) { body.innerHTML = '<tr><td colspan="6" class="text-center text-muted">Aucune évaluation pour cette équipe.</td></tr>'; return; }
            evals.sort(function (a, b) { return new Date(b.evaluationDate) - new Date(a.evaluationDate); });
            body.innerHTML = evals.map(function (e) {
                var resultBadge = e.knockedOut ? '<span class="badge bg-danger">Éliminatoire</span>'
                    : (e.passed ? '<span class="badge bg-success">Validé</span>' : '<span class="badge bg-warning text-dark">Non validé</span>');
                return "<tr><td>" + fmtDate(e.evaluationDate) + "</td>" +
                    '<td><a href="#" class="tl-qa-agent-link" data-username="' + escapeHtml(e.agentMatricule) + '" data-name="' + escapeHtml(e.agentName || e.agentMatricule) + '">' + escapeHtml(e.agentName || e.agentMatricule) + '</a></td>' +
                    "<td>" + escapeHtml(e.motifLabel || "—") + "</td>" +
                    "<td>" + Math.round(e.scorePercentage) + " %</td>" +
                    "<td>" + resultBadge + "</td>" +
                    '<td><button class="btn btn-sm btn-outline-primary" data-coach-username="' + escapeHtml(e.agentMatricule) + '" data-coach-date="' + e.evaluationDate + '"><i class="bi bi-chat-dots"></i> Entretien</button></td></tr>';
            }).join("");
            Array.prototype.forEach.call(body.querySelectorAll(".tl-qa-agent-link"), function (link) {
                link.addEventListener("click", function (evt) {
                    evt.preventDefault();
                    var r = reportingCache.find(function (row) { return row.username === link.getAttribute("data-username"); });
                    openAgentDetail(r || { username: link.getAttribute("data-username"), userFullName: link.getAttribute("data-name"), kpiMetrics: {} });
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll("[data-coach-username]"), function (btn) {
                btn.addEventListener("click", function () { openQaCoachingModal(btn.getAttribute("data-coach-username"), btn.getAttribute("data-coach-date")); });
            });
        }).catch(function (e) {
            $("tlQaBody").innerHTML = '<tr><td colspan="6" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    // ===================== VENTES / RDV ÉQUIPE (OUTBOUND uniquement) =====================

    function loadSales() {
        var from = $("tlSalesFrom").value, to = $("tlSalesTo").value;
        getJson("/api/outbound/sales/team?from=" + from + "&to=" + to).then(function (rows) {
            var container = $("tlSalesGroups");
            if (!rows.length) { container.innerHTML = '<p class="text-muted text-center">Aucune vente sur cette période.</p>'; return; }
            var byAgent = {};
            rows.forEach(function (s) {
                if (!byAgent[s.agentName]) byAgent[s.agentName] = [];
                byAgent[s.agentName].push(s);
            });
            var agentNames = Object.keys(byAgent).sort(function (a, b) { return a.localeCompare(b, "fr"); });
            container.innerHTML = agentNames.map(function (name, idx) {
                var sales = byAgent[name];
                var confirmedCount = sales.filter(function (s) { return s.status !== "CANCELLED"; }).length;
                var rowsHtml = sales.map(function (s) {
                    return "<tr><td>" + fmtDate(s.saleDate) + "</td><td>" + escapeHtml(s.productName) + "</td>" +
                        "<td>" + escapeHtml(s.clientName || "—") + "</td>" +
                        "<td>" + (s.amount != null ? s.amount.toLocaleString("fr-FR") + " F" : "—") + "</td>" +
                        '<td><span class="badge tl-status-badge ' + s.status + '">' + statusLabel(s.status) + "</span></td></tr>";
                }).join("");
                return '<div class="border rounded mb-2">' +
                    '<div class="d-flex justify-content-between align-items-center p-2 tl-sales-agent-row" data-sales-toggle="' + idx + '">' +
                        '<span><i class="bi bi-chevron-right tl-sales-chevron-' + idx + '"></i> <strong>' + escapeHtml(name) + '</strong></span>' +
                        '<span class="badge bg-primary">' + confirmedCount + ' vente(s)</span>' +
                    '</div>' +
                    '<div class="p-2 border-top" id="tl-sales-detail-' + idx + '" style="display:none;">' +
                        '<table class="table table-sm mb-0"><thead><tr><th>Date</th><th>Produit</th><th>Client</th><th>Montant</th><th>Statut</th></tr></thead>' +
                        '<tbody>' + rowsHtml + '</tbody></table>' +
                    '</div>' +
                '</div>';
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll("[data-sales-toggle]"), function (header) {
                header.addEventListener("click", function () {
                    var idx = header.getAttribute("data-sales-toggle");
                    var detail = $("tl-sales-detail-" + idx);
                    var showing = detail.style.display !== "none";
                    detail.style.display = showing ? "none" : "";
                    header.querySelector(".tl-sales-chevron-" + idx).className = "bi " + (showing ? "bi-chevron-right" : "bi-chevron-down") + " tl-sales-chevron-" + idx;
                });
            });
        }).catch(function (e) {
            $("tlSalesGroups").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    var rdvByAgent = {}; // agentName -> liste de RDV (période chargée par le filtre Du/Au)
    var currentAgentRdvName = null;
    var currentAgentGranularity = "day";
    var agentRdvModal;

    function loadRdv() {
        var from = $("tlRdvFrom").value, to = $("tlRdvTo").value;
        getJson("/api/outbound/appointments/team?from=" + from + "T00:00:00&to=" + to + "T23:59:59").then(function (rows) {
            var container = $("tlRdvGroups");
            if (!rows.length) { container.innerHTML = '<p class="text-muted text-center">Aucun rendez-vous sur cette période.</p>'; rdvByAgent = {}; return; }

            rdvByAgent = {};
            rows.forEach(function (a) {
                if (!rdvByAgent[a.agentName]) rdvByAgent[a.agentName] = [];
                rdvByAgent[a.agentName].push(a);
            });

            var agentNames = Object.keys(rdvByAgent).sort(function (a, b) { return a.localeCompare(b, "fr"); });
            container.innerHTML = agentNames.map(function (name) {
                var appts = rdvByAgent[name];
                var upcoming = appts.filter(function (a) { return a.status === "PLANNED"; }).length;
                return '<div class="d-flex justify-content-between align-items-center border rounded p-2 mb-2 tl-agent-rdv-row" data-agent="' + escapeHtml(name) + '" style="cursor:pointer;">' +
                    '<span><i class="bi bi-person-fill"></i> <strong>' + escapeHtml(name) + '</strong></span>' +
                    '<span class="d-flex gap-2">' +
                        '<span class="badge bg-primary">' + appts.length + ' RDV</span>' +
                        (upcoming ? '<span class="badge bg-warning text-dark">' + upcoming + ' à venir</span>' : "") +
                    '</span></div>';
            }).join("");

            Array.prototype.forEach.call(container.querySelectorAll(".tl-agent-rdv-row"), function (row) {
                row.addEventListener("click", function () { openAgentRdv(row.getAttribute("data-agent")); });
            });
        }).catch(function (e) {
            $("tlRdvGroups").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function openAgentRdv(agentName) {
        currentAgentRdvName = agentName;
        currentAgentGranularity = "day";
        $("tlAgentRdvModalTitle").innerHTML = '<i class="bi bi-calendar-check"></i> Rendez-vous de ' + escapeHtml(agentName);
        Array.prototype.forEach.call($("tlAgentRdvGranularity").querySelectorAll("button"), function (b) {
            b.classList.toggle("btn-primary", b.getAttribute("data-gran") === "day");
            b.classList.toggle("btn-outline-primary", b.getAttribute("data-gran") !== "day");
        });
        renderAgentRdv();
        agentRdvModal.show();
    }

    /** ISO week (année-Wsemaine), même logique que supervisor.js. */
    function isoWeekKey(date) {
        var d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
        var dayNum = d.getUTCDay() || 7;
        d.setUTCDate(d.getUTCDate() + 4 - dayNum);
        var yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
        var weekNo = Math.ceil((((d - yearStart) / 86400000) + 1) / 7);
        return d.getUTCFullYear() + " — Semaine " + weekNo;
    }

    function renderAgentRdv() {
        var appts = (rdvByAgent[currentAgentRdvName] || []).slice()
            .sort(function (a, b) { return new Date(a.scheduledAt) - new Date(b.scheduledAt); });
        var body = $("tlAgentRdvBody");
        if (!appts.length) { body.innerHTML = '<p class="text-muted text-center">Aucun rendez-vous.</p>'; return; }

        if (currentAgentGranularity === "day") {
            body.innerHTML = '<table class="table table-sm mb-0"><thead><tr><th>Quand</th><th>Client</th><th>Objet</th><th>Statut</th></tr></thead><tbody>' +
                appts.map(function (a) {
                    return "<tr><td>" + fmtDateTime(a.scheduledAt) + "</td><td>" + escapeHtml(a.clientName) + "</td>" +
                        "<td>" + escapeHtml(a.purpose || "—") + "</td>" +
                        '<td><span class="badge tl-status-badge ' + a.status + '">' + statusLabel(a.status) + "</span></td></tr>";
                }).join("") + "</tbody></table>";
            return;
        }

        var groupKey = currentAgentGranularity === "week"
            ? function (a) { return isoWeekKey(new Date(a.scheduledAt)); }
            : function (a) { return new Date(a.scheduledAt).toLocaleDateString("fr-FR", { month: "long", year: "numeric" }); };

        var groups = {};
        var order = [];
        appts.forEach(function (a) {
            var key = groupKey(a);
            if (!groups[key]) { groups[key] = []; order.push(key); }
            groups[key].push(a);
        });

        body.innerHTML = order.map(function (key) {
            var rows = groups[key].map(function (a) {
                return "<tr><td>" + fmtDateTime(a.scheduledAt) + "</td><td>" + escapeHtml(a.clientName) + "</td>" +
                    "<td>" + escapeHtml(a.purpose || "—") + "</td>" +
                    '<td><span class="badge tl-status-badge ' + a.status + '">' + statusLabel(a.status) + "</span></td></tr>";
            }).join("");
            return '<h6 class="text-capitalize mt-3">' + escapeHtml(key) + ' <span class="badge bg-secondary">' + groups[key].length + '</span></h6>' +
                '<table class="table table-sm mb-0"><thead><tr><th>Quand</th><th>Client</th><th>Objet</th><th>Statut</th></tr></thead><tbody>' + rows + '</tbody></table>';
        }).join("");
    }

    // ===================== CAMPAGNES (OUTBOUND) =====================

    var newCampaignModal, campaignDetailModal;
    var currentCampaignId = null;
    var campaignContactsCache = [];
    var currentContactStatusFilter = null; // null = tous
    var teamMembersCache = [];
    var editingCampaignFields = []; // questions de la campagne en cours de création — voir renderCampaignFieldsEditor

    var STATUS_COLORS = { PENDING: "#adb5bd", GREEN: "#00A651", RED: "#dc3545", YELLOW: "#F5A623" };
    var STATUS_LABELS_CALL = { PENDING: "À appeler", GREEN: "Interaction", RED: "Pas de réponse", YELLOW: "RDV pris" };

    /** Cercle chromatique (donut CSS conic-gradient) — pas de librairie externe nécessaire. */
    function buildDonutHtml(counts, size, holeLabel) {
        var order = ["GREEN", "YELLOW", "RED", "PENDING"];
        var total = order.reduce(function (sum, k) { return sum + (counts[k] || 0); }, 0);
        var gradientParts = [];
        var cursor = 0;
        if (total === 0) {
            gradientParts.push(STATUS_COLORS.PENDING + " 0deg 360deg");
        } else {
            order.forEach(function (key) {
                var value = counts[key] || 0;
                if (!value) return;
                var start = (cursor / total) * 360;
                cursor += value;
                var end = (cursor / total) * 360;
                gradientParts.push(STATUS_COLORS[key] + " " + start + "deg " + end + "deg");
            });
        }
        var holeSize = Math.round(size * 0.62);
        return '<div class="tl-donut" style="width:' + size + 'px;height:' + size + 'px;background:conic-gradient(' + gradientParts.join(",") + ');">' +
            '<div class="tl-donut-hole" style="width:' + holeSize + 'px;height:' + holeSize + 'px;font-size:' + Math.round(size * 0.16) + 'px;">' + holeLabel + '</div>' +
        '</div>';
    }

    function legendHtml(counts, clickable) {
        var order = ["GREEN", "YELLOW", "RED", "PENDING"];
        return order.map(function (key) {
            var value = counts[key] || 0;
            var cls = "tl-legend-item small" + (clickable && currentContactStatusFilter === key ? " active" : "");
            var attr = clickable ? ' data-legend-status="' + key + '"' : "";
            return '<span class="' + cls + '"' + attr + '><span class="tl-legend-dot" style="background:' + STATUS_COLORS[key] + ';"></span>' +
                STATUS_LABELS_CALL[key] + ' (' + value + ')</span>';
        }).join(" ");
    }

    function countsFromContacts(contacts) {
        var counts = { PENDING: 0, GREEN: 0, RED: 0, YELLOW: 0 };
        contacts.forEach(function (c) { counts[c.callStatus] = (counts[c.callStatus] || 0) + 1; });
        return counts;
    }

    function loadCampaigns() {
        getJson("/api/campaigns").then(function (campaigns) {
            var container = $("tlCampaignsList");
            if (!campaigns.length) { container.innerHTML = '<p class="text-muted text-center">Aucune campagne pour l\'instant.</p>'; return; }
            container.innerHTML = campaigns.map(function (c) {
                var statusBadge = c.status === "ACTIVE" ? '<span class="badge bg-success">Active</span>' : '<span class="badge bg-secondary">Clôturée</span>';
                var serviceBadge = c.targetService === "DIGITAL" ? '<span class="badge bg-primary">Service Digital</span>'
                    : c.targetService === "TELEVENTE" ? '<span class="badge bg-info text-dark">Télévente</span>'
                    : '<span class="badge bg-light text-dark border">Toute l\'équipe</span>';
                var counts = { GREEN: c.contacted, YELLOW: c.appointmentsTaken,
                    RED: Math.max(0, c.callsMade - c.contacted - c.appointmentsTaken),
                    PENDING: Math.max(0, c.totalContacts - c.callsMade) };
                return '<div class="border rounded p-3 mb-2 tl-campaign-row d-flex align-items-center gap-3" data-campaign-id="' + c.campaignId + '" style="cursor:pointer;">' +
                    buildDonutHtml(counts, 56, c.totalContacts) +
                    '<div class="flex-grow-1">' +
                        '<div class="d-flex justify-content-between align-items-start">' +
                            '<div><strong>' + escapeHtml(c.name) + '</strong> ' + statusBadge + ' ' + serviceBadge +
                                (c.description ? '<div class="small text-muted">' + escapeHtml(c.description) + '</div>' : '') + '</div>' +
                            '<span class="text-muted small">' + new Date(c.createdAt).toLocaleDateString("fr-FR") + '</span>' +
                        '</div>' +
                        '<div class="mt-1">' + legendHtml(counts, false) + '</div>' +
                        (c.unassigned ? '<div class="text-warning small mt-1"><i class="bi bi-exclamation-triangle"></i> ' + c.unassigned + ' non assigné(s)</div>' : '') +
                    '</div></div>';
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll(".tl-campaign-row"), function (row) {
                row.addEventListener("click", function () { openCampaignDetail(Number(row.getAttribute("data-campaign-id")), campaigns); });
            });
        }).catch(function (e) {
            $("tlCampaignsList").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    $("tlNewCampaignBtn").addEventListener("click", function () {
        $("tlCampaignName").value = ""; $("tlCampaignDescription").value = "";
        $("tlCampaignStart").value = ""; $("tlCampaignEnd").value = "";
        $("tlCampaignTargetService").value = "";
        $("tlCampaignIcon").value = "bi-megaphone-fill";
        $("tlCampaignColorFrom").value = "#0057B8";
        $("tlCampaignColorTo").value = "#00A651";
        editingCampaignFields = [];
        renderCampaignFieldsEditor();
        newCampaignModal.show();
    });

    /** Constructeur de questions — une ligne par question, type/options/obligatoire, comme
     *  vu par l'agent dans la modale d'appel séquentielle du tableau de bord Outbound. */
    function renderCampaignFieldsEditor() {
        var container = $("tlCampaignFieldsEditor");
        if (!editingCampaignFields.length) {
            container.innerHTML = '<p class="text-muted small mb-2">Aucune question — les 4 boutons de statut (À appeler/Interaction/Pas de réponse/RDV pris) suffisent pour cette campagne.</p>';
            return;
        }
        container.innerHTML = editingCampaignFields.map(function (f, idx) {
            var showOptions = f.type === "SELECT" || f.type === "RADIO";
            return '<div class="border rounded p-2 mb-2" data-field-idx="' + idx + '">' +
                '<div class="row g-2 align-items-center">' +
                    '<div class="col-md-5"><input type="text" class="form-control form-control-sm tl-field-label" placeholder="Intitulé de la question" value="' + escapeHtml(f.label) + '"></div>' +
                    '<div class="col-md-3"><select class="form-select form-select-sm tl-field-type">' +
                        ["TEXT", "TEXTAREA", "SELECT", "RADIO", "DATE", "TIME"].map(function (t) {
                            return '<option value="' + t + '" ' + (t === f.type ? "selected" : "") + '>' + t + '</option>';
                        }).join("") + '</select></div>' +
                    '<div class="col-md-2 form-check form-switch pt-1"><input class="form-check-input tl-field-required" type="checkbox" ' + (f.required ? "checked" : "") + '><label class="form-check-label small">Obligatoire</label></div>' +
                    '<div class="col-md-2 text-end"><button class="btn btn-sm btn-outline-danger tl-field-remove" type="button"><i class="bi bi-trash"></i></button></div>' +
                '</div>' +
                '<div class="mt-2" style="' + (showOptions ? "" : "display:none;") + '">' +
                    '<label class="form-label small mb-1">Choix proposés (un par ligne)</label>' +
                    '<textarea class="form-control form-control-sm tl-field-options" rows="3">' + escapeHtml((f.options || []).join("\n")) + '</textarea>' +
                '</div>' +
            '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll("[data-field-idx]"), function (row) {
            var idx = Number(row.getAttribute("data-field-idx"));
            row.querySelector(".tl-field-label").addEventListener("input", function () { editingCampaignFields[idx].label = this.value; });
            row.querySelector(".tl-field-required").addEventListener("change", function () { editingCampaignFields[idx].required = this.checked; });
            row.querySelector(".tl-field-options").addEventListener("input", function () {
                editingCampaignFields[idx].options = this.value.split("\n").map(function (s) { return s.trim(); }).filter(Boolean);
            });
            row.querySelector(".tl-field-type").addEventListener("change", function () {
                editingCampaignFields[idx].type = this.value;
                row.querySelector(".mt-2").style.display = (this.value === "SELECT" || this.value === "RADIO") ? "" : "none";
            });
            row.querySelector(".tl-field-remove").addEventListener("click", function () {
                editingCampaignFields.splice(idx, 1);
                renderCampaignFieldsEditor();
            });
        });
    }

    $("tlCampaignAddFieldBtn").addEventListener("click", function () {
        editingCampaignFields.push({ id: "q" + Date.now() + "_" + editingCampaignFields.length, label: "", type: "TEXT", options: [], required: false });
        renderCampaignFieldsEditor();
    });

    $("tlSaveCampaignBtn").addEventListener("click", function () {
        var name = $("tlCampaignName").value.trim();
        if (!name) { alert("Le nom de la campagne est obligatoire."); return; }
        var fields = editingCampaignFields.filter(function (f) { return f.label.trim(); });
        sendJson("/api/campaigns", "POST", {
            name: name,
            description: $("tlCampaignDescription").value.trim() || null,
            startDate: $("tlCampaignStart").value || null,
            endDate: $("tlCampaignEnd").value || null,
            targetService: $("tlCampaignTargetService").value || null,
            iconClass: $("tlCampaignIcon").value.trim() || null,
            colorFrom: $("tlCampaignColorFrom").value || null,
            colorTo: $("tlCampaignColorTo").value || null,
            fields: fields
        }).then(function () {
            newCampaignModal.hide();
            loadCampaigns();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    function openCampaignDetail(campaignId, campaigns) {
        currentCampaignId = campaignId;
        currentContactStatusFilter = null;
        var campaign = campaigns.find(function (c) { return c.campaignId === campaignId; });
        $("tlCampaignDetailTitle").innerHTML = '<i class="bi bi-megaphone-fill"></i> ' + escapeHtml(campaign ? campaign.name : "");
        $("tlCampaignImportFile").value = "";
        $("tlCampaignImportStatus").textContent = "";
        $("tlImportReport").innerHTML = "";
        $("tlDistributeAgents").removeAttribute("data-for");
        contactsShown = 200;
        if (!teamMembersCache.length) {
            getJson("/api/team-leader/members").then(function (members) { teamMembersCache = members; loadCampaignContacts(); });
        } else {
            loadCampaignContacts();
        }
        campaignDetailModal.show();
    }

    function campaignContactStatusBadge(status) {
        var colors = { PENDING: "secondary", GREEN: "success", RED: "danger", YELLOW: "warning" };
        var textClass = status === "YELLOW" ? " text-dark" : "";
        return '<span class="badge bg-' + colors[status] + textClass + '">' + STATUS_LABELS_CALL[status] + '</span>';
    }

    function renderCampaignDetail() {
        var counts = countsFromContacts(campaignContactsCache);
        $("tlCampaignDonutRow").innerHTML =
            buildDonutHtml(counts, 100, campaignContactsCache.length) +
            '<div>' +
                '<div class="small text-muted mb-1">Cliquez sur une couleur pour filtrer la liste</div>' +
                '<div class="d-flex flex-column gap-1">' + legendHtml(counts, true).replace(/<\/span> <span/g, "</span><br><span") + '</div>' +
            '</div>';
        Array.prototype.forEach.call($("tlCampaignDonutRow").querySelectorAll("[data-legend-status]"), function (el) {
            el.addEventListener("click", function () {
                var status = el.getAttribute("data-legend-status");
                currentContactStatusFilter = currentContactStatusFilter === status ? null : status;
                renderCampaignDetail();
            });
        });

        renderDistributeBox();
        var allVisible = currentContactStatusFilter ? campaignContactsCache.filter(function (c) { return c.callStatus === currentContactStatusFilter; }) : campaignContactsCache;
        // Grandes campagnes (plusieurs milliers de contacts) : affichage par tranches de 200.
        var visible = allVisible.slice(0, contactsShown);
        $("tlContactsMore").innerHTML = allVisible.length > visible.length
            ? '<button class="btn btn-sm btn-outline-primary" id="tlContactsMoreBtn">Afficher 200 de plus (' + visible.length + ' / ' + allVisible.length + ')</button>' : "";
        if ($("tlContactsMoreBtn")) $("tlContactsMoreBtn").addEventListener("click", function () { contactsShown += 200; renderCampaignDetail(); });
        var body = $("tlCampaignContactsBody");
        if (!visible.length) { body.innerHTML = '<tr><td colspan="7" class="text-center text-muted">Aucun contact' + (currentContactStatusFilter ? " dans ce filtre." : " importé.") + '</td></tr>'; return; }

        body.innerHTML = visible.map(function (c) {
            var agentSelect = '<select class="form-select form-select-sm tl-assign-select" data-contact-id="' + c.contactId + '" style="min-width:150px;">' +
                '<option value="">Non assigné</option>' +
                teamMembersCache.map(function (m) {
                    var selected = c.agentUserId === m.id ? " selected" : "";
                    return '<option value="' + m.id + '"' + selected + '>' + escapeHtml(m.fullName || m.username) + '</option>';
                }).join("") + '</select>';
            return "<tr><td>" + escapeHtml(c.clientName) + "</td>" +
                "<td>" + escapeHtml(c.clientPhone || "—") + "</td>" +
                "<td>" + escapeHtml(c.maskedAccountNumber || "—") + "</td>" +
                "<td>" + agentSelect + "</td>" +
                "<td>" + campaignContactStatusBadge(c.callStatus) + "</td>" +
                "<td>" + (c.lastCalledAt ? new Date(c.lastCalledAt).toLocaleString("fr-FR") : "—") + "</td>" +
                "<td>" + (c.appointmentId ? '<i class="bi bi-calendar-check text-success" title="RDV lié"></i>' : "") + "</td></tr>";
        }).join("");

        Array.prototype.forEach.call(body.querySelectorAll(".tl-assign-select"), function (select) {
            select.addEventListener("change", function () {
                var contactId = select.getAttribute("data-contact-id");
                sendJson("/api/campaigns/contacts/" + contactId + "/assign", "PUT", { agentUserId: select.value ? Number(select.value) : null })
                    .then(loadCampaignContacts)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    var STATUS_REPORT_LABELS = { PENDING: "À appeler", GREEN: "Interaction", RED: "Pas de réponse", YELLOW: "RDV pris" };
    function renderImportReport(r) {
        var chips = Object.keys(r.statusCounts || {}).map(function (k) { return '<span>' + (STATUS_REPORT_LABELS[k] || k) + ' : <b>' + r.statusCounts[k] + '</b></span>'; }).join("");
        var agents = Object.keys(r.matchedAgents || {}).map(function (k) { return '<span><i class="bi bi-person-check"></i> ' + escapeHtml(k) + ' : ' + r.matchedAgents[k] + '</span>'; }).join("");
        var unmatched = Object.keys(r.unmatchedAgents || {});
        var skipped = [];
        if (r.skippedDuplicates) skipped.push(r.skippedDuplicates + " doublon(s)");
        if (r.skippedNotToRecall) skipped.push(r.skippedNotToRecall + " déjà joint(s)");
        if (r.skippedWithoutName) skipped.push(r.skippedWithoutName + " sans nom");
        var sync = (r.updated || r.unchanged) ? ' · <b>' + (r.updated || 0) + ' mis à jour</b>, ' + (r.unchanged || 0) + ' déjà à jour' : "";
        var model = r.totalQuestions ? '<div class="small mt-1"><i class="bi bi-ui-checks"></i> Modèle de la campagne : ' + r.matchedQuestions + '/' + r.totalQuestions + ' question(s) pré-remplie(s) depuis le fichier' +
            ((r.unmatchedQuestions || []).length ? ' — sans colonne : ' + r.unmatchedQuestions.map(escapeHtml).join(" · ") : "") + '</div>' : "";
        $("tlImportReport").innerHTML = '<div class="tl-import-report">' +
            '<div><i class="bi bi-check-circle-fill text-success"></i> <b>' + r.imported + ' nouveau(x) contact(s)</b>' + sync +
            (skipped.length ? ' · écartés : ' + skipped.join(", ") : "") + ' · ' + r.assigned + ' déjà affecté(s) à un agent</div>' +
            '<div class="chips mt-1">' + chips + '</div>' +
            (agents ? '<div class="chips">' + agents + '</div>' : "") + model +
            (unmatched.length ? '<div class="warn small mt-1"><i class="bi bi-exclamation-triangle"></i> Agents du fichier introuvables dans le portail (contacts laissés non assignés) : ' +
                unmatched.map(function (k) { return escapeHtml(k) + " (" + r.unmatchedAgents[k] + ")"; }).join(", ") + ' — répartissez-les ci-dessous.</div>' : "") +
            '</div>';
    }

    var contactsShown = 200;
    function renderDistributeBox() {
        var unassigned = campaignContactsCache.filter(function (c) { return !c.agentUserId && c.callStatus === "PENDING"; }).length;
        $("tlDistributeBox").style.display = unassigned ? "" : "none";
        $("tlUnassignedCount").textContent = unassigned;
        if (!unassigned) return;
        var box = $("tlDistributeAgents");
        if (box.getAttribute("data-for") !== String(currentCampaignId)) {
            box.setAttribute("data-for", currentCampaignId);
            box.innerHTML = teamMembersCache.map(function (m) {
                return '<label><input type="checkbox" value="' + m.id + '" checked> ' + escapeHtml(m.fullName || m.username) + '</label>';
            }).join("") || '<span class="small text-muted">Aucun agent dans votre équipe.</span>';
        }
    }

    function loadCampaignContacts() {
        getJson("/api/campaigns/" + currentCampaignId + "/contacts").then(function (contacts) {
            campaignContactsCache = contacts;
            renderCampaignDetail();
        }).catch(function (e) {
            $("tlCampaignContactsBody").innerHTML = '<tr><td colspan="7" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    // ===================== IMPORT DE CONTACTS — mapping de colonnes libre =====================
    // N'importe quel fichier Excel est accepté : on prévisualise d'abord ses en-têtes
    // (/import/preview), on propose un mapping par défaut que l'utilisateur peut corriger dans
    // la modale, puis on confirme l'import (/import) avec ce mapping.
    var pendingImportFile = null;
    var pendingImportPreview = null;
    var importMappingModal;

    /**
     * Import synchronisé sur le modèle de la campagne : le fichier est d'abord analysé ; s'il est
     * reconnu sans ambiguïté (nom du client + questions du modèle retrouvées), l'import part
     * immédiatement. Sinon — ou si l'on clique « Vérifier les colonnes » — la correspondance des
     * colonnes s'affiche pour correction.
     */
    function startCampaignImport(forceReview) {
        var file = $("tlCampaignImportFile").files[0];
        if (!file) { alert("Choisissez un fichier."); return; }
        pendingImportFile = file;
        $("tlCampaignImportStatus").textContent = "Analyse du fichier…";
        var formData = new FormData();
        formData.append("file", file);
        fetch("/api/campaigns/" + currentCampaignId + "/import/preview", { method: "POST", credentials: "same-origin", body: formData })
            .then(readImportResponse)
            .then(function (preview) {
                pendingImportPreview = preview;
                if (preview.confident && !forceReview) {
                    var m = preview.suggestedMapping;
                    m.skipDuplicates = true;
                    m.onlyToRecall = $("tlImportRecallOnly").checked;
                    runImport(m);
                } else {
                    $("tlCampaignImportStatus").textContent = "";
                    openImportMappingModal(preview);
                }
            })
            .catch(function (e) {
                $("tlCampaignImportStatus").textContent = "Erreur : " + e.message;
            });
    }
    $("tlCampaignImportBtn").addEventListener("click", function () { startCampaignImport(false); });
    $("tlImportCheckColumnsLink").addEventListener("click", function (e) { e.preventDefault(); startCampaignImport(true); });

    function runImport(mapping) {
        var formData = new FormData();
        formData.append("file", pendingImportFile);
        formData.append("mapping", new Blob([JSON.stringify(mapping)], { type: "application/json" }));
        $("tlImportMappingError").textContent = "";
        $("tlCampaignImportStatus").textContent = "Import et synchronisation en cours… (quelques secondes pour plusieurs milliers de lignes)";
        return fetch("/api/campaigns/" + currentCampaignId + "/import", { method: "POST", credentials: "same-origin", body: formData })
            .then(readImportResponse)
            .then(function (result) {
                importMappingModal.hide();
                $("tlCampaignImportStatus").textContent = "";
                renderImportReport(result);
                $("tlCampaignImportFile").value = "";
                pendingImportFile = null;
                pendingImportPreview = null;
                loadCampaignContacts();
                loadCampaigns();
            })
            .catch(function (e) {
                $("tlCampaignImportStatus").textContent = "Erreur : " + e.message;
                $("tlImportMappingError").textContent = e.message;
            });
    }

    /** Erreurs du serveur au format { error: { message } } — affichées lisiblement. */
    function readImportResponse(res) {
        if (res.ok) return res.json();
        return res.text().then(function (t) {
            var msg = "HTTP " + res.status;
            try { var b = JSON.parse(t); msg = (b.error && b.error.message) || b.message || msg; } catch (e) { if (t) msg = t; }
            throw new Error(msg);
        });
    }

    function columnOptionsHtml(headers, sampleRow, selectedCol) {
        var opts = '<option value="">— Aucune colonne —</option>';
        headers.forEach(function (h, idx) {
            var label = (h || ("Colonne " + (idx + 1))) + (sampleRow[idx] ? "  (ex. " + sampleRow[idx] + ")" : "");
            opts += '<option value="' + idx + '"' + (selectedCol === idx ? " selected" : "") + '>' + escapeHtml(label) + '</option>';
        });
        return opts;
    }

    function openImportMappingModal(preview) {
        var headers = preview.headers || [];
        var sampleRow = preview.sampleRow || [];
        var suggested = preview.suggestedMapping || {};
        var fields = preview.campaignFields || [];

        $("tlImportMappingError").textContent = "";
        var total = fields.length, matched = preview.matchedQuestions || 0;
        $("tlImportSyncBanner").innerHTML = total
            ? '<div class="tl-sync-banner ' + (matched === total ? "ok" : "warn") + '"><i class="bi ' + (matched === total ? "bi-check-circle-fill" : "bi-exclamation-triangle-fill") + '"></i><div>' +
              '<b>Modèle de la campagne : ' + matched + ' question(s) sur ' + total + ' retrouvée(s) dans le fichier.</b>' +
              ((preview.unmatchedQuestions || []).length ? '<br>Sans colonne : ' + preview.unmatchedQuestions.map(escapeHtml).join(" · ") : "") +
              ((preview.unmatchedColumns || []).length ? '<br><span class="small">Colonnes gardées en informations complémentaires : ' + preview.unmatchedColumns.map(escapeHtml).join(" · ") + '</span>' : "") +
              '</div></div>'
            : "";
        $("tlImportMappingFixedFields").innerHTML = [
            ["tlMapName", "Nom du client (obligatoire)", suggested.nameColumn],
            ["tlMapPhone", "Téléphone", suggested.phoneColumn],
            ["tlMapAccount", "Numéro de compte", suggested.accountColumn],
            ["tlMapAgent", "Agent assigné (identifiant ou nom complet)", suggested.agentColumn]
        ].map(function (row) {
            return '<div class="mb-2"><label class="form-label small mb-1">' + row[1] + '</label>' +
                '<select class="form-select form-select-sm" id="' + row[0] + '">' + columnOptionsHtml(headers, sampleRow, row[2] === undefined ? null : row[2]) + '</select></div>';
        }).join("");

        // Fichier d'une campagne déjà en cours (export Microsoft Forms…) : reprise de l'historique.
        $("tlImportMappingHistory").innerHTML =
            '<h6><i class="bi bi-clock-history"></i> Appels déjà passés (facultatif)</h6>' +
            '<div class="row g-2">' +
            '<div class="col-md-6"><label class="form-label small mb-1">Statut d\'appel du fichier</label><select class="form-select form-select-sm" id="tlMapStatus">' + columnOptionsHtml(headers, sampleRow, suggested.statusColumn === undefined ? null : suggested.statusColumn) + '</select></div>' +
            '<div class="col-md-6"><label class="form-label small mb-1">Date de l\'appel</label><select class="form-select form-select-sm" id="tlMapCallDate">' + columnOptionsHtml(headers, sampleRow, suggested.callDateColumn === undefined ? null : suggested.callDateColumn) + '</select></div>' +
            '</div>' +
            '<div class="form-check mt-2"><input class="form-check-input" type="checkbox" id="tlMapSkipDup" checked><label class="form-check-label small" for="tlMapSkipDup">Ignorer les doublons (même compte : on garde l\'appel abouti le plus complet ; lignes « DOUBLON » écartées)</label></div>' +
            '<div class="form-check"><input class="form-check-input" type="checkbox" id="tlMapRecall"' + ($("tlImportRecallOnly").checked ? " checked" : "") + '><label class="form-check-label small" for="tlMapRecall"><b>Uniquement les clients à rappeler</b> (non joints : sonne dans le vide, messagerie, inaccessible… ou rappel demandé) — remis « À appeler »</label></div>' +
            '<div class="small text-muted mt-1">Sans cette case, les statuts du fichier sont repris : client entretenu → Interaction, non joint → Pas de réponse, rappel demandé → À appeler.</div>';

        // Le serveur renvoie { colonne: idQuestion } ; la modale travaille par question.
        var fieldColumns = {};
        Object.keys(suggested.fieldColumns || {}).forEach(function (col) { fieldColumns[suggested.fieldColumns[col]] = Number(col); });
        if (fields.length) {
            $("tlImportMappingCustomFields").innerHTML = '<hr><p class="small text-muted mb-2">Pré-remplissage automatique des questions du questionnaire (facultatif) :</p>' +
                fields.map(function (f) {
                    var preset = fieldColumns[f.id];
                    return '<div class="mb-2"><label class="form-label small mb-1">' + escapeHtml(f.label) + '</label>' +
                        '<select class="form-select form-select-sm tl-map-field" data-field-id="' + escapeHtml(f.id) + '">' +
                        columnOptionsHtml(headers, sampleRow, preset === undefined ? null : preset) + '</select></div>';
                }).join("");
        } else {
            $("tlImportMappingCustomFields").innerHTML = "";
        }

        importMappingModal.show();
    }

    $("tlDistributeBtn").addEventListener("click", function () {
        var ids = Array.prototype.map.call($("tlDistributeAgents").querySelectorAll("input:checked"), function (i) { return Number(i.value); });
        if (!ids.length) { alert("Cochez au moins un agent."); return; }
        var btn = this; btn.disabled = true;
        sendJson("/api/campaigns/" + currentCampaignId + "/distribute", "POST", { agentUserIds: ids })
            .then(function (r) {
                $("tlCampaignImportStatus").textContent = r.assigned + " contact(s) réparti(s) entre " + ids.length + " agent(s).";
                loadCampaignContacts();
                loadCampaigns();
            })
            .catch(function (e) { alert("Erreur : " + e.message); })
            .then(function () { btn.disabled = false; });
    });

    $("tlImportMappingConfirmBtn").addEventListener("click", function () {
        var nameCol = $("tlMapName").value;
        if (nameCol === "") { $("tlImportMappingError").textContent = "La colonne du nom du client est obligatoire."; return; }
        // Format attendu par le serveur : { "indexColonne": "idQuestion" }.
        var fieldColumns = {};
        Array.prototype.forEach.call($("tlImportMappingCustomFields").querySelectorAll(".tl-map-field"), function (sel) {
            if (sel.value !== "") fieldColumns[sel.value] = sel.getAttribute("data-field-id");
        });
        function colOrNull(id) { var el = $(id); return !el || el.value === "" ? null : Number(el.value); }
        var mapping = {
            nameColumn: Number(nameCol),
            phoneColumn: colOrNull("tlMapPhone"),
            accountColumn: colOrNull("tlMapAccount"),
            agentColumn: colOrNull("tlMapAgent"),
            fieldColumns: fieldColumns,
            statusColumn: colOrNull("tlMapStatus"),
            callDateColumn: colOrNull("tlMapCallDate"),
            skipDuplicates: $("tlMapSkipDup").checked,
            onlyToRecall: $("tlMapRecall").checked
        };

        runImport(mapping);
    });

    // ===================== COACHING QA (MY TODO) =====================

    var qaCoachingModal;
    var currentCoachingUsername = null;

    function openQaCoachingModal(username, evaluationDate) {
        currentCoachingUsername = username;
        $("tlQaCoachingTitle").innerHTML = '<i class="bi bi-chat-dots-fill"></i> Entretien qualité';
        $("tlQaCoachingDate").value = evaluationDate || todayIso();
        $("tlQaCoachingNotes").value = "";
        qaCoachingModal.show();
    }

    $("tlSaveQaCoachingBtn").addEventListener("click", function () {
        sendJson("/api/workflow/tasks/qa-coaching", "POST", {
            agentUsername: currentCoachingUsername,
            relatedDate: $("tlQaCoachingDate").value || null,
            notes: $("tlQaCoachingNotes").value.trim() || null
        }).then(function () {
            qaCoachingModal.hide();
            alert("Entretien programmé — une tâche à signer a été envoyée à l'agent.");
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===================== ALERTES =====================

    var ALERT_TYPE_LABELS_TL = {
        PRESENCE_FAIBLE: "Présence faible", SCORE_QA_FAIBLE: "Score QA faible",
        EVALUATION_FAIBLE: "Évaluation faible", PERFORMANCE_EN_BAISSE: "Performance en baisse",
        CHUTE_PRESENCE: "Chute de présence", CHUTE_SCORE_QA: "Chute du score QA", CHUTE_PERFORMANCE: "Chute de performance"
    };

    function loadAlerts() {
        var body = $("tlAlertsBody");
        var alertsRowCache = [];
        getJson("/api/team-leader/alerts").then(function (alerts) {
            alertsRowCache = alerts;
            if (!alerts.length) { body.innerHTML = '<tr><td colspan="4" class="text-center text-muted">Aucune alerte pour votre équipe ce mois-ci.</td></tr>'; return; }
            body.innerHTML = alerts.map(function (a, idx) {
                var sevBadge = { CRITIQUE: '<span class="badge bg-danger">Critique</span>',
                    ATTENTION: '<span class="badge bg-warning text-dark">Attention</span>' }[a.severity] || a.severity;
                return '<tr class="tl-alert-row" data-alert-idx="' + idx + '" style="cursor:pointer;"><td class="text-primary text-decoration-underline">' + escapeHtml(a.userFullName) + "</td>" +
                    "<td>" + escapeHtml(ALERT_TYPE_LABELS_TL[a.alertType] || a.alertType) + "</td>" +
                    "<td>" + sevBadge + "</td>" +
                    "<td>" + escapeHtml(a.message) + "</td></tr>";
            }).join("");
            Array.prototype.forEach.call(body.querySelectorAll(".tl-alert-row"), function (row) {
                row.addEventListener("click", function () {
                    var a = alertsRowCache[Number(row.getAttribute("data-alert-idx"))];
                    openAgentDetail({ userId: a.userId, userFullName: a.userFullName, kpiMetrics: {} });
                });
            });
        }).catch(function (e) {
            body.innerHTML = '<tr><td colspan="4" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    // ===================== COMPÉTITIONS =====================

    var tlCompParticipantsModal = null;
    var tlCompCurrentId = null;

    function loadTeamLeaderCompetitions() {
        var container = $("tlCompetitionsList");
        getJson("/api/competitions/team-leader").then(function (comps) {
            if (!comps.length) { container.innerHTML = '<p class="text-muted text-center">Aucune compétition pour votre équipe pour l\'instant.</p>'; return; }
            container.innerHTML = comps.map(function (c) {
                var myTeamEntry = c.teams.find(function (t) { return t.team === myTeam; });
                var validated = myTeamEntry && myTeamEntry.validated;
                var statusBadge = validated ? '<span class="badge bg-success">Équipe validée</span>' : '<span class="badge bg-warning text-dark">À valider</span>';
                var participantsHtml = myTeamEntry ? myTeamEntry.participants.map(function (p) {
                    return '<li>' + escapeHtml(p.fullName) + (p.completed ? ' — <strong>' + p.score + ' / 100</strong>' : '') + '</li>';
                }).join("") : "";
                var validateBtn = validated ? "" : '<button class="btn btn-sm btn-primary mt-2 tl-comp-validate-btn" data-comp-id="' + c.competitionId + '">Choisir mes membres</button>';
                return '<div class="border rounded p-3 mb-3">' +
                    '<div class="d-flex justify-content-between align-items-start">' +
                        '<div><strong><i class="bi bi-trophy-fill text-warning"></i> ' + escapeHtml(c.title) + '</strong> ' + statusBadge +
                            '<div class="small text-muted">' + escapeHtml(c.gameTitle) + ' — programmée le ' + new Date(c.scheduledAt).toLocaleString("fr-FR") + '</div></div>' +
                    '</div>' +
                    (participantsHtml ? '<ul class="small mt-2 mb-0">' + participantsHtml + '</ul>' : '') +
                    validateBtn +
                '</div>';
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll(".tl-comp-validate-btn"), function (btn) {
                btn.addEventListener("click", function () { openTlCompParticipantsPicker(Number(btn.getAttribute("data-comp-id"))); });
            });
        }).catch(function (e) {
            container.innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }
    window.reloadTeamLeaderCompetitions = loadTeamLeaderCompetitions;

    function openTlCompParticipantsPicker(competitionId) {
        tlCompCurrentId = competitionId;
        $("tlCompParticipantsList").innerHTML = '<p class="text-muted small">Chargement…</p>';
        // reportingCache contient déjà les agents de mon équipe, avec leur userId — pas besoin d'un nouvel appel.
        $("tlCompParticipantsList").innerHTML = reportingCache.map(function (r) {
            return '<div class="form-check"><input class="form-check-input tl-comp-participant-check" type="checkbox" value="' + r.userId + '" id="tlCompUser' + r.userId + '">' +
                '<label class="form-check-label" for="tlCompUser' + r.userId + '">' + escapeHtml(r.userFullName || r.username) + '</label></div>';
        }).join("");
        tlCompParticipantsModal.show();
    }

    function submitTlCompParticipants() {
        var userIds = Array.prototype.filter.call(document.querySelectorAll(".tl-comp-participant-check"), function (c) { return c.checked; })
            .map(function (c) { return Number(c.value); });
        if (!userIds.length) { alert("Choisissez au moins un membre."); return; }
        sendJson("/api/competitions/" + tlCompCurrentId + "/validate-members", "POST", { userIds: userIds })
            .then(function () {
                tlCompParticipantsModal.hide();
                loadTeamLeaderCompetitions();
            }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    /**
     * Popup automatique — au chargement de page, affiche la première notification non lue
     * de type "votre agent a été écouté" ou "choisissez les membres de la compétition",
     * avec la photo de l'agent (si connue) et un bouton "Consulter" qui l'ouvre directement.
     * Une seule fois par chargement de page (pas de sondage répété).
     */
    function checkPendingNotificationPopup(modal) {
        getJson("/api/mon-rcc/notifications/me").then(function (notifs) {
            var pending = (notifs || []).filter(function (n) {
                return !n.isRead && (n.actionType === "QA_EVALUATION_REVIEW" || n.actionType === "COMPETITION_TEAM_SELECTION");
            });
            if (!pending.length) return;
            var notif = pending[0];

            $("tlNotifMessage").textContent = notif.content;
            $("tlNotifPhotoWrap").innerHTML = notif.actionType === "COMPETITION_TEAM_SELECTION"
                ? '<i class="bi bi-trophy-fill" style="font-size:2.5rem;color:#F5A623;"></i>'
                : '<i class="bi bi-headset" style="font-size:2.5rem;color:#F5A623;"></i>';

            if (notif.actionType === "QA_EVALUATION_REVIEW" && notif.actionTarget) {
                getJson("/api/users/" + encodeURIComponent(notif.actionTarget) + "/team-info").then(function (info) {
                    if (info.photoUrl) {
                        $("tlNotifPhotoWrap").innerHTML = '<img src="' + info.photoUrl + '" style="width:100%;height:100%;object-fit:cover;">';
                    }
                }).catch(function () {});
            }

            $("tlNotifConsultBtn").onclick = function () {
                sendJson("/api/mon-rcc/notifications/" + notif.id + "/read", "POST").catch(function () {});
                modal.hide();
                if (notif.actionType === "COMPETITION_TEAM_SELECTION") {
                    switchTab("competitions");
                } else if (notif.actionTarget) {
                    var r = reportingCache.find(function (row) { return row.username === notif.actionTarget; });
                    if (r) { switchTab("reporting"); openAgentDetail(r); }
                }
            };
            modal.show();
        }).catch(function () {});
    }

    // ===================== INIT =====================

    /** Badges de compteur sur les raccourcis Congés/Permutations en haut de page — même
     *  endpoints que Shift (voir shift.js loadLeaveTeamLeader/loadSwapTeamLeader), affichés ici
     *  en lecture seule : la validation elle-même se fait sur la page Shift (?open=...), pas de
     *  duplication du formulaire de décision. */
    function loadTlToolBadges() {
        getJson("/api/workflow/requests/hr/leave").then(function (all) {
            var pending = (all || []).filter(function (r) { return r.status === "PENDING"; }).length;
            var badge = $("tlLeaveBadge");
            badge.style.display = pending > 0 ? "" : "none";
            badge.textContent = pending;
        }).catch(function () {});

        getJson("/api/shift-swaps/pending-for-team-leader").then(function (swaps) {
            var count = (swaps || []).length;
            var badge = $("tlSwapBadge");
            badge.style.display = count > 0 ? "" : "none";
            badge.textContent = count;
        }).catch(function () {});
    }

    function init() {
        agentModal = new bootstrap.Modal($("tlAgentModal"));
        agentRdvModal = new bootstrap.Modal($("tlAgentRdvModal"));
        newCampaignModal = new bootstrap.Modal($("tlNewCampaignModal"));
        campaignDetailModal = new bootstrap.Modal($("tlCampaignDetailModal"));
        importMappingModal = new bootstrap.Modal($("tlImportMappingModal"));
        var notifPopupModal = new bootstrap.Modal($("tlNotifPopupModal"));
        tlCompParticipantsModal = new bootstrap.Modal($("tlCompParticipantsModal"));
        $("tlCompValidateBtn").addEventListener("click", submitTlCompParticipants);
        qaCoachingModal = new bootstrap.Modal($("tlQaCoachingModal"));
        addMemberModal = new bootstrap.Modal($("tlAddMemberModal"));
        $("tlAddMemberBtn").addEventListener("click", openAddMemberModal);
        wirePlanningTab();
        $("tlAddMemberSearch").addEventListener("input", function () {
            clearTimeout(addMemberSearchTimer);
            var q = this.value;
            addMemberSearchTimer = setTimeout(function () { searchAddableAgents(q); }, 300);
        });

        Array.prototype.forEach.call($("tlAgentRdvGranularity").querySelectorAll("button"), function (btn) {
            btn.addEventListener("click", function () {
                currentAgentGranularity = btn.getAttribute("data-gran");
                Array.prototype.forEach.call($("tlAgentRdvGranularity").querySelectorAll("button"), function (b) {
                    b.classList.toggle("btn-primary", b === btn);
                    b.classList.toggle("btn-outline-primary", b !== btn);
                });
                renderAgentRdv();
            });
        });

        $("tlReportingMonth").value = currentMonthValue();
        var monthAgo = new Date(); monthAgo.setDate(monthAgo.getDate() - 30);
        $("tlSalesFrom").value = todayIso().slice(0, 8) + "01";
        $("tlSalesTo").value = todayIso();
        var rdvRangeStart = new Date(); rdvRangeStart.setMonth(rdvRangeStart.getMonth() - 3);
        var rdvRangeEnd = new Date(); rdvRangeEnd.setMonth(rdvRangeEnd.getMonth() + 3);
        $("tlRdvFrom").value = toIsoDateLocal(rdvRangeStart);
        $("tlRdvTo").value = toIsoDateLocal(rdvRangeEnd);

        $("tlTabReportingBtn").addEventListener("click", function () { switchTab("reporting"); });
        $("tlTabMembersBtn").addEventListener("click", function () { switchTab("members"); });
        $("tlTabPlanningBtn").addEventListener("click", function () { switchTab("planning"); });
        $("tlTabQaBtn").addEventListener("click", function () { switchTab("qa"); });
        $("tlTabSalesBtn").addEventListener("click", function () { switchTab("sales"); });
        $("tlTabRdvBtn").addEventListener("click", function () { switchTab("rdv"); });
        $("tlTabCampaignsBtn").addEventListener("click", function () { switchTab("campaigns"); });
        $("tlTabAlertsBtn").addEventListener("click", function () { switchTab("alerts"); });
        $("tlTabCompetitionsBtn").addEventListener("click", function () { switchTab("competitions"); });
        $("tlTabMeetingsBtn").addEventListener("click", function () { switchTab("meetings"); });
        $("tlStatMeetingsCard").addEventListener("click", function () { switchTab("meetings"); });
        $("tlMeetingBtn").addEventListener("click", function () { RccMeetings.openSchedule(); });
        RccMeetings.onChange(function () {
            if ($("tlPaneMeetings").style.display !== "none") loadMeetings(); else refreshMeetingCounts();
        });
        getJson("/api/auth/me").then(function (me) {
            var first = String((me && (me.name || me.username)) || "").split(/\s+/)[0];
            if (first) $("tlHello").textContent = "Bonjour " + first + " 👋";
        }).catch(function () { /* titre par défaut */ });

        $("tlReportingApplyBtn").addEventListener("click", loadReporting);
        $("tlSalesApplyBtn").addEventListener("click", loadSales);
        $("tlRdvApplyBtn").addEventListener("click", loadRdv);

        getJson("/api/team-leader/my-team").then(function (result) {
            myTeam = result.team;
            $("tlSubtitle").textContent = "Gestion de votre équipe — " + teamLabel(myTeam);
            $("tlContent").style.display = "";
            if (myTeam === "OUTBOUND") {
                $("tlTabSalesBtn").style.display = "";
                $("tlTabRdvBtn").style.display = "";
                $("tlTabCampaignsBtn").style.display = "";
            }
            loadReporting();
            refreshMeetingCounts();
            if (/[?&]tab=meetings\b/.test(location.search)) switchTab("meetings");
            checkPendingNotificationPopup(notifPopupModal);
            loadTlToolBadges();
        }).catch(function () {
            $("tlNoTeamWarning").style.display = "";
        });

        if (window.RccSession) window.RccSession.init();
    }

    function teamLabel(team) {
        return { INBOUND_VOICE: "Inbound Voix", INBOUND_MAIL: "Inbound Mail / Rafiki", CIB: "CIB", OUTBOUND: "Outbound" }[team] || team;
    }

    document.addEventListener("DOMContentLoaded", init);
})();
