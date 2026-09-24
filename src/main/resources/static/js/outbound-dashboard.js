"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var salesCache = [];
    var rdvCache = [];
    var journeysCache = [];
    var isJourneyAdmin = false;
    var saleModal, rdvModal, journeyModal, journeyAdminModal, journeyEditModal, campaignCallModal;
    var suggestionModal, campaignManageModal, campaignReportModal, contactDetailModal;
    var reportContactsCache = [];
    var currentReportCampaignId = null;
    var currentReportCampaignFields = [];
    var pendingCallContactId = null; // contact en cours d'appel, si le RDV est ouvert depuis "Mes appels" (bouton jaune)
    var pendingCallAnswers = null; // réponses aux champs dynamiques collectées avant l'ouverture de la modale RDV (onglet Campagne)
    var journeyStepIndex = 0;
    var currentJourney = null;
    var editingJourneyId = null;
    var editingSteps = [];

    // État de l'onglet Campagne — voir section dédiée plus bas.
    var campaignsCache = [];
    var currentCampaign = null;
    var currentCampaignQueue = []; // contacts de la campagne, PENDING d'abord (défilement séquentiel)
    var currentCallIndex = 0;

    // ===================== ONGLETS =====================

    function switchTab(tab) {
        ["Campaign", "Calls", "Sales", "Rdv", "Journeys"].forEach(function (t) {
            $("obTab" + t + "Btn").classList.toggle("active", t.toLowerCase() === tab);
            $("obPane" + t).style.display = t.toLowerCase() === tab ? "" : "none";
        });
        if (tab === "journeys") renderJourneysGrid();
        if (tab === "calls") loadMyCalls();
        if (tab === "campaign") loadCampaignGrid();
    }

    // ===================== STATS =====================

    function updateStats() {
        var now = new Date();
        var monthKey = now.getFullYear() + "-" + String(now.getMonth() + 1).padStart(2, "0");

        var salesThisMonth = salesCache.filter(function (s) { return (s.saleDate || "").indexOf(monthKey) === 0 && s.status !== "CANCELLED"; });
        $("obStatSalesMonth").textContent = salesThisMonth.length;
        var totalAmount = salesThisMonth.reduce(function (sum, s) { return sum + (s.amount || 0); }, 0);
        $("obStatAmountMonth").textContent = totalAmount ? totalAmount.toLocaleString("fr-FR") + " F" : "0 F";

        var upcoming = rdvCache.filter(function (a) { return a.status === "PLANNED" && new Date(a.scheduledAt) >= now; });
        $("obStatUpcomingRdv").textContent = upcoming.length;

        var noShow = rdvCache.filter(function (a) { return a.status === "NO_SHOW" && (a.scheduledAt || "").indexOf(monthKey) === 0; });
        $("obStatNoShow").textContent = noShow.length;
    }

    // ===================== CAMPAGNE (onglet dédié Service Digital / Télévente) =====================

    var CALL_STATUS_COLORS = { PENDING: "#dee2e6", GREEN: "#00A651", RED: "#dc3545", YELLOW: "#F5A623" };
    var CALL_STATUS_LABELS = { PENDING: "À appeler", GREEN: "Interaction", RED: "Pas de réponse", YELLOW: "RDV pris" };

    function loadCampaignGrid() {
        $("obCampaignGridView").style.display = "";
        $("obCampaignDetailView").style.display = "none";
        getJson("/api/campaigns/active-for-me").then(function (campaigns) {
            campaignsCache = campaigns || [];
            renderCampaignGrid();
        }).catch(function (e) {
            $("obCampaignGrid").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    /** Bibliothèque de photos de couverture prédéfinies (façon Microsoft Forms, voir les
     *  captures fournies) — illustrations LOCALES (/images/covers), affichées même sur un
     *  serveur sans accès Internet.
     *  Le Team Leader clique une miniature à la création ; coverImageUrl reste éditable en base
     *  pour une URL externe si besoin, cette liste n'est qu'un raccourci pratique. */
    var CAMPAIGN_COVER_LIBRARY = [
        { label: "Bureau chaleureux", url: "/images/covers/bureau.svg" },
        { label: "Montgolfières", url: "/images/covers/montgolfieres.svg" },
        { label: "Café / discussion", url: "/images/covers/cafe.svg" },
        { label: "Réunion d'équipe", url: "/images/covers/equipe.svg" },
        { label: "Finances / épargne", url: "/images/covers/epargne.svg" },
        { label: "Carte bancaire", url: "/images/covers/carte.svg" },
        { label: "Famille / scolaire", url: "/images/covers/famille.svg" },
        { label: "Nature apaisante", url: "/images/covers/nature.svg" }
    ];

    var selectedCoverUrl = null;

    function renderCoverGallery() {
        var gallery = $("obCmCoverGallery");
        gallery.innerHTML = '<div class="ob-cover-option ob-cover-none' + (!selectedCoverUrl ? " selected" : "") + '" data-cover="">Sans photo<br>(dégradé de couleur)</div>' +
            CAMPAIGN_COVER_LIBRARY.map(function (c) {
                return '<div class="ob-cover-option' + (selectedCoverUrl === c.url ? " selected" : "") + '" data-cover="' + escapeHtml(c.url) + '" ' +
                    'style="background-image:url(\'' + escapeHtml(c.url) + '\')" title="' + escapeHtml(c.label) + '"></div>';
            }).join("");

        Array.prototype.forEach.call(gallery.querySelectorAll("[data-cover]"), function (opt) {
            opt.addEventListener("click", function () {
                selectedCoverUrl = opt.getAttribute("data-cover") || null;
                $("obCmCoverImageUrl").value = selectedCoverUrl || "";
                renderCoverGallery();
            });
        });
    }

    function renderCampaignGrid() {
        var grid = $("obCampaignGrid");
        if (!campaignsCache.length) {
            grid.innerHTML = '<p class="text-muted text-center">Aucune campagne active ne vous est ouverte pour l\'instant.</p>';
            return;
        }
        grid.innerHTML = campaignsCache.map(function (c) {
            var hasPhoto = !!c.coverImageUrl;
            var tileStyle = hasPhoto
                ? 'background-image:url(\'' + escapeHtml(c.coverImageUrl) + '\')'
                : 'background:linear-gradient(135deg,' + (c.colorFrom || "#0057B8") + ',' + (c.colorTo || "#00A651") + ')';
            var stats = '<div class="ob-campaign-tile-stats">' +
                '<div><b>' + c.totalContacts + '</b>contact(s)</div>' +
                '<div><b>' + (c.totalContacts - c.callsMade) + '</b>à appeler</div>' +
                '<div><b>' + c.appointmentsTaken + '</b>RDV pris</div>' +
                '</div>';
            var questionCount = (c.fields ? c.fields.length : 0);
            var formBadge = '<span class="ob-tile-form-badge"><i class="bi bi-ui-checks-grid"></i> ' + questionCount + ' question' + (questionCount > 1 ? "s" : "") + '</span>';

            var body = hasPhoto
                ? '<div class="ob-tile-photo-caption">' + formBadge + '<h5>' + escapeHtml(c.name) + '</h5>' +
                    '<p>' + escapeHtml(c.description || "") + '</p>' + stats + '</div>'
                : '<div class="ob-tile-icon"><i class="bi ' + (c.iconClass || "bi-megaphone-fill") + '"></i></div>' +
                    formBadge + '<h5>' + escapeHtml(c.name) + '</h5>' +
                    '<p>' + escapeHtml(c.description || "") + '</p>' + stats;

            return '<div class="col-md-6 col-xl-4">' +
                '<div class="card ob-campaign-tile' + (hasPhoto ? " ob-tile-photo" : "") + '" style="' + tileStyle + ';" data-campaign-id="' + c.campaignId + '">' +
                    '<div class="card-body">' + body + '</div>' +
                '</div></div>';
        }).join("");
        Array.prototype.forEach.call(grid.querySelectorAll("[data-campaign-id]"), function (tile) {
            tile.addEventListener("click", function () { openCampaignDetail(Number(tile.getAttribute("data-campaign-id"))); });
        });
    }

    function openCampaignDetail(campaignId) {
        currentCampaign = campaignsCache.find(function (c) { return c.campaignId === campaignId; });
        if (!currentCampaign) return;
        $("obCampaignGridView").style.display = "none";
        $("obCampaignDetailView").style.display = "";
        var banner = $("obCampaignDetailBanner");
        if (currentCampaign.coverImageUrl) {
            banner.style.background = "linear-gradient(0deg, rgba(0,0,0,.45), rgba(0,0,0,.15)), url('" + currentCampaign.coverImageUrl + "') center/cover";
        } else {
            banner.style.background = "linear-gradient(135deg," + (currentCampaign.colorFrom || "#0057B8") + "," + (currentCampaign.colorTo || "#00A651") + ")";
        }
        $("obCampaignDetailName").innerHTML = '<i class="bi ' + (currentCampaign.iconClass || "bi-megaphone-fill") + '"></i> ' + escapeHtml(currentCampaign.name);
        $("obCampaignDetailDesc").textContent = currentCampaign.description || "";
        loadCampaignContacts();
    }

    function loadCampaignContacts() {
        getJson("/api/campaigns/" + currentCampaign.campaignId + "/contacts/me").then(function (contacts) {
            renderCampaignDetailStats(contacts);
            renderCampaignContactList(contacts);
        }).catch(function (e) {
            $("obCampaignContactList").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function renderCampaignDetailStats(contacts) {
        var counts = { PENDING: 0, GREEN: 0, RED: 0, YELLOW: 0 };
        contacts.forEach(function (c) { counts[c.callStatus] = (counts[c.callStatus] || 0) + 1; });
        var cards = [
            ["PENDING", "À appeler"], ["GREEN", "Interaction"], ["RED", "Pas de réponse"], ["YELLOW", "RDV pris"]
        ];
        $("obCampaignDetailStats").innerHTML = cards.map(function (pair) {
            return '<div class="col-6 col-lg-3"><div class="card ob-stat-card h-100"><div class="card-body">' +
                '<div class="ob-stat-value" style="color:' + CALL_STATUS_COLORS[pair[0]] + ';">' + counts[pair[0]] + '</div>' +
                '<div class="text-muted small">' + pair[1] + '</div>' +
            '</div></div></div>';
        }).join("");
    }

    function renderCampaignContactList(contacts) {
        var container = $("obCampaignContactList");
        if (!contacts.length) {
            container.innerHTML = '<p class="text-muted text-center">Aucun contact ne vous a été assigné pour cette campagne.</p>';
            return;
        }
        container.innerHTML = contacts.map(function (c, index) {
            var color = CALL_STATUS_COLORS[c.callStatus];
            return '<div class="border rounded p-3 mb-2 ob-campaign-contact-row" style="border-left:5px solid ' + color + ' !important;cursor:pointer;" data-contact-id="' + c.contactId + '">' +
                '<div class="d-flex justify-content-between align-items-center flex-wrap gap-2">' +
                    '<div><strong>' + (index + 1) + ' — ' + escapeHtml(c.clientName) + '</strong>' +
                        (c.clientPhone ? ' — ' + escapeHtml(c.clientPhone) : "") +
                        (c.notes ? '<div class="small text-muted mt-1"><i class="bi bi-chat-left-text"></i> ' + escapeHtml(c.notes.length > 80 ? c.notes.slice(0, 80) + "…" : c.notes) + '</div>' : "") +
                    '</div>' +
                    '<span class="badge" style="background:' + color + ';color:#fff;">' + CALL_STATUS_LABELS[c.callStatus] + '</span>' +
                '</div></div>';
        }).join("");
        Array.prototype.forEach.call(container.querySelectorAll("[data-contact-id]"), function (row) {
            row.addEventListener("click", function () { openCallFlowOnContact(Number(row.getAttribute("data-contact-id"))); });
        });
    }

    /** Ouvre la modale d'appel directement sur UN contact précis (clic depuis la liste) — même
     *  modale que "Commencer les appels" (défilement séquentiel), juste positionnée sur ce
     *  contact au lieu de partir du début de la file. L'agent peut ensuite continuer à défiler
     *  vers les suivants normalement (Précédent / disposition) depuis ce point. */
    function openCallFlowOnContact(contactId) {
        getJson("/api/campaigns/" + currentCampaign.campaignId + "/contacts/me").then(function (contacts) {
            currentCampaignQueue = buildCallQueue(contacts);
            var idx = currentCampaignQueue.findIndex(function (c) { return c.contactId === contactId; });
            currentCallIndex = idx >= 0 ? idx : 0;
            $("obCallCampaignName").textContent = currentCampaign.name;
            campaignCallModal.show();
            renderCurrentCallContact();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    /** Construit la file d'appel : contacts PENDING d'abord (nom), puis le reste — pas l'ordre
     *  alphabétique de statut renvoyé par l'API (qui met GREEN avant PENDING). */
    function buildCallQueue(contacts) {
        var pending = contacts.filter(function (c) { return c.callStatus === "PENDING"; });
        var others = contacts.filter(function (c) { return c.callStatus !== "PENDING"; });
        return pending.concat(others);
    }

    function startCallFlow() {
        getJson("/api/campaigns/" + currentCampaign.campaignId + "/contacts/me").then(function (contacts) {
            currentCampaignQueue = buildCallQueue(contacts);
            currentCallIndex = 0;
            $("obCallCampaignName").textContent = currentCampaign.name;
            campaignCallModal.show();
            renderCurrentCallContact();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    function renderCurrentCallContact() {
        var total = currentCampaignQueue.length;
        $("obCallPrevBtn").disabled = currentCallIndex <= 0;

        if (!total || currentCallIndex >= total) {
            $("obCallProgress").textContent = "";
            $("obCallClientName").textContent = "";
            $("obCallClientPhone").textContent = "";
            $("obCallClientAccount").textContent = "";
            $("obCallExtraData").innerHTML = "";
            $("obCallDynamicFields").innerHTML = "";
            $("obCallComment").value = "";
            $("obCallComment").parentElement.style.display = "none";
            document.querySelector(".ob-call-disposition-row").style.display = "none";
            $("obCallDone").style.display = "";
            return;
        }

        $("obCallDone").style.display = "none";
        $("obCallComment").parentElement.style.display = "";
        document.querySelector(".ob-call-disposition-row").style.display = "";

        var contact = currentCampaignQueue[currentCallIndex];
        $("obCallProgress").textContent = "Contact " + (currentCallIndex + 1) + " / " + total;
        $("obCallClientName").textContent = contact.clientName;
        $("obCallClientPhone").innerHTML = contact.clientPhone ? '<i class="bi bi-telephone"></i> ' + escapeHtml(contact.clientPhone) : "";
        $("obCallClientAccount").innerHTML = contact.maskedAccountNumber ? '<i class="bi bi-shield-lock"></i> Compte : ' + escapeHtml(contact.maskedAccountNumber) : "";
        $("obCallComment").value = contact.notes || "";

        renderExtraDataInto("obCallExtraData", contact);
        renderDynamicFields(contact);
    }

    /** Affiche en lecture seule toute colonne du fichier importé sans correspondance connue
     *  (CIF, région, segment, branche...) — informations de référence du dossier, jamais
     *  modifiables depuis l'appel (contrairement aux champs dynamiques de la campagne). */
    function renderExtraDataInto(containerId, contact) {
        var extra = contact.extraData || {};
        var container = $(containerId);
        var keys = Object.keys(extra);
        if (!keys.length) { container.innerHTML = ""; return; }

        container.innerHTML = '<div class="small text-muted mb-1"><i class="bi bi-info-circle"></i> Informations du dossier</div>' +
            '<div class="border rounded p-2 small" style="background:#f8f9fc;">' +
            keys.map(function (k) {
                return '<div class="d-flex justify-content-between gap-2 py-1 border-bottom">' +
                    '<span class="text-muted">' + escapeHtml(k) + '</span>' +
                    '<span class="text-end">' + escapeHtml(extra[k]) + '</span>' +
                    '</div>';
            }).join("") +
            '</div>';
    }

    /** Au-delà de ce nombre d'options, une question à choix (SELECT/RADIO) s'affiche comme un
     *  bouton "Choisir" qui ouvre la modale de sélection plutôt que la liste complète en ligne —
     *  pratique pour les longues listes de motifs (voir capture "si non pourquoi ?", 16 options). */
    var SUGGESTION_MODAL_THRESHOLD = 5;

    /** Rend les questions du modèle de campagne (Campaign.fields) — pré-remplies si le contact a déjà des réponses. */
    function renderDynamicFields(contact) {
        var fields = (currentCampaign && currentCampaign.fields) || [];
        var answers = contact.answers || {};
        var container = $("obCallDynamicFields");

        container.innerHTML = fields.map(function (f) {
            var value = answers[f.id] || "";
            var requiredMark = f.required ? ' <span class="text-danger">*</span>' : "";
            var inputHtml;
            var isChoiceField = (f.type === "SELECT" || f.type === "RADIO") && (f.options || []).length > SUGGESTION_MODAL_THRESHOLD;

            if (isChoiceField) {
                inputHtml = '<button type="button" class="btn btn-outline-primary ob-suggestion-trigger" data-field-id="' + escapeHtml(f.id) + '" data-suggestion-btn="1">' +
                    '<span class="' + (value ? "value" : "placeholder") + '">' + (value ? escapeHtml(value) : "Choisir une réponse…") + '</span>' +
                    '<i class="bi bi-chevron-right"></i></button>' +
                    '<input type="hidden" data-field-id="' + escapeHtml(f.id) + '" data-field-hidden="1" value="' + escapeHtml(value) + '">';
            } else if (f.type === "TEXTAREA") {
                inputHtml = '<textarea class="form-control" data-field-id="' + escapeHtml(f.id) + '" rows="2">' + escapeHtml(value) + '</textarea>';
            } else if (f.type === "SELECT") {
                inputHtml = '<select class="form-select" data-field-id="' + escapeHtml(f.id) + '">' +
                    '<option value="">Sélectionnez…</option>' +
                    (f.options || []).map(function (o) {
                        return '<option value="' + escapeHtml(o) + '" ' + (o === value ? "selected" : "") + '>' + escapeHtml(o) + '</option>';
                    }).join("") + '</select>';
            } else if (f.type === "RADIO") {
                inputHtml = '<div class="d-flex flex-wrap gap-3">' + (f.options || []).map(function (o) {
                    var id = "field_" + f.id + "_" + o.replace(/\W+/g, "_");
                    return '<div class="form-check">' +
                        '<input class="form-check-input" type="radio" name="field_' + escapeHtml(f.id) + '" id="' + id + '" value="' + escapeHtml(o) + '" data-field-id="' + escapeHtml(f.id) + '" ' + (o === value ? "checked" : "") + '>' +
                        '<label class="form-check-label small" for="' + id + '">' + escapeHtml(o) + '</label></div>';
                }).join("") + '</div>';
            } else if (f.type === "DATE") {
                inputHtml = '<input type="date" class="form-control" data-field-id="' + escapeHtml(f.id) + '" value="' + escapeHtml(value) + '">';
            } else if (f.type === "TIME") {
                inputHtml = '<input type="time" class="form-control" data-field-id="' + escapeHtml(f.id) + '" value="' + escapeHtml(value) + '">';
            } else if (f.type === "LINK") {
                inputHtml = '<input type="url" class="form-control" data-field-id="' + escapeHtml(f.id) + '" placeholder="https://…" value="' + escapeHtml(value) + '">';
            } else {
                inputHtml = '<input type="text" class="form-control" data-field-id="' + escapeHtml(f.id) + '" value="' + escapeHtml(value) + '">';
            }
            return '<div class="ob-call-dynamic-field"><label>' + escapeHtml(f.label) + requiredMark + '</label>' + inputHtml + '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll('[data-suggestion-btn]'), function (btn) {
            btn.addEventListener("click", function () {
                var fieldId = btn.getAttribute("data-field-id");
                var field = fields.find(function (f) { return f.id === fieldId; });
                if (field) openSuggestionModal(field, btn);
            });
        });
    }

    /** Modale de sélection pour une question à choix longue — clic sur une option = choix
     *  immédiat (pas de bouton "Valider" séparé, cohérent avec le geste rapide attendu en plein
     *  appel client). Recherche texte pour filtrer si la liste est très longue. */
    function openSuggestionModal(field, triggerBtn) {
        $("obSuggestionModalTitle").textContent = field.label;
        $("obSuggestionSearch").value = "";
        var hiddenInput = $("obCallDynamicFields").querySelector('[data-field-id="' + field.id + '"][data-field-hidden]');
        var currentValue = hiddenInput ? hiddenInput.value : "";

        function renderOptions(filter) {
            var options = (field.options || []).filter(function (o) {
                return !filter || o.toLowerCase().indexOf(filter.toLowerCase()) !== -1;
            });
            $("obSuggestionList").innerHTML = options.length ? options.map(function (o) {
                return '<button type="button" class="ob-suggestion-option' + (o === currentValue ? " selected" : "") + '" data-value="' + escapeHtml(o) + '">' +
                    (o === currentValue ? '<i class="bi bi-check-circle-fill text-success me-1"></i>' : "") + escapeHtml(o) + '</button>';
            }).join("") : '<p class="text-muted text-center small mb-0">Aucun résultat.</p>';

            Array.prototype.forEach.call($("obSuggestionList").querySelectorAll(".ob-suggestion-option"), function (opt) {
                opt.addEventListener("click", function () {
                    var chosen = opt.getAttribute("data-value");
                    if (hiddenInput) hiddenInput.value = chosen;
                    triggerBtn.querySelector("span").textContent = chosen;
                    triggerBtn.querySelector("span").className = "value";
                    suggestionModal.hide();
                });
            });
        }

        renderOptions("");
        $("obSuggestionSearch").oninput = function () { renderOptions(this.value); };
        suggestionModal.show();
    }

    function collectDynamicAnswers() {
        var answers = {};
        Array.prototype.forEach.call($("obCallDynamicFields").querySelectorAll("[data-field-id]"), function (el) {
            if (el.hasAttribute("data-field-hidden")) { if (el.value) answers[el.getAttribute("data-field-id")] = el.value; return; }
            if (el.type === "radio") { if (el.checked) answers[el.getAttribute("data-field-id")] = el.value; return; }
            var v = el.value.trim();
            if (v) answers[el.getAttribute("data-field-id")] = v;
        });
        return answers;
    }

    function missingRequiredField() {
        var fields = (currentCampaign && currentCampaign.fields) || [];
        var answers = collectDynamicAnswers();
        var missing = fields.find(function (f) { return f.required && !answers[f.id]; });
        return missing ? missing.label : null;
    }

    /** Enregistre le statut choisi pour le contact courant puis fait défiler vers le suivant.
     *  RDV pris (YELLOW) passe d'abord par la modale RDV existante (réutilisée telle quelle). */
    function submitCurrentDisposition(status) {
        var contact = currentCampaignQueue[currentCallIndex];
        if (!contact) return;

        var missingLabel = missingRequiredField();
        if (missingLabel && status !== "PENDING") {
            alert('Merci de renseigner "' + missingLabel + '" avant de continuer.');
            return;
        }

        var answers = collectDynamicAnswers();
        var comment = $("obCallComment").value.trim() || null;

        if (status === "YELLOW") {
            $("obRdvClient").value = contact.clientName;
            $("obRdvClientPhone").value = contact.clientPhone || "";
            $("obRdvPurpose").value = currentCampaign ? currentCampaign.name : "";
            $("obRdvScheduledAt").value = "";
            $("obRdvNotes").value = comment || "";
            pendingCallContactId = contact.contactId;
            pendingCallAnswers = answers;
            campaignCallModal.hide();
            rdvModal.show();
            return;
        }

        sendJson("/api/campaigns/contacts/" + contact.contactId + "/call-status", "PUT",
            { callStatus: status, notes: comment, answers: answers })
            .then(function () { advanceCallFlow(); })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    function advanceCallFlow() {
        currentCallIndex++;
        renderCurrentCallContact();
    }

    function wireCampaignTab() {
        $("obCampaignBackToGridBtn").addEventListener("click", loadCampaignGrid);
        $("obCampaignStartCallBtn").addEventListener("click", startCallFlow);
        $("obCallPrevBtn").addEventListener("click", function () {
            if (currentCallIndex > 0) { currentCallIndex--; renderCurrentCallContact(); }
        });
        Array.prototype.forEach.call(document.querySelectorAll("[data-disposition]"), function (btn) {
            btn.addEventListener("click", function () { submitCurrentDisposition(btn.getAttribute("data-disposition")); });
        });
        // Referme proprement la file en cours si l'agent quitte via la croix plutôt qu'en
        // traitant tous les contacts — la liste détail se rafraîchit pour refléter ce qui a été fait.
        $("obCampaignCallModal").addEventListener("hidden.bs.modal", function () {
            if (currentCampaign) loadCampaignContacts();
        });
    }

    // ===================== GESTION DES CAMPAGNES (Team Leader/QA/Admin/Superviseur) =====================

    var cmFieldCounter = 0;

    function wireCampaignManage() {
        $("obCampaignManageBtn").addEventListener("click", function () {
            switchCmTab("list");
            loadCmCampaignList();
            campaignManageModal.show();
        });

        $("obCmTabListBtn").addEventListener("click", function () { switchCmTab("list"); loadCmCampaignList(); });
        $("obCmTabNewBtn").addEventListener("click", function () { switchCmTab("new"); });

        $("obCmAddFieldBtn").addEventListener("click", function () { addCmFieldRow(); });
        $("obCmSaveBtn").addEventListener("click", submitNewCampaign);
    }

    function switchCmTab(tab) {
        $("obCmTabListBtn").classList.toggle("active", tab === "list");
        $("obCmTabNewBtn").classList.toggle("active", tab === "new");
        $("obCmPaneList").style.display = tab === "list" ? "" : "none";
        $("obCmPaneNew").style.display = tab === "new" ? "" : "none";
        if (tab === "new") resetCmForm();
    }

    function loadCmCampaignList() {
        getJson("/api/campaigns").then(function (campaigns) {
            var container = $("obCmCampaignList");
            if (!campaigns.length) { container.innerHTML = '<p class="text-muted text-center">Aucune campagne pour le moment.</p>'; return; }
            container.innerHTML = campaigns.map(function (c) {
                var statusBadge = c.status === "ACTIVE"
                    ? '<span class="badge bg-success">Active</span>'
                    : '<span class="badge bg-secondary">Clôturée</span>';
                return '<div class="d-flex justify-content-between align-items-center border-bottom py-2">' +
                    '<div><strong>' + escapeHtml(c.name) + '</strong> ' + statusBadge +
                    '<div class="small text-muted">' + (c.targetService ? escapeHtml(c.targetService) : "Toutes équipes") +
                    ' — ' + (c.fields ? c.fields.length : 0) + ' question(s)</div></div>' +
                    '<div class="d-flex gap-2">' +
                    '<button class="btn btn-sm btn-outline-primary ob-cm-report-btn" data-id="' + c.campaignId + '" data-name="' + escapeHtml(c.name) + '"><i class="bi bi-bar-chart-fill"></i> Rapport</button>' +
                    (c.status === "ACTIVE" ? '<button class="btn btn-sm btn-outline-danger ob-cm-close-btn" data-id="' + c.campaignId + '">Clôturer</button>' : '') +
                    '</div></div>';
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll(".ob-cm-close-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Clôturer cette campagne ? Elle ne sera plus visible des agents.")) return;
                    sendJson("/api/campaigns/" + btn.getAttribute("data-id") + "/close", "POST", {})
                        .then(loadCmCampaignList)
                        .then(loadCampaignGrid)
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
            Array.prototype.forEach.call(container.querySelectorAll(".ob-cm-report-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    openCampaignReport(Number(btn.getAttribute("data-id")), btn.getAttribute("data-name"));
                });
            });
        }).catch(function (e) { $("obCmCampaignList").innerHTML = '<p class="text-danger">Erreur : ' + e.message + '</p>'; });
    }

    function resetCmForm() {
        $("obCmName").value = "";
        $("obCmDescription").value = "";
        $("obCmTargetService").value = "";
        $("obCmStartDate").value = "";
        $("obCmEndDate").value = "";
        $("obCmFieldsBuilder").innerHTML = "";
        $("obCmSaveResult").textContent = "";
        cmFieldCounter = 0;
        selectedCoverUrl = null;
        $("obCmCoverImageUrl").value = "";
        renderCoverGallery();
    }

    /** Une ligne du constructeur = une question du modèle de campagne. Le type détermine quels
     *  sous-champs sont affichés (options seulement pour SELECT/RADIO). */
    function addCmFieldRow(prefill) {
        cmFieldCounter++;
        var rowId = "cmField_" + cmFieldCounter;
        var row = document.createElement("div");
        row.className = "ob-cm-field-row";
        row.setAttribute("data-row-id", rowId);
        var qNumber = $("obCmFieldsBuilder").children.length + 1;
        row.innerHTML =
            '<div class="row g-2 align-items-end">' +
                '<div class="col-md-5"><label class="form-label small"><span class="cm-field-index">' + qNumber + '</span>Intitulé de la question</label>' +
                    '<input type="text" class="form-control form-control-sm cm-field-label" placeholder="Ex. Le client est-il intéressé par le prêt ?"></div>' +
                '<div class="col-md-3"><label class="form-label small">Type de réponse</label>' +
                    '<select class="form-select form-select-sm cm-field-type">' +
                        '<option value="TEXT">Texte court</option>' +
                        '<option value="TEXTAREA">Texte long</option>' +
                        '<option value="SELECT">Liste déroulante</option>' +
                        '<option value="RADIO">Choix unique</option>' +
                        '<option value="DATE">Date</option>' +
                        '<option value="TIME">Heure</option>' +
                        '<option value="LINK">Lien (URL)</option>' +
                    '</select></div>' +
                '<div class="col-md-2 form-check ms-2">' +
                    '<input class="form-check-input cm-field-required" type="checkbox" id="' + rowId + '_req">' +
                    '<label class="form-check-label small" for="' + rowId + '_req">Obligatoire</label></div>' +
                '<div class="col-md-2 text-end"><button type="button" class="btn btn-sm btn-outline-danger cm-field-remove"><i class="bi bi-trash"></i></button></div>' +
            '</div>' +
            '<div class="cm-field-options mt-2" style="display:none;">' +
                '<label class="form-label small">Options proposées (une entrée par ligne — plus de 5 options = sélection en fenêtre pour l\'agent)</label>' +
                '<div class="cm-option-list"></div>' +
                '<button type="button" class="btn btn-sm btn-outline-secondary cm-option-add"><i class="bi bi-plus-lg"></i> Ajouter une option</button>' +
            '</div>';

        $("obCmFieldsBuilder").appendChild(row);
        renumberCmFieldRows();

        var typeSelect = row.querySelector(".cm-field-type");
        var optionsBox = row.querySelector(".cm-field-options");
        var optionList = row.querySelector(".cm-option-list");

        function toggleOptionsVisibility() {
            var needsOptions = typeSelect.value === "SELECT" || typeSelect.value === "RADIO";
            optionsBox.style.display = needsOptions ? "" : "none";
            if (needsOptions && !optionList.children.length) { addCmOptionRow(optionList); addCmOptionRow(optionList); }
        }
        typeSelect.addEventListener("change", toggleOptionsVisibility);
        row.querySelector(".cm-option-add").addEventListener("click", function () { addCmOptionRow(optionList); });
        row.querySelector(".cm-field-remove").addEventListener("click", function () { row.remove(); renumberCmFieldRows(); });

        if (prefill) {
            row.querySelector(".cm-field-label").value = prefill.label || "";
            typeSelect.value = prefill.type || "TEXT";
            row.querySelector(".cm-field-required").checked = !!prefill.required;
            toggleOptionsVisibility();
            if (prefill.options && prefill.options.length) {
                optionList.innerHTML = "";
                prefill.options.forEach(function (o) { addCmOptionRow(optionList, o); });
            }
        } else {
            toggleOptionsVisibility();
        }
    }

    /** Renumérote les pastilles de question (1, 2, 3…) après ajout/suppression — cohérence
     *  visuelle façon Google Forms. */
    function renumberCmFieldRows() {
        Array.prototype.forEach.call($("obCmFieldsBuilder").querySelectorAll(".ob-cm-field-row"), function (row, i) {
            var badge = row.querySelector(".cm-field-index");
            if (badge) badge.textContent = i + 1;
        });
    }

    function addCmOptionRow(optionList, value) {
        var row = document.createElement("div");
        row.className = "ob-cm-option-row";
        row.innerHTML = '<input type="text" class="form-control form-control-sm cm-option-value" value="' + (value ? escapeHtml(value) : "") + '" placeholder="Ex. Peur de ne pas pouvoir rembourser les mensualités">' +
            '<button type="button" class="btn btn-sm btn-outline-danger cm-option-remove"><i class="bi bi-x"></i></button>';
        row.querySelector(".cm-option-remove").addEventListener("click", function () { row.remove(); });
        optionList.appendChild(row);
    }

    function collectCmFields() {
        var fields = [];
        Array.prototype.forEach.call($("obCmFieldsBuilder").querySelectorAll(".ob-cm-field-row"), function (row, index) {
            var label = row.querySelector(".cm-field-label").value.trim();
            if (!label) return; // ligne vide ignorée plutôt que bloquante
            var type = row.querySelector(".cm-field-type").value;
            var required = row.querySelector(".cm-field-required").checked;
            var options = [];
            if (type === "SELECT" || type === "RADIO") {
                Array.prototype.forEach.call(row.querySelectorAll(".cm-option-value"), function (input) {
                    var v = input.value.trim();
                    if (v) options.push(v);
                });
            }
            fields.push({ id: "f" + (index + 1) + "_" + Date.now().toString(36), label: label, type: type, options: options, required: required });
        });
        return fields;
    }

    function submitNewCampaign() {
        var resultBox = $("obCmSaveResult");
        var name = $("obCmName").value.trim();
        if (!name) { resultBox.className = "small text-danger align-self-center"; resultBox.textContent = "Le nom de la campagne est requis."; return; }

        var payload = {
            name: name,
            description: $("obCmDescription").value.trim() || null,
            targetService: $("obCmTargetService").value || null,
            startDate: $("obCmStartDate").value || null,
            endDate: $("obCmEndDate").value || null,
            coverImageUrl: selectedCoverUrl || null,
            fields: collectCmFields()
        };

        resultBox.className = "small text-muted align-self-center";
        resultBox.textContent = "Création en cours…";

        sendJson("/api/campaigns", "POST", payload)
            .then(function () {
                resultBox.className = "small text-success align-self-center";
                resultBox.textContent = "Campagne créée.";
                loadCampaignGrid();
                switchCmTab("list");
                loadCmCampaignList();
            })
            .catch(function (e) {
                resultBox.className = "small text-danger align-self-center";
                resultBox.textContent = "Erreur : " + e.message;
            });
    }

    // ===================== RAPPORT D'ÉQUIPE (Team Leader/QA/Admin/Superviseur) =====================
    // Stats par agent + toute l'équipe, sur une campagne — toujours basées sur le statut d'appel
    // (contacts traités, interactions, RDV pris), communes à toutes les campagnes quel que soit
    // leur contenu (voir la demande métier — pas de compteurs dérivés des questions dynamiques).

    function openCampaignReport(campaignId, campaignName) {
        $("obReportCampaignName").textContent = campaignName || "";
        $("obReportTeamStats").innerHTML = '<p class="text-muted text-center">Chargement…</p>';
        $("obReportAgentTable").innerHTML = "";
        $("obReportContactList").innerHTML = "";
        $("obReportContactSearch").value = "";
        currentReportCampaignId = campaignId;
        currentReportCampaignFields = [];
        campaignReportModal.show();

        Promise.all([
            getJson("/api/campaigns/" + campaignId + "/contacts"),
            getJson("/api/campaigns")
        ]).then(function (results) {
            reportContactsCache = results[0] || [];
            var full = (results[1] || []).find(function (c) { return c.campaignId === campaignId; });
            currentReportCampaignFields = (full && full.fields) || [];
            renderReportTeamStats(reportContactsCache);
            renderReportAgentTable(reportContactsCache);
            renderReportContactList(reportContactsCache);
        }).catch(function (e) {
            $("obReportTeamStats").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    /** Contacts traités (statut ≠ PENDING), ventes/RDV pris (YELLOW), personnes intéressées
     *  (GREEN — a eu une interaction) — indicateurs fixes basés sur le statut d'appel, communs
     *  à toute campagne quel que soit son contenu (voir la demande métier). */
    function campaignCounts(contacts) {
        var total = contacts.length;
        var traites = contacts.filter(function (c) { return c.callStatus !== "PENDING"; }).length;
        var interesses = contacts.filter(function (c) { return c.callStatus === "GREEN"; }).length;
        var rdv = contacts.filter(function (c) { return c.callStatus === "YELLOW"; }).length;
        var pasReponse = contacts.filter(function (c) { return c.callStatus === "RED"; }).length;
        var aAppeler = contacts.filter(function (c) { return c.callStatus === "PENDING"; }).length;
        return { total: total, traites: traites, interesses: interesses, rdv: rdv, pasReponse: pasReponse, aAppeler: aAppeler };
    }

    function renderReportTeamStats(contacts) {
        var s = campaignCounts(contacts);
        var cards = [
            ["Contacts au total", s.total, "#0057B8"],
            ["Contacts traités", s.traites, "#6b7280"],
            ["Intéressés (interaction)", s.interesses, "#00A651"],
            ["RDV pris", s.rdv, "#F5A623"]
        ];
        $("obReportTeamStats").innerHTML = cards.map(function (c) {
            return '<div class="col-6 col-lg-3"><div class="card ob-stat-card h-100"><div class="card-body">' +
                '<div class="ob-stat-value" style="color:' + c[2] + ';">' + c[1] + '</div>' +
                '<div class="text-muted small">' + c[0] + '</div></div></div></div>';
        }).join("");
    }

    function renderReportAgentTable(contacts) {
        var byAgent = {};
        contacts.forEach(function (c) {
            var key = c.agentUserId || "unassigned";
            if (!byAgent[key]) byAgent[key] = { name: c.agentName || "Non assigné", contacts: [] };
            byAgent[key].contacts.push(c);
        });

        var agentIds = Object.keys(byAgent).sort(function (a, b) {
            return byAgent[a].name.localeCompare(byAgent[b].name, "fr");
        });

        $("obReportAgentTable").innerHTML = agentIds.map(function (id) {
            var s = campaignCounts(byAgent[id].contacts);
            return "<tr><td>" + escapeHtml(byAgent[id].name) + "</td><td>" + s.total + "</td>" +
                "<td>" + s.interesses + "</td><td>" + s.rdv + "</td><td>" + s.pasReponse + "</td><td>" + s.aAppeler + "</td></tr>";
        }).join("") || '<tr><td colspan="6" class="text-center text-muted">Aucun contact.</td></tr>';
    }

    function renderReportContactList(contacts) {
        var filter = $("obReportContactSearch").value.trim().toLowerCase();
        var filtered = !filter ? contacts : contacts.filter(function (c) {
            return (c.clientName || "").toLowerCase().indexOf(filter) !== -1 ||
                (c.agentName || "").toLowerCase().indexOf(filter) !== -1;
        });

        var container = $("obReportContactList");
        if (!filtered.length) { container.innerHTML = '<p class="text-muted text-center small">Aucun contact.</p>'; return; }

        container.innerHTML = filtered.map(function (c) {
            var color = CALL_STATUS_COLORS[c.callStatus];
            return '<div class="ob-report-contact-row" data-contact-id="' + c.contactId + '">' +
                '<div><strong>' + escapeHtml(c.clientName) + '</strong><div class="agent-name">' + escapeHtml(c.agentName || "Non assigné") + '</div></div>' +
                '<span class="badge" style="background:' + color + ';color:#fff;">' + CALL_STATUS_LABELS[c.callStatus] + '</span>' +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll("[data-contact-id]"), function (row) {
            row.addEventListener("click", function () { openContactDetail(Number(row.getAttribute("data-contact-id"))); });
        });
    }

    /** Détail lecture seule d'un contact — réponses aux questions dynamiques + commentaire de
     *  l'agent, pour que le Team Leader juge si le client était intéressé ou non. */
    function openContactDetail(contactId) {
        var contact = reportContactsCache.find(function (c) { return c.contactId === contactId; });
        if (!contact) return;

        $("obContactDetailName").textContent = contact.clientName;
        $("obContactDetailMeta").innerHTML =
            (contact.clientPhone ? '<i class="bi bi-telephone"></i> ' + escapeHtml(contact.clientPhone) + ' · ' : "") +
            'Agent : ' + escapeHtml(contact.agentName || "Non assigné") + ' · ' +
            '<span class="badge" style="background:' + CALL_STATUS_COLORS[contact.callStatus] + ';color:#fff;">' + CALL_STATUS_LABELS[contact.callStatus] + '</span>';

        renderExtraDataInto("obContactDetailExtraData", contact);

        var campaignFields = currentReportCampaignFields || [];
        var answers = contact.answers || {};
        if (!campaignFields.length || !Object.keys(answers).length) {
            $("obContactDetailAnswers").innerHTML = '<p class="text-muted small">Aucune réponse enregistrée pour ce contact.</p>';
        } else {
            $("obContactDetailAnswers").innerHTML = campaignFields.filter(function (f) { return answers[f.id]; }).map(function (f) {
                var displayValue = f.type === "LINK"
                    ? '<a href="' + escapeHtml(answers[f.id]) + '" target="_blank" rel="noopener">' + escapeHtml(answers[f.id]) + '</a>'
                    : escapeHtml(answers[f.id]);
                return '<div class="mb-2"><div class="small fw-semibold">' + escapeHtml(f.label) + '</div>' +
                    '<div class="small">' + displayValue + '</div></div>';
            }).join("");
        }

        $("obContactDetailComment").textContent = contact.notes || "—";
        contactDetailModal.show();
    }

    function wireCampaignReport() {
        $("obReportContactSearch").addEventListener("input", function () { renderReportContactList(reportContactsCache); });
    }

    // ===================== MES APPELS (CRM) =====================

    function wireSelfImport() {
        $("obSelfImportBtn").addEventListener("click", function () {
            var file = $("obSelfImportFile").files[0];
            if (!file) { alert("Choisissez un fichier."); return; }
            var formData = new FormData();
            formData.append("file", file);
            $("obSelfImportStatus").textContent = "Import en cours…";
            fetch("/api/campaigns/self-import", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
                .then(function (result) {
                    $("obSelfImportStatus").textContent = result.imported + " contact(s) importé(s).";
                    $("obSelfImportFile").value = "";
                    loadMyCalls();
                })
                .catch(function (e) { $("obSelfImportStatus").textContent = "Erreur : " + e.message; });
        });
    }

    function loadMyCalls() {
        getJson("/api/campaigns/contacts/me").then(function (contacts) {
            var container = $("obCallsList");
            if (!contacts.length) { container.innerHTML = '<p class="text-muted text-center">Aucun contact ne vous a été assigné pour l\'instant.</p>'; return; }
            container.innerHTML = contacts.map(function (c) {
                var borderColor = { PENDING: "#dee2e6", GREEN: "#00A651", RED: "#dc3545", YELLOW: "#F5A623" }[c.callStatus];
                return '<div class="border rounded p-3 mb-2" style="border-left:5px solid ' + borderColor + ' !important;" data-contact-id="' + c.contactId + '">' +
                    '<div class="d-flex justify-content-between align-items-start flex-wrap gap-2">' +
                        '<div>' +
                            '<strong>' + escapeHtml(c.clientName) + '</strong>' +
                            (c.clientPhone ? ' — <a href="tel:' + escapeHtml(c.clientPhone) + '">' + escapeHtml(c.clientPhone) + '</a>' : "") +
                            (c.maskedAccountNumber ? '<div class="small text-muted">Compte : ' + escapeHtml(c.maskedAccountNumber) + '</div>' : "") +
                            (c.notes ? '<div class="small text-muted mt-1"><i class="bi bi-sticky"></i> ' + escapeHtml(c.notes) + '</div>' : "") +
                        '</div>' +
                        '<div class="d-flex gap-1">' +
                            '<button class="btn btn-sm btn-outline-success" data-status="GREEN" title="Interaction réussie"><i class="bi bi-check-circle"></i></button>' +
                            '<button class="btn btn-sm btn-outline-danger" data-status="RED" title="Pas de réponse"><i class="bi bi-x-circle"></i></button>' +
                            '<button class="btn btn-sm btn-outline-warning" data-status="YELLOW" title="Prendre un RDV"><i class="bi bi-calendar-plus"></i></button>' +
                        '</div>' +
                    '</div></div>';
            }).join("");

            Array.prototype.forEach.call(container.querySelectorAll("[data-status]"), function (btn) {
                btn.addEventListener("click", function () {
                    var row = btn.closest("[data-contact-id]");
                    var contactId = Number(row.getAttribute("data-contact-id"));
                    var status = btn.getAttribute("data-status");
                    if (status === "YELLOW") { openCallRdvFlow(contactId, contacts); return; }
                    markCallStatus(contactId, status, null);
                });
            });
        }).catch(function (e) {
            $("obCallsList").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    function markCallStatus(contactId, status, notes) {
        sendJson("/api/campaigns/contacts/" + contactId + "/call-status", "PUT", { callStatus: status, notes: notes })
            .then(loadMyCalls)
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    /** Jaune = RDV pris pendant l'appel — ouvre directement le formulaire RDV pré-rempli, puis relie le contact au rendez-vous créé. */
    function openCallRdvFlow(contactId, contacts) {
        var contact = contacts.find(function (c) { return c.contactId === contactId; });
        if (!contact) return;
        $("obRdvClient").value = contact.clientName;
        $("obRdvClientPhone").value = contact.clientPhone || "";
        $("obRdvPurpose").value = "";
        $("obRdvScheduledAt").value = "";
        $("obRdvNotes").value = "";
        pendingCallContactId = contactId;
        rdvModal.show();
    }

    // ===================== VENTES =====================

    function loadSales() {
        getJson("/api/outbound/sales/me").then(function (rows) {
            salesCache = rows || [];
            renderSales();
            updateStats();
        }).catch(function (e) {
            $("obSalesBody").innerHTML = '<tr><td colspan="6" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function renderSales() {
        var body = $("obSalesBody");
        if (!salesCache.length) { body.innerHTML = '<tr><td colspan="6" class="text-center text-muted">Aucune vente enregistrée pour l\'instant.</td></tr>'; return; }
        body.innerHTML = salesCache.map(function (s) {
            return "<tr><td>" + fmtDate(s.saleDate) + "</td>" +
                "<td>" + escapeHtml(s.productName) + "</td>" +
                "<td>" + escapeHtml(s.clientName || "—") + "</td>" +
                "<td>" + (s.amount != null ? s.amount.toLocaleString("fr-FR") + " F" : "—") + "</td>" +
                '<td><span class="badge ob-status-badge ' + s.status + '">' + statusLabel(s.status) + "</span></td>" +
                '<td><button class="btn btn-sm btn-outline-danger" data-del-sale="' + s.saleId + '"><i class="bi bi-trash"></i></button></td></tr>';
        }).join("");
        Array.prototype.forEach.call(body.querySelectorAll("[data-del-sale]"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer cette vente ?")) return;
                fetch("/api/outbound/sales/" + btn.getAttribute("data-del-sale"), { method: "DELETE", credentials: "same-origin" })
                    .then(loadSales);
            });
        });
    }

    function wireSaleModal() {
        $("obNewSaleBtn").addEventListener("click", function () {
            $("obSaleProduct").value = ""; $("obSaleClient").value = ""; $("obSaleClientPhone").value = "";
            $("obSaleAmount").value = ""; $("obSaleDate").value = todayIso(); $("obSaleNotes").value = "";
            saleModal.show();
        });
        $("obSaveSaleBtn").addEventListener("click", function () {
            var product = $("obSaleProduct").value.trim();
            if (!product) { alert("Le produit est obligatoire."); return; }
            sendJson("/api/outbound/sales", "POST", {
                productName: product,
                clientName: $("obSaleClient").value.trim() || null,
                clientPhone: $("obSaleClientPhone").value.trim() || null,
                amount: $("obSaleAmount").value ? Number($("obSaleAmount").value) : null,
                saleDate: $("obSaleDate").value || todayIso(),
                status: "CONFIRMED",
                notes: $("obSaleNotes").value.trim() || null
            }).then(function () {
                saleModal.hide();
                loadSales();
            }).catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    // ===================== RENDEZ-VOUS =====================

    function loadRdv() {
        getJson("/api/outbound/appointments/me").then(function (rows) {
            rdvCache = rows || [];
            renderRdv();
            updateStats();
        }).catch(function (e) {
            $("obRdvBody").innerHTML = '<tr><td colspan="6" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function renderRdv() {
        var body = $("obRdvBody");
        if (!rdvCache.length) { body.innerHTML = '<tr><td colspan="6" class="text-center text-muted">Aucun rendez-vous pour l\'instant.</td></tr>'; return; }
        body.innerHTML = rdvCache.map(function (a) {
            return "<tr><td>" + fmtDateTime(a.scheduledAt) + "</td>" +
                "<td>" + escapeHtml(a.clientName) + "</td>" +
                "<td>" + escapeHtml(a.clientPhone || "—") + "</td>" +
                "<td>" + escapeHtml(a.purpose || "—") + "</td>" +
                '<td><select class="form-select form-select-sm ob-rdv-status" data-id="' + a.appointmentId + '" style="width:auto;">' +
                    ["PLANNED", "DONE", "NO_SHOW", "CANCELLED"].map(function (s) {
                        return '<option value="' + s + '" ' + (s === a.status ? "selected" : "") + '>' + statusLabel(s) + '</option>';
                    }).join("") +
                '</select></td>' +
                '<td><button class="btn btn-sm btn-outline-danger" data-del-rdv="' + a.appointmentId + '"><i class="bi bi-trash"></i></button></td></tr>';
        }).join("");
        Array.prototype.forEach.call(body.querySelectorAll(".ob-rdv-status"), function (sel) {
            sel.addEventListener("change", function () {
                sendJson("/api/outbound/appointments/" + sel.getAttribute("data-id") + "/status", "PUT", { status: sel.value })
                    .then(loadRdv).catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
        Array.prototype.forEach.call(body.querySelectorAll("[data-del-rdv]"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer ce rendez-vous ?")) return;
                fetch("/api/outbound/appointments/" + btn.getAttribute("data-del-rdv"), { method: "DELETE", credentials: "same-origin" })
                    .then(loadRdv);
            });
        });
    }

    function wireRdvModal() {
        $("obNewRdvBtn").addEventListener("click", function () {
            $("obRdvClient").value = ""; $("obRdvClientPhone").value = ""; $("obRdvPurpose").value = "";
            $("obRdvScheduledAt").value = ""; $("obRdvNotes").value = "";
            pendingCallContactId = null; // ouverture manuelle depuis l'onglet RDV — pas de contact d'appel lié
            pendingCallAnswers = null;
            rdvModal.show();
        });
        $("obSaveRdvBtn").addEventListener("click", function () {
            var client = $("obRdvClient").value.trim();
            var when = $("obRdvScheduledAt").value;
            if (!client) { alert("Le nom du client est obligatoire."); return; }
            if (!when) { alert("La date et l'heure sont obligatoires."); return; }
            var linkedContactId = pendingCallContactId;
            var linkedAnswers = pendingCallAnswers;
            var resumeCampaignFlow = !!currentCampaign && currentCampaignQueue.length > 0;
            sendJson("/api/outbound/appointments", "POST", {
                clientName: client,
                clientPhone: $("obRdvClientPhone").value.trim() || null,
                purpose: $("obRdvPurpose").value.trim() || null,
                scheduledAt: when,
                status: "PLANNED",
                notes: $("obRdvNotes").value.trim() || null
            }).then(function (created) {
                rdvModal.hide();
                pendingCallContactId = null;
                pendingCallAnswers = null;
                if (linkedContactId) {
                    // Ouvert depuis "Mes appels" ou l'onglet Campagne (bouton jaune) — marque le
                    // contact, relie le RDV créé, et si on venait de l'onglet Campagne, reprend
                    // le défilement séquentiel sur le contact suivant.
                    Promise.all([
                        sendJson("/api/campaigns/contacts/" + linkedContactId + "/call-status", "PUT",
                            { callStatus: "YELLOW", notes: $("obRdvNotes").value.trim() || null, answers: linkedAnswers }),
                        fetch("/api/campaigns/contacts/" + linkedContactId + "/link-appointment/" + created.appointmentId, { method: "PUT", credentials: "same-origin" })
                    ]).then(function () {
                        if (resumeCampaignFlow) {
                            campaignCallModal.show();
                            advanceCallFlow();
                        } else {
                            loadMyCalls();
                        }
                    }).catch(function (e) { alert("RDV créé, mais erreur de liaison : " + e.message); });
                }
                loadRdv();
            }).catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    // ===================== PARCOURS DE VENTE INTERACTIFS =====================

    function loadJourneys() {
        return getJson("/api/sales-journeys").then(function (rows) {
            journeysCache = rows || [];
            renderJourneysGrid();
        }).catch(function () {});
    }

    function renderJourneysGrid() {
        var grid = $("obJourneysGrid");
        if (!journeysCache.length) { grid.innerHTML = '<p class="text-muted text-center">Aucun parcours de vente disponible pour l\'instant.</p>'; return; }
        grid.innerHTML = journeysCache.map(function (j) {
            return '<div class="col-md-4">' +
                '<div class="card h-100 shadow-sm" style="cursor:pointer;border:none;background:linear-gradient(135deg,' + j.colorFrom + ',' + j.colorTo + ');color:#fff;" data-journey-key="' + j.journeyKey + '">' +
                    '<div class="card-body">' +
                        '<div style="font-size:1.8rem;"><i class="bi ' + j.icon + '"></i></div>' +
                        '<h6 class="mt-2 mb-1">' + escapeHtml(j.title) + '</h6>' +
                        '<p class="small mb-0" style="opacity:.9;">' + escapeHtml(j.pitch || "") + '</p>' +
                    '</div>' +
                '</div></div>';
        }).join("");
        Array.prototype.forEach.call(grid.querySelectorAll("[data-journey-key]"), function (card) {
            // Clic sur l'onglet/la vignette → la modale s'ouvre automatiquement.
            card.addEventListener("click", function () { openJourney(card.getAttribute("data-journey-key")); });
        });
    }

    var journeyUsedSuggestions = {}; // { stepIndex: [indices déjà cliqués] } — remis à zéro à chaque ouverture de parcours

    function openJourney(key) {
        currentJourney = journeysCache.find(function (j) { return j.journeyKey === key; });
        if (!currentJourney) return;
        journeyStepIndex = 0;
        journeyUsedSuggestions = {};
        $("obJourneyTitle").innerHTML = '<i class="bi ' + currentJourney.icon + '"></i> ' + escapeHtml(currentJourney.title);
        renderJourneyStep();
        journeyModal.show();
    }

    /** Message d'accroche du coach, adapté au type d'étape (découverte/présentation/objections/closing) —
     *  ce cadrage n'existait pas avant : il enrichit le parcours au lieu de livrer les lignes brutes. */
    function coachIntroFor(step) {
        var t = step.title.toLowerCase();
        if (t.indexOf("découverte") !== -1) return "Avant de présenter quoi que ce soit, comprenons le besoin. Cliquez sur une question pour la poser au client :";
        if (t.indexOf("présentation") !== -1) return "Voici les arguments à mettre en avant, un par un — cliquez sur celui qui correspond à ce que le client vient de dire :";
        if (t.indexOf("objection") !== -1) return "Le client hésite ou objecte ? Cliquez sur l'objection la plus proche de la sienne pour voir la réponse à donner :";
        if (t.indexOf("closing") !== -1) return "Le moment de conclure. Cliquez sur la formule la plus adaptée à la situation :";
        return "Cliquez sur un point pour l'ajouter à votre échange :";
    }

    /** Petite relance du coach après chaque clic — garde la conversation vivante plutôt qu'une simple liste cochée. */
    function coachReactionAfter(usedCount, total) {
        if (usedCount >= total) return "Vous avez fait le tour de cette étape — passez à la suivante quand vous êtes prêt.";
        var reactions = [
            "Bien. Un autre point à ajouter, ou on passe à la suite ?",
            "Continuez sur cette lancée si le client est réceptif.",
            "Bonne approche — vous pouvez enchaîner avec un autre argument."
        ];
        return reactions[usedCount % reactions.length];
    }

    function renderJourneyStep() {
        var step = currentJourney.steps[journeyStepIndex];
        var total = currentJourney.steps.length;
        var used = journeyUsedSuggestions[journeyStepIndex] || (journeyUsedSuggestions[journeyStepIndex] = []);

        var progressDots = "";
        for (var i = 0; i < total; i++) progressDots += '<span class="' + (i <= journeyStepIndex ? "done" : "") + '"></span>';

        var transcript = '<div class="ob-chat-msg coach"><div class="ob-chat-avatar"><i class="bi bi-mortarboard-fill"></i></div><div class="ob-chat-bubble"><strong>' + escapeHtml(step.title) + '</strong><br>' + coachIntroFor(step) + '</div></div>';

        used.forEach(function (idx, order) {
            transcript += '<div class="ob-chat-msg agent"><div class="ob-chat-bubble">' + escapeHtml(step.content[idx]) + '</div></div>';
            if (order === used.length - 1) {
                transcript += '<div class="ob-chat-msg coach"><div class="ob-chat-avatar"><i class="bi bi-mortarboard-fill"></i></div><div class="ob-chat-bubble">' + coachReactionAfter(used.length, step.content.length) + '</div></div>';
            }
        });

        var suggestionsHtml = step.content.map(function (line, idx) {
            if (used.indexOf(idx) !== -1) return "";
            return '<button type="button" class="ob-chat-suggestion-btn" data-suggestion-idx="' + idx + '">' + escapeHtml(line) + '</button>';
        }).join("");

        $("obJourneyBody").innerHTML =
            '<div class="ob-chat-progress">' + progressDots + '</div>' +
            '<div class="ob-chat-scroll" id="obJourneyScroll">' + transcript + '</div>' +
            (suggestionsHtml ? '<div class="ob-chat-suggestions" id="obJourneySuggestions">' + suggestionsHtml + '</div>' : '') +
            '<div class="d-flex justify-content-between mt-3">' +
                '<button class="btn btn-outline-secondary" id="obJourneyPrevBtn" ' + (journeyStepIndex === 0 ? "disabled" : "") + '><i class="bi bi-arrow-left"></i> Précédent</button>' +
                (journeyStepIndex < total - 1
                    ? '<button class="btn btn-primary" id="obJourneyNextBtn">Suivant <i class="bi bi-arrow-right"></i></button>'
                    : '<button class="btn btn-success" id="obJourneyDoneBtn"><i class="bi bi-check-lg"></i> Terminé</button>') +
            '</div>';

        Array.prototype.forEach.call($("obJourneyBody").querySelectorAll("[data-suggestion-idx]"), function (btn) {
            btn.addEventListener("click", function () {
                used.push(Number(btn.getAttribute("data-suggestion-idx")));
                renderJourneyStep();
            });
        });

        var scrollEl = $("obJourneyScroll");
        if (scrollEl) scrollEl.scrollTop = scrollEl.scrollHeight;

        var prevBtn = $("obJourneyPrevBtn");
        if (prevBtn) prevBtn.addEventListener("click", function () { journeyStepIndex--; renderJourneyStep(); });
        var nextBtn = $("obJourneyNextBtn");
        if (nextBtn) nextBtn.addEventListener("click", function () { journeyStepIndex++; renderJourneyStep(); });
        var doneBtn = $("obJourneyDoneBtn");
        if (doneBtn) doneBtn.addEventListener("click", function () { journeyModal.hide(); });
    }

    function wireJourneyFullscreen() {
        $("obJourneyFullscreenBtn").addEventListener("click", function () {
            var dialog = $("obJourneyDialog");
            var isFullscreen = dialog.classList.toggle("modal-fullscreen");
            dialog.classList.toggle("modal-lg", !isFullscreen);
            var icon = this.querySelector("i");
            icon.className = isFullscreen ? "bi bi-fullscreen-exit" : "bi bi-arrows-fullscreen";
        });
        // Repart en mode normal à chaque fermeture, pour ne pas rouvrir en plein écran la fois suivante.
        $("obJourneyModal").addEventListener("hidden.bs.modal", function () {
            var dialog = $("obJourneyDialog");
            dialog.classList.remove("modal-fullscreen");
            dialog.classList.add("modal-lg");
            $("obJourneyFullscreenBtn").querySelector("i").className = "bi bi-arrows-fullscreen";
        });
    }

    // ===================== ADMINISTRATION DES PARCOURS =====================

    function wireJourneyAdmin() {
        $("obJourneyAdminBtn").addEventListener("click", function () {
            loadJourneyAdminList();
            journeyAdminModal.show();
        });
        $("obJourneyNewBtn").addEventListener("click", function () { openJourneyEditor(null); });
        $("obJourneyAddStepBtn").addEventListener("click", function () {
            editingSteps.push({ title: "", content: [""] });
            renderStepsEditor();
        });
        $("obJourneySaveBtn").addEventListener("click", saveJourney);
    }

    function loadJourneyAdminList() {
        getJson("/api/sales-journeys/admin").then(function (rows) {
            var list = $("obJourneyAdminList");
            if (!rows.length) { list.innerHTML = '<p class="text-muted text-center">Aucun parcours pour l\'instant.</p>'; return; }
            list.innerHTML = rows.map(function (j) {
                var activeBadge = j.active ? '<span class="badge bg-success">Actif</span>' : '<span class="badge bg-secondary">Inactif</span>';
                return '<div class="d-flex justify-content-between align-items-center border rounded p-2 mb-2">' +
                    '<span><i class="bi ' + j.icon + '"></i> <strong>' + escapeHtml(j.title) + '</strong> ' + activeBadge + '</span>' +
                    '<span class="d-flex gap-1">' +
                        '<button class="btn btn-sm btn-outline-primary" data-edit-journey="' + j.journeyId + '"><i class="bi bi-pencil"></i></button>' +
                        '<button class="btn btn-sm btn-outline-danger" data-del-journey="' + j.journeyId + '"><i class="bi bi-trash"></i></button>' +
                    '</span></div>';
            }).join("");
            Array.prototype.forEach.call(list.querySelectorAll("[data-edit-journey]"), function (btn) {
                btn.addEventListener("click", function () {
                    var journey = rows.find(function (j) { return j.journeyId === Number(btn.getAttribute("data-edit-journey")); });
                    openJourneyEditor(journey);
                });
            });
            Array.prototype.forEach.call(list.querySelectorAll("[data-del-journey]"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Supprimer ce parcours de vente ?")) return;
                    fetch("/api/sales-journeys/" + btn.getAttribute("data-del-journey"), { method: "DELETE", credentials: "same-origin" })
                        .then(function () { loadJourneyAdminList(); loadJourneys(); });
                });
            });
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    function openJourneyEditor(journey) {
        editingJourneyId = journey ? journey.journeyId : null;
        $("obJourneyEditTitle").innerHTML = '<i class="bi bi-pencil-square"></i> ' + (journey ? "Modifier le parcours" : "Nouveau parcours de vente");
        $("obJourneyEditTitleInput").value = journey ? journey.title : "";
        $("obJourneyEditIcon").value = journey ? journey.icon : "bi-briefcase-fill";
        $("obJourneyEditColorFrom").value = journey ? journey.colorFrom : "#0057B8";
        $("obJourneyEditColorTo").value = journey ? journey.colorTo : "#00A651";
        $("obJourneyEditActive").checked = journey ? journey.active : true;
        $("obJourneyEditPitch").value = journey ? (journey.pitch || "") : "";
        editingSteps = journey ? JSON.parse(JSON.stringify(journey.steps)) : [
            { title: "1. Découverte", content: [""] },
            { title: "2. Présentation", content: [""] },
            { title: "3. Objections fréquentes", content: [""] },
            { title: "4. Closing", content: [""] }
        ];
        renderStepsEditor();
        journeyAdminModal.hide();
        journeyEditModal.show();
    }

    function renderStepsEditor() {
        var container = $("obJourneyStepsEditor");
        container.innerHTML = editingSteps.map(function (step, idx) {
            return '<div class="border rounded p-2 mb-2">' +
                '<div class="d-flex gap-2 mb-2">' +
                    '<input type="text" class="form-control form-control-sm" data-step-title="' + idx + '" value="' + escapeHtml(step.title) + '" placeholder="Titre de l\'étape">' +
                    '<button class="btn btn-sm btn-outline-danger" data-remove-step="' + idx + '"><i class="bi bi-trash"></i></button>' +
                '</div>' +
                '<label class="form-label small text-muted mb-1">Une ligne par point (question, argument, objection...)</label>' +
                '<textarea class="form-control form-control-sm" rows="3" data-step-content="' + idx + '">' + escapeHtml(step.content.join("\n")) + '</textarea>' +
            '</div>';
        }).join("");
        Array.prototype.forEach.call(container.querySelectorAll("[data-step-title]"), function (input) {
            input.addEventListener("input", function () { editingSteps[Number(input.getAttribute("data-step-title"))].title = input.value; });
        });
        Array.prototype.forEach.call(container.querySelectorAll("[data-step-content]"), function (ta) {
            ta.addEventListener("input", function () {
                editingSteps[Number(ta.getAttribute("data-step-content"))].content = ta.value.split("\n").filter(function (l) { return l.trim() !== ""; });
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll("[data-remove-step]"), function (btn) {
            btn.addEventListener("click", function () {
                editingSteps.splice(Number(btn.getAttribute("data-remove-step")), 1);
                renderStepsEditor();
            });
        });
    }

    function saveJourney() {
        var title = $("obJourneyEditTitleInput").value.trim();
        if (!title) { alert("Le titre est obligatoire."); return; }
        var steps = editingSteps.filter(function (s) { return s.title.trim() && s.content.length; });
        if (!steps.length) { alert("Le parcours doit avoir au moins une étape avec un titre et du contenu."); return; }

        var payload = {
            title: title,
            icon: $("obJourneyEditIcon").value.trim() || "bi-briefcase-fill",
            colorFrom: $("obJourneyEditColorFrom").value,
            colorTo: $("obJourneyEditColorTo").value,
            pitch: $("obJourneyEditPitch").value.trim(),
            active: $("obJourneyEditActive").checked,
            steps: steps
        };

        var request = editingJourneyId
            ? sendJson("/api/sales-journeys/" + editingJourneyId, "PUT", payload)
            : sendJson("/api/sales-journeys", "POST", payload);

        request.then(function () {
            journeyEditModal.hide();
            loadJourneys();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    // ===================== HELPERS =====================

    function statusLabel(s) {
        return { PENDING: "En cours", CONFIRMED: "Confirmée", CANCELLED: "Annulée",
            PLANNED: "Prévu", DONE: "Honoré", NO_SHOW: "Absent" }[s] || s;
    }
    function todayIso() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }
    function fmtDate(iso) {
        if (!iso) return "—";
        var d = new Date(iso);
        return d.toLocaleDateString("fr-FR");
    }
    function fmtDateTime(iso) {
        if (!iso) return "—";
        var d = new Date(iso);
        return d.toLocaleDateString("fr-FR") + " " + d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
    }

    function init() {
        saleModal = new bootstrap.Modal($("obSaleModal"));
        rdvModal = new bootstrap.Modal($("obRdvModal"));
        journeyModal = new bootstrap.Modal($("obJourneyModal"));
        journeyAdminModal = new bootstrap.Modal($("obJourneyAdminListModal"));
        journeyEditModal = new bootstrap.Modal($("obJourneyEditModal"));
        campaignCallModal = new bootstrap.Modal($("obCampaignCallModal"));
        suggestionModal = new bootstrap.Modal($("obSuggestionModal"));
        campaignManageModal = new bootstrap.Modal($("obCampaignManageModal"));
        campaignReportModal = new bootstrap.Modal($("obCampaignReportModal"));
        contactDetailModal = new bootstrap.Modal($("obContactDetailModal"));

        $("obTabCampaignBtn").addEventListener("click", function () { switchTab("campaign"); });
        $("obTabCallsBtn").addEventListener("click", function () { switchTab("calls"); });
        $("obTabSalesBtn").addEventListener("click", function () { switchTab("sales"); });
        $("obTabRdvBtn").addEventListener("click", function () { switchTab("rdv"); });
        $("obTabJourneysBtn").addEventListener("click", function () { switchTab("journeys"); });

        wireCampaignTab();
        wireCampaignManage();
        wireCampaignReport();
        wireSaleModal();
        wireRdvModal();
        wireJourneyFullscreen();
        wireJourneyAdmin();
        wireSelfImport();

        loadCampaignGrid();
        loadSales();
        loadRdv();
        loadJourneys();

        if (window.RccSession) {
            window.RccSession.init().then(function (session) {
                if (!session) return;
                var isAdmin = session.profile === "ADMIN";
                var isQa = session.profile === "QA";
                var isSupervisor = session.profile === "SUPERVISOR";
                var isOutboundTeamLeader = session.profile === "TEAM_LEADER"; // vérifié côté serveur à l'appel — le bouton reste visible même si ce n'est finalement pas son équipe, l'API refusera proprement
                if (isAdmin || isQa || isOutboundTeamLeader) {
                    $("obJourneyAdminBtn").style.display = "";
                }
                if (isAdmin || isQa || isSupervisor || isOutboundTeamLeader) {
                    $("obCampaignManageBtn").style.display = "";
                }
            });
        }
    }

    document.addEventListener("DOMContentLoaded", init);
})();
