"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var currentProfile = null;
    var categoriesCache = [];
    var articleContentEditor = null;
    var currentCategoryId = null;
    var currentArticleId = null;

    function isQaOrAdmin() { return currentProfile === "QA" || currentProfile === "ADMIN"; }

    // ===== Catégories =====

    function renderCategories() {
        renderCategoryGrid();
    }

    /** Grille de vignettes façon portail bancaire — upload d'image réservé à l'administrateur. */
    function renderCategoryGrid() {
        var grid = $("categoryGrid");
        if (!grid) return;

        grid.innerHTML = categoriesCache.map(function (c) {
            var imageBlock = c.imageUrl
                ? '<img src="' + c.imageUrl + '" alt="" style="width:100%;height:140px;object-fit:cover;">'
                : '<div style="width:100%;height:140px;background:#eef1f8;display:flex;align-items:center;justify-content:center;">' +
                  '<i class="bi ' + (c.icon || "bi-folder2") + ' text-muted" style="font-size:2rem;"></i></div>';
            var uploadBtn = isAdminOnly()
                ? '<label class="btn btn-sm btn-outline-secondary position-absolute top-0 end-0 m-1" style="cursor:pointer;" title="Changer l\'image (admin)">' +
                  '<i class="bi bi-camera"></i><input type="file" accept="image/*" class="d-none category-image-input" data-category-id="' + c.categoryId + '"></label>'
                : "";
            var manageBtns = isQaOrAdmin()
                ? '<div class="position-absolute top-0 start-0 m-1 d-flex gap-1">' +
                  '<button type="button" class="btn btn-sm btn-outline-secondary edit-category-btn" data-id="' + c.categoryId + '" title="Modifier"><i class="bi bi-pencil"></i></button>' +
                  '<button type="button" class="btn btn-sm btn-outline-danger delete-category-btn" data-id="' + c.categoryId + '" title="Supprimer"><i class="bi bi-trash"></i></button>' +
                  '</div>'
                : "";
            var quickImportBtn = currentProfile === "QA"
                ? '<button type="button" class="btn btn-sm btn-primary position-absolute bottom-0 end-0 m-1 kb-quick-upload-btn" ' +
                  'data-category-id="' + c.categoryId + '" data-category-label="' + escapeHtml(c.title) + '" title="Importer un fichier">' +
                  '<i class="bi bi-upload"></i></button>'
                : "";

            return '<div class="col-md-4 col-lg-3">' +
                '<div class="card dashboard-card shadow-sm h-100 category-card-btn" data-id="' + c.categoryId + '" style="cursor:pointer;">' +
                '<div class="position-relative">' + imageBlock + uploadBtn + manageBtns + quickImportBtn + '</div>' +
                '<div class="card-body">' +
                '<div class="fw-semibold">' + escapeHtml(c.title) + '</div>' +
                '</div></div></div>';
        }).join("");

        Array.prototype.forEach.call(grid.querySelectorAll(".kb-quick-upload-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                openKbQuickUploadModal(Number(btn.getAttribute("data-category-id")), btn.getAttribute("data-category-label"));
            });
        });

        Array.prototype.forEach.call(grid.querySelectorAll(".category-card-btn"), function (card) {
            card.addEventListener("click", function (evt) {
                if (evt.target.closest("label") || evt.target.closest("button")) return;
                selectCategory(Number(card.getAttribute("data-id")));
            });
        });

        Array.prototype.forEach.call(grid.querySelectorAll(".edit-category-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                var category = categoriesCache.filter(function (c) { return c.categoryId === Number(btn.dataset.id); })[0];
                if (!category) return;
                var newTitle = prompt("Nouveau titre de la catégorie :", category.title);
                if (!newTitle || !newTitle.trim()) return;
                var teamPrompt = "Équipe (laisser vide = Inbound historique) :\n" +
                    "INBOUND_VOICE, INBOUND_MAIL, CIB ou OUTBOUND";
                var newTeam = prompt(teamPrompt, category.team || "");
                if (newTeam === null) return; // annulé
                sendJson("/api/kb/categories/" + category.categoryId, "PUT", { title: newTitle.trim(), team: newTeam.trim() })
                    .then(function () { loadCategories(currentCategoryId); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
        Array.prototype.forEach.call(grid.querySelectorAll(".delete-category-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                if (!confirm("Supprimer cette catégorie ? Impossible si elle contient encore des articles.")) return;
                sendJson("/api/kb/categories/" + btn.dataset.id, "DELETE")
                    .then(function () { loadCategories(); })
                    .catch(function (e) {
                        // La catégorie contient encore des articles/fichiers — proposer une
                        // suppression forcée avec le décompte exact, jamais silencieuse.
                        if (e.message && e.message.indexOf("article(s) sont encore classés") !== -1) {
                            if (confirm(e.message + "\n\nSupprimer QUAND MÊME, avec tout leur contenu (fichiers et liens compris) ? Cette action est irréversible.")) {
                                sendJson("/api/kb/categories/" + btn.dataset.id + "?force=true", "DELETE")
                                    .then(function () { loadCategories(); })
                                    .catch(function (e2) { alert("Erreur : " + e2.message); });
                            }
                            return;
                        }
                        alert("Erreur : " + e.message);
                    });
            });
        });

        Array.prototype.forEach.call(grid.querySelectorAll(".category-image-input"), function (input) {
            input.addEventListener("change", function (evt) {
                evt.stopPropagation();
                var file = this.files[0];
                var categoryId = this.getAttribute("data-category-id");
                if (!file) return;
                var formData = new FormData();
                formData.append("file", file);
                fetch("/api/kb/categories/" + categoryId + "/image", { method: "POST", credentials: "same-origin", body: formData })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
                    .then(function () { loadCategories(currentCategoryId); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function isAdminOnly() { return currentProfile === "ADMIN"; }

    var currentTeamFilter = ""; // "" = auto (backend décide : QA/Admin voient tout, agent voit sa propre équipe)

    function loadCategories(thenSelectCategoryId) {
        var url = "/api/kb/categories" + (currentTeamFilter ? "?team=" + encodeURIComponent(currentTeamFilter) : "");
        getJson(url).then(function (categories) {
            categoriesCache = categories;
            renderCategories();
            populateCategorySelect();
            // Un lien profond explicite (?category=123) ouvre bien cette catégorie précise,
            // mais sinon on n'ouvre RIEN tant que l'utilisateur n'a pas cliqué une vignette.
            if (thenSelectCategoryId) {
                selectCategory(thenSelectCategoryId);
            }
        }).catch(function (e) { console.error(e); });
    }

    /** Barre d'onglets équipe — réservée QA/Admin (un agent classique est déjà auto-filtré côté serveur). */
    function renderTeamTabBar() {
        if (!isQaOrAdmin()) { $("kbTeamTabBar").style.display = "none"; return; }
        $("kbTeamTabBar").style.display = "";
        var teams = [
            { code: "", label: "Toutes" },
            { code: "INBOUND_VOICE", label: "Inbound Voix" },
            { code: "INBOUND_MAIL", label: "Inbound Mail / Rafiki" },
            { code: "CIB", label: "CIB" },
            { code: "OUTBOUND", label: "Outbound" }
        ];
        var buttons = teams.map(function (t) {
            var active = currentTeamFilter === t.code ? "btn-primary" : "btn-outline-secondary";
            return '<button type="button" class="btn btn-sm ' + active + ' team-tab-btn" data-team="' + t.code + '">' + t.label + '</button>';
        }).join("");
        $("kbTeamTabBar").innerHTML = '<span class="text-muted small"><i class="bi bi-people-fill"></i> Équipe :</span> ' + buttons;
        Array.prototype.forEach.call($("kbTeamTabBar").querySelectorAll(".team-tab-btn"), function (btn) {
            btn.addEventListener("click", function () {
                currentTeamFilter = btn.getAttribute("data-team");
                renderTeamTabBar();
                loadCategories();
            });
        });
    }

    function populateCategorySelect() {
        var options = categoriesCache.map(function (c) {
            return '<option value="' + c.categoryId + '">' + escapeHtml(c.title) + '</option>';
        }).join("");
        $("articleCategorySelect").innerHTML = options;
        $("importArticleCategorySelect").innerHTML = options;
    }

    // ===== Articles =====

    function selectCategory(categoryId) {
        currentCategoryId = categoryId;
        var category = categoriesCache.filter(function (c) { return c.categoryId === categoryId; })[0];
        $("categoryModalTitle").textContent = category ? category.title : "Rubrique";
        $("articlesList").innerHTML = '<p class="text-muted">Chargement…</p>';
        new bootstrap.Modal($("categoryModal")).show();

        getJson("/api/kb/articles?categoryId=" + categoryId + (currentCountryCode ? "&countryCode=" + currentCountryCode : ""))
            .then(renderArticles).catch(function (e) {
                $("articlesList").innerHTML = '<p class="text-danger">Erreur : ' + escapeHtml(e.message) + '</p>';
            });
    }

    function renderArticles(articles) {
        var container = $("articlesList");
        if (!articles.length) {
            container.innerHTML = '<p class="text-muted mb-0">Aucun article dans cette catégorie.</p>';
            return;
        }
        container.innerHTML = articles.map(function (a) {
            return '<button type="button" class="btn btn-outline-secondary text-start article-btn" data-id="' + a.articleId + '">' +
                '<div class="fw-semibold">' + escapeHtml(a.title) + '</div>' +
                '<div class="small text-muted">' + (a.countryLabel ? escapeHtml(a.countryLabel) : "Toutes filiales") +
                (a.tags ? " · " + escapeHtml(a.tags) : "") + '</div>' +
                '</button>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".article-btn"), function (btn) {
            btn.addEventListener("click", function () { openArticle(Number(btn.getAttribute("data-id"))); });
        });
    }

    // ===== Détail article + fichiers joints =====

    function openArticle(articleId) {
        currentArticleId = articleId;
        var categoryModalEl = $("categoryModal");
        var openCategoryModal = bootstrap.Modal.getInstance(categoryModalEl);
        if (openCategoryModal) openCategoryModal.hide();

        Promise.all([
            getJson("/api/kb/articles?categoryId=" + currentCategoryId),
            getJson("/api/kb/articles/" + articleId + "/attachments")
        ]).then(function (results) {
            var article = results[0].filter(function (a) { return a.articleId === articleId; })[0];
            var attachments = results[1];
            if (!article) return;

            $("articleModalTitle").textContent = article.title;
            $("articleModalContent").innerHTML = article.contentHtml || "<p class='text-muted'>Aucun contenu.</p>";
            renderAttachments(attachments);
            $("articleUploadRow").style.display = isQaOrAdmin() ? "" : "none";
            $("articleModalAdminActions").style.display = isQaOrAdmin() ? "" : "none";

            new bootstrap.Modal($("articleModal")).show();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    function renderAttachments(attachments) {
        var container = $("articleAttachments");
        if (!attachments.length) {
            container.innerHTML = '<p class="text-muted small mb-0">Aucun fichier joint.</p>';
            return;
        }
        container.innerHTML = attachments.map(function (a) {
            var removeBtn = isQaOrAdmin()
                ? '<button class="btn btn-sm btn-outline-danger remove-attachment-btn" data-id="' + a.id + '"><i class="bi bi-trash"></i></button>'
                : "";
            var isImage = /\.(png|jpe?g|gif|webp)$/i.test(a.fileName || a.storageUrl || "");

            if (isImage) {
                return '<div class="list-group-item">' +
                    '<div class="d-flex justify-content-between align-items-center mb-1">' +
                    '<span><i class="bi bi-image"></i> ' + escapeHtml(a.fileName) + '</span>' + removeBtn + '</div>' +
                    '<img src="' + escapeHtml(a.storageUrl) + '" alt="' + escapeHtml(a.fileName) + '" ' +
                    'class="img-fluid rounded attachment-image-preview" style="max-height:400px;cursor:zoom-in;" ' +
                    'data-full="' + escapeHtml(a.storageUrl) + '">' +
                    '</div>';
            }
            var isLink = a.mimeType === "text/uri-list";
            var icon = isLink ? "bi-link-45deg" : "bi-file-earmark-text";
            return '<div class="list-group-item d-flex justify-content-between align-items-center">' +
                '<a href="' + escapeHtml(a.storageUrl) + '" target="_blank" rel="noopener">' +
                '<i class="bi ' + icon + '"></i> ' + escapeHtml(a.fileName) + '</a>' + removeBtn + '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".attachment-image-preview"), function (img) {
            img.addEventListener("click", function () { window.open(img.getAttribute("data-full"), "_blank"); });
        });

        Array.prototype.forEach.call(container.querySelectorAll(".remove-attachment-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer ce fichier ?")) return;
                fetch("/api/kb/attachments/" + btn.getAttribute("data-id"), { method: "DELETE", credentials: "same-origin" })
                    .then(function () { openArticle(currentArticleId); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function wireFileUpload() {
        $("articleFileInput").addEventListener("change", function () {
            var file = this.files[0];
            if (!file || !currentArticleId) return;
            var formData = new FormData();
            formData.append("file", file);
            fetch("/api/kb/articles/" + currentArticleId + "/attachments", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function () { $("articleFileInput").value = ""; openArticle(currentArticleId); })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    // ===== Création / édition / suppression d'article (QA/admin) =====

    function wireArticleForm() {
        $("newArticleBtn").addEventListener("click", function () {
            $("articleForm").reset();
            $("articleForm").removeAttribute("data-editing-id");
            $("articleFormCard").style.display = "";
            articleContentEditor = window.RccRichText.create($("articleContentEditor"), "");
        });
        $("cancelArticleBtn").addEventListener("click", function () {
            $("articleFormCard").style.display = "none";
        });

        $("articleForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            var payload = {
                categoryId: Number($("articleCategorySelect").value),
                countryCode: $("articleCountrySelect").value || null,
                serviceCode: $("articleServiceSelect").value || null,
                title: $("articleTitleInput").value.trim(),
                contentHtml: articleContentEditor ? articleContentEditor.getHtml().trim() : "",
                tags: $("articleTagsInput").value.trim() || null,
                sortOrder: 0
            };
            if (!payload.title || !payload.contentHtml) { alert("Titre et contenu sont obligatoires."); return; }

            var editingId = $("articleForm").getAttribute("data-editing-id");
            var request = editingId
                ? sendJson("/api/kb/articles/" + editingId, "PUT", payload)
                : sendJson("/api/kb/articles", "POST", payload);

            request.then(function (article) {
                var file = $("articleCreateFileInput").files[0];
                var articleId = editingId || article.articleId;
                if (!file) return Promise.resolve();

                var formData = new FormData();
                formData.append("file", file);
                return fetch("/api/kb/articles/" + articleId + "/attachments", { method: "POST", credentials: "same-origin", body: formData })
                    .then(function (res) {
                        if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    });
            }).then(function () {
                $("articleFormCard").style.display = "none";
                $("articleForm").reset();
                loadCategories(payload.categoryId);
            }).catch(function (e) { alert("Erreur : " + e.message); });
        });

        $("editArticleBtn").addEventListener("click", function () {
            getJson("/api/kb/articles?categoryId=" + currentCategoryId).then(function (articles) {
                var article = articles.filter(function (a) { return a.articleId === currentArticleId; })[0];
                if (!article) return;
                $("articleCategorySelect").value = article.categoryId;
                $("articleCountrySelect").value = article.countryCode || "";
                $("articleServiceSelect").value = article.serviceCode || "";
                $("articleTagsInput").value = article.tags || "";
                $("articleTitleInput").value = article.title;
                $("articleFormCard").style.display = "";
                articleContentEditor = window.RccRichText.create($("articleContentEditor"), article.contentHtml || "");
                $("articleForm").setAttribute("data-editing-id", currentArticleId);
                bootstrap.Modal.getInstance($("articleModal")).hide();
                $("articleFormCard").scrollIntoView({ behavior: "smooth" });
            });
        });

        $("deleteArticleBtn").addEventListener("click", function () {
            if (!confirm("Supprimer cet article et ses fichiers joints ?")) return;
            sendJson("/api/kb/articles/" + currentArticleId, "DELETE")
                .then(function () {
                    bootstrap.Modal.getInstance($("articleModal")).hide();
                    selectCategory(currentCategoryId);
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    function wireCategoryForm() {
        $("newCategoryBtn").addEventListener("click", function () {
            $("categoryFormCard").style.display = "";
            $("categoryFormCard").scrollIntoView({ behavior: "smooth" });
        });
        $("cancelCategoryBtn").addEventListener("click", function () {
            $("categoryFormCard").style.display = "none";
        });
        $("createCategoryBtn").addEventListener("click", function () {
            var code = $("newCategoryCode").value.trim();
            var title = $("newCategoryTitle").value.trim();
            var icon = $("newCategoryIcon").value.trim() || null;
            var team = $("newCategoryTeam").value || null;
            if (!code || !title) { alert("Code et titre sont obligatoires."); return; }

            sendJson("/api/kb/categories", "POST", { code: code, title: title, icon: icon, sortOrder: categoriesCache.length, team: team })
                .then(function () {
                    $("newCategoryCode").value = "";
                    $("newCategoryTitle").value = "";
                    $("newCategoryIcon").value = "";
                    $("newCategoryTeam").value = "";
                    $("categoryFormCard").style.display = "none";
                    loadCategories();
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    function wireImportArticleForm() {
        $("newImportArticleBtn").addEventListener("click", function () {
            $("importArticleCard").style.display = "";
            $("importArticleCard").scrollIntoView({ behavior: "smooth" });
        });
        $("cancelImportArticleBtn").addEventListener("click", function () {
            $("importArticleCard").style.display = "none";
        });

        $("importArticleBtn").addEventListener("click", function () {
            var categoryId = $("importArticleCategorySelect").value;
            var countryCode = $("importArticleCountrySelect").value;
            var file = $("importArticleFile").files[0];
            var resultBox = $("importArticleResult");

            if (!categoryId || !file) { alert("Catégorie et fichier sont obligatoires."); return; }

            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Extraction en cours…";

            var formData = new FormData();
            formData.append("file", file);

            var url = "/api/kb/articles/import-document?categoryId=" + encodeURIComponent(categoryId) +
                (countryCode ? "&countryCode=" + encodeURIComponent(countryCode) : "");

            fetch(url, { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (articles) {
                    resultBox.className = "small mt-2 text-success";
                    if (articles.length > 1) {
                        resultBox.textContent = articles.length + " articles distincts détectés et créés : " +
                            articles.map(function (a) { return "« " + a.title + " »"; }).join(", ") +
                            " — vérifiez et ajustez si besoin.";
                    } else {
                        resultBox.textContent = "Créé : « " + articles[0].title + " » — vérifiez et ajustez si besoin.";
                    }
                    $("importArticleFile").value = "";
                    loadCategories(Number(categoryId));
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    // ===== Ralph =====

    // ===== Init =====

    var currentCountryCode = ""; // "" = toutes filiales

    var countriesCache = [];
    var servicesCache = [];

    function loadCountries() {
        var countriesPromise = getJson("/api/kb/countries").then(function (countries) {
            countriesCache = countries;
            var options = '<option value="">— Toutes filiales —</option>' +
                countries.map(function (c) { return '<option value="' + c.countryCode + '">' + escapeHtml(c.label) + '</option>'; }).join("");
            $("articleCountrySelect").innerHTML = options;
            $("importArticleCountrySelect").innerHTML = options;

            var select = $("countrySelect");
            var options = '<option value="">Toutes</option>' +
                countries.map(function (c) { return '<option value="' + c.countryCode + '">' + escapeHtml(c.label) + '</option>'; }).join("");
            select.innerHTML = options;
            select.value = currentCountryCode;

            select.addEventListener("change", function () {
                currentCountryCode = select.value;
                bankMapStale = true;
                if (isBankMapTabActive()) {
                    refreshBankMapTab(); // onglet « Agences / Carte » ouvert : on suit la filiale, sans modale
                } else if (currentCountryCode) {
                    openCountryModal(currentCountryCode);
                } else if (currentCategoryId) {
                    selectCategory(currentCategoryId);
                }
            });
        }).catch(function () {});

        getJson("/api/procedures/services").then(function (services) {
            servicesCache = services;
            var options = '<option value="">— Toutes équipes —</option>' +
                services.map(function (s) { return '<option value="' + s.code + '">' + escapeHtml(s.name) + '</option>'; }).join("");
            $("articleServiceSelect").innerHTML = options;
        }).catch(function () {});

        return countriesPromise; // permet à init() d'attendre countriesCache avant openCountryModal (lien profond)
    }

    /** Modale pays — liste les rubriques, chaque clic ouvre la modale d'articles déjà
     *  filtrée sur ce pays (currentCountryCode est déjà posé avant l'appel). */
    function openCountryModal(countryCode) {
        var country = countriesCache.filter(function (c) { return c.countryCode === countryCode; })[0];
        var flagImg = '<img src="https://flagcdn.com/24x18/' + countryCode.toLowerCase() + '.png" ' +
            'width="24" height="18" alt="" class="me-2" style="vertical-align:-3px;border-radius:2px;">';
        $("countryModalTitle").innerHTML = flagImg + escapeHtml(country ? country.label : countryCode);

        var listEl = $("countryModalCategories");
        if (!categoriesCache.length) {
            listEl.innerHTML = '<p class="text-muted small mb-0">Aucune rubrique disponible.</p>';
        } else {
            listEl.innerHTML = categoriesCache.map(function (c) {
                return '<button type="button" class="list-group-item list-group-item-action country-modal-category-btn" data-id="' + c.categoryId + '">' +
                    '<i class="bi ' + (c.icon || "bi-folder2") + '"></i> ' + escapeHtml(c.title) + '</button>';
            }).join("");
            Array.prototype.forEach.call(listEl.querySelectorAll(".country-modal-category-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    bootstrap.Modal.getInstance($("countryModal")).hide();
                    selectCategory(Number(btn.getAttribute("data-id")));
                });
            });
        }
        new bootstrap.Modal($("countryModal")).show();
    }

    // ===== Onglet « Agences / Carte » =====

    var bankMap = null;       // instance BankMap.mount (bank-map.js)
    var bankMapStale = true;  // filiale changée depuis le dernier chargement de la carte

    function isBankMapTabActive() {
        var btn = $("kbBankMapTabBtn");
        return !!btn && btn.classList.contains("active");
    }

    /** Affiche les agences de la filiale sélectionnée (currentCountryCode, "" = toutes → message).
     *  isQaOrAdmin() décide si "Ajouter une agence" est visible (écriture réservée QA/ADMIN). */
    function refreshBankMapTab() {
        if (!bankMap) return;
        bankMapStale = false;
        var country = countriesCache.filter(function (c) { return c.countryCode === currentCountryCode; })[0];
        $("kbBankMapCountryLabel").textContent = currentCountryCode
            ? (country ? country.label : currentCountryCode)
            : "choisissez une filiale";
        $("kbBankMapNoCountry").style.display = currentCountryCode ? "none" : "";
        $("kbBankMapBody").style.display = currentCountryCode ? "" : "none";
        if (currentCountryCode) {
            bankMap.load(currentCountryCode, isQaOrAdmin());
        }
    }

    function wireBankMapTab() {
        if (!window.BankMap || !$("kbBankMap")) return;
        bankMap = window.BankMap.mount($("kbBankMap"));

        // Chargement paresseux : la carte n'est (re)chargée qu'à l'affichage de l'onglet, puis
        // à chaque changement de filiale tant qu'il reste ouvert. Leaflet doit être recalé une
        // fois le conteneur visible (il mesure 0×0 dans un onglet masqué).
        $("kbBankMapTabBtn").addEventListener("shown.bs.tab", function () {
            if (bankMapStale) {
                refreshBankMapTab();
            } else {
                bankMap.refresh();
            }
        });

        $("countryModalBankMapBtn").addEventListener("click", function () {
            var countryModalInstance = bootstrap.Modal.getInstance($("countryModal"));
            if (countryModalInstance) countryModalInstance.hide();
            bootstrap.Tab.getOrCreateInstance($("kbBankMapTabBtn")).show();
        });
    }

    var kbQuickUploadCategoryId = null;

    function openKbQuickUploadModal(categoryId, categoryLabel) {
        kbQuickUploadCategoryId = categoryId;
        $("kbQuickUploadCategoryLabel").textContent = categoryLabel;
        $("kbQuickUploadFileInput").value = "";
        $("kbQuickUploadLinkLabel").value = "";
        $("kbQuickUploadLinkUrl").value = "";
        $("kbQuickUploadStatus").textContent = "";

        var countryOptions = '<option value="">— Toutes filiales —</option>' +
            countriesCache.map(function (c) { return '<option value="' + c.countryCode + '">' + escapeHtml(c.label) + '</option>'; }).join("");
        $("kbQuickUploadCountry").innerHTML = countryOptions;

        var serviceOptions = '<option value="">— Générique (toutes équipes) —</option>' +
            servicesCache.map(function (s) { return '<option value="' + s.code + '">' + escapeHtml(s.name) + '</option>'; }).join("");
        $("kbQuickUploadService").innerHTML = serviceOptions;

        new bootstrap.Modal($("kbQuickUploadModal")).show();
    }

    function wireKbQuickUploadModal() {
        $("kbQuickUploadSubmitBtn").addEventListener("click", function () {
            var statusBox = $("kbQuickUploadStatus");
            var countryCode = $("kbQuickUploadCountry").value;
            var serviceCode = $("kbQuickUploadService").value;
            var queryString = "?" + [
                serviceCode ? "serviceCode=" + encodeURIComponent(serviceCode) : "",
                countryCode ? "countryCode=" + encodeURIComponent(countryCode) : ""
            ].filter(Boolean).join("&");

            var linkTabActive = $("kbQuickUploadLinkTab").classList.contains("active");

            if (linkTabActive) {
                var label = $("kbQuickUploadLinkLabel").value.trim();
                var linkUrl = $("kbQuickUploadLinkUrl").value.trim();
                if (!label || !linkUrl) {
                    statusBox.className = "small mt-2 text-danger";
                    statusBox.textContent = "Titre et adresse du lien requis.";
                    return;
                }
                statusBox.className = "small mt-2 text-muted";
                statusBox.textContent = "Ajout du lien en cours…";

                fetch("/api/kb/categories/" + kbQuickUploadCategoryId + "/quick-upload-link" + queryString, {
                    method: "POST", credentials: "same-origin",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ fileName: label, mimeType: "text/uri-list", storageUrl: linkUrl })
                }).then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                }).then(function () {
                    statusBox.className = "small mt-2 text-success";
                    statusBox.textContent = "Lien ajouté.";
                    setTimeout(function () { bootstrap.Modal.getInstance($("kbQuickUploadModal")).hide(); }, 700);
                }).catch(function (e) {
                    statusBox.className = "small mt-2 text-danger";
                    statusBox.textContent = "Erreur : " + e.message;
                });
                return;
            }

            // Onglet Fichier(s) — plusieurs fichiers possibles, importés l'un après l'autre
            // (un seul champ "file" attendu côté serveur par appel), avec un compte-rendu global.
            var files = Array.prototype.slice.call($("kbQuickUploadFileInput").files);
            if (!files.length) { statusBox.className = "small mt-2 text-danger"; statusBox.textContent = "Choisissez au moins un fichier."; return; }

            var uploadUrl = "/api/kb/categories/" + kbQuickUploadCategoryId + "/quick-upload" + queryString;
            var uploaded = 0, failed = 0;

            function uploadNext(index) {
                if (index >= files.length) {
                    statusBox.className = failed === 0 ? "small mt-2 text-success" : "small mt-2 text-warning";
                    statusBox.textContent = uploaded + " fichier(s) importé(s)" + (failed ? ", " + failed + " échec(s)" : "") + ".";
                    if (failed === 0) setTimeout(function () { bootstrap.Modal.getInstance($("kbQuickUploadModal")).hide(); }, 700);
                    return;
                }
                statusBox.className = "small mt-2 text-muted";
                statusBox.textContent = "Import " + (index + 1) + " / " + files.length + " en cours…";

                var formData = new FormData();
                formData.append("file", files[index]);
                fetch(uploadUrl, { method: "POST", credentials: "same-origin", body: formData })
                    .then(function (res) {
                        if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                        return res.json();
                    })
                    .then(function () { uploaded++; uploadNext(index + 1); })
                    .catch(function () { failed++; uploadNext(index + 1); });
            }
            uploadNext(0);
        });
    }

    function init() {
        wireArticleForm();
        wireImportArticleForm();
        wireCategoryForm();
        wireFileUpload();
        wireKbQuickUploadModal();
        wireBankMapTab();
        var countriesLoaded = loadCountries();

        window.RccSession.init().then(function (session) {
            currentProfile = session ? session.profile : null;
            $("newArticleBtn").style.display = isQaOrAdmin() ? "" : "none";
            $("newImportArticleBtn").style.display = isQaOrAdmin() ? "" : "none";
            $("newCategoryBtn").style.display = isQaOrAdmin() ? "" : "none";
            renderTeamTabBar();

            var params = new URLSearchParams(window.location.search);
            var deepLinkArticleId = params.get("article") ? Number(params.get("article")) : null;
            var deepLinkCategoryId = params.get("category") ? Number(params.get("category")) : null;

            loadCategories(deepLinkCategoryId);

            if (deepLinkArticleId) {
                openArticleDeepLink(deepLinkArticleId, countriesLoaded);
            }
        });
    }

    /**
     * Lien profond depuis la recherche globale (session.js → /knowledge?article=123).
     * Corrige le bug "An unexpected error occurred" : l'ancien code appelait directement
     * openArticle(id) sans jamais renseigner currentCategoryId, donc l'appel articles?categoryId=
     * partait avec la valeur "null" et le backend rejetait la requête. On récupère maintenant
     * l'article d'abord (categoryId + countryCode) pour poser le bon contexte avant d'ouvrir.
     *
     * Pour la clarté ("de quelle filiale vient ce résultat ?"), si l'article est rattaché à un
     * pays on affiche d'abord la modale pays déjà existante (openCountryModal — même fonction que
     * le sélecteur de filiale plus haut, aucune modale dupliquée) avant de faire glisser l'article
     * par-dessus.
     */
    function openArticleDeepLink(articleId, countriesLoaded) {
        Promise.all([getJson("/api/kb/articles/" + articleId), countriesLoaded])
            .then(function (results) {
                var article = results[0];
                currentCategoryId = article.categoryId;

                if (article.countryCode) {
                    currentCountryCode = article.countryCode;
                    bankMapStale = true;
                    var select = $("countrySelect");
                    if (select) select.value = currentCountryCode;
                    openCountryModal(currentCountryCode); // modale existante — donne le contexte filiale
                    setTimeout(function () {
                        var countryModalInstance = bootstrap.Modal.getInstance($("countryModal"));
                        if (countryModalInstance) countryModalInstance.hide();
                        openArticle(articleId);
                    }, 900);
                } else {
                    openArticle(articleId);
                }
            })
            .catch(function (e) {
                console.error(e);
                alert("Erreur : " + e.message);
            });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
