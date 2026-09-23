"use strict";

(function () {

    var currentProfile = null; // "ADMIN" | "QA" | "AGENT"
    var currentDetailUserId = null;

    function computeProfile(user) {
        if (user.role && user.role.toUpperCase() === "ADMIN") return "ADMIN";
        if (user.service && user.service.toLowerCase().replace(/_/g, " ") === "quality assurance") return "QA";
        return "AGENT";
    }

    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson; // (url, method, body) — signature déjà celle utilisée par tous les appels ci-dessous

    /** Même extraction de message que RccApi (voir rcc-api.js) — postJson/del sont des
     *  raccourcis locaux à users.js et n'en bénéficiaient pas jusqu'ici : une erreur 403/409
     *  s'affichait comme du JSON brut au lieu du message lisible du backend. */
    function toErrorMessage(text, status) {
        if (text) {
            try {
                var parsed = JSON.parse(text);
                if (parsed && parsed.error && parsed.error.message) return parsed.error.message;
            } catch (e) { /* pas du JSON, on garde le texte brut */ }
        }
        return text || ("HTTP " + status);
    }

    function postJson(url, body) {
        return fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body || {})
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(toErrorMessage(t, res.status))); });
            return res.status === 204 ? null : res.json();
        });
    }

    function del(url) {
        return fetch(url, { method: "DELETE", credentials: "same-origin" }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(toErrorMessage(t, res.status))); });
            return null;
        });
    }

    var escapeHtml = RccApi.escapeHtml;

    // ===== Annuaire =====

    var directoryUsers = [];

    var FILIALE_LABELS = {
        CI: "Côte d'Ivoire", SN: "Sénégal", CM: "Cameroun", TG: "Togo",
        BJ: "Bénin", ML: "Mali", BF: "Burkina Faso", NE: "Niger",
        GH: "Ghana", NG: "Nigeria", KE: "Kenya", GN: "Guinée"
    };

    function filialeLabel(code) {
        if (!code) return "Sans filiale";
        return (FILIALE_LABELS[code] || code) + " (" + code + ")";
    }

    function serviceLabel(service) {
        return service || "Sans service";
    }

    var RCC_SUB_TEAMS = ["CMB CIB", "INBOUND", "OUTBOUND", "RESOLUTION"];

    function regroupRccTeams(service, team) {
        if (RCC_SUB_TEAMS.indexOf((service || "").toUpperCase()) !== -1) {
            return { service: "Rcc", team: service };
        }
        return { service: service, team: team };
    }

    function groupByFilialeAndService(users) {
        var groups = {};
        users.forEach(function (u) {
            var filiale = u.affiliateBranch || ""; // "" = pas de filiale — jamais de faux libellé "Sans filiale"
            var regrouped = regroupRccTeams(u.service || "", u.activity || null); // "" = pas de service
            var service = regrouped.service;
            var team = regrouped.team;
            if (!groups[filiale]) groups[filiale] = { label: filialeLabel(filiale), services: {} };
            if (!groups[filiale].services[service]) groups[filiale].services[service] = { label: serviceLabel(service), teams: {}, direct: [] };
            var svc = groups[filiale].services[service];
            if (team) {
                if (!svc.teams[team]) svc.teams[team] = [];
                svc.teams[team].push(u);
            } else {
                // Pas d'équipe assignée — pas de faux niveau "Sans équipe", affiché directement.
                svc.direct.push(u);
            }
        });
        return groups;
    }

    function userRowHtml(u) {
        var statusBadge = u.active
            ? '<button class="btn btn-sm btn-outline-success toggle-status-btn" data-id="' + u.id + '" data-active="true">Actif</button>'
            : '<button class="btn btn-sm btn-outline-secondary toggle-status-btn" data-id="' + u.id + '" data-active="false">Inactif</button>';
        var contractInfo = [u.contractType, u.contractStatus].filter(Boolean).join(" · ");
        return '<div class="directory-user-row flex-wrap">' +
            '<img src="' + (u.photoUrl || "/images/avatar.png") + '" alt="" class="rounded-circle me-2" ' +
            'style="width:32px;height:32px;object-fit:cover;flex-shrink:0;">' +
            '<div class="flex-grow-1">' +
            '<strong>' + escapeHtml(u.fullName) + '</strong> ' +
            '<span class="text-muted small">(' + escapeHtml(u.username) + ')</span> ' +
            statusBadge +
            '<div class="text-muted small">' +
            (u.role ? escapeHtml(u.role) : "—") +
            (contractInfo ? " · " + escapeHtml(contractInfo) : "") +
            '</div>' +
            '</div>' +
            '<button class="btn btn-sm btn-outline-secondary toggle-roles-btn" data-id="' + u.id + '">' +
            '<i class="bi bi-person-badge"></i> Rôles</button> ' +
            '<button class="btn btn-sm btn-outline-secondary view-sessions-btn" data-id="' + u.id + '" data-name="' + escapeHtml(u.fullName) + '">' +
            '<i class="bi bi-router"></i> Connexions</button> ' +
            '<button class="btn btn-sm btn-outline-primary view-detail-btn" data-id="' + u.id + '">' +
            '<i class="bi bi-pencil"></i> Voir / Modifier</button>' +
            '<div class="w-100 inline-roles-panel" id="inline-roles-' + u.id + '" style="display:none;"></div>' +
            '<div class="w-100 inline-sessions-panel" id="inline-sessions-' + u.id + '" style="display:none;"></div>' +
            '</div>';
    }

    /** Panneau inline — liste les sessions actives (connexions ouvertes) de l'utilisateur,
     *  avec un bouton de déconnexion forcée immédiate ("gérer les connexions"). */
    function toggleInlineSessions(userId, fullName) {
        var panel = document.getElementById("inline-sessions-" + userId);
        var isShown = panel.style.display !== "none";
        if (isShown) { panel.style.display = "none"; return; }
        panel.style.display = "";
        loadSessionsInto(panel, userId, fullName);
    }

    function loadSessionsInto(panel, userId, fullName) {
        panel.innerHTML = '<div class="border rounded p-2 mt-2 small">Chargement…</div>';

        getJson("/api/users/" + userId + "/sessions").then(function (sessions) {
            if (!sessions.length) {
                panel.innerHTML = '<div class="border rounded p-2 mt-2 small text-muted">Aucune connexion active — ' +
                    escapeHtml(fullName) + ' n\'est actuellement connecté(e) sur aucun appareil.</div>';
                return;
            }
            var rows = sessions.map(function (s) {
                return '<tr><td>' + new Date(s.connectedAt).toLocaleString("fr-FR") + '</td>' +
                    '<td>' + new Date(s.expiresAt).toLocaleString("fr-FR") + '</td></tr>';
            }).join("");
            panel.innerHTML = '<div class="border rounded p-2 mt-2">' +
                '<table class="table table-sm mb-2"><thead><tr><th>Connecté depuis</th><th>Expire le</th></tr></thead>' +
                '<tbody>' + rows + '</tbody></table>' +
                '<button class="btn btn-sm btn-outline-danger revoke-sessions-btn" data-id="' + userId + '">' +
                '<i class="bi bi-x-circle"></i> Déconnecter partout (' + sessions.length + ' session(s))</button>' +
                '</div>';
            panel.querySelector(".revoke-sessions-btn").addEventListener("click", function () {
                if (!confirm("Déconnecter " + fullName + " de tous ses appareils immédiatement ?")) return;
                fetch("/api/users/" + userId + "/sessions", { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); loadSessionsInto(panel, userId, fullName); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        }).catch(function (e) {
            panel.innerHTML = '<div class="border rounded p-2 mt-2 small text-danger">Erreur : ' + escapeHtml(e.message) + '</div>';
        });
    }

    /** Panneau inline (une case à cocher par rôle existant) — attribution immédiate, sans modale. */
    function toggleInlineRoles(userId) {
        var panel = document.getElementById("inline-roles-" + userId);
        var isShown = panel.style.display !== "none";
        if (isShown) { panel.style.display = "none"; return; }
        panel.style.display = "";
        loadRolesInto(panel, userId);
    }

    /** Rendu réutilisable du panneau de rôles — appelé aussi bien à l'ouverture qu'après une
     *  coche, SANS jamais reconstruire toute la liste des utilisateurs (contrairement à
     *  loadUsers(), qui refermerait immédiatement ce panneau à chaque modification — c'était
     *  le bug empêchant l'admin de voir ses coches réellement prises en compte). */
    function loadRolesInto(panel, userId) {
        panel.innerHTML = '<div class="border rounded p-2 mt-2 small">Chargement…</div>';

        getJson("/api/users/" + userId).then(function (detail) {
            var currentRoleIds = (detail.roles || []).map(function (r) { return r.id; });
            panel.innerHTML = '<div class="border rounded p-2 mt-2 d-flex flex-wrap gap-3">' +
                allRoles.map(function (r) {
                    var checked = currentRoleIds.indexOf(r.id) !== -1 ? "checked" : "";
                    return '<div class="form-check">' +
                        '<input class="form-check-input inline-role-checkbox" type="checkbox" ' + checked +
                        ' data-user-id="' + userId + '" data-role-id="' + r.id + '" id="role-' + userId + '-' + r.id + '">' +
                        '<label class="form-check-label small" for="role-' + userId + '-' + r.id + '">' + escapeHtml(r.name) + '</label>' +
                        '</div>';
                }).join("") + '</div>';

            Array.prototype.forEach.call(panel.querySelectorAll(".inline-role-checkbox"), function (cb) {
                cb.addEventListener("change", function () {
                    var uid = cb.getAttribute("data-user-id");
                    var roleId = cb.getAttribute("data-role-id");
                    var request = cb.checked
                        ? sendJson("/api/admin/users/" + uid + "/roles", "POST", { roleId: Number(roleId) })
                        : del("/api/admin/users/" + uid + "/roles/" + roleId);
                    request.then(function () {
                        // Rafraîchit UNIQUEMENT ce panneau — jamais toute la liste, sinon le
                        // panneau se referme aussitôt et l'admin ne voit jamais sa coche prise
                        // en compte. La ligne (badge de rôle affiché) se met à jour au prochain
                        // chargement normal de la liste, sans que ce soit gênant ici.
                        loadRolesInto(panel, userId);
                    }).catch(function (e) {
                        alert("Erreur : " + e.message);
                        cb.checked = !cb.checked;
                    });
                });
            });
        }).catch(function (e) {
            panel.innerHTML = '<p class="text-danger small mt-2">Erreur : ' + e.message + '</p>';
        });
    }

    function renderDirectoryTree(filterTerm) {
        var container = document.getElementById("directoryTree");
        var term = (filterTerm || "").trim().toLowerCase();

        var filtered = !term ? directoryUsers : directoryUsers.filter(function (u) {
            return (u.fullName || "").toLowerCase().indexOf(term) !== -1 ||
                (u.username || "").toLowerCase().indexOf(term) !== -1;
        });

        if (!filtered.length) {
            container.innerHTML = '<p class="text-muted text-center">Aucun utilisateur.</p>';
            return;
        }

        var groups = groupByFilialeAndService(filtered);
        var filialeKeys = Object.keys(groups).sort(function (a, b) {
            return groups[a].label.localeCompare(groups[b].label);
        });
        var autoOpen = !!term; // en mode recherche, tout est déplié directement

        container.innerHTML = filialeKeys.map(function (fKey) {
            var filiale = groups[fKey];
            var serviceKeys = Object.keys(filiale.services).sort(function (a, b) {
                return filiale.services[a].label.localeCompare(filiale.services[b].label);
            });
            var totalCount = serviceKeys.reduce(function (sum, sk) {
                var svc = filiale.services[sk];
                return sum + Object.values(svc.teams).reduce(function (n, us) { return n + us.length; }, 0) + svc.direct.length;
            }, 0);

            var servicesHtml = serviceKeys.map(function (sKey) {
                var service = filiale.services[sKey];
                var serviceCount = Object.values(service.teams).reduce(function (n, us) { return n + us.length; }, 0) + service.direct.length;
                var teamKeys = Object.keys(service.teams).sort();

                var teamsHtml = teamKeys.map(function (team) {
                    var teamUsers = service.teams[team];
                    var usersHtml = teamUsers
                        .sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || ""); })
                        .map(userRowHtml).join("");
                    return '<div class="directory-team' + (autoOpen ? " open" : "") + '">' +
                        '<div class="directory-team-header">' +
                        '<span><i class="bi bi-chevron-right chevron"></i> <i class="bi bi-people"></i> ' + escapeHtml(team) + '</span>' +
                        '<span class="badge bg-light text-dark border">' + teamUsers.length + '</span>' +
                        '</div>' +
                        '<div class="directory-team-body">' + usersHtml + '</div>' +
                        '</div>';
                }).join("");

                // Utilisateurs sans équipe assignée — affichés directement, sans faux niveau "Sans équipe".
                var directHtml = service.direct.length
                    ? service.direct.sort(function (a, b) { return (a.fullName || "").localeCompare(b.fullName || ""); }).map(userRowHtml).join("")
                    : "";
                var content = teamsHtml + directHtml;

                // Pas de service — pas de faux niveau "Sans service", contenu affiché directement.
                if (!sKey) return content;

                return '<div class="directory-service' + (autoOpen ? " open" : "") + '">' +
                    '<div class="directory-service-header">' +
                    '<span><i class="bi bi-chevron-right chevron"></i> ' + escapeHtml(service.label) + '</span>' +
                    '<span class="badge bg-light text-dark border">' + serviceCount + '</span>' +
                    '</div>' +
                    '<div class="directory-service-body">' + content + '</div>' +
                    '</div>';
            }).join("");

            // Pas de filiale — pas de faux niveau "Sans filiale", contenu affiché directement.
            if (!fKey) return servicesHtml;

            return '<div class="directory-branch' + (autoOpen ? " open" : "") + '">' +
                '<div class="directory-branch-header">' +
                '<span><i class="bi bi-chevron-right chevron"></i> <i class="bi bi-building"></i> ' + escapeHtml(filiale.label) + '</span>' +
                '<span class="badge bg-primary">' + totalCount + ' utilisateur' + (totalCount > 1 ? "s" : "") + '</span>' +
                '</div>' +
                '<div class="directory-branch-body">' + servicesHtml + '</div>' +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".directory-branch-header"), function (header) {
            header.addEventListener("click", function () { header.closest(".directory-branch").classList.toggle("open"); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".directory-service-header"), function (header) {
            header.addEventListener("click", function (evt) {
                evt.stopPropagation();
                header.closest(".directory-service").classList.toggle("open");
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".directory-team-header"), function (header) {
            header.addEventListener("click", function (evt) {
                evt.stopPropagation();
                header.closest(".directory-team").classList.toggle("open");
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".view-detail-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                openUserDetail(btn.getAttribute("data-id"));
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".toggle-roles-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                toggleInlineRoles(btn.getAttribute("data-id"));
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".view-sessions-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                toggleInlineSessions(btn.getAttribute("data-id"), btn.getAttribute("data-name"));
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".toggle-status-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                var id = btn.getAttribute("data-id");
                var isActive = btn.getAttribute("data-active") === "true";
                var action = isActive ? "disable" : "enable";
                var verb = isActive ? "désactiver" : "activer";
                if (!confirm("Voulez-vous vraiment " + verb + " ce compte ?")) return;
                sendJson("/api/users/" + id + "/" + action, "POST")
                    .then(loadUsers)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function loadUsers() {
        getJson("/api/users").then(function (users) {
            directoryUsers = users;
            renderDirectoryTree(document.getElementById("directoryFilter").value);
        }).catch(function (e) {
            document.getElementById("directoryTree").innerHTML =
                '<p class="text-danger text-center">Erreur de chargement (' + escapeHtml(e.message) + ')</p>';
        });
    }

    function wireDirectoryFilter() {
        document.getElementById("directoryFilter").addEventListener("input", function () {
            renderDirectoryTree(this.value);
        });
    }

    // ===== Comptes en attente (admin uniquement) =====

    function renderPending(accounts) {
        var body = document.getElementById("pendingBody");
        if (!accounts.length) {
            body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Aucun compte en attente.</td></tr>';
            return;
        }
        body.innerHTML = accounts.map(function (a) {
            var requestedAt = a.requestedAt ? new Date(a.requestedAt).toLocaleString("fr-FR") : "—";
            return "" +
                "<tr>" +
                "<td>" + escapeHtml(a.fullName) + "</td>" +
                "<td>" + escapeHtml(a.username) + "</td>" +
                "<td>" + escapeHtml(a.email) + "</td>" +
                "<td>" + requestedAt + "</td>" +
                '<td class="text-end">' +
                '<button class="btn btn-sm btn-success me-1 approve-btn" data-username="' + escapeHtml(a.username) + '">Approuver</button>' +
                '<button class="btn btn-sm btn-outline-danger reject-btn" data-username="' + escapeHtml(a.username) + '">Rejeter</button>' +
                "</td>" +
                "</tr>";
        }).join("");

        Array.prototype.forEach.call(body.querySelectorAll(".approve-btn"), function (btn) {
            btn.addEventListener("click", function () {
                postJson("/api/users/" + encodeURIComponent(btn.getAttribute("data-username")) + "/approve")
                    .then(function () { loadPending(); loadUsers(); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
        Array.prototype.forEach.call(body.querySelectorAll(".reject-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Rejeter ce compte ?")) return;
                postJson("/api/users/" + encodeURIComponent(btn.getAttribute("data-username")) + "/reject")
                    .then(function () { loadPending(); loadUsers(); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function loadPending() {
        if (currentProfile !== "ADMIN") return;
        getJson("/api/users/pending").then(renderPending).catch(function (e) {
            document.getElementById("pendingBody").innerHTML =
                '<tr><td colspan="5" class="text-center text-danger">Erreur (' + escapeHtml(e.message) + ')</td></tr>';
        });
    }

    // ===== Fiche détaillée =====

    var allRoles = [];
    var allServices = [];

    function loadRoleAndServiceCatalogs() {
        if (currentProfile !== "ADMIN") return Promise.resolve();
        return Promise.all([
            getJson("/api/admin/roles").then(function (r) { allRoles = r; }),
            getJson("/api/admin/services").then(function (s) { allServices = s; })
        ]).catch(function (e) { console.error("Catalogue rôles/services indisponible :", e); });
    }

    function renderBadgeList(container, items, onRemove) {
        if (!items.length) {
            container.innerHTML = '<span class="text-muted">Aucun</span>';
            return;
        }
        container.innerHTML = items.map(function (item) {
            var label = escapeHtml(item.name || item.label || item.description || ("#" + item.id));
            var removeBtn = currentProfile === "ADMIN"
                ? ' <button type="button" class="btn-close btn-close-white btn-sm ms-1" data-id="' + item.id + '" aria-label="Retirer"></button>'
                : "";
            return '<span class="badge bg-primary d-inline-flex align-items-center gap-1">' + label + removeBtn + "</span>";
        }).join(" ");

        if (currentProfile === "ADMIN") {
            Array.prototype.forEach.call(container.querySelectorAll("button[data-id]"), function (btn) {
                btn.addEventListener("click", function () { onRemove(btn.getAttribute("data-id")); });
            });
        }
    }

    function fillSelect(select, items, excludeIds) {
        var available = items.filter(function (i) { return excludeIds.indexOf(i.id) === -1; });
        select.innerHTML = available.map(function (i) {
            return '<option value="' + i.id + '">' + escapeHtml(i.name || i.label) + "</option>";
        }).join("");
        return available.length > 0;
    }

    /**
     * Aide au rangement des types d'utilisateurs : quand tel Rôle est attribué, les Services
     * proposés ensuite se limitent à la famille pertinente (ex. Rôle "Team Leader" → seuls les 3
     * services Team Leader par équipe apparaissent), et pour RH/Superviseur/Superviseur QA le
     * service correspondant est attribué automatiquement — pas besoin d'un second geste.
     * Comparaison insensible à la casse sur le NOM du rôle réel (voir WorkflowSchemaBootstrap).
     */
    var ROLE_SERVICE_FILTER = [
        { roleMatch: /team leader/i, serviceCodes: ["TEAM_LEADER_INBOUND_VOICE", "TEAM_LEADER_INBOUND_MAIL", "TEAM_LEADER_OUTBOUND"] },
        { roleMatch: /agent/i, serviceCodes: ["AGENT_INBOUND", "AGENT_OUTBOUND", "AGENT_INBOUND_MAIL", "AGENT_CIB"] },
        { roleMatch: /^quality assurance$/i, serviceCodes: ["QUALITY_ASSURANCE", "FORMATEUR", "COMMUNICATION"] }
    ];
    var ROLE_AUTO_SERVICE = [
        { roleMatch: /^rh$/i, serviceCode: "RH" },
        { roleMatch: /head rcc \(superviseur\)/i, serviceCode: "SUPERVISEUR" },
        { roleMatch: /superviseur qualit/i, serviceCode: "SUPERVISEUR_QA" }
    ];

    function filterServicesForRoles(services, roleNames) {
        var allowedCodes = null; // null = pas de filtre, tout est proposé
        roleNames.forEach(function (name) {
            var rule = ROLE_SERVICE_FILTER.filter(function (r) { return r.roleMatch.test(name); })[0];
            if (rule) {
                allowedCodes = (allowedCodes || []).concat(rule.serviceCodes);
            }
        });
        if (!allowedCodes) return services;
        return services.filter(function (s) { return allowedCodes.indexOf(s.code) !== -1; });
    }

    function autoAssignServiceForRole(userId, roleName) {
        var rule = ROLE_AUTO_SERVICE.filter(function (r) { return r.roleMatch.test(roleName); })[0];
        if (!rule) return Promise.resolve();
        var target = allServices.filter(function (s) { return s.code === rule.serviceCode; })[0];
        if (!target) return Promise.resolve();
        return postJson("/api/admin/users/" + userId + "/services", { serviceId: Number(target.id) }).catch(function () {});
    }

    function openUserDetail(userId) {
        currentDetailUserId = userId;
        getJson("/api/users/" + userId).then(function (detail) {
            document.getElementById("userDetailName").textContent = detail.name || detail.username;
            document.getElementById("userDetailUsername").textContent = detail.username;
            document.getElementById("userDetailActive").textContent = detail.active ? "Actif" : "Inactif";
            document.getElementById("userDetailAvatar").src = detail.photoUrl || "/images/avatar.png";

            // Réinitialisation de mot de passe — uniquement pertinent pour un compte Excelliam
            // (les autres rôles s'authentifient via l'AD Ecobank, pas de mot de passe local ici).
            var isExcelliam = (detail.roles || []).some(function (r) { return (r.name || "").toUpperCase() === "EXCELLIAM"; });
            document.getElementById("excelliamResetPasswordBtn").style.display = isExcelliam ? "" : "none";

            document.getElementById("editUsername").value = detail.username || "";
            document.getElementById("editName").value = detail.name || "";
            document.getElementById("editEmail").value = detail.email || "";
            document.getElementById("editGender").value = detail.gender || "";
            document.getElementById("editAffiliate").value = detail.affiliateBranch || "";
            document.getElementById("editActivity").value = detail.activity || "";
            document.getElementById("editLedTeam").value = detail.ledTeam || "";
            document.getElementById("editContractType").value = detail.contractType || "";
            document.getElementById("editContractStatus").value = detail.contractStatus || "";
            document.getElementById("editContractStartDate").value = detail.contractStartDate || "";

            var rolesContainer = document.getElementById("userDetailRoles");
            renderBadgeList(rolesContainer, detail.roles || [], function (roleId) {
                del("/api/admin/users/" + userId + "/roles/" + roleId).then(function () { openUserDetail(userId); });
            });

            var servicesContainer = document.getElementById("userDetailServices");
            renderBadgeList(servicesContainer, detail.services || [], function (serviceId) {
                del("/api/admin/users/" + userId + "/services/" + serviceId).then(function () { openUserDetail(userId); });
            });

            var addRoleRow = document.getElementById("userDetailAddRole");
            var addServiceRow = document.getElementById("userDetailAddService");
            var permissionsContainer = document.getElementById("userDetailPermissions");
            if (currentProfile === "ADMIN") {
                var currentRoleIds = (detail.roles || []).map(function (r) { return r.id; });
                var currentServiceIds = (detail.services || []).map(function (s) { return s.id; });
                var currentRoleNames = (detail.roles || []).map(function (r) { return r.name; });
                addRoleRow.style.display = fillSelect(document.getElementById("roleSelect"), allRoles, currentRoleIds) ? "flex" : "none";
                addServiceRow.style.display = fillSelect(document.getElementById("serviceSelect"), filterServicesForRoles(allServices, currentRoleNames), currentServiceIds) ? "flex" : "none";
                renderPermissions(userId, permissionsContainer, detail);
            } else {
                addRoleRow.style.display = "none";
                addServiceRow.style.display = "none";
                permissionsContainer.innerHTML = '<p class="text-muted small">Réservé à l\'administrateur.</p>';
            }

            new bootstrap.Modal(document.getElementById("userDetailModal")).show();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    var FEATURES = [
        { code: "users", label: "Utilisateurs" },
        { code: "performance", label: "Ma Performance" },
        { code: "mon-rcc", label: "MON RCC" },
        { code: "procedures", label: "Procédures" },
        { code: "mail-templates", label: "Masques de mail" },
        { code: "knowledge", label: "Knowledge Base" },
        { code: "training", label: "Formation" },
        { code: "workflow", label: "Workflow" },
        { code: "qa", label: "Quality Assurance" },
        { code: "reports", label: "Reporting" },
        { code: "audit", label: "Audit" },
        { code: "administration", label: "Administration" }
    ];

    // Doit rester identique aux attributs data-roles de fragments/sidebar.html : c'est la
    // même règle "rôle -> accès par défaut" que RccSession applique côté sidebar (voir
    // session.js computeProfile/applySidebarVisibility). Si un lien de la sidebar change de
    // rôles autorisés, cette table doit être mise à jour en miroir, sinon l'écran de
    // permissions afficherait un "par défaut" qui ne correspond plus à la réalité.
    var FEATURE_ROLES = {
        "users": ["ADMIN"],
        "performance": ["AGENT", "QA", "ADMIN"],
        "mon-rcc": ["AGENT", "QA", "ADMIN", "RH", "SUPERVISOR", "TEAM_LEADER"],
        "procedures": ["AGENT", "QA", "ADMIN"],
        "mail-templates": ["AGENT", "QA", "ADMIN"],
        "knowledge": ["AGENT", "QA", "ADMIN"],
        "training": ["AGENT", "QA", "ADMIN", "FORMATEUR"],
        "workflow": ["AGENT", "QA", "ADMIN"],
        "qa": ["QA", "ADMIN"],
        "reports": ["QA", "ADMIN", "RH"],
        "audit": ["ADMIN"],
        "administration": ["ADMIN"]
    };

    /** Même principe que computeProfile() dans session.js, mais à partir de la fiche
     *  utilisateur admin (detail.roles[] / detail.services[]), pas du JWT — nécessaire ici
     *  car on calcule le profil d'UN AUTRE utilisateur que celui connecté. */
    function computeTargetProfile(detail) {
        var roleNames = (detail.roles || []).map(function (r) { return (r.name || "").toUpperCase(); });
        if (roleNames.indexOf("ADMIN") !== -1) return "ADMIN";
        if (roleNames.indexOf("RH") !== -1) return "RH";
        if (roleNames.indexOf("EXCELLIAM") !== -1) return "EXCELLIAM";
        if (roleNames.indexOf("SUPERVISOR") !== -1) return "SUPERVISOR";
        if (roleNames.indexOf("TEAM_LEADER") !== -1) return "TEAM_LEADER";
        var serviceNames = (detail.services || []).map(function (s) { return (s.name || "").toLowerCase().replace(/_/g, " "); });
        if (serviceNames.indexOf("superviseur qa") !== -1) return "QA_SUPERVISOR";
        if (serviceNames.indexOf("quality assurance") !== -1) return "QA";
        if (serviceNames.indexOf("formateur") !== -1) return "FORMATEUR";
        return "AGENT";
    }

    function renderPermissions(userId, container, detail) {
        container.innerHTML = '<p class="text-muted small mb-0">Chargement…</p>';
        var profile = computeTargetProfile(detail);
        var roleNames = (detail.roles || []).map(function (r) { return (r.name || "").toLowerCase(); });
        var serviceNames = (detail.services || []).map(function (s) { return (s.name || "").toLowerCase(); });

        Promise.all([
            getJson("/api/admin/users/" + userId + "/permissions"),
            getJson("/api/tab-permissions").catch(function () { return []; })
        ]).then(function (results) {
            var overrides = results[0], tabRules = results[1];
            var byCode = {};
            overrides.forEach(function (o) { byCode[o.featureCode] = o.isAllowed; });

            container.innerHTML = '<div class="row row-cols-2 row-cols-md-3 g-2">' + FEATURES.map(function (f) {
                var hasOverride = Object.prototype.hasOwnProperty.call(byCode, f.code);

                // Défaut réel = le rôle donne accès à cette fonctionnalité (FEATURE_ROLES,
                // en miroir de la sidebar) ET aucune règle Équipe/Rôle (TabPermission) ne
                // l'interdit explicitement pour ce rôle ou ce service — mêmes deux couches
                // qu'applique RccSession côté portail, pour que ce que montre cet écran
                // corresponde exactement à ce que l'utilisateur verrait réellement.
                var roleDefault = (FEATURE_ROLES[f.code] || []).indexOf(profile) !== -1;
                var deniedByRule = tabRules.some(function (r) {
                    if (r.tabCode !== f.code || r.isAllowed) return false;
                    return (r.teamCode && serviceNames.indexOf(r.teamCode.toLowerCase()) !== -1)
                        || (r.roleCode && roleNames.indexOf(r.roleCode.toLowerCase()) !== -1);
                });
                var computedDefault = roleDefault && !deniedByRule;

                var isChecked = hasOverride ? byCode[f.code] : computedDefault;
                var badge = hasOverride
                    ? '<a href="#" class="reset-permission-btn small ms-1" data-code="' + f.code + '" title="Revenir au comportement par défaut (rôle)"><i class="bi bi-arrow-counterclockwise"></i></a>'
                    : '<span class="text-muted small ms-1">(' + (computedDefault ? "défaut : autorisé" : "défaut : non autorisé") + ')</span>';
                return '<div class="col">' +
                    '<div class="form-check">' +
                    '<input class="form-check-input permission-checkbox" type="checkbox" data-code="' + f.code + '" ' +
                    'id="perm-' + f.code + '" ' + (isChecked ? "checked" : "") + '>' +
                    '<label class="form-check-label small" for="perm-' + f.code + '">' + f.label + '</label>' +
                    badge +
                    '</div></div>';
            }).join("") + '</div>';

            Array.prototype.forEach.call(container.querySelectorAll(".permission-checkbox"), function (checkbox) {
                checkbox.addEventListener("change", function () {
                    var code = checkbox.getAttribute("data-code");
                    sendJson("/api/admin/users/" + userId + "/permissions/" + code, "PUT", { isAllowed: checkbox.checked })
                        .then(function () { renderPermissions(userId, container, detail); })
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
            Array.prototype.forEach.call(container.querySelectorAll(".reset-permission-btn"), function (link) {
                link.addEventListener("click", function (evt) {
                    evt.preventDefault();
                    var code = link.getAttribute("data-code");
                    del("/api/admin/users/" + userId + "/permissions/" + code)
                        .then(function () { renderPermissions(userId, container, detail); })
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function (e) {
            container.innerHTML = '<p class="text-danger small">Erreur : ' + e.message + '</p>';
        });
    }

    var userSavedToast = null;

    function wireEditForm() {
        userSavedToast = new bootstrap.Toast(document.getElementById("userSavedToast"));
        document.getElementById("userEditForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            if (!currentDetailUserId) return;

            var payload = {
                username: document.getElementById("editUsername").value.trim() || null,
                name: document.getElementById("editName").value.trim() || null,
                email: document.getElementById("editEmail").value.trim() || null,
                affiliateBranch: document.getElementById("editAffiliate").value.trim(),
                gender: document.getElementById("editGender").value,
                activity: document.getElementById("editActivity").value.trim(),
                ledTeam: document.getElementById("editLedTeam").value.trim(),
                contractType: document.getElementById("editContractType").value,
                contractStatus: document.getElementById("editContractStatus").value,
                contractStartDate: document.getElementById("editContractStartDate").value || null
            };

            var submitBtn = evt.target.querySelector("button[type=submit]");
            var originalLabel = submitBtn ? submitBtn.textContent : null;
            if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = "Enregistrement…"; }

            sendJson("/api/users/" + currentDetailUserId, "PUT", payload)
                .then(function () {
                    loadUsers();
                    var modalEl = document.getElementById("userDetailModal");
                    var modal = bootstrap.Modal.getInstance(modalEl) || new bootstrap.Modal(modalEl);
                    modal.hide();
                    userSavedToast.show();
                })
                .catch(function (e) { alert("Erreur : " + e.message); })
                .finally(function () {
                    if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = originalLabel; }
                });
        });
    }

    function wirePhotoLightbox() {
        document.getElementById("userDetailAvatar").addEventListener("click", function () {
            document.getElementById("photoLightboxImg").src = this.src;
            new bootstrap.Modal(document.getElementById("photoLightbox")).show();
        });
    }

    function loadCreateUserServiceOptions() {
        fetch("/api/procedures/services", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : [];
        }).then(function (services) {
            var select = document.getElementById("newUserService");
            select.innerHTML = '<option value="">— Aucun —</option>' +
                services.map(function (s) { return '<option value="' + s.code + '">' + s.name + '</option>'; }).join("");
        }).catch(function () {});
    }

    function wireCreateUserForm() {
        loadCreateUserServiceOptions();
        document.getElementById("createUserBtn").addEventListener("click", function () {
            var section = document.getElementById("createUserSection");
            section.style.display = section.style.display === "none" ? "" : "none";
        });
        document.getElementById("cancelCreateUserBtn").addEventListener("click", function () {
            document.getElementById("createUserSection").style.display = "none";
            document.getElementById("createUserForm").reset();
        });
        document.getElementById("createUserForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            var payload = {
                username: document.getElementById("newUserUsername").value.trim(),
                name: document.getElementById("newUserName").value.trim() || null,
                email: document.getElementById("newUserEmail").value.trim() || null,
                affiliateBranch: document.getElementById("newUserAffiliate").value.trim() || null,
                roleName: document.getElementById("newUserRole").value.trim() || null,
                serviceCode: document.getElementById("newUserService").value.trim() || null
            };
            if (!payload.username) return;

            sendJson("/api/users", "POST", payload)
                .then(function () {
                    document.getElementById("createUserSection").style.display = "none";
                    document.getElementById("createUserForm").reset();
                    loadUsers();
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    function wireAddButtons() {
        document.getElementById("addRoleBtn").addEventListener("click", function () {
            var roleId = document.getElementById("roleSelect").value;
            if (!roleId || !currentDetailUserId) return;
            var roleName = (allRoles.filter(function (r) { return String(r.id) === String(roleId); })[0] || {}).name || "";
            var userId = currentDetailUserId;
            postJson("/api/admin/users/" + userId + "/roles", { roleId: Number(roleId) })
                .then(function () { return autoAssignServiceForRole(userId, roleName); })
                .then(function () { openUserDetail(userId); })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
        document.getElementById("addServiceBtn").addEventListener("click", function () {
            var serviceId = document.getElementById("serviceSelect").value;
            if (!serviceId || !currentDetailUserId) return;
            postJson("/api/admin/users/" + currentDetailUserId + "/services", { serviceId: Number(serviceId) })
                .then(function () { openUserDetail(currentDetailUserId); })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    function wireDeleteUserButton() {
        document.getElementById("deleteUserBtn").addEventListener("click", function () {
            if (!currentDetailUserId) return;
            var name = document.getElementById("userDetailName").textContent || "ce compte";
            if (!confirm("Supprimer définitivement " + name + " ?\n\nCette action est irréversible. À réserver aux doublons créés par erreur — un compte déjà utilisé (évaluations, KPI, tâches...) ne pourra pas être supprimé ; désactivez-le plutôt.")) return;
            fetch("/api/admin/users/" + currentDetailUserId, { method: "DELETE", credentials: "same-origin" })
                .then(function (res) {
                    if (res.ok || res.status === 204) return;
                    return res.text().then(function (t) { return Promise.reject(new Error(toErrorMessage(t, res.status))); });
                })
                .then(function () {
                    bootstrap.Modal.getInstance(document.getElementById("userDetailModal")).hide();
                    loadUsers();
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    /** Réservé aux comptes Excelliam (voir la logique de visibilité dans openUserDetail) —
     *  remet le mot de passe à null, la personne devra en recréer un à sa prochaine connexion. */
    function wireExcelliamResetPasswordButton() {
        document.getElementById("excelliamResetPasswordBtn").addEventListener("click", function () {
            if (!currentDetailUserId) return;
            var name = document.getElementById("userDetailName").textContent || "ce compte";
            if (!confirm("Réinitialiser le mot de passe de " + name + " ?\n\nCette personne devra en créer un nouveau à sa prochaine connexion.")) return;
            postJson("/api/users/" + currentDetailUserId + "/excelliam-password-reset")
                .then(function (result) { alert(result.message || "Mot de passe réinitialisé."); })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    function loadLoginAlerts() {
        var container = document.getElementById("loginAlertsList");
        var countBadge = document.getElementById("loginAlertsCount");
        getJson("/api/admin/login-alerts").then(function (alerts) {
            if (!alerts.length) {
                container.innerHTML = '<p class="text-muted text-center">Aucune réclamation en attente.</p>';
                countBadge.style.display = "none";
                return;
            }
            countBadge.textContent = alerts.length;
            countBadge.style.display = "";

            var SUBJECT_ICONS = {
                "Compte verrouillé": "bi-lock-fill text-danger",
                "Mot de passe oublié": "bi-key-fill text-warning",
                "Demande d'assistance connexion": "bi-headset text-info"
            };

            container.innerHTML = alerts.map(function (a) {
                var icon = SUBJECT_ICONS[a.subject] || "bi-flag";
                var actionBtn = (a.actionType === "UNLOCK_ACCOUNT" && a.actionTarget)
                    ? '<button class="btn btn-sm btn-success unlock-from-alert-btn me-2" data-username="' + escapeHtml(a.actionTarget) + '" data-ids="' + a.notificationIds.join(",") + '"><i class="bi bi-unlock-fill"></i> Réactiver</button>'
                    : "";
                return '<div class="d-flex justify-content-between align-items-start border-bottom py-2 flex-wrap gap-2">' +
                    '<div><i class="bi ' + icon + '"></i> <strong>' + escapeHtml(a.subject) + '</strong>' +
                    '<div class="small text-muted">' + escapeHtml(a.content) + '</div>' +
                    '<div class="small text-muted">' + new Date(a.createdAt).toLocaleString("fr-FR") + '</div></div>' +
                    '<div>' + actionBtn +
                    '<button class="btn btn-sm btn-outline-secondary resolve-alert-btn" data-ids="' + a.notificationIds.join(",") + '"><i class="bi bi-check-lg"></i> Marquer traité</button>' +
                    '</div></div>';
            }).join("");

            Array.prototype.forEach.call(container.querySelectorAll(".resolve-alert-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var ids = btn.getAttribute("data-ids").split(",").map(Number);
                    sendJson("/api/admin/login-alerts/resolve", "POST", ids).then(loadLoginAlerts)
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
            Array.prototype.forEach.call(container.querySelectorAll(".unlock-from-alert-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var username = btn.getAttribute("data-username");
                    var ids = btn.getAttribute("data-ids").split(",").map(Number);
                    if (!confirm("Réactiver le compte « " + username + " » ?")) return;
                    fetch("/api/users/" + encodeURIComponent(username) + "/unlock", { method: "POST", credentials: "same-origin" })
                        .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                        .then(function () { return sendJson("/api/admin/login-alerts/resolve", "POST", ids); })
                        .then(loadLoginAlerts)
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function (e) { console.error(e); });
    }

    function init() {
        fetch("/api/auth/me", { credentials: "same-origin" })
            .then(function (res) { return res.json(); })
            .then(function (user) {
                currentProfile = computeProfile(user);
                document.getElementById("pendingSection").style.display = currentProfile === "ADMIN" ? "" : "none";
                document.getElementById("loginAlertsSection").style.display = currentProfile === "ADMIN" ? "" : "none";
                document.getElementById("createUserBtn").style.display = currentProfile === "ADMIN" ? "" : "none";
                document.getElementById("deleteUserBtn").style.display = currentProfile === "ADMIN" ? "" : "none";
                wireAddButtons();
                wireDeleteUserButton();
                wireExcelliamResetPasswordButton();
                wireCreateUserForm();
                wireEditForm();
                wirePhotoLightbox();
                wireDirectoryFilter();
                wireCleanupRolesButton();
                wireReclassifyTeamsButton();
                wireUsernameRemapButton();
                wireFindDuplicatesButton();
                wireUsernameDuplicatesButton();
                loadUsers();
                loadPending();
                if (currentProfile === "ADMIN") loadLoginAlerts();
                return loadRoleAndServiceCatalogs();
            })
            .catch(function (e) { console.error("users.js init failed:", e); });
    }

    /** Nettoyage ponctuel des rôles — action irréversible, double confirmation obligatoire
     *  avant tout appel réseau. Réservé Admin (déjà vérifié côté serveur, mais on cache
     *  aussi le bouton côté client pour ne pas y exposer un RH/Superviseur sans raison). */
    function wireCleanupRolesButton() {
        var btn = document.getElementById("cleanupRolesBtn");
        if (!btn) return;
        if (currentProfile !== "ADMIN") { btn.closest(".card").style.display = "none"; return; }

        btn.addEventListener("click", function () {
            var resultBox = document.getElementById("cleanupRolesResult");
            if (!confirm("Ceci va supprimer DÉFINITIVEMENT tous les rôles de la base sauf les rôles métier RCC " +
                    "(Team Leader, Head, Formateur, Quality Assurance...) et les 5 rôles vitaux au système de " +
                    "connexion. Cette action est irréversible. Continuer ?")) return;
            if (!confirm("Dernière confirmation — êtes-vous VRAIMENT sûr ? Les attributions existantes des rôles " +
                    "supprimés seront perdues elles aussi.")) return;

            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Nettoyage en cours…";
            sendJson("/api/admin/roles/cleanup", "POST")
                .then(function (result) {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = result.deleted + " rôle(s) supprimé(s).";
                    return loadRoleAndServiceCatalogs();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    function wireReclassifyTeamsButton() {
        var btn = document.getElementById("reclassifyTeamsBtn");
        if (!btn) return;
        if (currentProfile !== "ADMIN") { btn.closest(".card").style.display = "none"; return; }

        btn.addEventListener("click", function () {
            var resultBox = document.getElementById("reclassifyTeamsResult");
            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Rattrapage en cours…";
            sendJson("/api/admin/users/reclassify-teams", "POST")
                .then(function (result) {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = result.updated + " compte(s) rattaché(s) à leur équipe.";
                    loadUsers();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    function wireUsernameRemapButton() {
        var btn = document.getElementById("usernameRemapBtn");
        if (!btn) return;
        if (currentProfile !== "ADMIN") { btn.closest(".card").style.display = "none"; return; }

        btn.addEventListener("click", function () {
            var fileInput = document.getElementById("usernameRemapFile");
            var resultBox = document.getElementById("usernameRemapResult");
            var file = fileInput.files[0];
            if (!file) { resultBox.textContent = "Choisissez un fichier."; resultBox.className = "small mt-2 text-danger"; return; }

            var formData = new FormData();
            formData.append("file", file);
            resultBox.textContent = "Import en cours…";
            resultBox.className = "small mt-2 text-muted";

            fetch("/api/users/remap-usernames/import", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (result) {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.innerHTML = result.usernamesChanged + " identifiant(s) renommé(s) sur " + result.rowsProcessed + " ligne(s)." +
                        (result.skippedBlankNewId ? " " + result.skippedBlankNewId + " ligne(s) sans nouvel ID (inchangées)." : "");

                    if (result.unresolvedNames && result.unresolvedNames.length) {
                        var unresolvedBox = document.createElement("div");
                        unresolvedBox.className = "small mt-2 alert alert-warning";
                        unresolvedBox.innerHTML = '<strong>' + result.unresolvedNames.length + ' nom(s) non reconnu(s)</strong> (aucun compte créé) :' +
                            '<ul class="mb-0 mt-1">' + result.unresolvedNames.map(function (n) { return '<li>' + escapeHtml(n) + '</li>'; }).join("") + '</ul>';
                        resultBox.after(unresolvedBox);
                    }
                    if (result.conflicts && result.conflicts.length) {
                        var conflictBox = document.createElement("div");
                        conflictBox.className = "small mt-2 alert alert-danger";
                        conflictBox.innerHTML = '<strong>' + result.conflicts.length + ' conflit(s) bloqué(s)</strong> — à arbitrer manuellement :' +
                            '<ul class="mb-0 mt-1">' + result.conflicts.map(function (c) { return '<li>' + escapeHtml(c) + '</li>'; }).join("") + '</ul>';
                        resultBox.after(conflictBox);
                    }
                    if (result.preview && result.preview.length) {
                        var previewBox = document.createElement("div");
                        previewBox.className = "small mt-2";
                        previewBox.innerHTML = '<table class="table table-sm table-hover mt-1"><thead><tr><th>Agent</th><th>Ancien ID</th><th>Nouvel ID</th></tr></thead><tbody>' +
                            result.preview.map(function (p) {
                                return '<tr><td>' + escapeHtml(p.name || "") + '</td><td>' + escapeHtml(p.oldUsername || "") + '</td><td>' + escapeHtml(p.newUsername || "") + '</td></tr>';
                            }).join("") + '</tbody></table>' +
                            (result.previewTruncated ? '<p class="text-muted mb-0">Liste tronquée.</p>' : "");
                        resultBox.after(previewBox);
                    }

                    fileInput.value = "";
                    loadUsers();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    /** Fusion par USERNAME — distincte de wireFindDuplicatesButton (qui regroupe par nom
     *  complet, sans fusion réelle). Ici : aperçu détaillé par groupe, puis fusion à la
     *  demande via /api/admin/user-deduplication, un groupe (un username) à la fois — jamais
     *  "tout fusionner d'un coup", pour garder chaque action confirmable individuellement. */
    function wireUsernameDuplicatesButton() {
        var btn = document.getElementById("findUsernameDuplicatesBtn");
        if (!btn) return;
        if (currentProfile !== "ADMIN") { btn.closest(".card").style.display = "none"; return; }

        btn.addEventListener("click", loadUsernameDuplicates);
    }

    function loadUsernameDuplicates() {
        var box = document.getElementById("usernameDuplicatesResult");
        box.innerHTML = '<p class="text-muted small mb-0">Recherche…</p>';
        getJson("/api/admin/user-deduplication/preview")
            .then(function (groups) {
                if (!groups.length) {
                    box.innerHTML = '<p class="text-success small mb-0"><i class="bi bi-check-circle"></i> Aucun doublon d\'identifiant détecté.</p>' +
                        '<button class="btn btn-sm btn-outline-primary mt-2" id="lockUsernameBtn"><i class="bi bi-lock"></i> Verrouiller les identifiants (empêcher tout futur doublon)</button>';
                    var lockBtn = document.getElementById("lockUsernameBtn");
                    if (lockBtn) lockBtn.addEventListener("click", wireLockUsername);
                    return;
                }
                box.innerHTML = groups.map(function (group) { return renderUsernameDuplicateGroup(group); }).join("");
                Array.prototype.forEach.call(box.querySelectorAll(".username-dup-merge-btn"), function (mergeBtn) {
                    mergeBtn.addEventListener("click", function () { confirmAndMergeUsername(mergeBtn.getAttribute("data-username")); });
                });
            })
            .catch(function (e) { box.innerHTML = '<p class="text-danger small mb-0">Erreur : ' + escapeHtml(e.message) + '</p>'; });
    }

    function renderUsernameDuplicateGroup(group) {
        var rows = group.entries.map(function (entry) {
            var keptBadge = entry.wouldBeKept
                ? ' <span class="badge bg-success">Conservée</span>'
                : ' <span class="badge bg-secondary">Fusionnée puis supprimée</span>';
            return '<li>#' + entry.userId + ' — <b>' + escapeHtml(entry.name || "(sans nom)") + '</b>' + keptBadge +
                ' <span class="text-muted">— ' + entry.filledFieldCount + ' champs remplis' +
                (entry.activity ? ", équipe " + escapeHtml(entry.activity) : "") +
                (entry.status ? ", statut " + escapeHtml(entry.status) : "") + '</span></li>';
        }).join("");
        return '<div class="border rounded p-2 mb-2">' +
            '<div class="d-flex justify-content-between align-items-center">' +
            '<b>' + escapeHtml(group.username) + '</b> — ' + group.entries.length + ' comptes' +
            '<button class="btn btn-sm btn-outline-danger username-dup-merge-btn" data-username="' + escapeHtml(group.username) + '">' +
            '<i class="bi bi-arrow-down-up"></i> Fusionner</button>' +
            '</div>' +
            '<ul class="mb-0 mt-1">' + rows + '</ul></div>';
    }

    function confirmAndMergeUsername(username) {
        if (!confirm('Fusionner tous les comptes "' + username + '" ? Cette action est irréversible : ' +
            'l\'historique des comptes en trop sera transféré vers le compte le plus complet, puis les comptes en trop seront supprimés.')) {
            return;
        }
        sendJson("/api/admin/user-deduplication/merge", "POST", { username: username })
            .then(function (result) {
                alert('Fusion effectuée pour "' + result.username + '" — compte #' + result.keptUserId + ' conservé, ' +
                    result.rowsReassigned + ' ligne(s) réassignée(s), ' + result.rowsDeduplicatedAway + ' doublon(s) internes retiré(s).');
                loadUsernameDuplicates();
                loadUsers();
            })
            .catch(function (e) { alert("Erreur lors de la fusion : " + e.message); });
    }

    function wireLockUsername() {
        if (!confirm("Empêcher définitivement les doublons d'identifiant (contrainte d'unicité en base) ?")) return;
        sendJson("/api/admin/user-deduplication/lock-username", "POST", {})
            .then(function () { alert("Identifiants verrouillés — un même identifiant ne pourra plus être dupliqué."); })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    function wireFindDuplicatesButton() {
        var btn = document.getElementById("findDuplicatesBtn");
        if (!btn) return;
        if (currentProfile !== "ADMIN") { btn.closest(".card").style.display = "none"; return; }

        btn.addEventListener("click", function () {
            var box = document.getElementById("duplicatesResult");
            box.innerHTML = '<p class="text-muted small mb-0">Recherche…</p>';
            getJson("/api/admin/users/duplicates")
                .then(function (groups) {
                    if (!groups.length) {
                        box.innerHTML = '<p class="text-success small mb-0"><i class="bi bi-check-circle"></i> Aucun doublon détecté.</p>';
                        return;
                    }
                    box.innerHTML = groups.map(function (group) {
                        return '<div class="border rounded p-2 mb-2"><b>' + escapeHtml(group[0].fullName) + '</b> — ' + group.length + ' comptes<ul class="mb-0 mt-1">' +
                            group.map(function (u) {
                                return '<li><a href="#" class="dup-open-user" data-id="' + u.id + '">' + escapeHtml(u.username) +
                                    '</a> — ' + escapeHtml(u.affiliateBranch || "—") + ' · ' + escapeHtml(u.activity || "Non classée") +
                                    (u.active ? "" : ' <span class="badge bg-secondary">Désactivé</span>') + '</li>';
                            }).join("") + '</ul></div>';
                    }).join("");
                    Array.prototype.forEach.call(box.querySelectorAll(".dup-open-user"), function (link) {
                        link.addEventListener("click", function (e) {
                            e.preventDefault();
                            openUserDetail(Number(link.getAttribute("data-id")));
                        });
                    });
                })
                .catch(function (e) { box.innerHTML = '<p class="text-danger small mb-0">Erreur : ' + e.message + '</p>'; });
        });
    }

    document.addEventListener("DOMContentLoaded", init);
})();