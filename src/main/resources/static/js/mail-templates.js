"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };

    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var TEAMS = [
        { code: "EMAIL", label: "Équipe Email", icon: "bi-envelope-paper-fill", gradient: "linear-gradient(135deg, #0057B8, #003d82)" },
        { code: "RAFIKI", label: "Rafiki", icon: "bi-chat-dots-fill", gradient: "linear-gradient(135deg, #7b2ff7, #4b0fa8)" },
        { code: "SOCIAL", label: "Réseaux sociaux", icon: "bi-share-fill", gradient: "linear-gradient(135deg, #00A651, #007a3d)" }
    ];
    var TEAM_BY_CODE = {};
    TEAMS.forEach(function (t) { TEAM_BY_CODE[t.code] = t; });

    var currentProfile = null;
    var currentUsername = null;
    var isQaOrAdmin = false;
    var isLockedOutboundAgent = false; // agent Outbound classique — verrouillé sur sa seule équipe, jamais la grille complète (Rafiki/Réseaux sociaux inclus)
    var overviewCache = { categories: [], recipientGroups: [], templates: [] };
    var currentTeamCode = null;
    var useTemplateModal = null;
    var newCategoryModal = null;

    function applyQaVisibility(profile) {
        isQaOrAdmin = profile === "QA" || profile === "ADMIN";
        var elements = document.querySelectorAll(".qa-only");
        Array.prototype.forEach.call(elements, function (el) {
            el.style.display = isQaOrAdmin ? "" : "none";
        });
    }

    // ===================== CHARGEMENT =====================

    function loadMailTemplates() {
        return getJson("/api/mail-templates").then(function (overview) {
            overviewCache = overview || { categories: [], recipientGroups: [], templates: [] };
            renderTeamPicker();
            renderUnclassified();
            populateAdminSelectors();
            if (currentTeamCode) renderTeamDetail(currentTeamCode);
        }).catch(function (e) { console.error(e); });
    }

    function categoriesForTeam(teamCode) {
        return (overviewCache.categories || []).filter(function (c) { return c.team === teamCode; });
    }

    function templatesForCategory(categoryId) {
        return (overviewCache.templates || []).filter(function (t) { return t.categoryId === categoryId; });
    }

    // ===================== SÉLECTEUR D'ÉQUIPE =====================

    function renderTeamPicker() {
        var grid = $("mtTeamGrid");
        grid.innerHTML = TEAMS.map(function (team) {
            var cats = categoriesForTeam(team.code);
            var count = cats.reduce(function (sum, c) { return sum + templatesForCategory(c.id).length; }, 0);
            return '<div class="mt-team-card" style="background:' + team.gradient + ';" data-team="' + team.code + '">' +
                '<i class="bi ' + team.icon + ' mt-team-icon"></i>' +
                '<div class="mt-team-name">' + escapeHtml(team.label) + '</div>' +
                '<div class="mt-team-count">' + count + ' masque' + (count > 1 ? "s" : "") + ' · ' + cats.length + ' catégorie' + (cats.length > 1 ? "s" : "") + '</div>' +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(grid.querySelectorAll("[data-team]"), function (card) {
            card.addEventListener("click", function () { selectTeam(card.getAttribute("data-team")); });
        });
    }

    function selectTeam(teamCode) {
        currentTeamCode = teamCode;
        $("mtTeamPickerView").style.display = "none";
        $("mtTeamDetailView").style.display = "";
        $("mtTeamDetailTitle").textContent = (TEAM_BY_CODE[teamCode] || {}).label || teamCode;
        renderTeamDetail(teamCode);
    }

    $("mtBackBtn").addEventListener("click", function () {
        if (isLockedOutboundAgent) return; // pas d'accès à la grille complète (Rafiki, Réseaux sociaux...) — reste sur Outbound
        currentTeamCode = null;
        $("mtTeamDetailView").style.display = "none";
        $("mtTeamPickerView").style.display = "";
        renderTeamPicker();
    });

    // ===================== CATÉGORIES / MASQUES D'UNE ÉQUIPE =====================

    function renderTeamDetail(teamCode) {
        var container = $("mtCategoriesContainer");
        var cats = categoriesForTeam(teamCode).sort(function (a, b) { return a.sortOrder - b.sortOrder; });

        if (!cats.length) {
            container.innerHTML = '<div class="mt-empty-state"><i class="bi bi-inbox"></i>Aucun masque pour cette équipe pour l\'instant.</div>';
            return;
        }

        container.innerHTML = cats.map(function (c) {
            var templates = templatesForCategory(c.id);
            var icon = c.accentColor ? '' : "bi-folder2"; // accentColor non utilisé comme icône — fallback simple
            return '<div class="mt-category-card" data-category="' + c.id + '">' +
                '<div class="mt-category-header">' +
                    '<span class="mt-cat-title"><span class="mt-cat-icon" style="background:#eef1f5;color:#0057B8;"><i class="bi bi-folder2-open"></i></span>' +
                    escapeHtml(c.label) + ' <span class="badge bg-light text-dark ms-1">' + templates.length + '</span></span>' +
                    '<i class="bi bi-chevron-right mt-cat-chevron"></i>' +
                '</div>' +
                '<div class="mt-category-body">' +
                    (templates.length
                        ? templates.map(function (t) { return templateRowHtml(t); }).join("")
                        : '<p class="text-muted small mb-0 mt-2">Aucun masque dans cette catégorie.</p>') +
                '</div>' +
            '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".mt-category-header"), function (header) {
            header.addEventListener("click", function () { header.parentElement.classList.toggle("open"); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".use-template-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                openUseTemplate(Number(btn.getAttribute("data-id")));
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".delete-template-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                if (!confirm("Supprimer ce masque de mail ? Cette action est définitive.")) return;
                sendJson("/api/mail-templates/" + btn.getAttribute("data-id"), "DELETE")
                    .then(loadMailTemplates)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });

        // Une seule catégorie -> on l'ouvre directement, pas besoin de cliquer pour rien.
        if (cats.length === 1) container.querySelector(".mt-category-card").classList.add("open");
    }

    function templateRowHtml(t) {
        var isOwner = currentUsername && t.createdByUserId && currentUsername.toLowerCase() === t.createdByUserId.toLowerCase();
        var canDelete = !t.isSystemTemplate && (isOwner || isQaOrAdmin);
        var personalBadge = t.isSystemTemplate ? "" : '<span class="badge bg-light text-primary border ms-2">Personnel' + (isOwner ? "" : (t.createdByUserId ? " · " + escapeHtml(t.createdByUserId) : "")) + '</span>';
        return '<div class="mt-template-row">' +
            '<div><div class="mt-template-subject">' + escapeHtml(t.subject) + personalBadge + '</div>' +
            '<div class="mt-template-recipient">' + escapeHtml(t.recipientType === "service" ? "Service" : "Personne") + '</div></div>' +
            '<div class="d-flex gap-1">' +
            '<button class="btn btn-sm btn-outline-primary use-template-btn" data-id="' + t.id + '"><i class="bi bi-magic"></i> Utiliser</button>' +
            (canDelete ? '<button class="btn btn-sm btn-outline-danger delete-template-btn" data-id="' + t.id + '" title="Supprimer ce masque"><i class="bi bi-trash3"></i></button>' : '') +
            '</div>' +
        '</div>';
    }

    // ===================== CATÉGORIES NON CLASSÉES (QA/ADMIN) =====================

    function renderUnclassified() {
        var section = $("mtUnclassifiedSection");
        if (!isQaOrAdmin) { section.style.display = "none"; return; }

        var unclassified = (overviewCache.categories || []).filter(function (c) { return !c.team; });
        if (!unclassified.length) { section.style.display = "none"; return; }

        section.style.display = "";
        $("mtUnclassifiedList").innerHTML = unclassified.map(function (c) {
            return '<div class="d-flex align-items-center gap-2 mb-2">' +
                '<span class="flex-grow-1">' + escapeHtml(c.label) + '</span>' +
                '<select class="form-select form-select-sm" style="max-width:200px;" data-team-select="' + c.id + '">' +
                    TEAMS.map(function (t) { return '<option value="' + t.code + '">' + escapeHtml(t.label) + '</option>'; }).join("") +
                '</select>' +
                '<button class="btn btn-sm btn-primary" data-classify="' + c.id + '">Classer</button>' +
            '</div>';
        }).join("");

        Array.prototype.forEach.call($("mtUnclassifiedList").querySelectorAll("[data-classify]"), function (btn) {
            btn.addEventListener("click", function () {
                var id = btn.getAttribute("data-classify");
                var team = $("mtUnclassifiedList").querySelector('[data-team-select="' + id + '"]').value;
                sendJson("/api/mail-templates/categories/" + id + "/team", "PUT", { team: team })
                    .then(loadMailTemplates)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    // ===================== ADMIN : CRÉER CATÉGORIE / MASQUE =====================

    var recipientGroupsCache = [];

    function populateAdminSelectors() {
        var categorySelect = $("newTemplateCategory");
        categorySelect.innerHTML = (overviewCache.categories || []).map(function (c) {
            var teamLabel = (TEAM_BY_CODE[c.team] || {}).label || "Non classée";
            return '<option value="' + c.id + '">' + escapeHtml(c.label) + " — " + escapeHtml(teamLabel) + "</option>";
        }).join("");

        var groupSelect = $("newTemplateRecipientGroup");
        recipientGroupsCache = overviewCache.recipientGroups || [];
        groupSelect.innerHTML = recipientGroupsCache.map(function (g) {
            return '<option value="' + g.id + '">' + escapeHtml(g.label) + "</option>";
        }).join("");
    }

    $("mtNewCategoryBtn").addEventListener("click", function () {
        $("newCategoryLabel").value = "";
        $("newCategoryIcon").value = "";
        if (currentTeamCode) $("newCategoryTeam").value = currentTeamCode;
        newCategoryModal.show();
    });

    $("createCategoryBtn").addEventListener("click", function () {
        var label = $("newCategoryLabel").value.trim();
        if (!label) { alert("Le nom de la catégorie est obligatoire."); return; }
        sendJson("/api/mail-templates/categories", "POST", {
            label: label,
            team: $("newCategoryTeam").value,
            iconGlyph: $("newCategoryIcon").value.trim() || null
        }).then(function () {
            newCategoryModal.hide();
            loadMailTemplates();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    $("newTemplateRecipientType").addEventListener("change", function () {
        $("newTemplateRecipientGroup").style.display = this.value === "service" ? "" : "none";
    });

    $("createTemplateBtn").addEventListener("click", function () {
        var categoryId = Number($("newTemplateCategory").value);
        var subject = $("newTemplateSubject").value.trim();
        var body = $("newTemplateBody").value.trim();
        var recipientType = $("newTemplateRecipientType").value;
        var recipientGroupId = recipientType === "service" ? Number($("newTemplateRecipientGroup").value) : null;

        if (!categoryId || !subject || !body) { alert("Catégorie, sujet et corps sont obligatoires."); return; }

        sendJson("/api/mail-templates", "POST", {
            categoryId: categoryId, subject: subject, body: body,
            recipientType: recipientType, recipientGroupId: recipientGroupId
        }).then(function () {
            $("newTemplateSubject").value = "";
            $("newTemplateBody").value = "";
            loadMailTemplates();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===== Utilisation d'un masque — remplissage automatique =====

    /** Rend le nom de balise plus lisible : "CLE_ACTIVATION" -> "Cle activation". */
    function humanizePlaceholder(key) {
        var lower = key.toLowerCase().replace(/_/g, " ");
        return lower.charAt(0).toUpperCase() + lower.slice(1);
    }

    function openUseTemplate(templateId) {
        var template = (overviewCache.templates || []).find(function (t) { return t.id === templateId; });
        getJson("/api/mail-templates/" + templateId + "/placeholders").then(function (placeholders) {
            $("useTemplateModal").setAttribute("data-template-id", templateId);
            $("useTemplateModal").setAttribute("data-recipient-type", template ? template.recipientType : "");
            $("useTemplateModal").setAttribute("data-recipient-group-id", template && template.recipientGroupId ? template.recipientGroupId : "");

            $("filledRecipientTo").removeAttribute("data-user-edited");
            $("filledRecipientTo").value = "";
            $("filledRecipientCc").value = "";

            if (!placeholders.length) {
                $("clientFieldsCard").style.display = "none";
            } else {
                $("clientFieldsCard").style.display = "";
                $("clientFieldsRow").innerHTML = placeholders.map(function (key) {
                    return '<div class="col-md-4">' +
                        '<label class="form-label small">' + escapeHtml(humanizePlaceholder(key)) + '</label>' +
                        '<input type="text" class="form-control form-control-sm client-field-input" data-key="' + key + '">' +
                        '</div>';
                }).join("");

                Array.prototype.forEach.call($("clientFieldsRow").querySelectorAll(".client-field-input"), function (input) {
                    input.addEventListener("input", refreshFilledMail);
                });
            }

            refreshFilledMail();
            useTemplateModal.show();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    /** Marque le champ comme modifié manuellement — refreshFilledMail() ne l'écrasera plus. */
    $("filledRecipientTo").addEventListener("input", function () {
        $("filledRecipientTo").setAttribute("data-user-edited", "1");
    });

    /** Résout le destinataire par défaut : email du groupe (masque "service"),
     *  sinon un champ client ressemblant à un email (masque "personne"). */
    function resolveDefaultRecipient() {
        var recipientType = $("useTemplateModal").getAttribute("data-recipient-type");
        var recipientGroupId = Number($("useTemplateModal").getAttribute("data-recipient-group-id"));
        if (recipientType === "service" && recipientGroupId) {
            var group = recipientGroupsCache.find(function (g) { return g.id === recipientGroupId; });
            if (group && group.email) return group.email;
        }
        var found = "";
        Array.prototype.forEach.call($("clientFieldsRow").querySelectorAll(".client-field-input"), function (input) {
            var key = (input.getAttribute("data-key") || "").toLowerCase();
            if (!found && key.indexOf("mail") !== -1 && input.value.indexOf("@") !== -1) found = input.value.trim();
        });
        return found;
    }

    function refreshFilledMail() {
        var templateId = $("useTemplateModal").getAttribute("data-template-id");
        if (!templateId) return;

        var values = {};
        Array.prototype.forEach.call($("clientFieldsRow").querySelectorAll(".client-field-input"), function (input) {
            values[input.getAttribute("data-key")] = input.value;
        });

        sendJson("/api/mail-templates/" + templateId + "/fill", "POST", { values: values }).then(function (result) {
            $("filledSubject").value = result.subject;
            $("filledBody").value = result.body;
        }).catch(function (e) { console.error(e); });

        if (!$("filledRecipientTo").getAttribute("data-user-edited")) {
            $("filledRecipientTo").value = resolveDefaultRecipient();
        }
    }

    $("sendFilledMailBtn").addEventListener("click", function () {
        var subject = $("filledSubject").value;
        var body = $("filledBody").value;
        if (!subject && !body) return;

        var to = $("filledRecipientTo").value.trim();
        var cc = $("filledRecipientCc").value.trim();

        var mailto = "mailto:" + encodeURIComponent(to) + "?subject=" + encodeURIComponent(subject);
        if (cc) mailto += "&cc=" + encodeURIComponent(cc);
        mailto += "&body=" + encodeURIComponent(body);

        window.location.href = mailto;
    });

    $("copyFilledMailBtn").addEventListener("click", function () {
        var text = $("filledSubject").value + "\n\n" + $("filledBody").value;
        navigator.clipboard.writeText(text).then(function () {
            var btn = $("copyFilledMailBtn");
            var original = btn.innerHTML;
            btn.innerHTML = '<i class="bi bi-check-lg"></i> Copié';
            setTimeout(function () { btn.innerHTML = original; }, 1500);
        }).catch(function () { alert("Impossible de copier automatiquement — sélectionnez le texte manuellement."); });
    });

    // ===================== INIT =====================

    document.addEventListener("DOMContentLoaded", function () {
        useTemplateModal = new bootstrap.Modal($("useTemplateModal"));
        newCategoryModal = new bootstrap.Modal($("newCategoryModal"));
    });

    window.RccSession.init().then(function (session) {
        currentProfile = session ? session.profile : null;
        currentUsername = session && session.user ? session.user.username : null;
        if (session) applyQaVisibility(session.profile);
        if ($("mtCreateCardHeader")) {
            $("mtCreateCardHeader").textContent = isQaOrAdmin ? "Créer un masque de mail" : "Créer mon masque de mail";
        }
        if ($("mtCreateCardHint") && isQaOrAdmin) {
            $("mtCreateCardHint").textContent = "Choisissez la catégorie (type de mail) concernée, puis rédigez le modèle — visible par toute l'équipe qui utilise cette catégorie.";
        }
        loadMailTemplates().then(function () {
            if (session && session.isOutboundAgent && currentProfile === "AGENT") {
                isLockedOutboundAgent = true;
                $("mtBackBtn").style.display = "none";
                selectTeam("OUTBOUND");
            }
        });
    });
})();
