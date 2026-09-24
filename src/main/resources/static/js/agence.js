"use strict";

/**
 * Portail Agence — caissiers et gestionnaires clientèle. Réutilise les référentiels du RCC
 * (cartographie des cas, recherche procédures/KB, disponibilité cartes, agences, masques
 * d'escalade) pour un traitement uniforme, plus une boîte à outils locale (agence-tools.js).
 * Préférences locales (poste, agence, onglet) uniquement — jamais de donnée client stockée.
 */
(function () {

    var getJson = RccApi.getJson;
    var esc = RccApi.escapeHtml;
    var PREF_KEY = "rcc.agence.prefs";
    var COUNTRY = "CI";

    var prefs = (function () { try { return JSON.parse(localStorage.getItem(PREF_KEY) || "{}"); } catch (e) { return {}; } })();
    function savePrefs() { try { localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch (e) { /* navigateur privé */ } }
    function $(id) { return document.getElementById(id); }

    var branches = [];
    var cardReport = null;
    var CASES = (window.RccCaseMap && window.RccCaseMap.all) || [];

    // ── Onglets ─────────────────────────────────────────────────────────────
    var loaded = {};
    function showTab(name) {
        document.querySelectorAll("#agTabs button").forEach(function (b) { b.classList.toggle("active", b.dataset.tab === name); });
        document.querySelectorAll(".ag-pane").forEach(function (p) { p.classList.toggle("active", p.dataset.pane === name); });
        prefs.tab = name; savePrefs();
        if (!loaded[name]) { loaded[name] = true; (LOADERS[name] || function () {})(); }
        var active = document.querySelector('#agTabs button[data-tab="' + name + '"]');
        if (active && active.scrollIntoView) active.scrollIntoView({ block: "nearest", inline: "center" });
    }
    document.querySelectorAll("#agTabs button").forEach(function (b) { b.addEventListener("click", function () { showTab(b.dataset.tab); }); });
    document.addEventListener("click", function (e) {
        var g = e.target.closest("[data-goto]");
        if (g) { e.preventDefault(); showTab(g.dataset.goto); window.scrollTo({ top: 0, behavior: "smooth" }); }
    });

    // ── Poste (caissier / gestionnaire) ─────────────────────────────────────
    function setMode(mode) {
        prefs.mode = mode; savePrefs();
        document.querySelectorAll(".ag-mode button").forEach(function (b) { b.classList.toggle("active", b.dataset.mode === mode); });
        renderTiles();
        if (loaded.outils) renderToolNav();
    }
    document.querySelectorAll(".ag-mode button").forEach(function (b) { b.addEventListener("click", function () { setMode(b.dataset.mode); }); });

    // ── Horloge ─────────────────────────────────────────────────────────────
    function tick() {
        var d = new Date();
        $("agClock").textContent = d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
        $("agDate").textContent = d.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long" });
    }
    tick(); setInterval(tick, 20000);

    // ── Tuiles d'accueil ────────────────────────────────────────────────────
    function renderTiles() {
        var caissier = prefs.mode === "CAISSIER";
        var tiles = [
            { tab: "cas", icon: "bi-diagram-3-fill", color: "#0057B8", title: "Cartographie des cas", sub: CASES.length + " cas, même classement que le RCC" },
            { tab: "recherche", icon: "bi-journal-text", color: "#00838F", title: "Procédures & KB", sub: "La procédure en vigueur, en un mot-clé" },
            { tab: "cartes", icon: "bi-credit-card-2-front-fill", color: "#E0435B", title: "Cartes & PIN", sub: "Disponibilité par agence" },
            { tab: "escalades", icon: "bi-envelope-paper-fill", color: "#2E7D32", title: "Escalades", sub: "Reset PIN, DSD GAB, linkage…" },
            { tab: "transmission", icon: "bi-send-check-fill", color: "#6A1B9A", title: "Transmettre au RCC", sub: "Fiche masquée, prête à envoyer" },
            { tab: "agences", icon: "bi-bank2", color: "#F57C00", title: "Agences & GAB", sub: "Orienter le client" }
        ];
        var tools = caissier
            ? [{ tool: "billetage", icon: "bi-cash-stack", color: "#1B5E20", title: "Billetage", sub: "Compter et équilibrer la caisse" },
               { tool: "rendu", icon: "bi-coin", color: "#EF6C00", title: "Rendu de monnaie", sub: "Coupures optimales" }]
            : [{ tool: "pret", icon: "bi-piggy-bank-fill", color: "#0D47A1", title: "Simulateur de prêt", sub: "Mensualité + amortissement" },
               { tool: "kyc", icon: "bi-list-check", color: "#1565C0", title: "Checklist ouverture", sub: "Pièces à contrôler" }];
        $("agTiles").innerHTML = tools.concat(tiles).map(function (t, i) {
            return '<button type="button" class="ag-tile" style="--c:' + t.color + ';animation-delay:' + (i * 50) + 'ms" ' +
                (t.tool ? 'data-tool="' + t.tool + '"' : 'data-goto="' + t.tab + '"') + '>' +
                '<span class="ag-tile-ico"><i class="bi ' + t.icon + '"></i></span><span class="ag-tile-title">' + esc(t.title) + '</span>' +
                '<span class="ag-tile-sub">' + esc(t.sub) + '</span></button>';
        }).join("");
        $("agTiles").querySelectorAll("[data-tool]").forEach(function (b) {
            b.addEventListener("click", function () { showTab("outils"); openTool(b.dataset.tool); });
        });
    }

    // ── Recherche rapide dans la cartographie (accueil) ─────────────────────
    function fold(s) { return (s || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9 ]+/g, " "); }
    var FCR_LABEL = { R: ["ok", "Résolu sur place"], A: ["auth", "Autorisation requise"], O: ["auth", "RCC Outbound"], G: ["cond", "Selon profil GTP"], L: ["cond", "Selon linkage"] };
    $("agQuickInput").addEventListener("input", function (e) {
        var q = fold(e.target.value).trim();
        var box = $("agQuickResults");
        if (!q) { box.innerHTML = ""; return; }
        var words = q.split(/\s+/);
        var hits = CASES.filter(function (c) { return words.every(function (w) { return c.hay.indexOf(w) !== -1; }); }).slice(0, 6);
        box.innerHTML = hits.length ? hits.map(function (c) {
            var f = FCR_LABEL[c.fcr];
            return '<button type="button" class="ag-quick-hit" data-id="' + c.id + '"><i class="bi ' + c.product.icon + '" style="color:' + c.product.color + '"></i>' +
                '<span><b>' + esc(c.pretty) + '</b><small>' + esc(c.type + " › " + c.cat + " › " + c.sub) + '</small></span>' +
                '<span class="cm-fcr ' + f[0] + '">' + f[1] + '</span></button>';
        }).join("") + '<button type="button" class="ag-quick-more" id="agQuickMore"><i class="bi bi-search"></i> Chercher « ' + esc(e.target.value) + ' » dans les procédures</button>'
            : '<div class="text-muted small p-2">Aucun cas cartographié — <a href="#" id="agQuickMore">chercher dans les procédures</a>.</div>';
        box.querySelectorAll(".ag-quick-hit").forEach(function (b) {
            b.addEventListener("click", function () { window.RccCaseMap.open(e.target.value); });
        });
        var more = $("agQuickMore");
        if (more) more.addEventListener("click", function (ev) { ev.preventDefault(); showTab("recherche"); $("agSearchInput").value = e.target.value; runSearch(e.target.value); });
    });

    // ── Agences ─────────────────────────────────────────────────────────────
    function agencyCode(name) { var m = /\(([A-Z]{1,3}\d{1,4})\)\s*$/.exec(name || ""); return m ? m[1] : null; }

    function loadBranches() {
        return getJson("/api/bank-branches?country=" + COUNTRY).then(function (list) {
            branches = list || [];
            var sel = $("agAgencySelect");
            sel.innerHTML = '<option value="">Mon agence…</option>' + branches
                .slice().sort(function (a, b) { return (a.name || "").localeCompare(b.name || ""); })
                .map(function (b) { return '<option value="' + b.id + '">' + esc(b.name) + '</option>'; }).join("");
            if (prefs.agencyId) sel.value = String(prefs.agencyId);
            renderMyCards();
            return branches;
        }).catch(function () { branches = []; });
    }
    $("agAgencySelect").addEventListener("change", function (e) {
        prefs.agencyId = e.target.value ? Number(e.target.value) : null; savePrefs();
        renderMyCards();
    });

    function myBranch() { return branches.filter(function (b) { return b.id === prefs.agencyId; })[0] || null; }

    function renderBranches() {
        var q = fold($("agBranchFilter").value).trim();
        var list = branches.filter(function (b) {
            return !q || fold([b.name, b.city, b.address].join(" ")).indexOf(q) !== -1;
        });
        $("agBranches").innerHTML = list.length ? list.map(function (b, i) {
            var code = agencyCode(b.name);
            var maps = b.latitude != null ? "https://www.google.com/maps/dir/?api=1&destination=" + b.latitude + "," + b.longitude : null;
            return '<div class="ag-branch' + (b.id === prefs.agencyId ? " mine" : "") + '" style="animation-delay:' + Math.min(i, 20) * 25 + 'ms">' +
                '<div class="ag-branch-top"><span class="ag-branch-code">' + esc(code || "—") + '</span>' + (b.id === prefs.agencyId ? '<span class="ag-mine-badge">Mon agence</span>' : "") + '</div>' +
                '<div class="ag-branch-name">' + esc((b.name || "").replace(/\s*\([A-Z]{1,3}\d{1,4}\)\s*$/, "")) + '</div>' +
                '<div class="ag-branch-city"><i class="bi bi-geo-alt"></i> ' + esc(b.city || "") + '</div>' +
                (b.openingHours ? '<div class="ag-branch-meta"><i class="bi bi-clock"></i> ' + esc(b.openingHours) + '</div>' : "") +
                (b.phone ? '<div class="ag-branch-meta"><i class="bi bi-telephone"></i> ' + esc(b.phone) + '</div>' : "") +
                (maps ? '<a class="ag-branch-link" href="' + maps + '" target="_blank" rel="noopener"><i class="bi bi-sign-turn-right"></i> Itinéraire</a>' : "") +
                '</div>';
        }).join("") : '<p class="text-muted">Aucune agence ne correspond.</p>';
    }
    $("agBranchFilter").addEventListener("input", renderBranches);

    // ── Cartes & PIN ────────────────────────────────────────────────────────
    var STATUS = { OK: ["ok", "Disponible"], FAIBLE: ["low", "Stock faible"], RUPTURE: ["out", "Rupture"] };
    function statusChip(s) {
        var st = STATUS[(s || "").toUpperCase()] || ["unk", s || "—"];
        return '<span class="ag-st ' + st[0] + '">' + esc(st[1]) + '</span>';
    }

    function loadCards() {
        return getJson("/api/card-availability/agencies?country=" + COUNTRY).then(function (r) { cardReport = r; return r; })
            .catch(function () { cardReport = null; });
    }

    function renderMyCards() {
        var box = $("agMyCards");
        var b = myBranch();
        if (!b) { box.innerHTML = '<p class="text-muted small mb-0">Choisissez votre agence en haut de page.</p>'; return; }
        var code = agencyCode(b.name);
        var run = function () {
            var rows = cardReport && cardReport.rows ? cardReport.rows.filter(function (r) {
                return (code && r.agencyCode && r.agencyCode.toUpperCase() === code) || fold(b.name).indexOf(fold(r.agency).trim()) !== -1;
            }) : [];
            if (!rows.length) {
                box.innerHTML = '<p class="small mb-1"><b>' + esc(b.name) + '</b></p><p class="text-muted small mb-0">Pas de point cartes pour cette agence' +
                    (cardReport && cardReport.reportDate ? " au " + new Date(cardReport.reportDate).toLocaleDateString("fr-FR") : "") + '.</p>';
                return;
            }
            var r = rows[0];
            box.innerHTML = '<div class="ag-mycard"><div><small class="text-muted">Point du ' + new Date(r.reportDate).toLocaleDateString("fr-FR") + '</small>' +
                '<div class="fw-bold">' + esc(b.name) + '</div></div>' +
                '<div class="ag-mycard-row"><span>Cartes</span>' + statusChip(r.cardStatus) + '</div>' +
                '<div class="ag-mycard-row"><span>Codes PIN</span>' + statusChip(r.pinStatus) + '</div>' +
                '<div class="ag-types">' + (r.cardTypes || []).map(function (t) { return '<span>' + esc(t) + '</span>'; }).join("") + '</div></div>';
        };
        if (cardReport === null) loadCards().then(run); else run();
    }

    function renderCards() {
        var q = fold($("agCardFilter").value).trim();
        var rows = cardReport && cardReport.rows ? cardReport.rows : [];
        $("agCardsInfo").textContent = cardReport && cardReport.reportDate
            ? "Point du " + new Date(cardReport.reportDate).toLocaleDateString("fr-FR") + " — mis à jour par la QA / la monétique."
            : "Aucun point de disponibilité publié.";
        var list = rows.filter(function (r) { return !q || fold([r.agency, r.agencyCode, (r.cardTypes || []).join(" ")].join(" ")).indexOf(q) !== -1; });
        var mine = myBranch() ? agencyCode(myBranch().name) : null;
        $("agCardsTable").innerHTML = list.length ? '<table class="table table-hover align-middle ag-table"><thead><tr><th>Agence</th><th>Code</th><th>Cartes</th><th>PIN</th><th>Types disponibles</th></tr></thead><tbody>' +
            list.map(function (r) {
                return '<tr class="' + (mine && r.agencyCode === mine ? "mine" : "") + '"><td class="fw-semibold">' + esc(r.agency) + '</td><td>' + esc(r.agencyCode || "") + '</td><td>' + statusChip(r.cardStatus) +
                    '</td><td>' + statusChip(r.pinStatus) + '</td><td>' + (r.cardTypes || []).map(function (t) { return '<span class="ag-type">' + esc(t) + '</span>'; }).join(" ") + '</td></tr>';
            }).join("") + '</tbody></table>' : '<p class="text-muted">Aucune ligne.</p>';
    }
    $("agCardFilter").addEventListener("input", renderCards);

    // ── Recherche unifiée ───────────────────────────────────────────────────
    var SRC = {
        PROCEDURE: ["bi-list-check", "Procédure", function (id) { return "/procedures?openProcedureId=" + id; }],
        ARTICLE: ["bi-book", "Base de connaissances", function (id) { return "/knowledge?article=" + id; }],
        COURSE: ["bi-mortarboard", "Formation", function () { return "/training"; }],
        QUIZ: ["bi-patch-question", "Quiz", function () { return "/training"; }]
    };
    function runSearch(q) {
        q = (q || "").trim();
        var box = $("agSearchResults");
        if (!q) { box.innerHTML = ""; return; }
        box.innerHTML = '<div class="ag-loading"><span></span><span></span><span></span></div>';
        getJson("/api/ralph/search?keyword=" + encodeURIComponent(q) + "&country=" + COUNTRY).then(function (r) {
            var items = (r && r.results) || [];
            var web = (r && r.webResults) || [];
            var cases = CASES.filter(function (c) { return fold(q).split(/\s+/).every(function (w) { return c.hay.indexOf(w) !== -1; }); }).slice(0, 4);
            var html = "";
            if (cases.length) {
                html += '<div class="ag-res-group"><i class="bi bi-diagram-3-fill"></i> Cas cartographiés</div>' + cases.map(function (c) {
                    var f = FCR_LABEL[c.fcr];
                    return '<button type="button" class="ag-res" data-case="1"><span class="ag-res-ico" style="background:' + c.product.color + '"><i class="bi ' + c.product.icon + '"></i></span>' +
                        '<span class="ag-res-body"><b>' + esc(c.pretty) + '</b><small>' + esc(c.type + " › " + c.cat + " › " + c.sub) + '</small></span><span class="cm-fcr ' + f[0] + '">' + f[1] + '</span></button>';
                }).join("");
            }
            if (items.length) {
                html += '<div class="ag-res-group"><i class="bi bi-journal-text"></i> Procédures, articles et formations</div>' + items.map(function (it) {
                    var s = SRC[it.sourceType] || ["bi-file-text", it.sourceType, function () { return "#"; }];
                    return '<a class="ag-res" href="' + s[2](it.id) + '"><span class="ag-res-ico"><i class="bi ' + s[0] + '"></i></span>' +
                        '<span class="ag-res-body"><b>' + esc(it.title) + '</b><small>' + esc(s[1]) + (it.snippet ? " — " + esc(it.snippet) : "") + '</small></span><i class="bi bi-chevron-right"></i></a>';
                }).join("");
            }
            if (web.length) {
                html += '<div class="ag-res-group"><i class="bi bi-globe2"></i> Sur le web (source externe à vérifier)</div>' + web.map(function (w) {
                    return '<a class="ag-res" href="' + esc(w.url) + '" target="_blank" rel="noopener"><span class="ag-res-ico web"><i class="bi bi-globe2"></i></span>' +
                        '<span class="ag-res-body"><b>' + esc(w.title) + '</b><small>' + esc(w.snippet || "") + '</small></span><i class="bi bi-box-arrow-up-right"></i></a>';
                }).join("");
            }
            box.innerHTML = html || '<p class="text-muted">Aucun résultat. Essayez un autre mot, ou demandez à RAF (bulle en bas à droite).</p>';
            box.querySelectorAll('[data-case]').forEach(function (b) { b.addEventListener("click", function () { window.RccCaseMap.open(q); }); });
        }).catch(function (e) { box.innerHTML = '<p class="text-danger">Recherche indisponible : ' + esc(e.message) + '</p>'; });
    }
    $("agSearchForm").addEventListener("submit", function (e) { e.preventDefault(); runSearch($("agSearchInput").value); });
    $("agSearchSuggest").innerHTML = ["opposition carte", "ouverture de compte", "reset mobile app", "virement international", "carte capturée", "relevé de compte"]
        .map(function (s) { return '<button type="button" data-s="' + esc(s) + '">' + esc(s) + '</button>'; }).join("");
    $("agSearchSuggest").querySelectorAll("button").forEach(function (b) {
        b.addEventListener("click", function () { $("agSearchInput").value = b.dataset.s; runSearch(b.dataset.s); });
    });

    // ── Cartographie (grille produits) ──────────────────────────────────────
    function renderProducts() {
        var data = window.RCC_CASE_MAP || [];
        $("agProdGrid").innerHTML = data.map(function (p, i) {
            var ok = p.cases.filter(function (c) { return c.fcr === "R"; }).length;
            return '<button type="button" class="ag-prod" style="--c:' + p.color + ';animation-delay:' + i * 35 + 'ms" data-q="' + esc(p.label.split(/[:(|]/)[0].trim()) + '">' +
                '<span class="ag-prod-ico"><i class="bi ' + p.icon + '"></i></span><span class="ag-prod-name">' + esc(p.label) + '</span>' +
                '<span class="ag-prod-meta">' + p.cases.length + ' cas · ' + Math.round(ok / p.cases.length * 100) + ' % résolus sur place</span></button>';
        }).join("");
        $("agProdGrid").querySelectorAll(".ag-prod").forEach(function (b) {
            b.addEventListener("click", function () { window.RccCaseMap.open(b.dataset.q); });
        });
    }
    $("agOpenCaseMap").addEventListener("click", function () { window.RccCaseMap.open(); });

    // ── Escalades ───────────────────────────────────────────────────────────
    function loadEscalations() {
        getJson("/api/mail-templates").then(function (o) {
            var cat = (o.categories || []).filter(function (c) { return c.code === "ESCALADE"; })[0];
            var tpls = (o.templates || []).filter(function (t) { return cat && t.categoryId === cat.id; });
            $("agEscalations").innerHTML = tpls.length ? tpls.map(function (t, i) {
                var fields = ((t.subject + " " + t.body).match(/\[([A-Za-zÀ-ÿ_]+)\]/g) || []).filter(function (v, k, a) { return a.indexOf(v) === k; });
                var title = t.subject.replace(/\s*—\s*\[[^\]]+\]\s*$/, "");
                return '<div class="ag-esc" style="animation-delay:' + i * 50 + 'ms"><div class="ag-esc-ico"><i class="bi bi-table"></i></div>' +
                    '<div class="ag-esc-title">' + esc(title) + '</div><div class="ag-esc-fields">' + fields.length + ' champ(s) à remplir</div>' +
                    '<a class="btn btn-sm btn-primary mt-2" href="/mail-templates?template=' + t.id + '"><i class="bi bi-magic"></i> Préparer le mail</a></div>';
            }).join("") : '<p class="text-muted">Aucun masque d\'escalade disponible.</p>';
        }).catch(function (e) { $("agEscalations").innerHTML = '<p class="text-danger">Masques indisponibles : ' + esc(e.message) + '</p>'; });
    }

    // ── Transmission au RCC ─────────────────────────────────────────────────
    function fillCaseSelect() {
        var sel = $("agTrCase");
        var data = window.RCC_CASE_MAP || [];
        sel.innerHTML = '<option value="">— Choisir le cas —</option>' + data.map(function (p) {
            return '<optgroup label="' + esc(p.label) + '">' + p.cases.map(function (c) {
                return '<option value="' + c.id + '">' + esc(window.RccCaseMap.pretty(c.t)) + '</option>';
            }).join("") + '</optgroup>';
        }).join("");
    }
    function transmissionText() {
        var mask = window.AgenceTools.calc.maskSensitive;
        var c = CASES.filter(function (x) { return x.id === $("agTrCase").value; })[0];
        var b = myBranch();
        var lines = [
            "FICHE DE TRANSMISSION AGENCE → RCC",
            "Date : " + new Date().toLocaleString("fr-FR"),
            "Agence : " + (b ? b.name : "(à préciser)"),
            "Poste : " + (prefs.mode === "CAISSIER" ? "Caissier" : "Gestionnaire clientèle"),
            "Urgence : " + $("agTrUrgency").value,
            "",
            "Motif : " + (c ? c.pretty : "(à préciser)"),
            c ? "Classification CRM : " + c.type + " > " + c.cat + " > " + c.sub : "",
            "Référence client : " + (mask($("agTrRef").value) || "(non communiquée)"),
            "Rappel souhaité : " + $("agTrChannel").value,
            "",
            "Fait en agence :",
            mask($("agTrDone").value) || "-",
            "",
            "Action attendue du RCC :",
            mask($("agTrAsk").value) || "-"
        ];
        return lines.filter(function (l, i) { return l !== "" || i > 0; }).join("\n");
    }
    function updateTransmission() {
        var txt = transmissionText();
        $("agTrPreview").textContent = txt;
        var c = CASES.filter(function (x) { return x.id === $("agTrCase").value; })[0];
        $("agTrMail").href = "mailto:?subject=" + encodeURIComponent("[Agence → RCC] " + (c ? c.pretty : "Transmission") + " — " + $("agTrUrgency").value) +
            "&body=" + encodeURIComponent(txt);
    }
    ["agTrCase", "agTrUrgency", "agTrRef", "agTrChannel", "agTrDone", "agTrAsk"].forEach(function (id) {
        $(id).addEventListener("input", updateTransmission);
        $(id).addEventListener("change", updateTransmission);
    });
    $("agTrCopy").addEventListener("click", function (e) {
        var btn = e.currentTarget, txt = transmissionText();
        var ok = function () { var o = btn.innerHTML; btn.innerHTML = '<i class="bi bi-check2"></i> Copiée'; setTimeout(function () { btn.innerHTML = o; }, 1300); };
        if (navigator.clipboard) navigator.clipboard.writeText(txt).then(ok, function () { prompt("Copiez :", txt); }); else prompt("Copiez :", txt);
    });
    $("agTrReset").addEventListener("click", function () {
        ["agTrRef", "agTrDone", "agTrAsk"].forEach(function (id) { $(id).value = ""; });
        $("agTrCase").value = "";
        updateTransmission();
    });

    // ── Outils ──────────────────────────────────────────────────────────────
    var currentTool = null;
    function renderToolNav() {
        var order = window.AgenceTools.order(prefs.mode);
        $("agToolNav").innerHTML = order.map(function (id, i) {
            var t = window.AgenceTools.tools[id];
            return '<button type="button" class="ag-tool-btn' + (id === currentTool ? " active" : "") + '" data-tool="' + id + '" style="--c:' + t.color + ';animation-delay:' + i * 40 + 'ms">' +
                '<span class="ag-tool-ico"><i class="bi ' + t.icon + '"></i></span><span><b>' + esc(t.label) + '</b><small>' + esc(t.desc) + '</small></span></button>';
        }).join("");
        $("agToolNav").querySelectorAll("[data-tool]").forEach(function (b) { b.addEventListener("click", function () { openTool(b.dataset.tool); }); });
        if (!currentTool) openTool(order[0]);
    }
    function openTool(id) {
        if (!loaded.outils) { loaded.outils = true; }
        currentTool = id;
        if (!$("agToolNav").children.length) renderToolNav();
        $("agToolNav").querySelectorAll("[data-tool]").forEach(function (b) { b.classList.toggle("active", b.dataset.tool === id); });
        window.AgenceTools.render(id, $("agToolBody")); // re-rendu = champs vidés (aucune saisie conservée)
    }

    var LOADERS = {
        accueil: function () {},
        cas: renderProducts,
        recherche: function () {},
        cartes: function () { (cardReport ? Promise.resolve() : loadCards()).then(renderCards); },
        agences: function () { (branches.length ? Promise.resolve() : loadBranches()).then(renderBranches); },
        escalades: loadEscalations,
        transmission: function () { fillCaseSelect(); updateTransmission(); },
        outils: renderToolNav
    };

    // ── Démarrage ───────────────────────────────────────────────────────────
    window.RccSession.init().then(function (session) {
        var user = session && session.user;
        if (user && user.name) $("agGreeting").innerHTML = "Bonjour <b>" + esc(user.name.split(" ")[0]) + "</b> — les mêmes référentiels que le Centre de Relation Client, pour un traitement uniforme en agence et au RCC.";
        if (!prefs.mode) {
            var svc = ((user && user.service) || "").toUpperCase();
            prefs.mode = svc.indexOf("CAISS") !== -1 ? "CAISSIER" : "GESTIONNAIRE";
        }
        setMode(prefs.mode);
    }).catch(function () { setMode(prefs.mode || "GESTIONNAIRE"); });

    renderTiles();
    loadBranches();
    loaded.accueil = true;
    var start = prefs.tab && document.querySelector('.ag-pane[data-pane="' + prefs.tab + '"]') ? prefs.tab : "accueil";
    if (start !== "accueil") showTab(start);
})();
