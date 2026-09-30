"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };


    var getJson = RccApi.getJson;

    function postJson(url, body) {
        return fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body || {})
        }).then(function (res) {
            if (res.status === 401) { window.location.href = "/login"; return Promise.reject(new Error("unauthenticated")); }
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || ("HTTP " + res.status))); });
            return res.status === 204 ? null : res.json();
        });
    }

    function del(url) {
        return fetch(url, { method: "DELETE", credentials: "same-origin" }).then(function (res) {
            if (res.status === 401) { window.location.href = "/login"; return Promise.reject(new Error("unauthenticated")); }
            if (!res.ok) return Promise.reject(new Error("HTTP " + res.status));
        });
    }

    var escapeHtml = RccApi.escapeHtml;

    // Rôles et services du portail : gérés dans l'onglet « Base de données » (tables ROLES et SERVICES, admin-db-editor.js).

    // ===== Équipes =====

    function loadTeams() {
        getJson("/api/teams").then(function (teams) {
            getJson("/api/teams/usage").then(function (usage) {
                $("teamsTable").innerHTML = teams.map(function (t) {
                    var count = usage[t.code] || 0;
                    var deleteBtn = count === 0
                        ? '<button class="btn btn-sm btn-outline-danger delete-team-btn" data-id="' + t.teamId + '" data-code="' + escapeHtml(t.code) + '"><i class="bi bi-trash"></i></button>'
                        : "";
                    return "<tr><td>" + escapeHtml(t.code) + "</td><td>" + escapeHtml(t.label) + "</td>" +
                        "<td>" + count + "</td><td>" + deleteBtn + "</td></tr>";
                }).join("") || '<tr><td colspan="4" class="text-muted text-center">Aucune équipe.</td></tr>';

                Array.prototype.forEach.call($("teamsTable").querySelectorAll(".delete-team-btn"), function (btn) {
                    btn.addEventListener("click", function () {
                        if (!confirm("Supprimer l'équipe « " + btn.getAttribute("data-code") + " » (0 agent) ?")) return;
                        fetch("/api/teams/" + btn.getAttribute("data-id"), { method: "DELETE", credentials: "same-origin" })
                            .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                            .then(loadTeams)
                            .catch(function (e) { alert("Erreur : " + e.message); });
                    });
                });
            }).catch(function (e) { console.error(e); });

            var select = $("tpTeamSelect");
            select.innerHTML = '<option value="">— Équipe —</option>' +
                teams.map(function (t) { return '<option value="' + t.code + '">' + escapeHtml(t.label) + "</option>"; }).join("");
        }).catch(function (e) { console.error(e); });
    }

    $("createTeamBtn").addEventListener("click", function () {
        var code = $("newTeamCode").value.trim();
        var label = $("newTeamLabel").value.trim();
        if (!code || !label) { alert("Code et libellé sont obligatoires."); return; }
        postJson("/api/teams", { code: code, label: label })
            .then(function () {
                $("newTeamCode").value = "";
                $("newTeamLabel").value = "";
                loadTeams();
            })
            .catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===== Permissions par équipe / rôle (onglets interdits) =====

    function loadTabPermissions() {
        getJson("/api/tab-permissions").then(function (rows) {
            $("tabPermissionsTable").innerHTML = rows.map(function (r) {
                return "<tr><td>" + (r.teamCode ? escapeHtml(r.teamCode) : "—") + "</td>" +
                    "<td>" + (r.roleCode ? escapeHtml(r.roleCode) : "—") + "</td>" +
                    "<td>" + escapeHtml(r.tabCode) + "</td>" +
                    '<td><button class="btn btn-sm btn-outline-danger remove-tp-btn" data-id="' + r.id + '"><i class="bi bi-trash"></i></button></td></tr>';
            }).join("") || '<tr><td colspan="4" class="text-muted text-center">Aucune restriction — tout est autorisé par défaut.</td></tr>';

            Array.prototype.forEach.call(document.querySelectorAll(".remove-tp-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    del("/api/tab-permissions/" + btn.dataset.id).then(loadTabPermissions).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function (e) { console.error(e); });
    }

    function loadRoleOptionsForTabPermissions() {
        getJson("/api/admin/roles").then(function (roles) {
            $("tpRoleSelect").innerHTML = '<option value="">— Rôle —</option>' +
                roles.map(function (r) { return '<option value="' + r.name + '">' + escapeHtml(r.name) + "</option>"; }).join("");
        }).catch(function (e) { console.error(e); });
    }

    $("addDenyBtn").addEventListener("click", function () {
        var teamCode = $("tpTeamSelect").value || null;
        var roleCode = $("tpRoleSelect").value || null;
        var tabCode = $("tpTabSelect").value;
        if (!teamCode && !roleCode) { alert("Choisissez une équipe ou un rôle."); return; }
        if (teamCode && roleCode) { alert("Choisissez soit une équipe, soit un rôle — pas les deux."); return; }
        postJson("/api/tab-permissions", { teamCode: teamCode, roleCode: roleCode, tabCode: tabCode, isAllowed: false })
            .then(loadTabPermissions)
            .catch(function (e) { alert("Erreur : " + e.message); });
    });

    loadTeams();
    loadTabPermissions();
    loadRoleOptionsForTabPermissions();

    // ===== Apparence du site (photo de connexion) =====

    function loadSiteAppearance() {
        getJson("/api/site-settings").then(function (settings) {
            var opacity = settings["login.hero.opacity"] || "0.14";
            document.getElementById("loginHeroOpacityRange").value = opacity;
            document.getElementById("loginHeroOpacityValue").textContent = opacity;
            var url = settings["login.hero.imageUrl"];
            var preview = document.getElementById("loginHeroPreview");
            if (url) { preview.src = url; preview.style.display = ""; }

            var bannerUrl = settings["monrcc.banner.imageUrl"];
            var bannerPreview = document.getElementById("monRccBannerPreview");
            if (bannerUrl && bannerPreview) { bannerPreview.src = bannerUrl; bannerPreview.style.display = ""; }

            var formationBannerUrl = settings["formation.banner.imageUrl"];
            var formationPreview = document.getElementById("formationBannerPreview");
            if (formationBannerUrl && formationPreview) { formationPreview.src = formationBannerUrl; formationPreview.style.display = ""; }

            document.getElementById("formationBannerTitleInput").value = settings["formation.banner.title"] || "";
            document.getElementById("formationBannerSubtitleInput").value = settings["formation.banner.subtitle"] || "";
            document.getElementById("formationBannerFontSelect").value = settings["formation.banner.fontFamily"] || "system";
            document.getElementById("formationBannerSizeSelect").value = settings["formation.banner.titleSize"] || "1.75rem";
            document.getElementById("formationBannerTitleColorInput").value = settings["formation.banner.titleColor"] || "#ffffff";
            document.getElementById("formationBannerSubtitleColorInput").value = settings["formation.banner.subtitleColor"] || "#ffffff";
        }).catch(function (e) { console.error(e); });
    }

    /** Enregistre en une fois les réglages de texte de la bannière Formation (SiteSetting,
     * même principe que l'image) — appliqués ensuite par training.js à chaque chargement. */
    document.getElementById("formationBannerTextSaveBtn").addEventListener("click", function () {
        var resultEl = document.getElementById("formationBannerTextResult");
        var entries = {
            "formation.banner.title": document.getElementById("formationBannerTitleInput").value,
            "formation.banner.subtitle": document.getElementById("formationBannerSubtitleInput").value,
            "formation.banner.fontFamily": document.getElementById("formationBannerFontSelect").value,
            "formation.banner.titleSize": document.getElementById("formationBannerSizeSelect").value,
            "formation.banner.titleColor": document.getElementById("formationBannerTitleColorInput").value,
            "formation.banner.subtitleColor": document.getElementById("formationBannerSubtitleColorInput").value
        };
        resultEl.textContent = "Enregistrement...";
        resultEl.className = "small ms-2 text-muted";
        Promise.all(Object.keys(entries).map(function (key) {
            return fetch("/api/site-settings/" + encodeURIComponent(key), {
                method: "POST", credentials: "same-origin",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ value: entries[key] })
            });
        })).then(function () {
            resultEl.textContent = "Enregistré — visible dès le prochain chargement de Formation.";
            resultEl.className = "small ms-2 text-success";
        }).catch(function (e) {
            resultEl.textContent = "Erreur : " + e.message;
            resultEl.className = "small ms-2 text-danger";
        });
    });

    /**
     * Câble un input file + bouton pour un réglage d'image (SiteSetting) — ouvre toujours
     * l'éditeur (rognage/effets) entre la sélection et l'upload réel, réutilisable pour
     * n'importe quel emplacement d'image piloté par SiteSetting.
     */
    function wireSiteSettingImageUpload(fileInputId, buttonId, resultId, previewId, settingKey, successMessage) {
        document.getElementById(buttonId).addEventListener("click", function () {
            var fileInput = document.getElementById(fileInputId);
            var resultBox = document.getElementById(resultId);
            var file = fileInput.files[0];
            if (!file) { resultBox.innerHTML = '<span class="text-danger">Choisissez un fichier.</span>'; return; }

            window.RccImageEditor.open(file, function (editedBlob) {
                var formData = new FormData();
                formData.append("file", editedBlob, "image.jpg");
                fetch("/api/site-settings/" + settingKey + "/image", { method: "POST", credentials: "same-origin", body: formData })
                    .then(function (res) {
                        if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                        return res.json();
                    })
                    .then(function (result) {
                        resultBox.innerHTML = '<span class="text-success">' + successMessage + '</span>';
                        var preview = document.getElementById(previewId);
                        preview.src = result.value;
                        preview.style.display = "";
                        fileInput.value = "";
                    })
                    .catch(function (e) { resultBox.innerHTML = '<span class="text-danger">Erreur : ' + e.message + '</span>'; });
            });
        });
    }

    wireSiteSettingImageUpload("monRccBannerFile", "monRccBannerUploadBtn", "monRccBannerResult", "monRccBannerPreview",
        "monrcc.banner.imageUrl", "Bannière mise à jour — visible dès le prochain chargement de MON RCC.");

    wireSiteSettingImageUpload("loginHeroFile", "loginHeroUploadBtn", "loginHeroResult", "loginHeroPreview",
        "login.hero.imageUrl", "Photo mise à jour — visible dès le prochain chargement de la page de connexion.");

    wireSiteSettingImageUpload("formationBannerFile", "formationBannerUploadBtn", "formationBannerResult", "formationBannerPreview",
        "formation.banner.imageUrl", "Bannière mise à jour — visible dès le prochain chargement de Formation.");

    document.getElementById("loginHeroOpacityRange").addEventListener("input", function () {
        document.getElementById("loginHeroOpacityValue").textContent = this.value;
    });
    document.getElementById("loginHeroOpacityRange").addEventListener("change", function () {
        var value = this.value;
        fetch("/api/site-settings/login.hero.opacity", {
            method: "POST", credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ value: value })
        }).catch(function (e) { console.error(e); });
    });

    // ===== Carousel de photos de connexion (plusieurs images) =====

    function loadLoginHeroCarousel() {
        getJson("/api/site-settings/login-hero-images").then(function (urls) {
            var list = document.getElementById("loginHeroCarouselList");
            if (!urls.length) { list.innerHTML = '<p class="text-muted small mb-0">Aucune image dans le carousel — la photo unique ci-dessus reste utilisée seule.</p>'; return; }
            list.innerHTML = urls.map(function (url) {
                return '<div class="position-relative" style="width:100px;">' +
                    '<img src="' + url + '" style="width:100%;height:70px;object-fit:cover;border-radius:.4rem;">' +
                    '<button class="btn btn-sm btn-danger position-absolute top-0 end-0" style="padding:0 5px;" data-remove-hero="' + encodeURIComponent(url) + '"><i class="bi bi-x"></i></button>' +
                    '</div>';
            }).join("");
            Array.prototype.forEach.call(list.querySelectorAll("[data-remove-hero]"), function (btn) {
                btn.addEventListener("click", function () {
                    fetch("/api/site-settings/login-hero-images/remove", {
                        method: "POST", credentials: "same-origin",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ url: decodeURIComponent(btn.getAttribute("data-remove-hero")) })
                    }).then(loadLoginHeroCarousel);
                });
            });
        }).catch(function () {});
    }

    document.getElementById("loginHeroCarouselAddBtn").addEventListener("click", function () {
        var fileInput = document.getElementById("loginHeroCarouselFile");
        var resultBox = document.getElementById("loginHeroCarouselResult");
        var file = fileInput.files[0];
        if (!file) { resultBox.innerHTML = '<span class="text-danger">Choisissez un fichier.</span>'; return; }
        window.RccImageEditor.open(file, function (editedBlob) {
            var formData = new FormData();
            formData.append("file", editedBlob, "image.jpg");
            fetch("/api/site-settings/login-hero-images", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
                .then(function () {
                    resultBox.innerHTML = '<span class="text-success">Image ajoutée au carousel.</span>';
                    fileInput.value = "";
                    loadLoginHeroCarousel();
                })
                .catch(function (e) { resultBox.innerHTML = '<span class="text-danger">Erreur : ' + e.message + '</span>'; });
        });
    });

    // ===== Style des cartes "fonctionnalité" (police, interligne) =====

    function loadLoginFeaturesStyle() {
        getJson("/api/site-settings/public").then(function (settings) {
            document.getElementById("loginFeaturesFontSelect").value = settings["login.features.fontFamily"] || "";
            document.getElementById("loginFeaturesLineHeightSelect").value = settings["login.features.lineHeight"] || "1.5";
        }).catch(function () {});
    }

    document.getElementById("loginFeaturesStyleSaveBtn").addEventListener("click", function () {
        var resultEl = document.getElementById("loginFeaturesStyleResult");
        var entries = {
            "login.features.fontFamily": document.getElementById("loginFeaturesFontSelect").value,
            "login.features.lineHeight": document.getElementById("loginFeaturesLineHeightSelect").value
        };
        resultEl.textContent = "Enregistrement...";
        resultEl.className = "small ms-2 text-muted";
        Promise.all(Object.keys(entries).map(function (key) {
            return fetch("/api/site-settings/" + encodeURIComponent(key), {
                method: "POST", credentials: "same-origin",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ value: entries[key] })
            });
        })).then(function () {
            resultEl.textContent = "Enregistré.";
            resultEl.className = "small ms-2 text-success";
        }).catch(function (e) {
            resultEl.textContent = "Erreur : " + e.message;
            resultEl.className = "small ms-2 text-danger";
        });
    });

    // ===== Cartes "fonctionnalité" (CRUD) =====

    var loginFeatureCardsCache = [];

    function loadLoginFeatureCards() {
        getJson("/api/login-feature-cards").then(function (cards) {
            loginFeatureCardsCache = cards;
            var body = document.getElementById("loginFeatureCardsBody");
            if (!cards.length) { body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Aucune carte — les 4 cartes par défaut seront recréées au prochain démarrage si la table est vide.</td></tr>'; return; }
            body.innerHTML = cards.map(function (c) {
                return "<tr data-card-id=\"" + c.cardId + "\">" +
                    "<td><i class=\"bi " + escapeHtml(c.icon) + "\"></i> <span class=\"text-muted small\">" + escapeHtml(c.icon) + "</span></td>" +
                    "<td>" + escapeHtml(c.title) + "</td>" +
                    "<td>" + escapeHtml(c.subtitle || "") + "</td>" +
                    "<td><div class=\"form-check form-switch\"><input class=\"form-check-input toggle-card-active\" type=\"checkbox\" " + (c.active ? "checked" : "") + "></div></td>" +
                    "<td><button class=\"btn btn-sm btn-outline-danger delete-card-btn\"><i class=\"bi bi-trash\"></i></button></td>" +
                    "</tr>";
            }).join("");

            Array.prototype.forEach.call(body.querySelectorAll(".toggle-card-active"), function (checkbox) {
                checkbox.addEventListener("change", function () {
                    var row = checkbox.closest("tr");
                    var card = loginFeatureCardsCache.find(function (c) { return c.cardId === parseInt(row.getAttribute("data-card-id"), 10); });
                    if (!card) return;
                    fetch("/api/login-feature-cards/" + card.cardId, {
                        method: "PUT", credentials: "same-origin",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ icon: card.icon, title: card.title, subtitle: card.subtitle, sortOrder: card.sortOrder, active: checkbox.checked })
                    }).then(loadLoginFeatureCards).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll(".delete-card-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var row = btn.closest("tr");
                    if (!confirm("Supprimer cette carte ?")) return;
                    fetch("/api/login-feature-cards/" + row.getAttribute("data-card-id"), { method: "DELETE", credentials: "same-origin" })
                        .then(loadLoginFeatureCards).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function () {
            document.getElementById("loginFeatureCardsBody").innerHTML = '<tr><td colspan="5" class="text-center text-danger">Impossible de charger les cartes.</td></tr>';
        });
    }

    document.getElementById("loginFeatureCardAddBtn").addEventListener("click", function () {
        var icon = document.getElementById("loginFeatureCardIconInput").value.trim();
        var title = document.getElementById("loginFeatureCardTitleInput").value.trim();
        var subtitle = document.getElementById("loginFeatureCardSubtitleInput").value.trim();
        if (!icon || !title) { alert("L'icône et le titre sont obligatoires."); return; }
        fetch("/api/login-feature-cards", {
            method: "POST", credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ icon: icon, title: title, subtitle: subtitle, active: true })
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
            document.getElementById("loginFeatureCardIconInput").value = "";
            document.getElementById("loginFeatureCardTitleInput").value = "";
            document.getElementById("loginFeatureCardSubtitleInput").value = "";
            loadLoginFeatureCards();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    loadLoginHeroCarousel();
    loadLoginFeaturesStyle();
    loadLoginFeatureCards();

    // ===== Référentiel SLA RCC (délais de traitement communiqués au client) =====

    var slaRulesCache = [];

    /** Recherche, catégorie et statut : le tableau SLA (très long) n'affiche que ce qui est cherché. */
    function filteredSlaRules() {
        var q = ($("slaFilterText") ? $("slaFilterText").value : "").trim().toLowerCase();
        var cat = $("slaFilterCategory") ? $("slaFilterCategory").value : "";
        var act = $("slaFilterActive") ? $("slaFilterActive").value : "";
        return slaRulesCache.filter(function (r) {
            if (cat && (r.category || "") !== cat) return false;
            if (act === "1" && !r.isActive) return false;
            if (act === "0" && r.isActive) return false;
            if (q && [r.motif, r.category, r.level, r.slaLabel, r.destinationService, r.priority, r.notes].join(" ").toLowerCase().indexOf(q) === -1) return false;
            return true;
        });
    }

    function fillSlaCategories() {
        var sel = $("slaFilterCategory");
        if (!sel) return;
        var cur = sel.value;
        var cats = slaRulesCache.map(function (r) { return r.category || ""; }).filter(function (c, i, a) { return c && a.indexOf(c) === i; }).sort();
        sel.innerHTML = '<option value="">Toutes les catégories</option>' + cats.map(function (c) { return '<option value="' + escapeHtml(c) + '">' + escapeHtml(c) + '</option>'; }).join("");
        sel.value = cats.indexOf(cur) !== -1 ? cur : "";
    }

    ["slaFilterText", "slaFilterCategory", "slaFilterActive"].forEach(function (id) {
        var el = $(id);
        if (el) el.addEventListener(id === "slaFilterText" ? "input" : "change", function () { renderSlaRules(); });
    });

    function loadSlaRules() {
        getJson("/api/sla-rules?includeInactive=true").then(function (rules) {
            slaRulesCache = rules;
            fillSlaCategories();
            renderSlaRules();
        }).catch(function () {
            document.getElementById("slaRulesTable").innerHTML = '<tr><td colspan="9" class="text-center text-danger">Impossible de charger le référentiel SLA.</td></tr>';
        });
    }

    function renderSlaRules() {
        {
            var rules = filteredSlaRules();
            var body = document.getElementById("slaRulesTable");
            if ($("slaFilterCount")) $("slaFilterCount").textContent = rules.length + " / " + slaRulesCache.length + " règle(s)";
            if (!rules.length) { body.innerHTML = '<tr><td colspan="9" class="text-center text-muted">Aucune règle SLA ne correspond.</td></tr>'; return; }
            body.innerHTML = rules.map(function (r) {
                return "<tr data-sla-id=\"" + r.id + "\">" +
                    "<td>" + escapeHtml(r.motif) + "</td>" +
                    "<td>" + escapeHtml(r.category || "") + "</td>" +
                    "<td>" + escapeHtml(r.level || "") + "</td>" +
                    "<td><strong>" + escapeHtml(r.slaLabel) + "</strong></td>" +
                    "<td>" + escapeHtml(r.destinationService || "") + "</td>" +
                    "<td>" + escapeHtml(r.priority || "") + "</td>" +
                    "<td class=\"text-center\">" + (r.autoEscalation ? "<i class=\"bi bi-check-circle-fill text-success\"></i>" : "") + "</td>" +
                    "<td><div class=\"form-check form-switch\"><input class=\"form-check-input toggle-sla-active\" type=\"checkbox\" " + (r.isActive ? "checked" : "") + "></div></td>" +
                    "<td><button class=\"btn btn-sm btn-outline-danger delete-sla-btn\"><i class=\"bi bi-trash\"></i></button></td>" +
                    "</tr>";
            }).join("");

            Array.prototype.forEach.call(body.querySelectorAll(".toggle-sla-active"), function (checkbox) {
                checkbox.addEventListener("change", function () {
                    var row = checkbox.closest("tr");
                    var rule = slaRulesCache.find(function (r) { return r.id === parseInt(row.getAttribute("data-sla-id"), 10); });
                    if (!rule) return;
                    fetch("/api/sla-rules/" + rule.id, {
                        method: "PUT", credentials: "same-origin",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify(Object.assign({}, rule, { isActive: checkbox.checked }))
                    }).then(loadSlaRules).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll(".delete-sla-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var row = btn.closest("tr");
                    if (!confirm("Supprimer cette règle SLA ?")) return;
                    fetch("/api/sla-rules/" + row.getAttribute("data-sla-id"), { method: "DELETE", credentials: "same-origin" })
                        .then(loadSlaRules).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }
    }

    var createSlaRuleBtnEl = document.getElementById("createSlaRuleBtn");
    if (createSlaRuleBtnEl) {
        createSlaRuleBtnEl.addEventListener("click", function () {
            var resultEl = document.getElementById("slaRuleResult");
            var motif = document.getElementById("newSlaMotif").value.trim();
            var category = document.getElementById("newSlaCategory").value.trim();
            var level = document.getElementById("newSlaLevel").value.trim();
            var slaHours = parseInt(document.getElementById("newSlaHours").value, 10);
            var slaLabel = document.getElementById("newSlaLabel").value.trim();
            var destinationService = document.getElementById("newSlaService").value.trim();
            var priority = document.getElementById("newSlaPriority").value;
            var autoEscalation = document.getElementById("newSlaAutoEscalation").checked;
            var notes = document.getElementById("newSlaNotes").value.trim();

            if (!motif || !category || !slaLabel || !slaHours) {
                resultEl.textContent = "Motif, catégorie, libellé SLA et durée en heures sont obligatoires.";
                resultEl.className = "small mt-2 text-danger";
                return;
            }

            fetch("/api/sla-rules", {
                method: "POST", credentials: "same-origin",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    motif: motif, category: category, level: level || null, slaHours: slaHours,
                    slaLabel: slaLabel, destinationService: destinationService || null,
                    priority: priority || null, autoEscalation: autoEscalation, notes: notes || null,
                    isActive: true, sortOrder: slaRulesCache.length + 1
                })
            }).then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                ["newSlaMotif", "newSlaCategory", "newSlaLevel", "newSlaHours", "newSlaLabel", "newSlaService", "newSlaNotes"]
                    .forEach(function (id) { document.getElementById(id).value = ""; });
                document.getElementById("newSlaPriority").value = "";
                document.getElementById("newSlaAutoEscalation").checked = false;
                resultEl.textContent = "Règle SLA ajoutée.";
                resultEl.className = "small mt-2 text-success";
                loadSlaRules();
            }).catch(function (e) {
                resultEl.textContent = "Erreur : " + e.message;
                resultEl.className = "small mt-2 text-danger";
            });
        });
    }

    function loadPending() {
        fetch("/api/users/pending", { credentials: "same-origin" }).then(function (res) {
            if (!res.ok) throw new Error("HTTP " + res.status);
            return res.json();
        }).then(function (accounts) {
            var section = document.getElementById("pendingSection");
            var body = document.getElementById("pendingBody");
            if (!accounts.length) {
                section.style.display = "none";
                return;
            }
            section.style.display = "";
            body.innerHTML = accounts.map(function (a) {
                var requested = a.requestedAt ? new Date(a.requestedAt).toLocaleString("fr-FR") : "—";
                return "<tr>" +
                    "<td>" + (a.name || "—") + "</td>" +
                    "<td>" + a.matricule + "</td>" +
                    "<td>" + (a.email || "—") + "</td>" +
                    "<td>" + requested + "</td>" +
                    '<td class="text-end">' +
                        '<a href="/users" class="btn btn-sm btn-outline-secondary me-1" title="Définir le rôle et les permissions avant approbation"><i class="bi bi-pencil"></i> Rôle</a>' +
                        '<button class="btn btn-sm btn-success me-1" data-approve-pending="' + a.matricule + '"><i class="bi bi-check-lg"></i> Approuver</button>' +
                        '<button class="btn btn-sm btn-outline-danger" data-reject-pending="' + a.matricule + '"><i class="bi bi-x-lg"></i> Refuser</button>' +
                    "</td>" +
                "</tr>";
            }).join("");

            Array.prototype.forEach.call(body.querySelectorAll("[data-approve-pending]"), function (btn) {
                btn.addEventListener("click", function () {
                    var matricule = btn.getAttribute("data-approve-pending");
                    fetch("/api/users/" + encodeURIComponent(matricule) + "/approve", { method: "POST", credentials: "same-origin" })
                        .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                        .then(loadPending)
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll("[data-reject-pending]"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Refuser cette demande d'accès ? Le compte restera bloqué.")) return;
                    var matricule = btn.getAttribute("data-reject-pending");
                    fetch("/api/users/" + encodeURIComponent(matricule) + "/reject", { method: "POST", credentials: "same-origin" })
                        .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                        .then(loadPending)
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function () {
            document.getElementById("pendingSection").style.display = "none";
        });
    }

    loadSiteAppearance();
    loadSlaRules();
    loadPending();
})();