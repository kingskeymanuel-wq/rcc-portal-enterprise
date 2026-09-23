"use strict";

(function () {
    var getJson = RccApi.getJson;
    var escapeHtml = RccApi.escapeHtml;
    var employees = [];
    var leaves = [];
    var balances = [];
    var formations = [];
    var tasks = [];
    var reporting = [];
    var performanceChart = null;
    var currentDossierUserId = null;
    var teamRosterModal = null;
    var dossierModal = null;
    var controlDetailModal = null;

    function el(id) { return document.getElementById(id); }
    function val(v, fallback) { return v === null || v === undefined || v === "" ? (fallback || "—") : v; }
    function monthNow() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0");
    }
    function formatDate(v) {
        if (!v) return "—";
        try { return new Date(v + (String(v).length === 10 ? "T00:00:00" : "")).toLocaleDateString("fr-FR"); } catch (e) { return v; }
    }
    function safeNumber(v) { return typeof v === "number" && isFinite(v) ? v : 0; }
    function normalizeStatus(u) { return u && u.active === false ? "INACTIF" : "ACTIF"; }
    function teamKey(u) { return u.activity || u.service || "Sans équipe"; }

    function api(url) {
        return getJson(url).catch(function (e) {
            // Une carte indisponible ne doit pas empêcher le reste du portail de fonctionner.
            return { __error: e.message || "Erreur API" };
        });
    }

    function showError(message) {
        var box = el("hrError");
        if (!box) return;
        box.textContent = message;
        box.classList.remove("d-none");
    }

    function hideError() { if (el("hrError")) el("hrError").classList.add("d-none"); }

    function filterByHrScope(list) {
        if (!Array.isArray(list) || !employees.length) return Array.isArray(list) ? list : [];
        var usernames = {};
        employees.forEach(function (u) { if (u.username) usernames[String(u.username).toLowerCase()] = true; });
        return list.filter(function (x) {
            var username = x.username || x.requestedByUsername || x.assignedToUsername;
            return !username || usernames[String(username).toLowerCase()];
        });
    }

    function renderKpis() {
        var activeEmployees = employees.filter(function (u) { return normalizeStatus(u) === "ACTIF"; });
        var pending = leaves.filter(function (x) { return String(x.status || "").toUpperCase() === "PENDING"; });
        var onLeave = leaves.filter(function (x) { return String(x.status || "").toUpperCase() === "APPROVED" && x.periodFrom && x.periodTo; });
        var activeTraining = formations.filter(function (f) { return !f.status || !["ARCHIVED", "CLOSED", "TERMINEE"].includes(String(f.status).toUpperCase()); });

        el("kpiEmployees").textContent = employees.length;
        el("kpiEmployeesTrend").textContent = activeEmployees.length + " actifs · données RCC";
        el("kpiLeave").textContent = onLeave.length;
        el("kpiPending").textContent = pending.length + " demande(s) en attente";
        el("kpiTraining").textContent = activeTraining.length;

        var progress = activeTraining.filter(function (f) { return typeof f.myProgressPercent === "number"; });
        var avgProgress = progress.length ? progress.reduce(function (a, f) { return a + f.myProgressPercent; }, 0) / progress.length : null;
        el("kpiTrainingProgress").textContent = avgProgress === null ? "Parcours RCC disponibles" : Math.round(avgProgress) + "% de progression moyenne";
    }

    function renderTeams() {
        var counts = {};
        employees.forEach(function (u) {
            var team = u.activity || u.service || "Sans équipe";
            counts[team] = (counts[team] || 0) + 1;
        });
        var rows = Object.keys(counts).map(function (team) { return { team: team, count: counts[team] }; })
            .sort(function (a, b) { return b.count - a.count; });
        var max = rows.length ? rows[0].count : 1;
        el("teamBars").innerHTML = rows.length ? rows.slice(0, 10).map(function (r) {
            return '<div class="team-row"><span title="' + escapeHtml(r.team) + '">' + escapeHtml(r.team) + '</span><div class="team-track"><div class="team-fill" style="width:' + ((r.count / max) * 100).toFixed(1) + '%"></div></div><b>' + r.count + '</b></div>';
        }).join("") : '<div class="text-muted small">Aucune donnée d’équipe.</div>';
    }

    function renderLeaves() {
        var pending = leaves.filter(function (x) { return String(x.status || "").toUpperCase() === "PENDING"; }).length;
        var approved = leaves.filter(function (x) { return String(x.status || "").toUpperCase() === "APPROVED"; }).length;
        var rejected = leaves.filter(function (x) { return String(x.status || "").toUpperCase() === "REJECTED"; }).length;
        el("leavePending").textContent = pending;
        el("leaveApproved").textContent = approved;
        el("leaveRejected").textContent = rejected;
        el("leaveTable").innerHTML = leaves.length ? leaves.slice(0, 6).map(function (r) {
            var cls = String(r.status || "").toUpperCase() === "APPROVED" ? "status-approved" : String(r.status || "").toUpperCase() === "REJECTED" ? "status-rejected" : "status-pending";
            var label = String(r.status || "PENDING").toUpperCase();
            return '<div class="mini-row"><span><b>' + escapeHtml(val(r.requestedByName || r.requestedByUsername)) + '</b><br><small>' + escapeHtml(val(r.title || r.type)) + '</small></span><span class="status-pill ' + cls + '">' + escapeHtml(label) + '</span></div>';
        }).join("") : '<div class="text-muted small">Aucune demande de congé.</div>';
    }

    function renderSync(sync) {
        if (!sync || sync.__error) {
            el("syncStatus").innerHTML = '<div class="sync-state"><i class="sync-dot warn"></i><b>Statut indisponible</b></div><div class="text-muted">' + escapeHtml(sync && sync.__error ? sync.__error : "—") + '</div>';
            return;
        }
        var warn = !!sync.dataStaleWarning;
        el("syncStatus").innerHTML = '<div class="sync-state"><i class="sync-dot ' + (warn ? "warn" : "") + '"></i><b>' + (warn ? "Attention : données à actualiser" : "Données synchronisées") + '</b></div>' +
            '<div class="sync-item"><span>Dernier import KPI</span><b>' + escapeHtml(formatDateTime(sync.lastKpiImportAt)) + '</b></div>' +
            '<div class="sync-item"><span>Dernier import planning</span><b>' + escapeHtml(formatDateTime(sync.lastScheduleImportAt)) + '</b></div>' +
            '<div class="sync-item"><span>Imports aujourd’hui</span><b>' + safeNumber(sync.totalImportsToday) + '</b></div>' +
            '<div class="sync-item"><span>Entrées KPI</span><b>' + safeNumber(sync.totalKpiEntries) + '</b></div>';
    }

    function formatDateTime(v) {
        if (!v) return "—";
        try { return new Date(v).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" }); } catch (e) { return v; }
    }

    function renderTeamGrid() {
        var counts = {};
        employees.forEach(function (u) { var t = teamKey(u); counts[t] = (counts[t] || 0) + 1; });
        var teams = Object.keys(counts).sort(function (a, b) { return counts[b] - counts[a]; });
        el("employeeTeamGrid").innerHTML = teams.length ? teams.map(function (t) {
            return '<div class="team-tile" data-team="' + escapeHtml(t) + '" role="button" tabindex="0">' +
                '<i class="bi bi-people-fill"></i><div><b>' + escapeHtml(t) + '</b><span>' + counts[t] + ' collaborateur' + (counts[t] > 1 ? "s" : "") + '</span></div></div>';
        }).join("") : '<div class="text-muted small">Aucune équipe.</div>';
        Array.prototype.forEach.call(el("employeeTeamGrid").querySelectorAll(".team-tile"), function (tile) {
            tile.addEventListener("click", function () { openTeamRoster(tile.getAttribute("data-team")); });
            tile.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); tile.click(); } });
        });
    }

    function openTeamRoster(team) {
        var members = employees.filter(function (u) { return teamKey(u) === team; });
        el("teamRosterName").textContent = team;
        el("teamRosterBody").innerHTML = members.length ? members.map(function (u) {
            var statusClass = normalizeStatus(u) === "ACTIF" ? "status-approved" : "status-rejected";
            return '<tr><td><div class="employee-name">' + escapeHtml(val(u.fullName, u.username)) + '</div><div class="employee-meta">' + escapeHtml(val(u.username)) + '</div></td>' +
                '<td><span class="status-pill ' + statusClass + '">' + normalizeStatus(u) + '</span></td>' +
                '<td>' + escapeHtml(val(u.role)) + '</td>' +
                '<td>' + escapeHtml(val(u.contractType)) + '</td>' +
                '<td>' + escapeHtml(val(u.residencePlace)) + '</td>' +
                '<td><button type="button" class="btn btn-sm btn-outline-primary dossier-btn" data-id="' + u.id + '" data-name="' + escapeHtml(val(u.fullName, u.username)) + '"><i class="bi bi-folder2"></i> Dossier</button></td></tr>';
        }).join("") : '<tr><td colspan="6" class="text-center text-muted">Aucun agent dans cette équipe.</td></tr>';
        Array.prototype.forEach.call(el("teamRosterBody").querySelectorAll(".dossier-btn"), function (btn) {
            btn.addEventListener("click", function () { openDossier(btn.getAttribute("data-id"), btn.getAttribute("data-name")); });
        });
        teamRosterModal.show();
    }

    function openDossier(userId, userName) {
        currentDossierUserId = userId;
        el("dossierUserName").textContent = userName;
        el("dossierError").classList.add("d-none");
        el("dossierList").innerHTML = '<div class="text-muted small"><span class="spinner-border spinner-border-sm"></span> Chargement…</div>';
        dossierModal.show();
        loadDossier();
    }

    function loadDossier() {
        getJson("/api/hr/dossiers/" + currentDossierUserId).then(function (files) {
            renderDossier(Array.isArray(files) ? files : []);
        }).catch(function (e) {
            el("dossierList").innerHTML = '<div class="text-muted small">Impossible de charger le dossier.</div>';
            showDossierError(e.message);
        });
    }

    function renderDossier(files) {
        el("dossierList").innerHTML = files.length ? files.map(function (f) {
            return '<div class="dossier-item"><a href="' + f.storageUrl + '" target="_blank" rel="noopener"><i class="bi bi-file-earmark-text"></i> ' + escapeHtml(f.fileName) + '</a>' +
                '<button type="button" class="btn btn-sm btn-link text-danger dossier-remove" data-id="' + f.id + '"><i class="bi bi-trash"></i></button></div>';
        }).join("") : '<div class="text-muted small">Aucun document chargé pour ce collaborateur.</div>';
        Array.prototype.forEach.call(el("dossierList").querySelectorAll(".dossier-remove"), function (btn) {
            btn.addEventListener("click", function () {
                fetch("/api/hr/dossiers/attachments/" + btn.getAttribute("data-id"), { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok && res.status !== 204) throw new Error("HTTP " + res.status); loadDossier(); })
                    .catch(function (e) { showDossierError(e.message); });
            });
        });
    }

    function showDossierError(message) {
        var box = el("dossierError");
        box.textContent = message || "Une erreur est survenue.";
        box.classList.remove("d-none");
    }

    function wireDossierUpload() {
        el("dossierUploadForm").addEventListener("submit", function (e) {
            e.preventDefault();
            var file = el("dossierFileInput").files[0];
            if (!file || !currentDossierUserId) return;
            var formData = new FormData();
            formData.append("file", file);
            fetch("/api/hr/dossiers/" + currentDossierUserId, { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function () { el("dossierFileInput").value = ""; loadDossier(); })
                .catch(function (e) { showDossierError(e.message); });
        });
    }

    function renderControlBadges() {
        el("badgeContracts").textContent = employees.length + " dossier(s)";

        var pending = leaves.filter(function (x) { return String(x.status || "").toUpperCase() === "PENDING"; }).length;
        el("badgeLeave").textContent = pending + " en attente";

        var perfValues = (Array.isArray(reporting) ? reporting : []).map(function (r) { return r.performanceGlobale; }).filter(function (v) { return v != null; });
        var avgPerf = perfValues.length ? (perfValues.reduce(function (a, b) { return a + Number(b); }, 0) / perfValues.length) : null;
        el("badgePerformance").textContent = avgPerf === null ? "—" : avgPerf.toFixed(1) + "% moyen";
    }

    // ═══════════════════════════════════════════════════════════════════
    // Centre de contrôle RH — navigation Équipe → Service → Agent (4 tuiles)
    // ═══════════════════════════════════════════════════════════════════

    var CONTROL_TITLES = {
        contracts: '<i class="bi bi-file-earmark-person"></i> Contrats & dossiers',
        leave: '<i class="bi bi-calendar2-check"></i> Congés — demandes',
        performance: '<i class="bi bi-graph-up-arrow"></i> Performance'
    };
    var controlNav = { kind: null, team: null, service: null };
    var agentDetailModal = null;

    /** Arbre Équipe → Service → liste d'agents, construit une fois à partir des collaborateurs
     *  déjà chargés — commun aux 4 tuiles, seul le contenu affiché à l'étape "agent" varie. */
    function teamServiceTree() {
        var tree = {};
        employees.forEach(function (u) {
            var t = teamKey(u);
            var s = u.service || "Sans service";
            if (!tree[t]) tree[t] = {};
            if (!tree[t][s]) tree[t][s] = [];
            tree[t][s].push(u);
        });
        return tree;
    }

    function openControlDetail(kind) {
        controlNav = { kind: kind, team: null, service: null };
        renderControlNav();
        controlDetailModal.show();
    }

    function renderControlBreadcrumb() {
        var parts = ['<a href="#" data-nav="root">' + CONTROL_TITLES[controlNav.kind].replace(/<i[^>]*><\/i>\s*/, "") + '</a>'];
        if (controlNav.team) parts.push('<a href="#" data-nav="team">' + escapeHtml(controlNav.team) + '</a>');
        if (controlNav.service) parts.push('<span>' + escapeHtml(controlNav.service) + '</span>');
        el("controlDetailBreadcrumb").innerHTML = parts.join(' <i class="bi bi-chevron-right small text-muted"></i> ');
        var root = el("controlDetailBreadcrumb").querySelector('[data-nav="root"]');
        if (root) root.addEventListener("click", function (e) { e.preventDefault(); controlNav.team = null; controlNav.service = null; renderControlNav(); });
        var teamLink = el("controlDetailBreadcrumb").querySelector('[data-nav="team"]');
        if (teamLink) teamLink.addEventListener("click", function (e) { e.preventDefault(); controlNav.service = null; renderControlNav(); });
    }

    function renderControlNav() {
        el("controlDetailTitle").innerHTML = CONTROL_TITLES[controlNav.kind] || "";
        renderControlBreadcrumb();
        var tree = teamServiceTree();

        if (!controlNav.team) {
            var teams = Object.keys(tree).sort();
            el("controlDetailContent").innerHTML = teams.length ? '<div class="team-tile-grid">' + teams.map(function (t) {
                var count = Object.values(tree[t]).reduce(function (n, arr) { return n + arr.length; }, 0);
                return '<div class="team-tile" data-team="' + escapeHtml(t) + '" role="button" tabindex="0">' +
                    '<i class="bi bi-people-fill"></i><div><b>' + escapeHtml(t) + '</b><span>' + count + ' collaborateur' + (count > 1 ? "s" : "") + '</span></div></div>';
            }).join("") + '</div>' : '<p class="text-muted text-center">Aucune équipe.</p>';
            Array.prototype.forEach.call(el("controlDetailContent").querySelectorAll(".team-tile"), function (tile) {
                tile.addEventListener("click", function () { controlNav.team = tile.getAttribute("data-team"); renderControlNav(); });
            });
            return;
        }

        if (!controlNav.service) {
            var services = tree[controlNav.team] || {};
            var names = Object.keys(services).sort();
            el("controlDetailContent").innerHTML = names.length ? '<div class="team-tile-grid">' + names.map(function (s) {
                return '<div class="team-tile" data-service="' + escapeHtml(s) + '" role="button" tabindex="0">' +
                    '<i class="bi bi-briefcase-fill"></i><div><b>' + escapeHtml(s) + '</b><span>' + services[s].length + ' agent' + (services[s].length > 1 ? "s" : "") + '</span></div></div>';
            }).join("") + '</div>' : '<p class="text-muted text-center">Aucun service.</p>';
            Array.prototype.forEach.call(el("controlDetailContent").querySelectorAll("[data-service]"), function (tile) {
                tile.addEventListener("click", function () { controlNav.service = tile.getAttribute("data-service"); renderControlNav(); });
            });
            return;
        }

        var agents = ((tree[controlNav.team] || {})[controlNav.service] || []).slice()
            .sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || ""); });
        el("controlDetailContent").innerHTML = agents.length ? '<div class="table-responsive"><table class="table table-hover align-middle hr-table">' +
            '<thead><tr><th></th><th>Collaborateur</th><th>Statut</th><th>Aperçu</th></tr></thead><tbody>' +
            agents.map(function (u) {
                var preview = controlAgentPreview(controlNav.kind, u);
                var photoCell = u.photoUrl
                    ? '<img src="' + escapeHtml(u.photoUrl) + '" style="width:32px;height:32px;border-radius:50%;object-fit:cover;">'
                    : '<i class="bi bi-person-circle text-muted" style="font-size:32px;"></i>';
                return '<tr class="agent-row" data-username="' + escapeHtml(u.username) + '" style="cursor:pointer">' +
                    '<td>' + photoCell + '</td>' +
                    '<td>' + escapeHtml(val(u.fullName, u.username)) + '</td>' +
                    '<td><span class="status-pill ' + (normalizeStatus(u) === "ACTIF" ? "status-approved" : "status-rejected") + '">' + normalizeStatus(u) + '</span></td>' +
                    '<td>' + preview + '</td></tr>';
            }).join("") + '</tbody></table></div>'
            : '<p class="text-muted text-center">Aucun agent.</p>';
        Array.prototype.forEach.call(el("controlDetailContent").querySelectorAll(".agent-row"), function (row) {
            row.addEventListener("click", function () { openAgentDetail(controlNav.kind, row.getAttribute("data-username")); });
        });
    }

    /** Court aperçu affiché dans la liste d'agents (étape 3), avant d'ouvrir la fiche détaillée. */
    function controlAgentPreview(kind, u) {
        if (kind === "contracts") return escapeHtml(val(u.contractType)) + " · " + escapeHtml(val(u.contractStatus));
        if (kind === "leave") {
            var count = leaves.filter(function (r) { return r.requestedByUsername === u.username; }).length;
            return count ? count + " demande(s)" : '<span class="text-muted">Aucune</span>';
        }
        var r = reporting.filter(function (x) { return x.username === u.username; })[0];
        return r && r.performanceGlobale != null ? Number(r.performanceGlobale).toFixed(1) + "%" : '<span class="text-muted">—</span>';
    }

    function openAgentDetail(kind, username) {
        var u = employees.filter(function (x) { return x.username === username; })[0];
        if (!u) return;

        if (u.photoUrl) {
            el("agentDetailPhoto").src = u.photoUrl;
            el("agentDetailPhoto").style.display = "";
            el("agentDetailPhotoPlaceholder").style.display = "none";
        } else {
            el("agentDetailPhoto").style.display = "none";
            el("agentDetailPhotoPlaceholder").style.display = "";
        }
        el("agentDetailName").textContent = val(u.fullName, u.username);
        el("agentDetailMeta").textContent = [u.username, teamKey(u), u.service].filter(Boolean).join(" · ");

        var body = el("agentDetailBody");
        if (kind === "contracts") {
            body.innerHTML = controlDetailRow("Filiale", u.affiliateBranch) + controlDetailRow("Équipe", teamKey(u)) +
                controlDetailRow("Genre", u.gender) + controlDetailRow("Type de contrat", u.contractType) +
                controlDetailRow("Statut contrat", u.contractStatus) + controlDetailRow("Début contrat", formatDate(u.contractStartDate)) +
                controlDetailRow("Fin contrat", formatDate(u.contractEndDate)) + controlDetailRow("Lieu d'habitation", u.residencePlace);
        } else if (kind === "leave") {
            var rows = leaves.filter(function (r) { return r.requestedByUsername === username; });
            body.innerHTML = rows.length ? '<div class="table-responsive"><table class="table table-sm"><thead><tr><th>Type</th><th>Période</th><th>Statut</th></tr></thead><tbody>' +
                rows.map(function (r) {
                    var cls = String(r.status || "").toUpperCase() === "APPROVED" ? "status-approved" : String(r.status || "").toUpperCase() === "REJECTED" ? "status-rejected" : "status-pending";
                    return '<tr><td>' + escapeHtml(val(r.title || r.type)) + '</td><td>' + escapeHtml(formatDate(r.periodFrom)) + ' → ' + escapeHtml(formatDate(r.periodTo)) + '</td><td><span class="status-pill ' + cls + '">' + escapeHtml(val(r.status)) + '</span></td></tr>';
                }).join("") + '</tbody></table></div>' : '<p class="text-muted">Aucune demande de congé pour cet agent.</p>';
        } else {
            var r = reporting.filter(function (x) { return x.username === username; })[0];
            body.innerHTML = r ? controlDetailRow("Performance globale", r.performanceGlobale != null ? Number(r.performanceGlobale).toFixed(1) + "%" : "—") +
                controlDetailRow("Qualité moyenne", r.avgQualityScore != null ? Number(r.avgQualityScore).toFixed(1) + "%" : "—") +
                controlDetailRow("Présence", r.presenceRate != null ? Number(r.presenceRate).toFixed(1) + "%" : "—") +
                controlDetailRow("Évaluations QA", r.evaluationCount)
                : '<p class="text-muted">Aucune donnée de performance pour cet agent ce mois-ci.</p>';
        }

        agentDetailModal.show();
    }

    function controlDetailRow(label, value) {
        return '<div class="d-flex justify-content-between border-bottom py-1 small"><span class="text-muted">' + escapeHtml(label) + '</span><span>' + escapeHtml(val(value)) + '</span></div>';
    }

    function renderHrFollowupKpis() {
        var inactive = employees.filter(function (u) { return normalizeStatus(u) === "INACTIF"; }).length;
        el("kpiTurnover").textContent = employees.length ? ((inactive / employees.length) * 100).toFixed(1) + "%" : "—";

        var activeEmployees = employees.filter(function (u) { return normalizeStatus(u) === "ACTIF"; });
        var onLeaveToday = leaves.filter(function (x) { return String(x.status || "").toUpperCase() === "APPROVED" && x.periodFrom && x.periodTo; }).length;
        el("kpiAbsenteeism").textContent = activeEmployees.length ? ((onLeaveToday / activeEmployees.length) * 100).toFixed(1) + "%" : "—";

        var activeTraining = formations.filter(function (f) { return !f.status || !["ARCHIVED", "CLOSED", "TERMINEE"].includes(String(f.status).toUpperCase()); });
        var progress = activeTraining.filter(function (f) { return typeof f.myProgressPercent === "number"; });
        var avgProgress = progress.length ? progress.reduce(function (a, f) { return a + f.myProgressPercent; }, 0) / progress.length : null;
        el("kpiFormationRate").textContent = avgProgress === null ? "—" : Math.round(avgProgress) + "%";
    }

    function renderEmployees() {
        var q = (el("employeeSearch").value || "").trim().toLowerCase();
        var status = el("employeeStatus").value;
        var filtered = employees.filter(function (u) {
            var hay = [u.fullName, u.username, u.activity, u.service, u.affiliateBranch, u.contractType, u.contractStatus].filter(Boolean).join(" ").toLowerCase();
            return (!q || hay.includes(q)) && (!status || normalizeStatus(u) === status);
        });
        el("employeesBody").innerHTML = filtered.slice(0, 100).map(function (u) {
            var end = u.contractEndDate;
            var endClass = end && new Date(end + "T00:00:00") < new Date() ? "text-danger fw-bold" : "";
            return '<tr><td><div class="employee-name">' + escapeHtml(val(u.fullName, u.username)) + '</div><div class="employee-meta">' + escapeHtml(val(u.username)) + ' · ' + escapeHtml(val(u.affiliateBranch)) + '</div></td>' +
                '<td>' + escapeHtml(val(u.activity || u.service)) + '</td>' +
                '<td>' + escapeHtml(val(u.contractType)) + '</td>' +
                '<td><span class="status-pill ' + (normalizeStatus(u) === "ACTIF" ? "status-approved" : "status-rejected") + '">' + normalizeStatus(u) + '</span><div class="employee-meta">' + escapeHtml(val(u.contractStatus)) + '</div></td>' +
                '<td class="' + endClass + '">' + escapeHtml(formatDate(end)) + '</td></tr>';
        }).join("") || '<tr><td colspan="5" class="text-center text-muted">Aucun collaborateur trouvé.</td></tr>';
    }


    function renderTraining() {
        el("trainingList").innerHTML = formations.slice(0, 6).map(function (f) {
            var p = typeof f.myProgressPercent === "number" ? f.myProgressPercent : null;
            return '<div class="training-item"><div class="training-title">' + escapeHtml(val(f.title)) + '</div><div class="training-meta">' + escapeHtml(val(f.category, "Formation")) + ' · ' + escapeHtml(formatDate(f.scheduledDate)) + (f.mandatory ? ' · Obligatoire' : '') + '</div>' +
                (p !== null ? '<div class="progress"><div class="progress-bar" role="progressbar" style="width:' + Math.max(0, Math.min(100, p)) + '%"></div></div><div class="training-meta mt-1">' + p + '% de progression</div>' : '') + '</div>';
        }).join("") || '<div class="text-muted small">Aucune formation disponible.</div>';
    }

    function renderFollowup() {
        var scoped = filterByHrScope(tasks);
        el("followupList").innerHTML = scoped.slice(0, 6).map(function (t) {
            var icon = t.category === "QA_COACHING" ? "bi-patch-check" : "bi-clock-history";
            return '<div class="activity-item"><div class="activity-icon"><i class="bi ' + icon + '"></i></div><div><div class="training-title">' + escapeHtml(val(t.assignedToName || t.assignedToUsername)) + '</div><div class="training-meta">' + escapeHtml(val(t.description, t.category)) + '</div><div class="activity-meta">' + escapeHtml(val(t.relatedDate)) + ' · ' + escapeHtml(val(t.status)) + '</div></div></div>';
        }).join("") || '<div class="text-muted small">Aucune action de suivi récente.</div>';
    }

    function renderPerformance() {
        var rows = Array.isArray(reporting) ? reporting : [];
        var grouped = {};
        rows.forEach(function (r) {
            var key = r.activity || r.serviceName || "Sans équipe";
            if (!grouped[key]) grouped[key] = { quality: [], performance: [], presence: [] };
            if (r.avgQualityScore != null) grouped[key].quality.push(Number(r.avgQualityScore));
            if (r.performanceGlobale != null) grouped[key].performance.push(Number(r.performanceGlobale));
            if (r.presenceRate != null) grouped[key].presence.push(Number(r.presenceRate));
        });
        var labels = Object.keys(grouped).slice(0, 12);
        var avg = function (a) { return a.length ? a.reduce(function (x, y) { return x + y; }, 0) / a.length : 0; };
        var data = labels.map(function (k) { return Number(avg(grouped[k].performance).toFixed(1)); });
        var quality = labels.map(function (k) { return Number(avg(grouped[k].quality).toFixed(1)); });
        var presence = labels.map(function (k) { return Number(avg(grouped[k].presence).toFixed(1)); });
        var ctx = el("performanceChart");
        if (!ctx) return;
        if (performanceChart) performanceChart.destroy();
        performanceChart = new Chart(ctx, { type: "bar", data: { labels: labels, datasets: [
            { label: "Performance globale", data: data, borderWidth: 1 },
            { label: "Qualité moyenne", data: quality, borderWidth: 1 },
            { label: "Présence", data: presence, borderWidth: 1 }
        ] }, options: { responsive: true, maintainAspectRatio: false, scales: { y: { beginAtZero: true, max: 100 } }, plugins: { legend: { position: "bottom" } } } });
    }

    function exportEmployees() {
        var rows = [["Nom","Matricule","Email","Filiale","Équipe","Contrat","Statut contrat","Début contrat","Fin contrat","Statut compte"]];
        employees.forEach(function (u) { rows.push([u.fullName,u.username,u.email,u.affiliateBranch,u.activity,u.contractType,u.contractStatus,u.contractStartDate,u.contractEndDate,normalizeStatus(u)]); });
        var csv = rows.map(function (r) { return r.map(function (x) { return '"' + String(x == null ? "" : x).replace(/"/g, '""') + '"'; }).join(";"); }).join("\r\n");
        var blob = new Blob(["\ufeff" + csv], { type: "text/csv;charset=utf-8;" });
        var a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = "rcc-rh-collaborateurs.csv"; a.click(); URL.revokeObjectURL(a.href);
    }

    async function loadAll() {
        hideError();
        el("hrLoading").classList.remove("d-none");
        el("hrApp").classList.add("d-none");
        var month = el("hrMonth").value || monthNow();
        var results = await Promise.all([
            api("/api/users/hr/contracts"),
            api("/api/workflow/requests/hr/leave"),
            api("/api/leave-balance?year=" + new Date().getFullYear()),
            api("/api/training/formations"),
            api("/api/workflow/tasks/oversight"),
            api("/api/reporting/team?month=" + encodeURIComponent(month)),
            api("/api/sync/status")
        ]);
        employees = Array.isArray(results[0]) ? results[0] : [];
        leaves = Array.isArray(results[1]) ? results[1] : [];
        balances = Array.isArray(results[2]) ? results[2] : [];
        formations = Array.isArray(results[3]) ? results[3] : [];
        tasks = Array.isArray(results[4]) ? results[4] : [];
        reporting = Array.isArray(results[5]) ? filterByHrScope(results[5]) : [];

        if (!employees.length && results[0] && results[0].__error) showError("Impossible de charger le périmètre RH : " + results[0].__error);
        renderKpis(); renderTeams(); renderLeaves(); renderSync(results[6]); renderEmployees(); renderTeamGrid(); renderTraining(); renderFollowup(); renderHrFollowupKpis(); renderPerformance(); renderControlBadges();
        el("hrLoading").classList.add("d-none"); el("hrApp").classList.remove("d-none");
        el("lastRefresh").textContent = "Actualisé à " + new Date().toLocaleTimeString("fr-FR", {hour:"2-digit", minute:"2-digit"});
    }

    function init() {
        teamRosterModal = new bootstrap.Modal(el("teamRosterModal"));
        dossierModal = new bootstrap.Modal(el("dossierModal"));
        controlDetailModal = new bootstrap.Modal(el("controlDetailModal"));
        agentDetailModal = new bootstrap.Modal(el("agentDetailModal"));
        wireDossierUpload();

        el("hrMonth").value = monthNow();
        el("refreshHrBtn").addEventListener("click", loadAll);
        el("hrMonth").addEventListener("change", loadAll);
        el("employeeSearch").addEventListener("input", renderEmployees);
        el("employeeStatus").addEventListener("change", renderEmployees);
        el("exportEmployeesBtn").addEventListener("click", exportEmployees);
        el("openLegacyBtn").addEventListener("click", function () { document.getElementById("employeesBody").closest(".hr-card").scrollIntoView({behavior:"smooth", block:"start"}); });

        el("viewByTeamBtn").addEventListener("click", function () {
            el("viewByTeamBtn").classList.add("active"); el("viewListBtn").classList.remove("active");
            el("teamGridView").classList.remove("d-none"); el("employeeListView").classList.add("d-none");
        });
        el("viewListBtn").addEventListener("click", function () {
            el("viewListBtn").classList.add("active"); el("viewByTeamBtn").classList.remove("active");
            el("employeeListView").classList.remove("d-none"); el("teamGridView").classList.add("d-none");
        });

        Array.prototype.forEach.call(document.querySelectorAll(".control-card.clickable"), function (card) {
            card.addEventListener("click", function () { openControlDetail(card.getAttribute("data-kind")); });
            card.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); card.click(); } });
        });

        window.RccSession.init().then(function (session) {
            if (!session || (session.profile !== "RH" && session.profile !== "ADMIN")) {
                showError("Cette interface est réservée au portail RH et à l’administrateur.");
                return;
            }
            loadAll();
        }).catch(function (e) { showError(e.message || "Session indisponible."); });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
