"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };
    var currentProcedureId = null;
    var currentNodes = [];
    var currentProfile = null; // renseigné une fois la session chargée — voir window.RccSession.init() plus bas

    var getJson = RccApi.getJson;

    var sendJson = RccApi.sendJson;

    var escapeHtml = RccApi.escapeHtml;

    /** Le contenu (créer/modifier une procédure, son parcours, ses pièces jointes) est réservé à la QA — l'admin
     *  s'occupe des réglages et ne voit plus ces boutons (il se les verrait de toute façon refuser côté serveur). */
    function applyQaVisibility(profile) {
        var isQa = profile === "QA";
        var elements = document.querySelectorAll(".qa-only");
        Array.prototype.forEach.call(elements, function (el) {
            el.style.display = isQa ? "" : "none";
        });
    }

    var zonesCache = [];
    var servicesCache = [];

    var currentZoneTeamFilter = ""; // "" = auto (backend décide) — QA/Admin uniquement, voir renderZoneTeamTabBar()

    function loadZones() {
        var url = "/api/procedures/zones" + (currentZoneTeamFilter ? "?team=" + encodeURIComponent(currentZoneTeamFilter) : "");
        return getJson(url).then(function (zones) {
            zonesCache = zones;

            var filter = $("procedureZoneFilter");
            filter.innerHTML = '<option value="">Toutes les zones</option>';
            zones.forEach(function (z) {
                var opt = document.createElement("option");
                opt.value = z.code;
                opt.textContent = z.label;
                filter.appendChild(opt);
            });

            var newZone = $("newProcedureZone");
            newZone.innerHTML = zones.map(function (z) {
                return '<option value="' + escapeHtml(z.code) + '">' + escapeHtml(z.label) + "</option>";
            }).join("");

            var importZone = $("importProcedureZone");
            importZone.innerHTML = newZone.innerHTML;

            renderZoneTeamTabBar();
            renderProcedureZoneGrid();

            return getJson("/api/procedures/services");
        }).then(function (services) {
            servicesCache = services;
            [$("newProcedureService"), $("importProcedureService")].forEach(function (select) {
                services.forEach(function (s) {
                    var opt = document.createElement("option");
                    opt.value = s.code;
                    opt.textContent = s.name;
                    select.appendChild(opt.cloneNode(true));
                });
            });
        });
    }

    /** Barre d'onglets équipe — réservée QA/Admin (un agent classique est déjà auto-filtré côté serveur). */
    function renderZoneTeamTabBar() {
        var bar = $("procedureTeamTabBar");
        if (!bar) return;
        if (!(currentProfile === "QA" || currentProfile === "ADMIN")) { bar.style.display = "none"; return; }
        bar.style.display = "";
        var teams = [
            { code: "", label: "Toutes" },
            { code: "INBOUND_VOICE", label: "Inbound Voix" },
            { code: "INBOUND_MAIL", label: "Inbound Mail / Rafiki" },
            { code: "CIB", label: "CIB" },
            { code: "OUTBOUND", label: "Outbound" }
        ];
        var buttons = teams.map(function (t) {
            var active = currentZoneTeamFilter === t.code ? "btn-primary" : "btn-outline-secondary";
            return '<button type="button" class="btn btn-sm ' + active + ' zone-team-tab-btn" data-team="' + t.code + '">' + t.label + '</button>';
        }).join("");
        bar.innerHTML = '<span class="text-muted small"><i class="bi bi-people-fill"></i> Équipe :</span> ' + buttons;
        Array.prototype.forEach.call(bar.querySelectorAll(".zone-team-tab-btn"), function (btn) {
            btn.addEventListener("click", function () {
                currentZoneTeamFilter = btn.getAttribute("data-team");
                loadZones();
            });
        });
    }

    /** Grille de thématiques cliquables — cliquer une vignette filtre la liste sur cette zone. */
    function renderProcedureZoneGrid() {
        var grid = $("procedureZoneGrid");
        grid.innerHTML = zonesCache.map(function (z) {
            var imageBlock = z.imageUrl
                ? '<img src="' + z.imageUrl + '" alt="" style="width:100%;height:140px;object-fit:cover;">'
                : '<div style="width:100%;height:140px;background:linear-gradient(135deg,#0057B8,#003E8A);display:flex;align-items:center;justify-content:center;">' +
                  '<i class="bi bi-signpost-split text-white" style="font-size:2rem;"></i></div>';
            var uploadBtn = (currentProfile === "ADMIN" || currentProfile === "QA")
                ? '<label class="btn btn-sm btn-outline-secondary position-absolute top-0 end-0 m-1" style="cursor:pointer;" title="Changer l\'image">' +
                  '<i class="bi bi-camera"></i><input type="file" accept="image/*" class="d-none zone-image-input" data-zone-code="' + z.code + '"></label>'
                : "";
            var quickImportBtn = currentProfile === "QA"
                ? '<button type="button" class="btn btn-sm btn-primary position-absolute bottom-0 end-0 m-1 quick-upload-btn" ' +
                  'data-zone-code="' + z.code + '" data-zone-label="' + escapeHtml(z.label) + '" title="Importer un fichier">' +
                  '<i class="bi bi-upload"></i></button>'
                : "";

            return '<div class="col-md-4 col-lg-3">' +
                '<div class="card dashboard-card shadow-sm h-100 zone-card-btn" data-code="' + z.code + '" data-label="' + escapeHtml(z.label) + '" style="cursor:pointer;">' +
                '<div class="position-relative">' + imageBlock + uploadBtn + quickImportBtn + '</div>' +
                '<div class="card-body"><div class="fw-semibold">' + escapeHtml(z.label) + '</div></div>' +
                '</div></div>';
        }).join("");

        Array.prototype.forEach.call(grid.querySelectorAll(".zone-card-btn"), function (card) {
            card.addEventListener("click", function (evt) {
                if (evt.target.closest("label") || evt.target.closest("button")) return;
                $("procedureZoneFilter").value = card.getAttribute("data-code");
                $("proceduresListZoneLabel").textContent = card.getAttribute("data-label");
                new bootstrap.Modal($("proceduresListModal")).show();
                loadProcedures();
            });
        });

        Array.prototype.forEach.call(grid.querySelectorAll(".quick-upload-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                openQuickUploadModal(btn.getAttribute("data-zone-code"), btn.getAttribute("data-zone-label"));
            });
        });

        Array.prototype.forEach.call(grid.querySelectorAll(".zone-image-input"), function (input) {
            input.addEventListener("change", function (evt) {
                evt.stopPropagation();
                var file = this.files[0];
                var code = this.getAttribute("data-zone-code");
                var zone = zonesCache.filter(function (z) { return z.code === code; })[0];
                if (!file || !zone) return;
                window.RccImageEditor.open(file, function (editedBlob) {
                    var formData = new FormData();
                    formData.append("file", editedBlob, "image.jpg");
                    fetch("/api/procedures/zones/" + zone.id + "/image", { method: "POST", credentials: "same-origin", body: formData })
                        .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
                        .then(function () { loadZones(); })
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        });
    }

    var quickUploadZoneCode = null;

    function openQuickUploadModal(zoneCode, zoneLabel) {
        quickUploadZoneCode = zoneCode;
        $("quickUploadZoneLabel").textContent = zoneLabel;
        $("quickUploadFileInput").value = "";
        $("quickUploadStatus").textContent = "";

        var countryOptions = '<option value="">— Toutes filiales —</option>' +
            countriesCache.map(function (c) { return '<option value="' + c.countryCode + '">' + escapeHtml(c.label) + '</option>'; }).join("");
        $("quickUploadCountry").innerHTML = countryOptions;

        var serviceOptions = '<option value="">— Générique (toutes équipes) —</option>' +
            servicesCache.map(function (s) { return '<option value="' + s.code + '">' + escapeHtml(s.name) + '</option>'; }).join("");
        $("quickUploadService").innerHTML = serviceOptions;

        new bootstrap.Modal($("quickUploadModal")).show();
    }

    function wireQuickUploadModal() {
        $("quickUploadSubmitBtn").addEventListener("click", function () {
            var file = $("quickUploadFileInput").files[0];
            var statusBox = $("quickUploadStatus");
            if (!file) { statusBox.className = "small mt-2 text-danger"; statusBox.textContent = "Choisissez un fichier."; return; }

            statusBox.className = "small mt-2 text-muted";
            statusBox.textContent = "Import en cours…";

            var countryCode = $("quickUploadCountry").value;
            var serviceCode = $("quickUploadService").value;
            var url = "/api/procedures/zones/" + encodeURIComponent(quickUploadZoneCode) + "/quick-upload" +
                "?" + [
                    serviceCode ? "serviceCode=" + encodeURIComponent(serviceCode) : "",
                    countryCode ? "countryCode=" + encodeURIComponent(countryCode) : ""
                ].filter(Boolean).join("&");

            var formData = new FormData();
            formData.append("file", file);

            fetch(url, { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function () {
                    statusBox.className = "small mt-2 text-success";
                    statusBox.textContent = "Fichier importé.";
                    loadProcedures();
                    setTimeout(function () { bootstrap.Modal.getInstance($("quickUploadModal")).hide(); }, 700);
                })
                .catch(function (e) {
                    statusBox.className = "small mt-2 text-danger";
                    statusBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    /** Barre de filiales — même principe que Knowledge Base, filtre côté client la liste déjà chargée. */
    var countriesCache = [];

    function loadProcedureCountries() {
        getJson("/api/kb/countries").then(function (countries) {
            countriesCache = countries;
            var options = '<option value="">— Toutes filiales —</option>' +
                countries.map(function (c) { return '<option value="' + c.countryCode + '">' + escapeHtml(c.label) + '</option>'; }).join("");
            $("newProcedureCountry").innerHTML = options;
            $("importProcedureCountry").innerHTML = options;

            var tabBar = $("procedureCountryTabBar");
            var allBtn = '<button type="button" class="btn btn-sm ' + (currentProcedureCountry === "" ? "btn-primary" : "btn-outline-secondary") +
                ' procedure-country-tab-btn" data-code="">Toutes</button>';
            var countryBtns = countries.map(function (c) {
                var active = currentProcedureCountry === c.countryCode ? "btn-primary" : "btn-outline-secondary";
                var flagImg = '<img src="https://flagcdn.com/20x15/' + c.countryCode.toLowerCase() + '.png" ' +
                    'width="20" height="15" alt="" class="me-1" style="vertical-align:-2px;border-radius:2px;">';
                return '<button type="button" class="btn btn-sm ' + active + ' procedure-country-tab-btn" data-code="' + c.countryCode + '">' +
                    flagImg + escapeHtml(c.countryCode) + '</button>';
            }).join("");
            tabBar.innerHTML = '<span class="text-muted small"><i class="bi bi-globe"></i> Filiale :</span> ' + allBtn + countryBtns;

            Array.prototype.forEach.call(tabBar.querySelectorAll(".procedure-country-tab-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    currentProcedureCountry = btn.getAttribute("data-code");
                    Array.prototype.forEach.call(tabBar.querySelectorAll(".procedure-country-tab-btn"), function (b) {
                        b.classList.toggle("btn-primary", b === btn);
                        b.classList.toggle("btn-outline-secondary", b !== btn);
                    });
                    loadProcedures();
                });
            });
        }).catch(function () {});
    }

    var currentProcedureCountry = ""; // "" = toutes filiales

    function loadProcedures() {
        var searchTerm = new URLSearchParams(window.location.search).get("q");
        var url;
        if (searchTerm) {
            url = "/api/procedures/search?q=" + encodeURIComponent(searchTerm);
        } else {
            var zone = $("procedureZoneFilter").value;
            url = zone ? "/api/procedures?zone=" + encodeURIComponent(zone) : "/api/procedures";
        }

        getJson(url).then(function (allProcedures) {
            var procedures = allProcedures;
            if (currentProcedureCountry) {
                procedures = procedures.filter(function (p) { return !p.countryCode || p.countryCode === currentProcedureCountry; });
            }
            if (procedureCardFilter === "workflow") {
                procedures = procedures.filter(function (p) { return p.hasWorkflow; });
            } else if (procedureCardFilter === "favorite") {
                procedures = procedures.filter(function (p) { return p.isFavorite; });
            }

            var table = $("proceduresTable");
            renderSearchBanner(searchTerm, procedures.length);

            if (!procedures.length) {
                var emptyMsg = procedureCardFilter === "workflow" ? "Aucun parcours interactif configuré pour l'instant."
                    : procedureCardFilter === "favorite" ? "Aucun favori pour l'instant."
                    : "Aucune procédure.";
                table.innerHTML = '<tr><td colspan="5" class="text-muted text-center">' + emptyMsg + '</td></tr>';
                return;
            }
            table.innerHTML = procedures.map(function (p) {
                var serviceBadge = p.serviceName
                    ? '<span class="badge bg-primary">' + escapeHtml(p.serviceName) + '</span>'
                    : '<span class="badge bg-light text-dark border">Générique</span>';
                return "<tr>" +
                    "<td>" + escapeHtml(p.zoneCode) + "</td>" +
                    "<td>" + serviceBadge + "</td>" +
                    "<td>" + escapeHtml(p.title) + "</td>" +
                    "<td>" + p.stepCount + "</td>" +
                    '<td><button class="btn btn-sm btn-outline-primary" data-id="' + p.id + '">Voir</button></td>' +
                    "</tr>";
            }).join("");

            Array.prototype.forEach.call(table.querySelectorAll("button"), function (btn) {
                btn.addEventListener("click", function () {
                    var openModal = bootstrap.Modal.getInstance($("proceduresListModal"));
                    if (openModal) openModal.hide();
                    loadProcedureDetail(Number(btn.dataset.id));
                });
            });
        }).catch(function (e) { console.error(e); });
    }

    function renderSearchBanner(searchTerm, resultCount) {
        var existing = document.getElementById("searchResultsBanner");
        if (existing) existing.remove();

        var extendedCard = $("extendedSearchCard");
        if (!searchTerm) {
            extendedCard.style.display = "none";
            return;
        }

        var table = $("proceduresTable");
        var banner = document.createElement("div");
        banner.id = "searchResultsBanner";
        banner.className = "alert alert-info d-flex justify-content-between align-items-center mb-3";
        banner.innerHTML = '<span>' + resultCount + ' résultat(s) pour « ' + escapeHtml(searchTerm) + ' »</span>' +
            '<button type="button" class="btn btn-sm btn-outline-secondary" id="clearSearchBtn">Effacer</button>';
        table.closest(".card-body").insertBefore(banner, table.closest(".table-responsive"));

        document.getElementById("clearSearchBtn").addEventListener("click", function () {
            window.location.href = "/procedures";
        });

        extendedCard.style.display = "";
        searchKnowledgeBase(searchTerm);
        searchCourses(searchTerm);
    }

    function searchKnowledgeBase(searchTerm) {
        var container = $("kbSearchResults");
        container.innerHTML = '<p class="text-muted small">Recherche…</p>';
        getJson("/api/kb/articles/search?q=" + encodeURIComponent(searchTerm)).then(function (articles) {
            if (!articles.length) {
                container.innerHTML = '<p class="text-muted small">Aucun article.</p>';
                return;
            }
            container.innerHTML = articles.map(function (a) {
                return '<a href="/knowledge?category=' + a.categoryId + '&article=' + a.articleId + '" class="d-block small mb-1">' +
                    '<i class="bi bi-file-text"></i> ' + escapeHtml(a.title) +
                    (a.categoryTitle ? ' <span class="text-muted">(' + escapeHtml(a.categoryTitle) + ')</span>' : '') +
                    '</a>';
            }).join("");
        }).catch(function () {
            container.innerHTML = '<p class="text-muted small">Recherche indisponible.</p>';
        });
    }

    /** Minuscules sans accents — « procédure » et « procedure » doivent se retrouver. */
    function normalizeForSearch(text) {
        return (text || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
    }

    function searchCourses(searchTerm) {
        var container = $("courseSearchResults");
        container.innerHTML = '<p class="text-muted small">Recherche…</p>';
        // Tous les mots significatifs doivent apparaître (dans n'importe quel ordre) au lieu
        // de l'expression exacte, sensible aux accents, de l'ancienne version.
        var words = normalizeForSearch(searchTerm).split(/[^a-z0-9]+/).filter(function (w) { return w.length > 2; });
        getJson("/api/courses").then(function (courses) {
            var matches = courses.filter(function (c) {
                var haystack = normalizeForSearch([c.title, c.description, c.category].join(" "));
                return words.length > 0 && words.every(function (w) { return haystack.indexOf(w) !== -1; });
            });
            if (!matches.length) {
                container.innerHTML = '<p class="text-muted small">Aucun cours.</p>';
                return;
            }
            container.innerHTML = matches.map(function (c) {
                return '<a href="/training?openCourseId=' + encodeURIComponent(c.courseId) + '" class="d-block small mb-1">' +
                    '<i class="bi bi-mortarboard"></i> ' + escapeHtml(c.title) + '</a>';
            }).join("");
        }).catch(function () {
            container.innerHTML = '<p class="text-muted small">Recherche indisponible.</p>';
        });
    }

    $("procedureZoneFilter").addEventListener("change", loadProcedures);

    function renderProcedureSlaBadges(p) {
        var box = $("procedureDetailSlaBadges");
        var badges = [];
        if (p.slaDelay) badges.push('<span class="badge bg-primary"><i class="bi bi-clock"></i> ' + escapeHtml(p.slaDelay) + '</span>');
        if (p.level) badges.push('<span class="badge bg-warning text-dark"><i class="bi bi-diagram-2"></i> Niveau ' + escapeHtml(p.level) + '</span>');
        if (p.responsibleTeam) badges.push('<span class="badge bg-secondary"><i class="bi bi-people"></i> ' + escapeHtml(p.responsibleTeam) + '</span>');
        box.innerHTML = badges.join(" ");
    }

    function loadProcedureDetail(id) {
        getJson("/api/procedures/" + id).then(function (p) {
            currentProcedureId = id;
            new bootstrap.Modal($("procedureDetailModal")).show();
            $("procedureDetailTitle").textContent = p.title;
            renderProcedureSlaBadges(p);

            renderAttachments(p.attachments || []);

            if (currentProfile === "QA") {
                $("workflowBuilderCard").style.display = "";
                loadWorkflowNodes();
            } else {
                $("workflowBuilderCard").style.display = "none";
            }

            var fullscreenBtn = $("openFullscreenPlayerBtn");
            fullscreenBtn.classList.remove("d-none");
            fullscreenBtn.href = "/procedures/" + id + "/play";

            playWorkflowFromStart();
        }).catch(function (e) { console.error(e); });
    }

    function renderAttachments(attachments) {
        var list = $("procedureAttachments");
        if (!attachments.length) {
            list.innerHTML = '<li class="list-group-item text-muted">Aucun fichier importé.</li>';
            return;
        }
        list.innerHTML = attachments.map(function (a) {
            var starClass = a.isFavorite ? "bi-star-fill text-warning" : "bi-star";
            return '<li class="list-group-item d-flex justify-content-between align-items-center">' +
                '<span><i class="bi ' + starClass + ' favorite-attachment-btn" data-attachment-id="' + a.id + '" data-favorite="' + a.isFavorite + '" style="cursor:pointer;"></i> ' +
                '<a href="' + escapeHtml(a.storageUrl) + '" target="_blank" rel="noopener">' + escapeHtml(a.fileName) + '</a></span>' +
                '<button class="btn btn-sm btn-outline-danger remove-attachment-btn" data-attachment-id="' + a.id + '">Retirer</button>' +
                '</li>';
        }).join("");

        Array.prototype.forEach.call(list.querySelectorAll(".remove-attachment-btn"), function (btn) {
            btn.addEventListener("click", function () {
                sendJson("/api/procedures/attachments/" + btn.dataset.attachmentId, "DELETE")
                    .then(function () { loadProcedureDetail(currentProcedureId); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
        Array.prototype.forEach.call(list.querySelectorAll(".favorite-attachment-btn"), function (icon) {
            icon.addEventListener("click", function () {
                var isFavorite = icon.getAttribute("data-favorite") === "true";
                var method = isFavorite ? "DELETE" : "POST";
                sendJson("/api/procedures/attachments/" + icon.dataset.attachmentId + "/favorite", method)
                    .then(function () { loadProcedureDetail(currentProcedureId); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    $("addAttachmentBtn").addEventListener("click", function () {
        if (!currentProcedureId) return;
        var file = $("attachmentFileInput").files[0];
        if (!file) { alert("Choisissez un fichier."); return; }

        var formData = new FormData();
        formData.append("file", file);

        fetch("/api/procedures/" + currentProcedureId + "/attachments/upload", { method: "POST", credentials: "same-origin", body: formData })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                return res.json();
            })
            .then(function () {
                $("attachmentFileInput").value = "";
                loadProcedureDetail(currentProcedureId);
            })
            .catch(function (e) { alert("Erreur : " + e.message); });
    });

    $("importProcedureBtn").addEventListener("click", function () {
        var zoneCode = $("importProcedureZone").value;
        var serviceCode = $("importProcedureService").value || "";
        var countryCode = $("importProcedureCountry").value || "";
        var file = $("importProcedureFile").files[0];
        var resultBox = $("importProcedureResult");

        if (!zoneCode || !file) { alert("Zone et fichier sont obligatoires."); return; }

        resultBox.className = "small mt-2 text-muted";
        resultBox.textContent = "Extraction en cours…";

        var formData = new FormData();
        formData.append("file", file);

        var url = "/api/procedures/import-document?zoneCode=" + encodeURIComponent(zoneCode) +
            (serviceCode ? "&serviceCode=" + encodeURIComponent(serviceCode) : "") +
            (countryCode ? "&countryCode=" + encodeURIComponent(countryCode) : "");

        fetch(url, { method: "POST", credentials: "same-origin", body: formData })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                return res.json();
            })
            .then(function (procedures) {
                resultBox.className = "small mt-2 text-success";
                if (procedures.length > 1) {
                    resultBox.textContent = procedures.length + " fiches distinctes détectées et créées : " +
                        procedures.map(function (p) { return "« " + p.title + " »"; }).join(", ") +
                        " — vérifiez et ajustez si besoin.";
                } else {
                    resultBox.textContent = "Créée : « " + procedures[0].title + " » (" + procedures[0].steps.length + " étape(s) extraite(s)) — vérifiez et ajustez si besoin.";
                }
                $("importProcedureFile").value = "";
                loadProcedures();
            })
            .catch(function (e) {
                resultBox.className = "small mt-2 text-danger";
                resultBox.textContent = "Erreur : " + e.message;
            });
    });

    $("createProcedureBtn").addEventListener("click", function () {
        var zoneCode = $("newProcedureZone").value;
        var serviceCode = $("newProcedureService").value || null;
        var countryCode = $("newProcedureCountry").value || null;
        var title = $("newProcedureTitle").value.trim();
        var stepsRaw = $("newProcedureSteps").value.trim();

        if (!zoneCode || !title || !stepsRaw) { alert("Zone, titre et au moins une étape sont obligatoires."); return; }

        var steps = stepsRaw.split("\n").map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });

        sendJson("/api/procedures", "POST", { zoneCode: zoneCode, serviceCode: serviceCode, countryCode: countryCode, title: title, steps: steps })
            .then(function () {
                $("newProcedureTitle").value = "";
                $("newProcedureSteps").value = "";
                loadProcedures();
            })
            .catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===== Lecteur du parcours interactif (agent) — révélation progressive animée =====

    var workflowHistory = []; // [{ question, answerLabel }] — étapes déjà répondues, dans l'ordre

    function renderWorkflowHistoryStep(entry, index) {
        return '<div class="qa-step done">' +
            '<div class="small text-muted">Étape ' + (index + 1) + '</div>' +
            '<div class="qa-step-question">' + escapeHtml(entry.question) + '</div>' +
            '<div class="qa-step-answer"><i class="bi bi-check-circle-fill"></i> ' + escapeHtml(entry.answerLabel) + '</div>' +
            '</div>';
    }

    function renderWorkflowNode(node) {
        var player = $("workflowPlayer");

        var suggestion = node.suggestionLabel
            ? '<div class="alert alert-info py-2 px-3 small mb-3">' +
            '<i class="bi bi-lightbulb"></i> <a href="' + escapeHtml(node.suggestionUrl) + '" target="_blank" rel="noopener">' +
            escapeHtml(node.suggestionLabel) + '</a>' +
            '</div>'
            : "";

        var buttons = (node.options || []).map(function (o) {
            return '<button class="btn btn-outline-primary w-100 mb-2 workflow-option-btn" ' +
                'data-next-node-id="' + (o.nextNodeId || "") + '" data-outcome="' + (o.outcome || "") + '" ' +
                'data-label="' + escapeHtml(o.label) + '">' +
                escapeHtml(o.label) + '</button>';
        }).join("");

        if (!node.options || !node.options.length) {
            buttons = '<p class="text-muted small">Aucune réponse configurée pour cette question.</p>';
        }

        var historyHtml = workflowHistory.map(renderWorkflowHistoryStep).join("");
        var progressPct = Math.min(90, workflowHistory.length * 18); // avance visuelle, le total réel dépend des choix

        player.innerHTML =
            '<div class="mb-3">' +
            '<div class="d-flex justify-content-between small text-muted mb-1"><span>Progression</span><span>Étape ' + (workflowHistory.length + 1) + '</span></div>' +
            '<div class="qa-progress-bar-track"><div class="qa-progress-bar-fill" style="width:' + progressPct + '%;"></div></div>' +
            '</div>' +
            '<div class="qa-step-list mb-2">' + historyHtml + '</div>' +
            '<div class="qa-step active qa-step-enter" id="workflowActiveStep">' +
            '<div class="qa-step-question">' + escapeHtml(node.questionText) + '</div>' +
            suggestion +
            buttons +
            '</div>';

        Array.prototype.forEach.call(player.querySelectorAll(".workflow-option-btn"), function (btn) {
            btn.addEventListener("click", function () {
                var nextNodeId = btn.dataset.nextNodeId;
                var outcome = btn.dataset.outcome;

                // L'étape qu'on vient de répondre rejoint l'historique — animée puis figée au clic suivant.
                workflowHistory.push({ question: node.questionText, answerLabel: btn.dataset.label });

                if (nextNodeId) {
                    getJson("/api/procedures/workflow/nodes/" + nextNodeId)
                        .then(renderWorkflowNode)
                        .catch(function (e) { alert("Erreur : " + e.message); });
                } else if (outcome) {
                    renderWorkflowOutcome(outcome);
                }
            });
        });
    }

    function renderWorkflowOutcome(outcome) {
        var player = $("workflowPlayer");
        var badge = outcome === "FIDELISATION"
            ? '<span class="badge text-bg-success fs-6">Fidélisation — dossier réglé</span>'
            : '<span class="badge text-bg-secondary fs-6">Clôturé</span>';

        var historyHtml = workflowHistory.map(renderWorkflowHistoryStep).join("");

        player.innerHTML =
            '<div class="qa-step-list mb-2">' + historyHtml + '</div>' +
            '<div class="text-center py-3 qa-step-enter">' + badge + '<br>' +
            '<button class="btn btn-outline-primary btn-sm mt-3" id="restartWorkflowBtn">Recommencer le parcours</button>' +
            '</div>';

        $("restartWorkflowBtn").addEventListener("click", playWorkflowFromStart);
    }

    function playWorkflowFromStart() {
        var player = $("workflowPlayer");
        workflowHistory = [];
        player.innerHTML = '<p class="text-muted small">Chargement du parcours...</p>';

        getJson("/api/procedures/" + currentProcedureId + "/workflow/start")
            .then(renderWorkflowNode)
            .catch(function () {
                player.innerHTML = '<p class="text-muted">Aucun parcours interactif configuré pour cette procédure pour l\'instant.</p>';
            });
    }

    // ===== Parcours interactif (construction QA/admin) =====

    function loadWorkflowNodes() {
        getJson("/api/procedures/" + currentProcedureId + "/workflow").then(function (nodes) {
            currentNodes = nodes;
            renderWorkflowNodesList(nodes);
            renderNodeSelects(nodes);
        }).catch(function (e) { console.error(e); });
    }

    function outcomeLabel(outcome) {
        if (outcome === "FIDELISATION") return '<span class="badge text-bg-success">Fin : Fidélisation</span>';
        if (outcome === "CLOTURE") return '<span class="badge text-bg-secondary">Fin : Clôture</span>';
        return "";
    }

    function nodeQuestionById(nodeId) {
        var found = currentNodes.filter(function (n) { return n.nodeId === nodeId; })[0];
        return found ? found.questionText : ("Question #" + nodeId);
    }

    function renderWorkflowNodesList(nodes) {
        var container = $("workflowNodesList");
        if (!nodes.length) {
            container.innerHTML = '<p class="text-muted">Aucune question créée pour l\'instant.</p>';
            return;
        }
        container.innerHTML = nodes.map(function (n) {
            var startBadge = n.isStart ? '<span class="badge text-bg-primary ms-2">Départ</span>' : "";
            var suggestion = n.suggestionLabel
                ? '<div class="small text-muted">Suggestion : <a href="' + escapeHtml(n.suggestionUrl) + '" target="_blank">' + escapeHtml(n.suggestionLabel) + '</a></div>'
                : "";
            var options = (n.options || []).map(function (o) {
                var dest = o.nextNodeId ? ('→ "' + escapeHtml(nodeQuestionById(o.nextNodeId)) + '"') : outcomeLabel(o.outcome);
                return '<li>' + escapeHtml(o.label) + ' ' + dest + '</li>';
            }).join("");
            return '<div class="border rounded p-2 mb-2">' +
                '<strong>#' + n.nodeId + '</strong> ' + escapeHtml(n.questionText) + startBadge +
                suggestion +
                '<ul class="mb-0 small">' + (options || '<li class="text-muted">Aucune réponse encore</li>') + '</ul>' +
                '</div>';
        }).join("");
    }

    function renderNodeSelects(nodes) {
        var optionsHtml = nodes.map(function (n) {
            return '<option value="' + n.nodeId + '">#' + n.nodeId + ' — ' + escapeHtml(n.questionText) + '</option>';
        }).join("");

        $("optionNodeSelect").innerHTML = optionsHtml || '<option value="">Aucune question créée</option>';

        $("newOptionNextNode").innerHTML =
            '<option value="">— Aucune (choisir une fin) —</option>' + optionsHtml;
    }

    $("createNodeBtn").addEventListener("click", function () {
        if (!currentProcedureId) return;
        var questionText = $("newNodeQuestion").value.trim();
        if (!questionText) { alert("Le texte de la question est obligatoire."); return; }

        sendJson("/api/procedures/" + currentProcedureId + "/workflow/nodes", "POST", {
            questionText: questionText,
            isStart: $("newNodeIsStart").checked,
            suggestionLabel: $("newNodeSuggestionLabel").value.trim() || null,
            suggestionUrl: $("newNodeSuggestionUrl").value.trim() || null
        }).then(function () {
            $("newNodeQuestion").value = "";
            $("newNodeIsStart").checked = false;
            $("newNodeSuggestionLabel").value = "";
            $("newNodeSuggestionUrl").value = "";
            loadWorkflowNodes();
            playWorkflowFromStart();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    $("createOptionBtn").addEventListener("click", function () {
        var nodeId = Number($("optionNodeSelect").value);
        var label = $("newOptionLabel").value.trim();
        var nextNodeId = $("newOptionNextNode").value ? Number($("newOptionNextNode").value) : null;
        var outcome = $("newOptionOutcome").value || null;

        if (!nodeId) { alert("Créez d'abord au moins une question."); return; }
        if (!label) { alert("Le libellé de la réponse est obligatoire."); return; }
        if (!nextNodeId && !outcome) { alert("Choisissez soit une question suivante, soit une fin."); return; }
        if (nextNodeId && outcome) { alert("Choisissez soit une question suivante, soit une fin — pas les deux."); return; }

        sendJson("/api/procedures/workflow/options", "POST", {
            nodeId: nodeId, label: label, nextNodeId: nextNodeId, outcome: outcome
        }).then(function () {
            $("newOptionLabel").value = "";
            $("newOptionNextNode").value = "";
            $("newOptionOutcome").value = "";
            loadWorkflowNodes();
            playWorkflowFromStart();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    $("createZoneBtn").addEventListener("click", function () {
        var code = $("newZoneCode").value.trim();
        var label = $("newZoneLabel").value.trim();
        var team = $("newZoneTeam").value || null;
        if (!code || !label) { alert("Code et libellé sont obligatoires."); return; }

        sendJson("/api/procedures/zones", "POST", { code: code, label: label, team: team })
            .then(function () {
                $("newZoneCode").value = "";
                $("newZoneLabel").value = "";
                $("newZoneTeam").value = "";
                loadZones();
            })
            .catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===== Init =====

    var procedureCardFilter = null; // null | "workflow" | "favorite"

    function loadProcedureStats() {
        getJson("/api/procedures/dashboard").then(function (stats) {
            var cards = [
                { key: null, label: "Procédures", value: stats.procedures, icon: "bi-file-text", color: "#0057B8" },
                { key: "category", label: "Catégories", value: stats.categories, icon: "bi-folder2", color: "#0f9d6c" },
                { key: "workflow", label: "Parcours interactifs", value: stats.workflows, icon: "bi-signpost-split", color: "#fd7e14" },
                { key: "favorite", label: "Favoris", value: stats.favorites, icon: "bi-star-fill", color: "#7b2ff7" }
            ];
            document.getElementById("procedureStats").innerHTML = cards.map(function (c) {
                var active = procedureCardFilter === c.key ? "border border-2" : "";
                return '<div class="col-6 col-lg-3">' +
                    '<div class="card dashboard-card shadow-sm h-100 procedure-stat-card ' + active + '" data-key="' + (c.key || "") + '" style="cursor:pointer;border-color:' + c.color + ';">' +
                    '<div class="card-body text-center">' +
                    '<i class="bi ' + c.icon + ' fs-3" style="color:' + c.color + ';"></i>' +
                    '<div class="fs-4 fw-bold mt-2">' + c.value + '</div>' +
                    '<div class="text-muted small">' + c.label + '</div>' +
                    '</div></div></div>';
            }).join("");

            Array.prototype.forEach.call(document.querySelectorAll(".procedure-stat-card"), function (card) {
                card.addEventListener("click", function () {
                    var key = card.getAttribute("data-key") || null;
                    if (key === "category") {
                        document.getElementById("procedureZoneGrid").scrollIntoView({ behavior: "smooth" });
                        return;
                    }
                    procedureCardFilter = (procedureCardFilter === key) ? null : key; // re-cliquer retire le filtre
                    loadProcedureStats();
                    if (procedureCardFilter === null) return; // filtre retiré : rien à ouvrir

                    var labels = { workflow: "Parcours interactifs", favorite: "Favoris" };
                    $("proceduresListZoneLabel").textContent = labels[procedureCardFilter] || "";
                    loadProcedures();
                    new bootstrap.Modal($("proceduresListModal")).show();
                });
            });
        }).catch(function (e) { console.error(e); });
    }

    window.RccSession.init().then(function (session) {
        if (session) {
            currentProfile = session.profile;
            applyQaVisibility(session.profile);
            renderZoneTeamTabBar();
            if (zonesCache.length) renderProcedureZoneGrid();
        }
    });

    var unInstantTimerInterval = null;
    var unInstantStartedAt = null;

    function wireUnInstant() {
        var modalEl = $("unInstantModal");
        var modal = new bootstrap.Modal(modalEl);

        $("unInstantBtn").addEventListener("click", function () {
            unInstantStartedAt = Date.now();
            $("unInstantTimer").textContent = "00:00";
            unInstantTimerInterval = setInterval(function () {
                var elapsed = Math.floor((Date.now() - unInstantStartedAt) / 1000);
                var m = String(Math.floor(elapsed / 60)).padStart(2, "0");
                var s = String(elapsed % 60).padStart(2, "0");
                $("unInstantTimer").textContent = m + ":" + s;
            }, 1000);
            modal.show();
        });

        modalEl.addEventListener("hidden.bs.modal", function () {
            clearInterval(unInstantTimerInterval);
        });

        $("unInstantResumeBtn").addEventListener("click", function () {
            modal.hide();
        });

        $("unInstantProcedureBtn").addEventListener("click", function (evt) {
            evt.preventDefault();
            modal.hide();
            document.getElementById("procedureZoneGrid").scrollIntoView({ behavior: "smooth" });
        });

        $("unInstantRafBtn").addEventListener("click", function () {
            modal.hide();
            var fab = document.getElementById("ralphFab");
            var panel = document.getElementById("ralphPanel");
            if (fab && panel) {
                fab.classList.add("d-none");
                panel.classList.remove("d-none");
                document.getElementById("ralphFabInput").focus();
            }
        });
    }

    // ===== Apparence du site (photo de connexion) — même panneau que côté Administration, ouvert aux QA ici =====

    function wireSiteAppearance() {
        var uploadBtn = document.getElementById("loginHeroUploadBtn");
        if (!uploadBtn) return;

        getJson("/api/site-settings").then(function (settings) {
            var opacity = settings["login.hero.opacity"] || "0.14";
            document.getElementById("loginHeroOpacityRange").value = opacity;
            document.getElementById("loginHeroOpacityValue").textContent = opacity;
            var url = settings["login.hero.imageUrl"];
            var preview = document.getElementById("loginHeroPreview");
            if (url) { preview.src = url; preview.style.display = ""; }
        }).catch(function () { /* pas QA/Admin ou pas encore de réglage — la carte reste utilisable pour en créer un */ });

        uploadBtn.addEventListener("click", function () {
            var fileInput = document.getElementById("loginHeroFile");
            var resultBox = document.getElementById("loginHeroResult");
            var file = fileInput.files[0];
            if (!file) { resultBox.innerHTML = '<span class="text-danger">Choisissez un fichier.</span>'; return; }

            var formData = new FormData();
            formData.append("file", file);
            fetch("/api/site-settings/login.hero.imageUrl/image", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (result) {
                    resultBox.innerHTML = '<span class="text-success">Photo mise à jour — visible dès le prochain chargement de la page de connexion.</span>';
                    var preview = document.getElementById("loginHeroPreview");
                    preview.src = result.value;
                    preview.style.display = "";
                    fileInput.value = "";
                })
                .catch(function (e) { resultBox.innerHTML = '<span class="text-danger">Erreur : ' + e.message + '</span>'; });
        });

        document.getElementById("loginHeroOpacityRange").addEventListener("input", function () {
            document.getElementById("loginHeroOpacityValue").textContent = this.value;
        });
        document.getElementById("loginHeroOpacityRange").addEventListener("change", function () {
            fetch("/api/site-settings/login.hero.opacity", {
                method: "POST", credentials: "same-origin",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ value: this.value })
            }).catch(function (e) { console.error(e); });
        });
    }

    wireUnInstant();
    wireQuickUploadModal();
    wireSiteAppearance();
    loadZones().then(loadProcedures);
    loadProcedureCountries();
    loadProcedureStats();

    // Lien direct depuis la recherche globale (session.js) — ouvre la procédure exacte par
    // ID sans obliger l'agent à la sélectionner à nouveau dans les résultats.
    var openProcedureId = new URLSearchParams(window.location.search).get("openProcedureId");
    if (openProcedureId) {
        setTimeout(function () { loadProcedureDetail(Number(openProcedureId)); }, 400);
    }
})();