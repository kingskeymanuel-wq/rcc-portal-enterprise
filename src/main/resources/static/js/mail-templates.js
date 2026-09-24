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
            renderKpis();
            renderUnclassified();
            renderSearch();
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

    // ===================== UTILITAIRES D'AFFICHAGE =====================

    var CATEGORY_ICONS = [
        [/carte|card|visa|gab|atm/, "bi-credit-card-2-front", "#eaf2ff", "#0057B8"],
        [/virement|transfert|transfer|rapid/, "bi-arrow-left-right", "#e4f7ef", "#0b7a4b"],
        [/compte|account|rib|solde/, "bi-bank", "#fff4e0", "#c77700"],
        [/digital|mobile|app|internet|omni/, "bi-phone", "#f1e9ff", "#6a2bd9"],
        [/chat|rafiki|whatsapp/, "bi-chat-dots", "#f1e9ff", "#6a2bd9"],
        [/promo|offre|campagne|marketing/, "bi-megaphone", "#ffe9f2", "#c2185b"],
        [/interne|message|note/, "bi-envelope-paper", "#eef1f5", "#4a5568"],
        [/reclam|plainte|litige|contest/, "bi-exclamation-octagon", "#ffecee", "#c2414f"],
        [/pret|credit|loan/, "bi-cash-coin", "#e4f7ef", "#0b7a4b"],
        [/social|facebook|twitter|linkedin/, "bi-share", "#e4f7ef", "#0b7a4b"]
    ];
    function categoryVisual(label) {
        var l = (label || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
        for (var i = 0; i < CATEGORY_ICONS.length; i++) {
            if (CATEGORY_ICONS[i][0].test(l)) return { icon: CATEGORY_ICONS[i][1], bg: CATEGORY_ICONS[i][2], fg: CATEGORY_ICONS[i][3] };
        }
        return { icon: "bi-folder2-open", bg: "#eef4ff", fg: "#0057B8" };
    }
    function catIconHtml(label) {
        var v = categoryVisual(label);
        return '<span class="mt-cat-icon" style="background:' + v.bg + ';color:' + v.fg + '"><i class="bi ' + v.icon + '"></i></span>';
    }
    function plural(n, word) { return n + " " + word + (n > 1 ? "s" : ""); }
    function placeholdersOf(text) {
        var found = [], re = /\[([A-Za-zÀ-ÿ_]+)\]/g, m;
        while ((m = re.exec(text || ""))) if (found.indexOf(m[1]) === -1) found.push(m[1]);
        return found;
    }
    function snippet(body) {
        return (body || "").replace(/\s+/g, " ").trim().slice(0, 160);
    }
    function isMine(t) {
        return !!(currentUsername && t.createdByUserId && currentUsername.toLowerCase() === t.createdByUserId.toLowerCase());
    }
    function countUp(el, target) {
        if (!el) return;
        var start = Number(el.getAttribute("data-value") || 0), t0 = null;
        el.setAttribute("data-value", target);
        if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) { el.textContent = target; return; }
        function step(ts) {
            if (!t0) t0 = ts;
            var k = Math.min(1, (ts - t0) / 700);
            el.textContent = Math.round(start + (target - start) * (1 - Math.pow(1 - k, 3)));
            if (k < 1) requestAnimationFrame(step);
        }
        requestAnimationFrame(step);
    }
    function toast(message, error) {
        var t = $("mtToast");
        if (!t) { alert(message); return; }
        t.textContent = message;
        t.classList.toggle("error", !!error);
        t.classList.add("show");
        clearTimeout(toast.timer);
        toast.timer = setTimeout(function () { t.classList.remove("show"); }, 3000);
    }
    function renderKpis() {
        var templates = overviewCache.templates || [];
        countUp($("mtKpiTemplates"), templates.length);
        countUp($("mtKpiCategories"), (overviewCache.categories || []).length);
        countUp($("mtKpiMine"), templates.filter(isMine).length);
    }

    // ===================== SÉLECTEUR D'ÉQUIPE =====================

    function renderTeamPicker() {
        var grid = $("mtTeamGrid");
        var total = Math.max(1, (overviewCache.templates || []).length);
        grid.innerHTML = TEAMS.map(function (team, i) {
            var cats = categoriesForTeam(team.code);
            var count = cats.reduce(function (sum, c) { return sum + templatesForCategory(c.id).length; }, 0);
            return '<div class="mt-team-card" tabindex="0" role="button" style="background:' + team.gradient + ';animation-delay:' + (i * 90) + 'ms" data-team="' + team.code + '">' +
                '<span class="mt-team-icon"><i class="bi ' + team.icon + '"></i></span>' +
                '<div class="mt-team-name">' + escapeHtml(team.label) + '</div>' +
                '<div class="mt-team-count">' + plural(count, "masque") + ' · ' + plural(cats.length, "catégorie") + '</div>' +
                '<div class="mt-team-bar"><span data-w="' + Math.round(count / total * 100) + '"></span></div>' +
                '<div class="mt-team-foot"><span>' + (count ? "Voir les masques" : "Commencer") + '</span><span class="mt-arrow"><i class="bi bi-arrow-right"></i></span></div>' +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(grid.querySelectorAll("[data-team]"), function (card) {
            card.addEventListener("click", function () { selectTeam(card.getAttribute("data-team")); });
            card.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); selectTeam(card.getAttribute("data-team")); } });
        });
        requestAnimationFrame(function () {
            Array.prototype.forEach.call(grid.querySelectorAll("[data-w]"), function (bar) { bar.style.width = Math.max(4, Number(bar.getAttribute("data-w"))) + "%"; });
        });
    }

    function selectTeam(teamCode) {
        currentTeamCode = teamCode;
        var team = TEAM_BY_CODE[teamCode] || { label: teamCode, icon: "bi-envelope-paper-fill", gradient: "linear-gradient(135deg, #0057B8, #003d82)" };
        $("mtTeamPickerView").style.display = "none";
        $("mtTeamDetailView").style.display = "";
        $("mtTeamDetailTitle").textContent = team.label;
        $("mtDetailIcon").style.background = team.gradient;
        $("mtDetailIcon").innerHTML = '<i class="bi ' + team.icon + '"></i>';
        var head = $("mtDetailHead");
        head.style.animation = "none"; void head.offsetWidth; head.style.animation = "";
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
        var openIds = Array.prototype.map.call(container.querySelectorAll(".mt-category-card.open"), function (c) { return c.getAttribute("data-category"); });
        var cats = categoriesForTeam(teamCode).sort(function (a, b) { return a.sortOrder - b.sortOrder; });

        if (!cats.length) {
            container.innerHTML = '<div class="mt-empty-state"><div class="mt-empty-art"><i class="bi bi-inbox"></i></div>' +
                '<b>Aucun masque pour cette équipe pour l\'instant</b>' +
                '<small>' + (isQaOrAdmin ? "Créez une catégorie puis vos premiers masques." : "La QA prépare les modèles : revenez bientôt.") + '</small></div>';
            return;
        }

        container.innerHTML = cats.map(function (c, i) {
            var templates = templatesForCategory(c.id);
            return '<div class="mt-category-card' + (openIds.indexOf(String(c.id)) !== -1 ? " open" : "") + '" data-category="' + c.id + '" style="animation-delay:' + (i * 60) + 'ms">' +
                '<div class="mt-category-header" role="button" tabindex="0">' + catIconHtml(c.label) +
                    '<span class="mt-cat-title"><b>' + escapeHtml(c.label) + '</b><small>' + plural(templates.length, "masque") + '</small></span>' +
                    '<button type="button" class="mt-btn mt-btn-soft mt-btn-sm add-in-category-btn" data-category-id="' + c.id + '" title="Nouveau masque dans cette catégorie"><i class="bi bi-plus-lg"></i></button>' +
                    '<i class="bi bi-chevron-down mt-cat-chevron"></i>' +
                '</div>' +
                '<div class="mt-category-body"><div class="mt-category-inner">' +
                    (templates.length
                        ? '<div class="mt-template-grid">' + templates.map(function (t, k) { return templateCardHtml(t, null, k); }).join("") + '</div>'
                        : '<p class="mt-empty-mini mb-0"><i class="bi bi-info-circle"></i> Aucun masque dans cette catégorie : utilisez « + » pour en créer un.</p>') +
                '</div></div>' +
            '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".mt-category-header"), function (header) {
            var toggle = function () { header.parentElement.classList.toggle("open"); };
            header.addEventListener("click", toggle);
            header.addEventListener("keydown", function (e) { if (e.key === "Enter") toggle(); });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".add-in-category-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                openCreate(Number(btn.getAttribute("data-category-id")));
            });
        });
        wireTemplateButtons(container);

        // Une seule catégorie -> on l'ouvre directement, pas besoin de cliquer pour rien.
        if (cats.length === 1) container.querySelector(".mt-category-card").classList.add("open");
    }

    function highlight(text, query) {
        var safe = escapeHtml(text || "");
        if (!query) return safe;
        var q = query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
        return safe.replace(new RegExp("(" + q + ")", "ig"), '<mark class="mt-hl">$1</mark>');
    }

    function templateCardHtml(t, query, index) {
        var mine = isMine(t);
        var canDelete = !t.isSystemTemplate && (mine || isQaOrAdmin);
        var cat = (overviewCache.categories || []).find(function (c) { return c.id === t.categoryId; });
        var fields = placeholdersOf(t.subject + " " + t.body);
        var tags = [];
        tags.push(t.recipientType === "service"
            ? '<span class="mt-tag mt-tag-violet"><i class="bi bi-building"></i> Service</span>'
            : '<span class="mt-tag"><i class="bi bi-person"></i> Personne</span>');
        if (fields.length) tags.push('<span class="mt-tag mt-tag-blue"><i class="bi bi-input-cursor-text"></i> ' + plural(fields.length, "champ") + '</span>');
        if (!t.isSystemTemplate) tags.push('<span class="mt-tag mt-tag-green"><i class="bi bi-person-badge"></i> ' +
            (mine ? '<i class="bi bi-lock-fill"></i> Personnel · visible par vous seul' : "Personnel" + (t.createdByUserId ? " · " + escapeHtml(t.createdByUserId) : "")) + '</span>');
        return '<div class="mt-tpl" style="animation-delay:' + ((index || 0) * 40) + 'ms">' +
            '<div class="mt-tpl-top"><span class="mt-tpl-ic"><i class="bi bi-envelope"></i></span><div class="min-w-0">' +
            (query && cat ? '<div class="mt-tpl-cat">' + escapeHtml(cat.label) + '</div>' : "") +
            '<div class="mt-tpl-subject">' + highlight(t.subject, query) + '</div></div></div>' +
            '<p class="mt-tpl-snippet">' + highlight(snippet(t.body), query) + '</p>' +
            '<div class="mt-tpl-tags">' + tags.join("") + '</div>' +
            '<div class="mt-tpl-actions">' +
            '<button class="mt-btn mt-btn-primary mt-btn-sm use-template-btn" data-id="' + t.id + '"><i class="bi bi-magic"></i> Utiliser</button>' +
            (canDelete ? '<button class="mt-btn mt-btn-danger mt-btn-sm delete-template-btn" data-id="' + t.id + '" title="Supprimer ce masque"><i class="bi bi-trash3"></i></button>' : '') +
            '</div></div>';
    }

    function wireTemplateButtons(root) {
        Array.prototype.forEach.call(root.querySelectorAll(".use-template-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                openUseTemplate(Number(btn.getAttribute("data-id")));
            });
        });
        Array.prototype.forEach.call(root.querySelectorAll(".delete-template-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                if (!confirm("Supprimer ce masque de mail ? Cette action est définitive.")) return;
                sendJson("/api/mail-templates/" + btn.getAttribute("data-id"), "DELETE")
                    .then(function () { toast("Masque supprimé"); return loadMailTemplates(); })
                    .catch(function (e) { toast("Erreur : " + e.message, true); });
            });
        });
    }

    // ===================== RECHERCHE =====================

    function visibleTemplates() {
        // Un agent Outbound verrouillé ne cherche que dans son équipe.
        var templates = overviewCache.templates || [];
        if (!isLockedOutboundAgent) return templates;
        var ids = categoriesForTeam(currentTeamCode).map(function (c) { return c.id; });
        return templates.filter(function (t) { return ids.indexOf(t.categoryId) !== -1; });
    }

    function renderSearch() {
        var query = ($("mtSearch").value || "").trim();
        var view = $("mtSearchView");
        if (!query) { view.hidden = true; return; }
        var norm = function (x) { return (x || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, ""); };
        var terms = norm(query).split(/\s+/).filter(Boolean);
        var hits = visibleTemplates().filter(function (t) {
            var cat = (overviewCache.categories || []).find(function (c) { return c.id === t.categoryId; });
            var hay = norm(t.subject + " " + t.body + " " + (cat ? cat.label : ""));
            return terms.every(function (term) { return hay.indexOf(term) !== -1; });
        });
        view.hidden = false;
        $("mtSearchCount").textContent = hits.length;
        var box = $("mtSearchResults");
        box.innerHTML = hits.length
            ? hits.slice(0, 30).map(function (t, i) { return templateCardHtml(t, query.split(/\s+/)[0], i); }).join("")
            : '<div class="mt-empty-state" style="grid-column:1/-1"><div class="mt-empty-art"><i class="bi bi-search"></i></div><b>Aucun masque ne correspond à « ' + escapeHtml(query) + ' »</b><small>Essayez un autre mot-clé, ou créez ce masque.</small></div>';
        wireTemplateButtons(box);
    }

    $("mtSearch").addEventListener("input", renderSearch);
    document.addEventListener("keydown", function (e) {
        var tag = (e.target.tagName || "").toLowerCase();
        if (e.key === "/" && tag !== "input" && tag !== "textarea" && tag !== "select" && !e.target.isContentEditable) {
            e.preventDefault();
            $("mtSearch").focus();
        }
        if (e.key === "Escape" && e.target === $("mtSearch")) { $("mtSearch").value = ""; renderSearch(); }
    });

    // ===================== CATÉGORIES NON CLASSÉES (QA/ADMIN) =====================

    $("mtUnclassifiedToggle").addEventListener("click", function () {
        var section = $("mtUnclassifiedSection");
        section.classList.toggle("open");
        this.setAttribute("aria-expanded", section.classList.contains("open") ? "true" : "false");
    });

    function renderUnclassified() {
        var section = $("mtUnclassifiedSection");
        if (!isQaOrAdmin) { section.style.display = "none"; return; }

        var unclassified = (overviewCache.categories || []).filter(function (c) { return !c.team; });
        if (!unclassified.length) { section.style.display = "none"; return; }

        section.style.display = "";
        $("mtUnclassifiedCount").textContent = unclassified.length;
        $("mtUnclassifiedList").innerHTML = unclassified.map(function (c, i) {
            return '<div class="mt-unc-item" style="animation-delay:' + (i * 40) + 'ms">' + catIconHtml(c.label) +
                '<span class="lbl" title="' + escapeHtml(c.label) + '">' + escapeHtml(c.label) + '</span>' +
                '<select class="form-select form-select-sm" data-team-select="' + c.id + '">' +
                    TEAMS.map(function (t) { return '<option value="' + t.code + '">' + escapeHtml(t.label) + '</option>'; }).join("") +
                '</select>' +
                '<button class="mt-btn mt-btn-primary mt-btn-sm" data-classify="' + c.id + '"><i class="bi bi-check2"></i> Classer</button>' +
            '</div>';
        }).join("");

        Array.prototype.forEach.call($("mtUnclassifiedList").querySelectorAll("[data-classify]"), function (btn) {
            btn.addEventListener("click", function () {
                var id = btn.getAttribute("data-classify");
                var select = $("mtUnclassifiedList").querySelector('[data-team-select="' + id + '"]');
                var team = select.value;
                btn.disabled = true;
                sendJson("/api/mail-templates/categories/" + id + "/team", "PUT", { team: team })
                    .then(function () {
                        var row = btn.closest(".mt-unc-item");
                        row.classList.add("leaving");
                        toast("Catégorie rattachée à « " + select.selectedOptions[0].textContent + " » ✓");
                        setTimeout(loadMailTemplates, 300);
                    })
                    .catch(function (e) { btn.disabled = false; toast("Erreur : " + e.message, true); });
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
        if (!label) { toast("Le nom de la catégorie est obligatoire.", true); return; }
        sendJson("/api/mail-templates/categories", "POST", {
            label: label,
            team: $("newCategoryTeam").value,
            iconGlyph: $("newCategoryIcon").value.trim() || null
        }).then(function () {
            newCategoryModal.hide();
            toast("Catégorie créée ✓");
            loadMailTemplates();
        }).catch(function (e) { toast("Erreur : " + e.message, true); });
    });

    // ===================== CRÉER UN MASQUE (modale + aperçu) =====================

    var newTemplateModal = null;

    function setRecipientType(type) {
        $("newTemplateRecipientType").value = type;
        Array.prototype.forEach.call($("mtRecipientSeg").querySelectorAll("[data-rt]"), function (b) {
            b.classList.toggle("active", b.getAttribute("data-rt") === type);
        });
        $("newTemplateRecipientGroup").style.display = type === "service" ? "" : "none";
        refreshCreatePreview();
    }

    Array.prototype.forEach.call($("mtRecipientSeg").querySelectorAll("[data-rt]"), function (b) {
        b.addEventListener("click", function () { setRecipientType(b.getAttribute("data-rt")); });
    });
    $("newTemplateRecipientGroup").addEventListener("change", refreshCreatePreview);
    $("newTemplateSubject").addEventListener("input", refreshCreatePreview);
    $("newTemplateBody").addEventListener("input", refreshCreatePreview);

    Array.prototype.forEach.call($("mtPlaceholderChips").querySelectorAll("[data-ph]"), function (b) {
        b.addEventListener("click", function () {
            var area = $("newTemplateBody");
            var token = "[" + b.getAttribute("data-ph") + "]";
            var start = area.selectionStart != null ? area.selectionStart : area.value.length;
            var end = area.selectionEnd != null ? area.selectionEnd : area.value.length;
            area.value = area.value.slice(0, start) + token + area.value.slice(end);
            area.focus();
            area.selectionStart = area.selectionEnd = start + token.length;
            refreshCreatePreview();
        });
    });

    function withVars(text) {
        return escapeHtml(text).replace(/\[([A-Za-zÀ-ÿ_]+)\]/g, '<span class="mt-var">[$1]</span>');
    }

    function refreshCreatePreview() {
        var subject = $("newTemplateSubject").value;
        var body = $("newTemplateBody").value;
        $("mtPrevSubject").className = subject ? "" : "mt-ph";
        $("mtPrevSubject").innerHTML = subject ? withVars(subject) : "Objet du mail";
        $("mtPrevBody").innerHTML = body ? withVars(body) : '<span class="mt-ph">Le corps du message apparaîtra ici.</span>';
        var to = "client@exemple.com";
        if ($("newTemplateRecipientType").value === "service") {
            var g = recipientGroupsCache.find(function (x) { return String(x.id) === $("newTemplateRecipientGroup").value; });
            to = g ? (g.email || g.label) : "service";
        }
        $("mtPrevTo").textContent = to;
        var fields = placeholdersOf(subject + " " + body);
        $("mtPrevFields").innerHTML = fields.length
            ? fields.map(function (f) { return '<span class="mt-tag mt-tag-blue">' + escapeHtml(humanizePlaceholder(f)) + '</span>'; }).join("")
            : '<span class="mt-muted small">Aucun pour l\'instant.</span>';
    }

    function openCreate(categoryId) {
        var select = $("newTemplateCategory");
        if (categoryId) {
            select.value = String(categoryId);
        } else if (currentTeamCode) {
            var first = categoriesForTeam(currentTeamCode)[0];
            if (first) select.value = String(first.id);
        }
        $("newTemplateSubject").value = "";
        $("newTemplateBody").value = "";
        setRecipientType("person");
        refreshCreatePreview();
        newTemplateModal.show();
        setTimeout(function () { $("newTemplateSubject").focus(); }, 350);
    }

    $("mtOpenCreateBtn").addEventListener("click", function () { openCreate(null); });
    $("mtDetailCreateBtn").addEventListener("click", function () { openCreate(null); });

    $("createTemplateBtn").addEventListener("click", function () {
        var categoryId = Number($("newTemplateCategory").value);
        var subject = $("newTemplateSubject").value.trim();
        var body = $("newTemplateBody").value.trim();
        var recipientType = $("newTemplateRecipientType").value;
        var recipientGroupId = recipientType === "service" ? Number($("newTemplateRecipientGroup").value) : null;

        if (!categoryId || !subject || !body) {
            toast("Catégorie, objet et corps du message sont obligatoires.", true);
            (!subject ? $("newTemplateSubject") : $("newTemplateBody")).focus();
            return;
        }

        var btn = $("createTemplateBtn");
        btn.disabled = true;
        sendJson("/api/mail-templates", "POST", {
            categoryId: categoryId, subject: subject, body: body,
            recipientType: recipientType, recipientGroupId: recipientGroupId
        }).then(function () {
            newTemplateModal.hide();
            toast("Masque créé ✓");
            var cat = (overviewCache.categories || []).find(function (c) { return c.id === categoryId; });
            return loadMailTemplates().then(function () {
                if (cat && cat.team && !isLockedOutboundAgent) {
                    if (currentTeamCode !== cat.team) selectTeam(cat.team);
                    var card = document.querySelector('.mt-category-card[data-category="' + categoryId + '"]');
                    if (card) { card.classList.add("open"); card.scrollIntoView({ behavior: "smooth", block: "center" }); }
                }
            });
        }).catch(function (e) { toast("Erreur : " + e.message, true); })
          .then(function () { btn.disabled = false; });
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
            $("mtUseTitle").textContent = template ? template.subject : "Masque";
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
                    input.addEventListener("input", function () {
                        input.classList.toggle("filled", !!input.value.trim());
                        refreshFilledMail();
                    });
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

        var inputs = $("clientFieldsRow").querySelectorAll(".client-field-input");
        var filled = Array.prototype.filter.call(inputs, function (i) { return i.value.trim(); }).length;
        var label = $("mtFillProgress");
        if (label) {
            label.textContent = inputs.length ? filled + "/" + inputs.length + " champs remplis" : "";
            label.classList.toggle("done", inputs.length > 0 && filled === inputs.length);
        }

        var values = {};
        Array.prototype.forEach.call($("clientFieldsRow").querySelectorAll(".client-field-input"), function (input) {
            values[input.getAttribute("data-key")] = input.value;
        });

        sendJson("/api/mail-templates/" + templateId + "/fill", "POST", { values: values }).then(function (result) {
            $("filledSubject").value = result.subject;
            $("filledBody").value = result.body;
            renderRichPreview(result.body);
        }).catch(function (e) { console.error(e); });

        if (!$("filledRecipientTo").getAttribute("data-user-edited")) {
            $("filledRecipientTo").value = resolveDefaultRecipient();
        }
    }

    // ===================== TABLEAUX D'ESCALADE =====================
    // Corps au format « === TITRE === » suivi de lignes « LIBELLÉ : valeur » → vrai tableau HTML
    // (en-tête coloré comme les tableaux Excel d'escalade) collable dans Outlook.

    var TABLE_TITLE = /^===\s*(.+?)\s*===\s*$/;
    var TABLE_ROW = /^([^:\n]{2,60}?)\s*:\s*(.*)$/;

    function hasTable(body) {
        return (body || "").split("\n").some(function (l) { return TABLE_TITLE.test(l.trim()); });
    }

    function bodyToHtml(body) {
        var lines = (body || "").split("\n");
        var html = [];
        var i = 0;
        var cell = "border:1px solid #1f1f1f;padding:3px 8px;font:12px Calibri,Arial,sans-serif;";
        while (i < lines.length) {
            var m = TABLE_TITLE.exec(lines[i].trim());
            if (m) {
                var title = m[1];
                var head = /DSD|RÉCLAMATION|RECLAMATION|GAB/i.test(title) ? "#F4806B" : "#D9EAD3";
                var rows = [];
                i++;
                while (i < lines.length && lines[i].trim() && TABLE_ROW.test(lines[i].trim())) {
                    var r = TABLE_ROW.exec(lines[i].trim());
                    rows.push('<tr><td style="' + cell + 'font-weight:bold;width:45%;">' + escapeHtml(r[1]) + '</td>' +
                        '<td style="' + cell + (/AUTHENTIFI/i.test(r[2]) ? "text-align:center;" : "") + '">' + escapeHtml(r[2]) + '</td></tr>');
                    i++;
                }
                html.push('<table style="border-collapse:collapse;min-width:420px;margin:6px 0 10px;">' +
                    '<tr><th colspan="2" style="' + cell + 'background:' + head + ';text-align:center;font-weight:bold;">' + escapeHtml(title) + '</th></tr>' +
                    rows.join("") + '</table>');
                continue;
            }
            html.push(lines[i].trim() ? '<div style="font:13px Calibri,Arial,sans-serif;">' + escapeHtml(lines[i]) + '</div>' : '<div>&nbsp;</div>');
            i++;
        }
        return html.join("");
    }

    function bodyToText(body) {
        return (body || "").replace(/^===\s*(.+?)\s*===\s*$/gm, "$1");
    }

    function renderRichPreview(body) {
        var wrap = $("mtRichWrap");
        if (!wrap) return;
        var show = hasTable(body);
        wrap.hidden = !show;
        if (show) $("mtRichPreview").innerHTML = bodyToHtml(body);
    }

    /** Copie riche (tableau HTML) si possible, texte sinon. */
    function copyMail(subjectToo) {
        var body = $("filledBody").value;
        var plain = (subjectToo ? $("filledSubject").value + "\n\n" : "") + bodyToText(body);
        if (hasTable(body) && window.ClipboardItem && navigator.clipboard && navigator.clipboard.write) {
            var html = '<div>' + (subjectToo ? '<div style="font:bold 13px Calibri,Arial,sans-serif;">' + escapeHtml($("filledSubject").value) + '</div><div>&nbsp;</div>' : "") + bodyToHtml(body) + '</div>';
            return navigator.clipboard.write([new ClipboardItem({
                "text/html": new Blob([html], { type: "text/html" }),
                "text/plain": new Blob([plain], { type: "text/plain" })
            })]).catch(function () { return navigator.clipboard.writeText(plain); });
        }
        return navigator.clipboard.writeText(plain);
    }

    $("sendFilledMailBtn").addEventListener("click", function () {
        var subject = $("filledSubject").value;
        var body = $("filledBody").value;
        if (!subject && !body) return;

        var to = $("filledRecipientTo").value.trim();
        var cc = $("filledRecipientCc").value.trim();

        var mailto = "mailto:" + encodeURIComponent(to) + "?subject=" + encodeURIComponent(subject);
        if (cc) mailto += "&cc=" + encodeURIComponent(cc);
        if (hasTable(body)) {
            // Outlook n'accepte que du texte via mailto : le tableau est copié, l'agent le colle (Ctrl+V).
            copyMail(false).catch(function () {});
            mailto += "&body=" + encodeURIComponent(bodyToText(body));
        } else {
            mailto += "&body=" + encodeURIComponent(body);
        }

        window.location.href = mailto;
    });

    $("copyFilledMailBtn").addEventListener("click", function () {
        copyMail(true).then(function () {
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
        newTemplateModal = new bootstrap.Modal($("newTemplateModal"));
    });

    window.RccSession.init().then(function (session) {
        currentProfile = session ? session.profile : null;
        currentUsername = session && session.user ? session.user.username : null;
        if (session) applyQaVisibility(session.profile);
        if ($("mtCreateCardHeader")) {
            $("mtCreateCardHeader").textContent = isQaOrAdmin ? "Nouveau masque" : "Créer mon masque";
            $("mtCreateTitle").textContent = isQaOrAdmin ? "Créer un masque de mail" : "Créer mon masque de mail";
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
