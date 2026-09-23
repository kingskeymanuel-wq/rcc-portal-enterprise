"use strict";

(function () {

    var TYPE_LABELS = {
        LEAVE: "Congé / absence",
        PROCEDURE_CHANGE: "Changement de procédure",
        ACCESS: "Matériel / accès",
        TEAM_ASSIGNMENT: "Affectation initiale"
    };

    var TEAM_LABELS = {
        QA: "Quality Assurance",
        ADMIN: "Administration"
    };

    var STATUS_BADGES = {
        PENDING: '<span class="badge bg-warning text-dark">En attente</span>',
        APPROVED: '<span class="badge bg-success">Approuvée</span>',
        REJECTED: '<span class="badge bg-danger">Rejetée</span>'
    };

    // ===== Référentiel RCC360 (motifs de réclamation client) =====
    var motifCatalogCache = [];
    var MOTIF_BY_CODE = {};

    function loadMotifCatalog() {
        return getJson("/api/workflow/requests/motif-catalog").then(function (entries) {
            motifCatalogCache = entries || [];
            MOTIF_BY_CODE = {};
            motifCatalogCache.forEach(function (m) {
                MOTIF_BY_CODE[m.code] = m;
                TYPE_LABELS[m.code] = m.label;
                if (!TEAM_LABELS[m.teamCode]) TEAM_LABELS[m.teamCode] = m.teamLabel;
            });
            populateMotifDropdowns();
            loadSla(); // relance avec les bons libellés si des demandes de ce type existent déjà
        }).catch(function () { motifCatalogCache = []; });
    }

    function motifOptgroupsHtml() {
        var order = [];
        var groups = {};
        motifCatalogCache.forEach(function (m) {
            if (!groups[m.thematique]) { groups[m.thematique] = []; order.push(m.thematique); }
            groups[m.thematique].push(m);
        });
        return order.map(function (thematique) {
            return '<optgroup label="' + escapeHtml(thematique) + '">' +
                groups[thematique].map(function (m) {
                    return '<option value="' + m.code + '">' + escapeHtml(m.label) + '</option>';
                }).join("") +
                '</optgroup>';
        }).join("");
    }

    function teamOptionsHtml() {
        var seen = {};
        var html = "";
        motifCatalogCache.forEach(function (m) {
            if (seen[m.teamCode]) return;
            seen[m.teamCode] = true;
            html += '<option value="' + m.teamCode + '">' + escapeHtml(m.teamLabel) + '</option>';
        });
        return html;
    }

    /** Ajoute les motifs/équipes du référentiel RCC360 aux listes déjà présentes dans le HTML
     *  (demandes internes Procédure/Accès + équipes QA/Admin) sans les remplacer. */
    function populateMotifDropdowns() {
        var typeSelect = document.getElementById("typeInput");
        if (typeSelect) typeSelect.insertAdjacentHTML("beforeend", motifOptgroupsHtml());

        var teamSelect = document.getElementById("assignedTeamInput");
        if (teamSelect) teamSelect.insertAdjacentHTML("beforeend", teamOptionsHtml());
    }

    var currentProfile = null;
    var currentTeam = null; // "QA" | "ADMIN" | null (agent)
    var mineCache = [];
    var pendingCache = [];
    var tasksCache = [];
    var historyCache = [];

    function computeProfile(user) {
        if (user.role && user.role.toUpperCase() === "ADMIN") return "ADMIN";
        if (user.service && user.service.toLowerCase().replace(/_/g, " ") === "quality assurance") return "QA";
        return "AGENT";
    }

    var escapeHtml = RccApi.escapeHtml;

    var getJson = RccApi.getJson;

    function postJson(url, body) {
        return fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body || {})
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || ("HTTP " + res.status))); });
            return res.json();
        });
    }

    function formatDate(iso) {
        return new Date(iso).toLocaleString("fr-FR");
    }

    function toIsoDate(d) {
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }

    function formatPeriod(r) {
        if (r.periodFrom === r.periodTo) return r.periodFrom;
        return r.periodFrom + " → " + r.periodTo;
    }

    // ===== Mes demandes =====

    function renderMine(requests) {
        mineCache = requests || [];
        var body = document.getElementById("mineBody");
        if (!requests.length) {
            body.innerHTML = '<tr><td colspan="7" class="text-center text-muted">Aucune demande.</td></tr>';
            return;
        }
        body.innerHTML = requests.map(function (r) {
            var slaWarning = r.slaBreached ? ' <span class="badge bg-danger" title="Délai de traitement dépassé"><i class="bi bi-exclamation-triangle"></i> ' + formatHoursOpen(r.hoursOpen) + '</span>' : "";
            return "" +
                "<tr>" +
                "<td>" + escapeHtml(TYPE_LABELS[r.type] || r.type) + "</td>" +
                "<td>" + escapeHtml(r.title) + "</td>" +
                "<td>" + escapeHtml(formatPeriod(r)) + "</td>" +
                "<td>" + escapeHtml(TEAM_LABELS[r.assignedTeam] || r.assignedTeam) + "</td>" +
                "<td>" + (STATUS_BADGES[r.status] || escapeHtml(r.status)) + slaWarning + "</td>" +
                "<td>" + formatDate(r.createdAt) + "</td>" +
                '<td class="text-end"><button class="btn btn-sm btn-outline-danger delete-mine-btn" data-id="' + r.requestId + '"><i class="bi bi-trash"></i></button></td>' +
                "</tr>";
        }).join("");

        Array.prototype.forEach.call(body.querySelectorAll(".delete-mine-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer définitivement cette demande ?")) return;
                fetch("/api/workflow/requests/" + btn.getAttribute("data-id"), { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                    .then(loadMine)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });

        updateOverviewAndBadges();
    }

    function loadMine() {
        getJson("/api/workflow/requests/mine").then(renderMine).catch(function (e) {
            document.getElementById("mineBody").innerHTML =
                '<tr><td colspan="6" class="text-center text-danger">Erreur (' + escapeHtml(e.message) + ')</td></tr>';
        });
    }

    // ===== Soumission =====

    var responsablesCache = [];

    function loadResponsables() {
        getJson("/api/users/directory?role=RESPONSABLE").then(function (users) {
            responsablesCache = users;
            updateAssignedToOptions();
        }).catch(function () { responsablesCache = []; });
    }

    function updateAssignedToOptions() {
        var team = document.getElementById("assignedTeamInput").value;
        var select = document.getElementById("assignedToInput");

        var filtered = responsablesCache.filter(function (u) {
            if (team === "ADMIN") return (u.role || "").toUpperCase() === "ADMIN";
            if (team === "QA") return (u.service || "").toLowerCase().replace(/_/g, " ") === "quality assurance";
            return false;
        });

        select.innerHTML = '<option value="">— Aucun en particulier —</option>' +
            filtered.map(function (u) {
                return '<option value="' + u.username + '">' + (u.fullName || u.username) + '</option>';
            }).join("");
    }

    function updateFieldsForType() {
        var type = document.getElementById("typeInput").value;
        var motif = MOTIF_BY_CODE[type];
        if (motif) {
            document.getElementById("assignedTeamInput").value = motif.teamCode;
            updateAssignedToOptions();
        }
    }

    function loadServiceOptions() {
        fetch("/api/procedures/services", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : [];
        }).then(function (services) {
            var select = document.getElementById("serviceCodeInput");
            select.innerHTML = '<option value="">— Choisir —</option>' +
                services.map(function (s) { return '<option value="' + s.code + '">' + s.name + '</option>'; }).join("");
        }).catch(function () {});
    }

    var templatesCache = [];

    function loadRequestTemplates() {
        getJson("/api/workflow/templates").then(function (templates) {
            templatesCache = templates || [];
            var select = document.getElementById("templateSelectInput");
            var group = document.getElementById("templateSelectGroup");
            if (!templatesCache.length) { group.style.display = "none"; return; }
            group.style.display = "";
            select.innerHTML = '<option value="">— Saisie libre —</option>' +
                templatesCache.map(function (t) { return '<option value="' + t.templateId + '">' + escapeHtml(t.name) + '</option>'; }).join("");
        }).catch(function () {});
    }

    function wireTemplateSelect() {
        document.getElementById("templateSelectInput").addEventListener("change", function () {
            var id = parseInt(this.value, 10);
            var template = templatesCache.find(function (t) { return t.templateId === id; });
            if (!template) return;
            document.getElementById("typeInput").value = template.type;
            document.getElementById("titleInput").value = template.defaultTitle;
            document.getElementById("detailsInput").value = template.defaultDetails || "";
            if (template.defaultAssignedTeam) document.getElementById("assignedTeamInput").value = template.defaultAssignedTeam;
            updateFieldsForType();
            updateAssignedToOptions();
        });
    }

    function wireForm() {
        document.getElementById("assignedTeamInput").addEventListener("change", updateAssignedToOptions);
        document.getElementById("typeInput").addEventListener("change", updateFieldsForType);
        updateFieldsForType();
        loadServiceOptions();
        loadResponsables();

        document.getElementById("newRequestForm").addEventListener("submit", function (evt) {
            evt.preventDefault();

            var payload = {
                type: document.getElementById("typeInput").value,
                title: document.getElementById("titleInput").value.trim(),
                details: document.getElementById("detailsInput").value.trim(),
                periodType: document.getElementById("periodTypeInput").value,
                periodFrom: null,
                periodTo: null,
                assignedTeam: document.getElementById("assignedTeamInput").value,
                assignedToUsername: document.getElementById("assignedToInput").value || null,
                serviceCode: document.getElementById("serviceCodeInput").value || null
            };
            if (!payload.title) return;

            postJson("/api/workflow/requests", payload)
                .then(function () {
                    document.getElementById("newRequestForm").reset();
                    updateFieldsForType();
                    updateAssignedToOptions();
                    loadMine();
                    if (currentTeam) loadPending();
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    // ===== À valider (QA/ADMIN) =====

    function renderPending(requests) {
        pendingCache = requests || [];
        var body = document.getElementById("pendingBody");
        if (!requests.length) {
            body.innerHTML = '<tr><td colspan="7" class="text-center text-muted">Aucune demande en attente.</td></tr>';
            return;
        }
        body.innerHTML = requests.map(function (r) {
            return "" +
                "<tr data-request-id=\"" + r.requestId + "\" class=\"wf-pending-row\" style=\"cursor:pointer;\">" +
                "<td>" + escapeHtml(TYPE_LABELS[r.type] || r.type) + "</td>" +
                "<td>" + escapeHtml(r.title) + "</td>" +
                "<td>" + escapeHtml(formatPeriod(r)) + "</td>" +
                "<td>" + escapeHtml(r.details || "—") + "</td>" +
                "<td>" + escapeHtml(r.requestedByName || r.requestedByUsername) +
                (r.assignedToName ? '<div class="small text-primary"><i class="bi bi-person-check"></i> ' + escapeHtml(r.assignedToName) + '</div>' : "") +
                "</td>" +
                "<td>" + formatDate(r.createdAt) + "</td>" +
                '<td class="text-end">' +
                '<button class="btn btn-sm btn-success me-1 approve-btn" data-id="' + r.requestId + '">Approuver</button>' +
                '<button class="btn btn-sm btn-outline-danger reject-btn" data-id="' + r.requestId + '">Rejeter</button>' +
                "</td>" +
                "</tr>";
        }).join("");

        Array.prototype.forEach.call(body.querySelectorAll(".approve-btn"), function (btn) {
            btn.addEventListener("click", function (ev) { ev.stopPropagation(); decide(btn.getAttribute("data-id"), true); });
        });
        Array.prototype.forEach.call(body.querySelectorAll(".reject-btn"), function (btn) {
            btn.addEventListener("click", function (ev) { ev.stopPropagation(); decide(btn.getAttribute("data-id"), false); });
        });
        Array.prototype.forEach.call(body.querySelectorAll(".wf-pending-row"), function (row) {
            row.addEventListener("click", function () { openPendingDetail(row.getAttribute("data-request-id")); });
        });

        highlightDeepLinkedRequest();
        updateOverviewAndBadges();
    }

    /** Vient d'une notification (?request=<id>) — surligne et centre la ligne concernée pour que l'admin la retrouve tout de suite. */
    function highlightDeepLinkedRequest() {
        var params = new URLSearchParams(window.location.search);
        var requestId = params.get("request");
        if (!requestId) return;
        var row = document.querySelector('#pendingBody tr[data-request-id="' + requestId + '"]');
        if (!row) return;
        row.scrollIntoView({ behavior: "smooth", block: "center" });
        row.classList.add("table-warning");
        setTimeout(function () { row.classList.remove("table-warning"); }, 4000);
    }

    function decide(id, approve) {
        var comment = prompt(approve ? "Commentaire (optionnel) pour approuver :" : "Motif du rejet (optionnel) :");
        if (comment === null) return; // annulé
        var url = "/api/workflow/requests/" + id + (approve ? "/approve" : "/reject");
        postJson(url, { comment: comment })
            .then(function () { loadPending(); loadMine(); })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    var pendingDetailModal = null;

    function openPendingDetail(requestId) {
        var r = pendingCache.filter(function (x) { return String(x.requestId) === String(requestId); })[0];
        if (!r) return;

        document.getElementById("wfPendingDetailTitle").textContent = r.title || (TYPE_LABELS[r.type] || r.type);
        document.getElementById("wfPdType").textContent = TYPE_LABELS[r.type] || r.type;
        document.getElementById("wfPdPeriod").textContent = formatPeriod(r);
        document.getElementById("wfPdRequester").textContent = r.requestedByName || r.requestedByUsername;
        document.getElementById("wfPdOrg").textContent = [r.requestedByAffiliateBranch, r.requestedByService, r.requestedByActivity]
            .filter(Boolean).join(" / ") || "—";
        document.getElementById("wfPdAssignee").textContent = r.assignedToName || (TEAM_LABELS[r.assignedTeam] || r.assignedTeam || "—");
        document.getElementById("wfPdSubmitted").textContent = formatDate(r.createdAt);
        document.getElementById("wfPdSla").innerHTML = r.slaBreached
            ? '<span class="badge bg-danger"><i class="bi bi-exclamation-triangle"></i> ' + formatHoursOpen(r.hoursOpen) + '</span>'
            : formatHoursOpen(r.hoursOpen);
        document.getElementById("wfPdDetails").textContent = r.details || "—";

        var approveBtn = document.getElementById("wfPdApproveBtn");
        var rejectBtn = document.getElementById("wfPdRejectBtn");
        approveBtn.onclick = function () { decide(r.requestId, true); pendingDetailModal.hide(); };
        rejectBtn.onclick = function () { decide(r.requestId, false); pendingDetailModal.hide(); };

        if (!pendingDetailModal) pendingDetailModal = new bootstrap.Modal(document.getElementById("wfPendingDetailModal"));
        pendingDetailModal.show();
    }

    function loadPending() {
        getJson("/api/workflow/requests/pending").then(renderPending).catch(function (e) {
            document.getElementById("pendingBody").innerHTML =
                '<tr><td colspan="7" class="text-center text-danger">Erreur (' + escapeHtml(e.message) + ')</td></tr>';
        });
    }

    // ===== Notes — ouvert à tout le monde =====

    var PRIORITY_LABELS = { LOW: "Basse", NORMAL: "Normale", HIGH: "Haute" };

    function renderNotes(tasks) {
        tasksCache = tasks || [];
        var container = document.getElementById("notesList");

        var openCount = tasksCache.filter(function (t) { return t.status !== "DONE"; }).length;
        var doneCount = tasksCache.length - openCount;
        var todayIso = new Date().toISOString().slice(0, 10);
        var overdueCount = tasksCache.filter(function (t) { return t.status !== "DONE" && t.dueDate && t.dueDate < todayIso; }).length;
        setText("wfTaskStatOpen", openCount);
        setText("wfTaskStatOverdue", overdueCount);
        setText("wfTaskStatDone", doneCount);

        if (!tasksCache.length) {
            container.innerHTML = '<div class="wf-empty-state"><i class="bi bi-emoji-smile"></i>Aucune tâche pour l\'instant — profitez-en ! 🎉</div>';
            updateOverviewAndBadges();
            return;
        }

        var open = tasksCache.filter(function (t) { return t.status !== "DONE"; });
        var done = tasksCache.filter(function (t) { return t.status === "DONE"; });

        var overdue = open.filter(function (t) { return t.dueDate && t.dueDate < todayIso; });
        var today = open.filter(function (t) { return t.dueDate === todayIso; });
        var upcoming = open.filter(function (t) { return t.dueDate && t.dueDate > todayIso; });
        var noDate = open.filter(function (t) { return !t.dueDate; });

        function sortByPriorityThenDate(list) {
            var weight = { HIGH: 0, NORMAL: 1, LOW: 2 };
            return list.slice().sort(function (a, b) {
                var pw = (weight[a.priority] ?? 1) - (weight[b.priority] ?? 1);
                if (pw !== 0) return pw;
                return (a.dueDate || "9999") < (b.dueDate || "9999") ? -1 : 1;
            });
        }

        var html = "";
        if (overdue.length) html += taskGroupHtml('<i class="bi bi-exclamation-triangle-fill text-danger"></i> En retard (' + overdue.length + ")", sortByPriorityThenDate(overdue), todayIso);
        if (today.length) html += taskGroupHtml('<i class="bi bi-calendar-day-fill text-primary"></i> Aujourd\'hui (' + today.length + ")", sortByPriorityThenDate(today), todayIso);
        if (upcoming.length) html += taskGroupHtml('<i class="bi bi-calendar-week"></i> À venir (' + upcoming.length + ")", sortByPriorityThenDate(upcoming), todayIso);
        if (noDate.length) html += taskGroupHtml('<i class="bi bi-pin-angle"></i> Sans échéance (' + noDate.length + ")", sortByPriorityThenDate(noDate), todayIso);

        if (done.length) {
            html += '<div class="wf-task-group-title" id="wfDoneToggle" style="cursor:pointer;">' +
                '<i class="bi bi-check-circle-fill text-success"></i> Terminées (' + done.length + ') <i class="bi bi-chevron-down small ms-1"></i></div>' +
                '<div id="wfDoneGroup">' + taskGroupHtml("", done, todayIso, true) + '</div>';
        }

        container.innerHTML = html;

        var doneToggle = document.getElementById("wfDoneToggle");
        if (doneToggle) doneToggle.addEventListener("click", function () { document.getElementById("wfDoneGroup").classList.toggle("wf-show"); });

        Array.prototype.forEach.call(container.querySelectorAll(".wf-task-check"), function (btn) {
            btn.addEventListener("click", function () {
                postJson("/api/workflow/tasks/" + btn.getAttribute("data-id") + "/toggle-done").then(loadNotes)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".delete-task-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer cette note ?")) return;
                fetch("/api/workflow/tasks/" + btn.getAttribute("data-id"), { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                    .then(loadNotes)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
        updateOverviewAndBadges();
    }

    function taskGroupHtml(titleHtml, tasks, todayIso, skipGroupTitle) {
        var html = titleHtml && !skipGroupTitle ? '<div class="wf-task-group-title">' + titleHtml + '</div>' : "";
        html += tasks.map(function (t) { return taskCardHtml(t, todayIso); }).join("");
        return html;
    }

    function taskCardHtml(t, todayIso) {
        var isDone = t.status === "DONE";
        var isOverdue = !isDone && t.dueDate && t.dueDate < todayIso;
        var prio = t.priority || "NORMAL";
        var cardClass = "wf-task-card wf-prio-" + prio.toLowerCase() + (isOverdue ? " wf-overdue" : "") + (isDone ? " wf-done" : "");

        var origin = t.createdByUsername && t.assignedToUsername && t.createdByUsername !== t.assignedToUsername
            ? '<span class="wf-task-chip wf-chip-origin"><i class="bi bi-person-fill"></i> ' + escapeHtml(t.createdByName || t.createdByUsername) + '</span>'
            : (t.assignedToTeamCode ? '<span class="wf-task-chip wf-chip-team"><i class="bi bi-people-fill"></i> ' + escapeHtml(t.assignedToTeamCode) + '</span>' : "");
        var dueChip = t.dueDate
            ? '<span class="wf-task-chip ' + (isOverdue ? "wf-chip-overdue" : "wf-chip-due") + '"><i class="bi bi-calendar-event"></i> ' + escapeHtml(formatDate(t.dueDate)) + '</span>'
            : "";
        var prioChip = prio === "HIGH" ? '<span class="wf-task-chip wf-chip-prio-high"><i class="bi bi-flag-fill"></i> Haute</span>'
            : (prio === "LOW" ? '<span class="wf-task-chip wf-chip-prio-low"><i class="bi bi-flag"></i> Basse</span>' : "");

        var categoryChip = "";
        if (t.category === "ATTENDANCE_LATE" || t.category === "ATTENDANCE_ABSENCE") {
            var justifiedChip = t.justified === true ? '<span class="badge bg-success ms-1">Justifié</span>'
                : (t.justified === false ? '<span class="badge bg-danger ms-1">Injustifié</span>' : "");
            categoryChip = '<span class="wf-task-chip" style="background:#fff3cd;color:#7a5b00;"><i class="bi bi-clock-history"></i> ' +
                (t.category === "ATTENDANCE_LATE" ? "Retard" : "Absence") + '</span>' + justifiedChip;
        } else if (t.category === "QA_COACHING") {
            categoryChip = '<span class="wf-task-chip" style="background:#e7f1ff;color:#0057B8;"><i class="bi bi-chat-dots-fill"></i> Entretien qualité</span>';
        }
        var signaturePrompt = (t.category && !isDone)
            ? '<div class="small text-primary mt-1"><i class="bi bi-pen"></i> Cochez pour signer — vous attestez en avoir pris connaissance.</div>' : "";

        return '<div class="' + cardClass + '">' +
            '<div class="wf-task-check" data-id="' + t.taskId + '" title="' + (t.category ? "Signer" : "Marquer comme fait") + '">' + (isDone ? '<i class="bi bi-check-lg small"></i>' : '') + '</div>' +
            '<div class="flex-grow-1">' +
            '<div class="wf-task-title">' + escapeHtml(t.title) + '</div>' +
            (t.description ? '<div class="wf-task-desc">' + escapeHtml(t.description) + '</div>' : "") +
            '<div class="wf-task-meta">' + categoryChip + prioChip + dueChip + origin + '</div>' +
            signaturePrompt +
            '</div>' +
            '<div class="wf-task-actions">' +
            '<button class="btn btn-sm btn-outline-danger delete-task-btn" data-id="' + t.taskId + '"><i class="bi bi-trash"></i></button>' +
            '</div></div>';
    }

    function loadNotes() {
        getJson("/api/workflow/tasks/mine").then(renderNotes).catch(function (e) {
            document.getElementById("notesList").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function wireNotes() {
        document.getElementById("noteAddBtn").addEventListener("click", function () {
            var title = document.getElementById("noteTitle").value.trim();
            var dueDate = document.getElementById("noteDueDate").value || null;
            var priority = document.getElementById("notePriority").value;
            if (!title) return;
            postJson("/api/workflow/tasks", { title: title, dueDate: dueDate, priority: priority })
                .then(function () {
                    document.getElementById("noteTitle").value = "";
                    document.getElementById("noteDueDate").value = "";
                    document.getElementById("notePriority").value = "NORMAL";
                    loadNotes();
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    // ===== Programmer une tâche (QA/Admin) =====

    function wireTaskAssign() {
        var teamSelect = document.getElementById("taskAssignTeam");
        var individualSelect = document.getElementById("taskAssignIndividual");
        var allUsers = [];

        getJson("/api/teams").then(function (teams) {
            teams.forEach(function (team) {
                var opt = document.createElement("option");
                opt.value = team.code;
                opt.textContent = team.label;
                teamSelect.appendChild(opt);
            });
        }).catch(function (e) { console.error(e); });

        getJson("/api/users/directory").then(function (users) { allUsers = users; }).catch(function (e) { console.error(e); });

        teamSelect.addEventListener("change", function () {
            var team = teamSelect.value;
            individualSelect.innerHTML = '<option value="">— Toute l\'équipe —</option>';
            if (!team) return;
            allUsers.filter(function (u) { return u.activity === team; }).forEach(function (u) {
                var opt = document.createElement("option");
                opt.value = u.username;
                opt.textContent = u.name || u.username;
                individualSelect.appendChild(opt);
            });
        });

        document.getElementById("taskAssignSubmitBtn").addEventListener("click", function () {
            var title = document.getElementById("taskAssignTitle").value.trim();
            var resultBox = document.getElementById("taskAssignResult");
            if (!title) { resultBox.innerHTML = '<span class="text-danger">Le titre est requis.</span>'; return; }

            var payload = {
                title: title,
                description: document.getElementById("taskAssignDescription").value.trim() || null,
                dueDate: document.getElementById("taskAssignDueDate").value || null,
                priority: document.getElementById("taskAssignPriority").value,
                assignedToUsername: individualSelect.value || null,
                assignedToTeamCode: (!individualSelect.value && teamSelect.value) ? teamSelect.value : null
            };

            postJson("/api/workflow/tasks", payload)
                .then(function () {
                    resultBox.innerHTML = '<span class="text-success">Tâche assignée — notification envoyée.</span>';
                    document.getElementById("taskAssignTitle").value = "";
                    document.getElementById("taskAssignDescription").value = "";
                    document.getElementById("taskAssignDueDate").value = "";
                    document.getElementById("taskAssignPriority").value = "NORMAL";
                    loadNotes();
                })
                .catch(function (e) { resultBox.innerHTML = '<span class="text-danger">Erreur : ' + e.message + '</span>'; });
        });
    }

    // ===== Pôles RCC =====

    var polesCache = [];
    var poleActivitiesCache = {}; // poleId -> [SlaRuleResponse]
    var currentPoleId = null;
    var wfPoleModal, wfPoleActivityModal, wfPoleAlertModal;

    function deleteJson(url) {
        return fetch(url, { method: "DELETE", credentials: "same-origin" }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || ("HTTP " + res.status))); });
            return res.status === 204 ? null : res.json();
        });
    }

    function loadPoles() {
        var grid = document.getElementById("wfPolesGrid");
        return getJson("/api/rcc-poles").then(function (poles) {
            polesCache = poles || [];
            if (!polesCache.length) {
                grid.innerHTML = '<div class="col-12 text-center text-muted py-4">Aucun pôle enregistré pour l\'instant.</div>';
                return;
            }
            grid.innerHTML = polesCache.map(function (p) {
                var managerHtml = p.manager
                    ? '<div class="wf-pole-manager"><i class="bi bi-person-circle"></i> ' + escapeHtml(p.manager.name || p.manager.username) + '</div>'
                    : '<div class="wf-pole-no-manager"><i class="bi bi-exclamation-circle"></i> Manager non renseigné</div>';
                return '<div class="col-md-4 col-sm-6">' +
                    '<div class="wf-pole-card" data-pole-id="' + p.id + '">' +
                        '<div class="wf-pole-name">' + escapeHtml(p.name) + '</div>' +
                        managerHtml +
                        '<div class="wf-pole-count">' + p.activityCount + ' activité(s) référencée(s)</div>' +
                    '</div>' +
                '</div>';
            }).join("");
            Array.prototype.forEach.call(grid.querySelectorAll(".wf-pole-card"), function (card) {
                card.addEventListener("click", function () {
                    openPoleDetail(Number(card.getAttribute("data-pole-id")));
                });
            });
        }).catch(function (e) {
            grid.innerHTML = '<div class="col-12 text-center text-danger py-4">Erreur : ' + escapeHtml(e.message) + '</div>';
        });
    }

    function showPolesListView() {
        currentPoleId = null;
        document.getElementById("wfPolesListView").style.display = "";
        document.getElementById("wfPoleDetailView").style.display = "none";
        loadPoles();
    }

    function openPoleDetail(poleId) {
        currentPoleId = poleId;
        var pole = polesCache.filter(function (p) { return p.id === poleId; })[0];
        if (!pole) return;

        document.getElementById("wfPolesListView").style.display = "none";
        document.getElementById("wfPoleDetailView").style.display = "";

        document.getElementById("wfPoleDetailName").textContent = pole.name;
        document.getElementById("wfPoleDetailWho").textContent = pole.whoWeAre || "";
        document.getElementById("wfPoleDetailWhat").textContent = pole.whatWeDo || "";
        document.getElementById("wfPoleDetailManager").textContent = pole.manager
            ? (pole.manager.name || pole.manager.username) : "Manager non renseigné";
        document.getElementById("wfPoleDetailContact").textContent = pole.contactPhone
            ? ("Tél : " + pole.contactPhone + (pole.contactEmail ? " — " + pole.contactEmail : "")) : (pole.contactEmail || "");
        document.getElementById("wfPoleDetailTeamContact").textContent = pole.teamContactLabel || "";

        var alertBtn = document.getElementById("wfPoleAlertBtn");
        alertBtn.disabled = !pole.manager;
        alertBtn.title = pole.manager ? "" : "Aucun manager désigné pour ce pôle.";

        document.getElementById("wfPoleActivityPoleId").value = poleId;
        loadPoleActivities(poleId);
    }

    function loadPoleActivities(poleId) {
        var container = document.getElementById("wfPoleActivitiesContainer");
        container.innerHTML = '<p class="text-center text-muted">Chargement…</p>';
        return getJson("/api/rcc-poles/" + poleId + "/activities").then(function (rules) {
            poleActivitiesCache[poleId] = rules || [];
            renderPoleActivities(rules || []);
        }).catch(function (e) {
            container.innerHTML = '<p class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    /** Icône par mot-clé de catégorie — repli sur une icône générique si aucun mot-clé ne correspond
     *  (les pôles Outbound/Résolution/Opérations/Business/Agences ont leurs propres catégories). */
    function categoryIcon(category) {
        var c = (category || "").toLowerCase();
        if (c.indexOf("mobile") >= 0) return "bi-phone";
        if (c.indexOf("online") >= 0 || c.indexOf("web") >= 0) return "bi-laptop";
        if (c.indexOf("prépayée") >= 0 || c.indexOf("prepayee") >= 0) return "bi-credit-card-2-front";
        if (c.indexOf("carte") >= 0) return "bi-credit-card";
        if (c.indexOf("wallet") >= 0 || c.indexOf("orange") >= 0 || c.indexOf("wave") >= 0) return "bi-wallet2";
        if (c.indexOf("compte") >= 0) return "bi-bank";
        if (c.indexOf("chèque") >= 0 || c.indexOf("cheque") >= 0) return "bi-postcard";
        if (c.indexOf("virement") >= 0 || c.indexOf("transfert") >= 0) return "bi-arrow-left-right";
        if (c.indexOf("crédit") >= 0 || c.indexOf("credit") >= 0 || c.indexOf("prêt") >= 0 || c.indexOf("pret") >= 0) return "bi-cash-coin";
        if (c.indexOf("réclam") >= 0 || c.indexOf("reclam") >= 0) return "bi-flag";
        return "bi-list-check";
    }

    /** Couleur du badge SLA selon l'urgence — lecture rapide sans avoir à lire le texte. */
    function slaClass(slaLabel) {
        var s = (slaLabel || "").toLowerCase();
        if (s.indexOf("instant") >= 0) return "sla-instant";
        var minutesMatch = s.match(/(\d+)\s*mn/);
        if (minutesMatch) return "sla-fast";
        var hoursMatch = s.match(/(\d+)\s*h/);
        if (hoursMatch) {
            var hours = Number(hoursMatch[1]);
            if (hours <= 4) return "sla-fast";
            if (hours < 24) return "sla-medium";
            return "sla-slow";
        }
        return "sla-medium";
    }

    function renderPoleActivities(rules) {
        var container = document.getElementById("wfPoleActivitiesContainer");
        if (!rules.length) {
            container.innerHTML = '<p class="text-center text-muted">Aucune activité référencée pour ce pôle.</p>';
            return;
        }
        var isQa = currentTeam === "QA" || currentTeam === "ADMIN";
        var order = [];
        var groups = {};
        rules.forEach(function (r) {
            if (!groups[r.category]) { groups[r.category] = []; order.push(r.category); }
            groups[r.category].push(r);
        });

        container.innerHTML = '<div class="wf-pole-categories">' + order.map(function (category, idx) {
            var rows = groups[category].map(function (r) {
                return '<div class="wf-pole-activity-row' + (isQa ? ' qa-editable' : '') + '" data-rule-id="' + r.id + '" data-motif="' + escapeHtml((r.motif || "").toLowerCase()) + '">' +
                    '<span class="wf-pole-activity-motif">' + escapeHtml(r.motif) + '</span>' +
                    '<span class="wf-pole-activity-sla ' + slaClass(r.slaLabel) + '">' + escapeHtml(r.slaLabel) + '</span>' +
                '</div>';
            }).join("");
            // Grille compacte : toutes les catégories ouvertes, délais visibles d'un coup d'œil
            // (un clic sur l'en-tête replie une catégorie).
            return '<div class="wf-pole-category wf-open" style="animation-delay:' + (idx * 40) + 'ms" data-category="' + escapeHtml(category.toLowerCase()) + '">' +
                '<div class="wf-pole-category-header">' +
                    '<div class="wf-pole-category-icon"><i class="bi ' + categoryIcon(category) + '"></i></div>' +
                    '<div class="wf-pole-category-title">' + escapeHtml(category) + '</div>' +
                    '<div class="wf-pole-category-badge">' + groups[category].length + '</div>' +
                    '<i class="bi bi-chevron-down wf-pole-category-chevron"></i>' +
                '</div>' +
                '<div class="wf-pole-category-body"><div class="wf-pole-category-body-inner">' + rows + '</div></div>' +
            '</div>';
        }).join("") + '</div><p class="wf-pole-no-match" style="display:none;">Aucune activité ne correspond à cette recherche.</p>';

        Array.prototype.forEach.call(container.querySelectorAll(".wf-pole-category-header"), function (header) {
            header.addEventListener("click", function () {
                header.closest(".wf-pole-category").classList.toggle("wf-open");
            });
        });
        if (isQa) {
            Array.prototype.forEach.call(container.querySelectorAll(".wf-pole-activity-row"), function (row) {
                row.addEventListener("click", function () {
                    openActivityModal(Number(row.getAttribute("data-rule-id")));
                });
            });
        }

        wirePoleActivitySearch(container);
    }

    /** Recherche instantanée : filtre les lignes par motif, masque les catégories sans résultat
     *  et déplie celles qui correspondent (toutes rouvertes une fois le champ vidé). */
    function wirePoleActivitySearch(container) {
        var input = document.getElementById("wfPoleActivitySearch");
        if (!input) return;
        input.value = ""; // repart d'une recherche vide à chaque changement de pôle
        if (input.dataset.wired) return;
        input.dataset.wired = "1";
        input.addEventListener("input", function () {
            var term = input.value.trim().toLowerCase();
            var anyVisible = false;
            Array.prototype.forEach.call(container.querySelectorAll(".wf-pole-category"), function (cat) {
                var rows = cat.querySelectorAll(".wf-pole-activity-row");
                var matchCount = 0;
                Array.prototype.forEach.call(rows, function (row) {
                    var match = !term || row.getAttribute("data-motif").indexOf(term) >= 0;
                    row.style.display = match ? "" : "none";
                    if (match) matchCount++;
                });
                var categoryMatches = matchCount > 0;
                cat.style.display = categoryMatches ? "" : "none";
                cat.classList.toggle("wf-open", categoryMatches);
                if (categoryMatches) anyVisible = true;
            });
            var noMatch = container.querySelector(".wf-pole-no-match");
            if (noMatch) noMatch.style.display = (term && !anyVisible) ? "" : "none";
        });
    }

    function openPoleModal(poleId) {
        var resultBox = document.getElementById("wfPoleModalResult");
        resultBox.textContent = "";
        var pole = poleId ? polesCache.filter(function (p) { return p.id === poleId; })[0] : null;
        document.getElementById("wfPoleEditId").value = poleId || "";
        document.getElementById("wfPoleModalTitle").textContent = poleId ? "Modifier le pôle" : "Nouveau pôle";
        document.getElementById("wfPoleName").value = pole ? pole.name : "";
        document.getElementById("wfPoleManagerUsername").value = pole && pole.manager ? pole.manager.username : "";
        document.getElementById("wfPoleContactPhone").value = pole ? (pole.contactPhone || "") : "";
        document.getElementById("wfPoleContactEmail").value = pole ? (pole.contactEmail || "") : "";
        document.getElementById("wfPoleTeamContactLabel").value = pole ? (pole.teamContactLabel || "") : "";
        document.getElementById("wfPoleWhoWeAre").value = pole ? (pole.whoWeAre || "") : "";
        document.getElementById("wfPoleWhatWeDo").value = pole ? (pole.whatWeDo || "") : "";
        document.getElementById("wfPoleActive").checked = pole ? pole.isActive !== false : true;
        wfPoleModal.show();
    }

    function wirePoleModal() {
        document.getElementById("wfPoleNewBtn").addEventListener("click", function () { openPoleModal(null); });
        document.getElementById("wfPoleEditBtn").addEventListener("click", function () { openPoleModal(currentPoleId); });

        document.getElementById("wfPoleSaveBtn").addEventListener("click", function () {
            var resultBox = document.getElementById("wfPoleModalResult");
            var id = document.getElementById("wfPoleEditId").value;
            var name = document.getElementById("wfPoleName").value.trim();
            if (!name) {
                resultBox.innerHTML = '<span class="text-danger">Le nom du pôle est obligatoire.</span>';
                return;
            }
            var payload = {
                name: name,
                managerUsername: document.getElementById("wfPoleManagerUsername").value.trim() || null,
                contactPhone: document.getElementById("wfPoleContactPhone").value.trim() || null,
                contactEmail: document.getElementById("wfPoleContactEmail").value.trim() || null,
                teamContactLabel: document.getElementById("wfPoleTeamContactLabel").value.trim() || null,
                whoWeAre: document.getElementById("wfPoleWhoWeAre").value.trim() || null,
                whatWeDo: document.getElementById("wfPoleWhatWeDo").value.trim() || null,
                isActive: document.getElementById("wfPoleActive").checked
            };
            var request = id ? putJson("/api/rcc-poles/" + id, payload) : postJson("/api/rcc-poles", payload);
            request.then(function () {
                wfPoleModal.hide();
                if (id) {
                    loadPoles().then(function () { if (currentPoleId) openPoleDetail(currentPoleId); });
                } else {
                    showPolesListView();
                }
            }).catch(function (e) {
                resultBox.innerHTML = '<span class="text-danger">Erreur : ' + escapeHtml(e.message) + '</span>';
            });
        });
    }

    function openActivityModal(ruleId) {
        var resultBox = document.getElementById("wfPoleActivityModalResult");
        resultBox.textContent = "";
        var rule = ruleId ? (poleActivitiesCache[currentPoleId] || []).filter(function (r) { return r.id === ruleId; })[0] : null;
        document.getElementById("wfPoleActivityEditId").value = ruleId || "";
        document.getElementById("wfPoleActivityModalTitle").textContent = ruleId ? "Modifier l'activité" : "Nouvelle activité";
        document.getElementById("wfPoleActivityCategory").value = rule ? rule.category : "";
        document.getElementById("wfPoleActivityMotif").value = rule ? rule.motif : "";
        document.getElementById("wfPoleActivitySlaLabel").value = rule ? rule.slaLabel : "";
        document.getElementById("wfPoleActivityActive").checked = rule ? rule.isActive !== false : true;
        document.getElementById("wfPoleActivityDeleteBtn").style.display = ruleId ? "" : "none";
        wfPoleActivityModal.show();
    }

    function wirePoleActivityModal() {
        document.getElementById("wfPoleActivityNewBtn").addEventListener("click", function () { openActivityModal(null); });

        document.getElementById("wfPoleActivitySaveBtn").addEventListener("click", function () {
            var resultBox = document.getElementById("wfPoleActivityModalResult");
            var poleId = document.getElementById("wfPoleActivityPoleId").value;
            var ruleId = document.getElementById("wfPoleActivityEditId").value;
            var category = document.getElementById("wfPoleActivityCategory").value.trim();
            var motif = document.getElementById("wfPoleActivityMotif").value.trim();
            var slaLabel = document.getElementById("wfPoleActivitySlaLabel").value.trim();
            if (!category || !motif || !slaLabel) {
                resultBox.innerHTML = '<span class="text-danger">Produit/service, activité et délai sont obligatoires.</span>';
                return;
            }
            var payload = {
                category: category,
                motif: motif,
                slaLabel: slaLabel,
                slaHours: 0,
                isActive: document.getElementById("wfPoleActivityActive").checked
            };
            var request = ruleId
                ? putJson("/api/rcc-poles/" + poleId + "/activities/" + ruleId, payload)
                : postJson("/api/rcc-poles/" + poleId + "/activities", payload);
            request.then(function () {
                wfPoleActivityModal.hide();
                loadPoleActivities(Number(poleId));
                loadPoles();
            }).catch(function (e) {
                resultBox.innerHTML = '<span class="text-danger">Erreur : ' + escapeHtml(e.message) + '</span>';
            });
        });

        document.getElementById("wfPoleActivityDeleteBtn").addEventListener("click", function () {
            if (!confirm("Supprimer cette activité ?")) return;
            var poleId = document.getElementById("wfPoleActivityPoleId").value;
            var ruleId = document.getElementById("wfPoleActivityEditId").value;
            deleteJson("/api/rcc-poles/" + poleId + "/activities/" + ruleId).then(function () {
                wfPoleActivityModal.hide();
                loadPoleActivities(Number(poleId));
                loadPoles();
            }).catch(function (e) {
                document.getElementById("wfPoleActivityModalResult").innerHTML =
                    '<span class="text-danger">Erreur : ' + escapeHtml(e.message) + '</span>';
            });
        });
    }

    function wirePoleAlert() {
        document.getElementById("wfPoleAlertBtn").addEventListener("click", function () {
            var pole = polesCache.filter(function (p) { return p.id === currentPoleId; })[0];
            if (!pole || !pole.manager) return;
            document.getElementById("wfPoleAlertManagerName").textContent = pole.manager.name || pole.manager.username;
            document.getElementById("wfPoleAlertMessage").value = "";
            document.getElementById("wfPoleAlertResult").textContent = "";
            wfPoleAlertModal.show();
        });

        document.getElementById("wfPoleAlertSendBtn").addEventListener("click", function () {
            var resultBox = document.getElementById("wfPoleAlertResult");
            var message = document.getElementById("wfPoleAlertMessage").value.trim();
            postJson("/api/rcc-poles/" + currentPoleId + "/alert-manager", { message: message || null })
                .then(function () {
                    resultBox.innerHTML = '<span class="text-success">Alerte envoyée.</span>';
                    setTimeout(function () { wfPoleAlertModal.hide(); }, 900);
                })
                .catch(function (e) {
                    resultBox.innerHTML = '<span class="text-danger">Erreur : ' + escapeHtml(e.message) + '</span>';
                });
        });
    }

    function wirePolesTab() {
        wfPoleModal = new bootstrap.Modal(document.getElementById("wfPoleModal"));
        wfPoleActivityModal = new bootstrap.Modal(document.getElementById("wfPoleActivityModal"));
        wfPoleAlertModal = new bootstrap.Modal(document.getElementById("wfPoleAlertModal"));
        document.getElementById("wfPoleBackBtn").addEventListener("click", showPolesListView);
        wirePoleModal();
        wirePoleActivityModal();
        wirePoleAlert();
    }

    // ===== Onglets =====

    var TAB_DEFS = [
        { btn: "wfTabOverviewBtn", pane: "wfPaneOverview", onShow: function () { updateOverviewAndBadges(); } },
        { btn: "wfTabMineBtn", pane: "wfPaneMine" },
        { btn: "wfTabPendingBtn", pane: "wfPanePending", onShow: function () { loadPending(); } },
        { btn: "wfTabSlaBtn", pane: "wfPaneSla", onShow: function () { showPolesListView(); } },
        { btn: "wfTabHistoryBtn", pane: "wfPaneHistory", onShow: function () { loadHistory(); } },
        { btn: "wfTabTemplatesBtn", pane: "wfPaneTemplates", onShow: function () { loadTemplatesAdmin(); } },
        { btn: "wfTabTasksBtn", pane: "wfPaneTasks" }
    ];

    function wireTabs() {
        TAB_DEFS.forEach(function (def) {
            var btn = document.getElementById(def.btn);
            if (!btn) return;
            btn.addEventListener("click", function () { activateTab(def); });
        });
    }

    function activateTab(activeDef) {
        TAB_DEFS.forEach(function (def) {
            var btn = document.getElementById(def.btn);
            var pane = document.getElementById(def.pane);
            if (!btn || !pane) return;
            var isActive = def === activeDef;
            btn.classList.toggle("active", isActive);
            pane.style.display = isActive ? "" : "none";
        });
        if (activeDef.onShow) activeDef.onShow();
    }

    // ===== Vue d'ensemble =====

    function updateOverviewAndBadges() {
        var mineOpen = mineCache.filter(function (r) { return r.status === "PENDING"; });
        var tasksOpen = tasksCache.filter(function (t) { return t.status !== "DONE"; });

        setText("wfStatMineOpen", mineOpen.length);
        setText("wfStatTasksOpen", tasksOpen.length);

        var badgePending = document.getElementById("wfBadgePending");
        if (badgePending) {
            if (pendingCache.length > 0) { badgePending.textContent = pendingCache.length; badgePending.style.display = ""; }
            else { badgePending.style.display = "none"; }
        }
        var badgeTasks = document.getElementById("wfBadgeTasks");
        if (badgeTasks) {
            if (tasksOpen.length > 0) { badgeTasks.textContent = tasksOpen.length; badgeTasks.style.display = ""; }
            else { badgeTasks.style.display = "none"; }
        }

        if (currentTeam) {
            setText("wfStatPendingCount", pendingCache.length);
            var decided = historyCache.filter(function (r) { return r.status === "APPROVED" || r.status === "REJECTED"; });
            var approved = decided.filter(function (r) { return r.status === "APPROVED"; });
            var rateEl = document.getElementById("wfStatApprovalRate");
            if (rateEl) rateEl.textContent = decided.length > 0 ? Math.round((approved.length / decided.length) * 100) + "%" : "—";
        }

        renderOverviewMineList();
        renderOverviewPendingList();
        renderOverviewTasksList();
    }

    function setText(id, value) {
        var el = document.getElementById(id);
        if (el) el.textContent = value;
    }

    function renderOverviewMineList() {
        var container = document.getElementById("wfOverviewMineList");
        if (!container) return;
        var recent = mineCache.slice(0, 5);
        if (!recent.length) { container.innerHTML = '<p class="text-muted text-center mb-0">Aucune demande pour l\'instant.</p>'; return; }
        container.innerHTML = recent.map(function (r) {
            return '<div class="d-flex justify-content-between align-items-center py-2 border-bottom">' +
                '<div><div class="fw-semibold small">' + escapeHtml(r.title) + '</div>' +
                '<div class="text-muted small">' + escapeHtml(TYPE_LABELS[r.type] || r.type) + ' · ' + escapeHtml(formatPeriod(r)) + '</div></div>' +
                (STATUS_BADGES[r.status] || escapeHtml(r.status)) + '</div>';
        }).join("");
    }

    function renderOverviewPendingList() {
        var container = document.getElementById("wfOverviewPendingList");
        if (!container) return;
        var recent = pendingCache.slice(0, 5);
        if (!recent.length) { container.innerHTML = '<p class="text-muted text-center mb-0">Aucune demande en attente.</p>'; return; }
        container.innerHTML = recent.map(function (r) {
            return '<div class="d-flex justify-content-between align-items-center py-2 border-bottom">' +
                '<div><div class="fw-semibold small">' + escapeHtml(r.title) + '</div>' +
                '<div class="text-muted small">' + escapeHtml(r.requestedByName || r.requestedByUsername) + ' · ' + escapeHtml(formatPeriod(r)) + '</div></div>' +
                '<span class="badge bg-warning text-dark">En attente</span></div>';
        }).join("");
    }

    function renderOverviewTasksList() {
        var container = document.getElementById("wfOverviewTasksList");
        if (!container) return;
        var open = tasksCache.filter(function (t) { return t.status !== "DONE"; }).slice(0, 5);
        if (!open.length) { container.innerHTML = '<p class="text-muted text-center mb-0">Aucune tâche ouverte. 🎉</p>'; return; }
        container.innerHTML = open.map(function (t) {
            var due = t.dueDate ? '<span class="text-muted small"><i class="bi bi-calendar-event"></i> ' + escapeHtml(t.dueDate) + '</span>' : "";
            return '<div class="d-flex justify-content-between align-items-center py-2 border-bottom">' +
                '<span class="small">' + escapeHtml(t.title) + '</span>' + due + '</div>';
        }).join("");
    }

    // ===== Historique & archives (QA/ADMIN) =====

    function loadHistory() {
        getJson("/api/workflow/requests").then(function (requests) {
            historyCache = requests || [];
            renderHistory();
            updateOverviewAndBadges();
        }).catch(function (e) {
            document.getElementById("wfHistoryBody").innerHTML =
                '<tr><td colspan="8" class="text-center text-danger">Erreur (' + escapeHtml(e.message) + ')</td></tr>';
        });
    }

    function renderHistory() {
        var status = document.getElementById("wfHistStatus").value;
        var type = document.getElementById("wfHistType").value;
        var search = document.getElementById("wfHistSearch").value.trim().toLowerCase();

        var filtered = historyCache.filter(function (r) {
            if (status && r.status !== status) return false;
            if (type && r.type !== type) return false;
            if (search) {
                var haystack = (r.title + " " + (r.requestedByName || r.requestedByUsername || "")).toLowerCase();
                if (haystack.indexOf(search) === -1) return false;
            }
            return true;
        });

        var body = document.getElementById("wfHistoryBody");
        if (!filtered.length) {
            body.innerHTML = '<tr><td colspan="8" class="text-center text-muted">Aucune demande ne correspond à ces filtres.</td></tr>';
            return;
        }
        filtered.sort(function (a, b) { return new Date(b.createdAt) - new Date(a.createdAt); });
        body.innerHTML = filtered.map(function (r) {
            return "<tr>" +
                "<td>" + escapeHtml(TYPE_LABELS[r.type] || r.type) + "</td>" +
                "<td>" + escapeHtml(r.title) + "</td>" +
                "<td>" + escapeHtml(formatPeriod(r)) + "</td>" +
                "<td>" + escapeHtml(r.requestedByName || r.requestedByUsername) + "</td>" +
                "<td>" + (STATUS_BADGES[r.status] || escapeHtml(r.status)) + "</td>" +
                "<td>" + (r.decidedByUsername ? escapeHtml(r.decidedByUsername) : "—") + "</td>" +
                "<td>" + (r.decidedAt ? formatDate(r.decidedAt) : "—") + "</td>" +
                '<td class="text-end"><button class="btn btn-sm btn-outline-danger delete-history-btn" data-id="' + r.requestId + '"><i class="bi bi-trash"></i></button></td>' +
                "</tr>";
        }).join("");

        Array.prototype.forEach.call(body.querySelectorAll(".delete-history-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer définitivement cette demande de l'historique ?")) return;
                fetch("/api/workflow/requests/" + btn.getAttribute("data-id"), { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                    .then(loadHistory)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function wireHistoryFilters() {
        ["wfHistStatus", "wfHistType"].forEach(function (id) {
            document.getElementById(id).addEventListener("change", renderHistory);
        });
        var timer;
        document.getElementById("wfHistSearch").addEventListener("input", function () {
            clearTimeout(timer);
            timer = setTimeout(renderHistory, 300);
        });
    }

    var wfTemplateModal = null;

    function formatHoursOpen(hours) {
        if (hours == null) return "—";
        if (hours < 24) return hours + " h";
        return Math.floor(hours / 24) + " j " + (hours % 24) + " h";
    }

    // ===== Modèles de demande (QA/ADMIN) =====

    var templatesAdminCache = [];

    function loadTemplatesAdmin() {
        getJson("/api/workflow/templates/all").then(function (templates) {
            templatesAdminCache = templates || [];
            renderTemplatesAdmin();
        }).catch(function (e) {
            document.getElementById("wfTemplatesBody").innerHTML = '<tr><td colspan="6" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function renderTemplatesAdmin() {
        var body = document.getElementById("wfTemplatesBody");
        if (!templatesAdminCache.length) { body.innerHTML = '<tr><td colspan="6" class="text-center text-muted">Aucun modèle. Cliquez sur "Nouveau modèle" pour commencer.</td></tr>'; return; }
        body.innerHTML = templatesAdminCache.map(function (t) {
            var statusBadge = t.active ? '<span class="badge bg-success">Actif</span>' : '<span class="badge bg-secondary">Inactif</span>';
            return "<tr><td>" + escapeHtml(t.name) + "</td><td>" + escapeHtml(TYPE_LABELS[t.type] || t.type) + "</td><td>" +
                escapeHtml(t.defaultTitle) + "</td><td>" + escapeHtml(TEAM_LABELS[t.defaultAssignedTeam] || t.defaultAssignedTeam || "—") +
                "</td><td>" + statusBadge + '</td><td class="text-end">' +
                '<button class="btn btn-sm btn-outline-secondary me-1" data-edit-template="' + t.templateId + '"><i class="bi bi-pencil"></i></button>' +
                '<button class="btn btn-sm btn-outline-danger" data-del-template="' + t.templateId + '"><i class="bi bi-trash"></i></button></td></tr>';
        }).join("");
        Array.prototype.forEach.call(body.querySelectorAll("[data-edit-template]"), function (btn) {
            btn.addEventListener("click", function () { openTemplateModal(parseInt(btn.getAttribute("data-edit-template"), 10)); });
        });
        Array.prototype.forEach.call(body.querySelectorAll("[data-del-template]"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer ce modèle ?")) return;
                fetch("/api/workflow/templates/" + btn.getAttribute("data-del-template"), { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                    .then(function () { loadTemplatesAdmin(); loadRequestTemplates(); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function openTemplateModal(id) {
        var t = id ? templatesAdminCache.find(function (x) { return x.templateId === id; }) : null;
        document.getElementById("wfTemplateModalTitle").textContent = t ? "Modifier le modèle" : "Nouveau modèle";
        document.getElementById("wfTemplateEditId").value = t ? t.templateId : "";
        document.getElementById("wfTemplateName").value = t ? t.name : "";
        document.getElementById("wfTemplateType").value = t ? t.type : "LEAVE";
        document.getElementById("wfTemplateDefaultTitle").value = t ? t.defaultTitle : "";
        document.getElementById("wfTemplateDefaultDetails").value = t ? (t.defaultDetails || "") : "";
        document.getElementById("wfTemplateDefaultTeam").value = t ? (t.defaultAssignedTeam || "ADMIN") : "ADMIN";
        document.getElementById("wfTemplateActive").checked = t ? t.active : true;
        wfTemplateModal.show();
    }

    function wireTemplatesAdmin() {
        var newBtn = document.getElementById("wfTemplateNewBtn");
        if (!newBtn) return;
        newBtn.addEventListener("click", function () { openTemplateModal(null); });
        document.getElementById("wfTemplateSaveBtn").addEventListener("click", function () {
            var name = document.getElementById("wfTemplateName").value.trim();
            var defaultTitle = document.getElementById("wfTemplateDefaultTitle").value.trim();
            if (!name || !defaultTitle) { alert("Le nom et le titre par défaut sont obligatoires."); return; }
            var payload = {
                name: name,
                type: document.getElementById("wfTemplateType").value,
                defaultTitle: defaultTitle,
                defaultDetails: document.getElementById("wfTemplateDefaultDetails").value.trim() || null,
                defaultAssignedTeam: document.getElementById("wfTemplateDefaultTeam").value,
                active: document.getElementById("wfTemplateActive").checked
            };
            var editId = document.getElementById("wfTemplateEditId").value;
            var request = editId ? putJson("/api/workflow/templates/" + editId, payload) : postJson("/api/workflow/templates", payload);
            request.then(function () {
                wfTemplateModal.hide();
                loadTemplatesAdmin();
                loadRequestTemplates();
            }).catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    function putJson(url, body) {
        return fetch(url, {
            method: "PUT",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body || {})
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || ("HTTP " + res.status))); });
            return res.json();
        });
    }

    // ===== Init =====

    function init() {
        wfTemplateModal = new bootstrap.Modal(document.getElementById("wfTemplateModal"));
        loadMotifCatalog();
        wireForm();
        wireTabs();
        wirePolesTab();
        wireHistoryFilters();
        wireTemplateSelect();
        wireTemplatesAdmin();
        loadMine();
        loadRequestTemplates();
        wireNotes();
        loadNotes();

        fetch("/api/auth/me", { credentials: "same-origin" })
            .then(function (res) { return res.json(); })
            .then(function (user) {
                currentProfile = computeProfile(user);
                if (currentProfile === "ADMIN" || currentProfile === "QA") {
                    currentTeam = currentProfile;
                    Array.prototype.forEach.call(document.querySelectorAll(".qa-only"), function (el) {
                        el.style.display = "";
                    });
                    document.getElementById("teamBadge").textContent = TEAM_LABELS[currentTeam];
                    loadPending();
                    wireTaskAssign();
                }
                updateOverviewAndBadges();
            })
            .catch(function (e) { console.error("workflow.js init failed:", e); });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
