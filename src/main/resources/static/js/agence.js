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
            { tab: "dispo", icon: "bi-check2-square", color: "#E0435B", title: "Mon agence & cartes", sub: "Cocher la disponibilité du jour" },
            { tab: "cartes", icon: "bi-credit-card-2-front-fill", color: "#AD1457", title: "Cartes & PIN", sub: "Toutes les agences" },
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
            renderAgencyBox();
            return branches;
        }).catch(function () { branches = []; });
    }

    // ── Mon agence (rattachement côté serveur : chaque agent a SON agence) ──
    var me = null;
    var sendJson = RccApi.sendJson;

    function loadMe() {
        return getJson("/api/agence/me").then(function (m) {
            me = m;
            renderAgencyBox();
            renderMyCards();
            if (loaded.dispo) renderDispo();
            return m;
        }).catch(function () { me = null; renderAgencyBox(); });
    }

    function myBranch() { return me && me.branch ? me.branch : null; }

    function renderAgencyBox() {
        var box = $("agAgencyBox");
        if (!box) return;
        if (me && me.branch) {
            box.innerHTML = '<span class="ag-agency-badge" title="' + (me.canChoose ? "Vous pouvez changer (administration)" : "Modifiable uniquement par l'administration") + '">' +
                '<i class="bi bi-geo-alt-fill"></i> ' + esc(me.branch.name) + ' <i class="bi bi-lock-fill ag-lock"></i></span>' +
                (me.canChoose ? ' <button type="button" class="btn btn-sm btn-light" id="agChangeAgency">Changer</button>' : "");
            var ch = $("agChangeAgency");
            if (ch) ch.addEventListener("click", function () { me = Object.assign({}, me, { branch: null, assigned: false }); renderAgencyBox(); });
            return;
        }
        if (me && me.canChoose) {
            box.innerHTML = '<label class="ag-agency-pick"><i class="bi bi-geo-alt-fill"></i><select id="agAgencySelect" aria-label="Mon agence"><option value="">Choisir mon agence…</option>' +
                branches.slice().sort(function (a, b) { return (a.name || "").localeCompare(b.name || ""); })
                    .map(function (b) { return '<option value="' + b.id + '">' + esc(b.name) + '</option>'; }).join("") +
                '</select></label> <button type="button" class="btn btn-warning btn-sm fw-bold" id="agConfirmAgency"><i class="bi bi-check2-circle"></i> Confirmer</button>';
            $("agConfirmAgency").addEventListener("click", function () {
                var id = Number($("agAgencySelect").value);
                if (!id) { alert("Choisissez votre agence dans la liste."); return; }
                var name = $("agAgencySelect").selectedOptions[0].textContent;
                if (!confirm("Confirmer « " + name + " » comme VOTRE agence ?\n\nCe choix est définitif : seul l'administrateur pourra le modifier.")) return;
                sendJson("/api/agence/me/agency", "PUT", { branchId: id }).then(function (m) {
                    me = m; renderAgencyBox(); renderMyCards(); if (loaded.dispo) renderDispo();
                }).catch(function (e) { alert("Erreur : " + e.message); });
            });
            return;
        }
        box.innerHTML = '<span class="ag-agency-badge muted"><i class="bi bi-eye"></i> Consultation (aucune agence rattachée)</span>';
    }

    function renderBranches() {
        var q = fold($("agBranchFilter").value).trim();
        var list = branches.filter(function (b) {
            return !q || fold([b.name, b.city, b.address].join(" ")).indexOf(q) !== -1;
        });
        $("agBranches").innerHTML = list.length ? list.map(function (b, i) {
            var code = agencyCode(b.name);
            var maps = b.latitude != null ? "https://www.google.com/maps/dir/?api=1&destination=" + b.latitude + "," + b.longitude : null;
            var isMine = myBranch() && myBranch().id === b.id;
            return '<div class="ag-branch' + (isMine ? " mine" : "") + '" style="animation-delay:' + Math.min(i, 20) * 25 + 'ms">' +
                '<div class="ag-branch-top"><span class="ag-branch-code">' + esc(code || "—") + '</span>' + (isMine ? '<span class="ag-mine-badge">Mon agence</span>' : "") + '</div>' +
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
        if (!b) { box.innerHTML = '<p class="text-muted small mb-0">Confirmez votre agence en haut de page.</p>'; return; }
        var r = me.cards;
        if (!r) {
            box.innerHTML = '<p class="small mb-1"><b>' + esc(b.name) + '</b></p><p class="text-muted small mb-2">Aucune disponibilité cartes publiée pour votre agence.</p>' +
                '<div class="ag-mycard-row mb-2"><span>GAB</span>' + atmChip(me.atm) + '</div>' +
                '<a href="#" data-goto="dispo" class="btn btn-sm btn-primary"><i class="bi bi-check2-square"></i> Cocher la disponibilité</a>';
            return;
        }
        box.innerHTML = '<div class="ag-mycard"><div><small class="text-muted">Mis à jour le ' + new Date(r.reportDate).toLocaleDateString("fr-FR") + (r.updatedBy ? " par " + esc(r.updatedBy) : "") + '</small>' +
            '<div class="fw-bold">' + esc(b.name) + '</div></div>' +
            '<div class="ag-mycard-row"><span>Cartes</span>' + statusChip(r.cardStatus) + '</div>' +
            '<div class="ag-mycard-row"><span>Codes PIN</span>' + statusChip(r.pinStatus) + '</div>' +
            '<div class="ag-mycard-row"><span>GAB</span>' + atmChip(me.atm) + '</div>' +
            '<div class="ag-types">' + (r.cardTypes || []).map(function (t) { return '<span>' + esc(t) + '</span>'; }).join("") + '</div>' +
            '<a href="#" data-goto="dispo" class="small mt-1"><i class="bi bi-pencil"></i> Mettre à jour</a></div>';
    }

    // ── GAB ─────────────────────────────────────────────────────────────────
    var ATM = { EN_SERVICE: ["ok", "En service"], PARTIEL: ["low", "Service partiel"], SANS_BILLETS: ["low", "Sans billets"], HORS_SERVICE: ["out", "Hors service"] };
    var ATM_SERVICES = { RETRAIT: "Retrait", DEPOT: "Dépôt", SANS_CARTE: "Retrait sans carte", SOLDE: "Consultation de solde", PIN: "Changement de PIN", VISA_MASTERCARD: "Cartes Visa / Mastercard" };
    var atmList = null;
    function atmChip(a) {
        if (!a) return '<span class="ag-st unk">Non renseigné</span>';
        var st = ATM[a.status] || ["unk", a.status];
        return '<span class="ag-st ' + st[0] + '">' + esc(st[1]) + (a.gabTotal != null && a.gabWorking != null ? " · " + a.gabWorking + "/" + a.gabTotal : "") + '</span>';
    }
    function loadAtm() {
        return getJson("/api/agence/atm?country=" + COUNTRY).then(function (l) { atmList = l || []; return atmList; }).catch(function () { atmList = []; return atmList; });
    }
    function atmFor(code) {
        return (atmList || []).filter(function (a) { return code && a.agencyCode && a.agencyCode.toUpperCase() === code.toUpperCase(); })[0] || null;
    }
    var atmForm = { status: null, services: [] };

    function renderAtmForm() {
        var a = me && me.atm;
        atmForm.status = a ? a.status : null;
        atmForm.services = a && a.services ? a.services.slice() : [];
        $("agAtmLast").innerHTML = a && a.updatedAt ? '<i class="bi bi-clock-history"></i> ' + new Date(a.updatedAt).toLocaleString("fr-FR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }) + (a.updatedBy ? " — " + esc(a.updatedBy) : "")
            : '<span class="text-warning">Jamais publié</span>';
        document.querySelectorAll('[data-atm="status"] button').forEach(function (btn) {
            btn.classList.toggle("on", atmForm.status === btn.dataset.v);
            btn.disabled = !me.canEdit;
        });
        $("agAtmTotal").value = a && a.gabTotal != null ? a.gabTotal : "";
        $("agAtmWorking").value = a && a.gabWorking != null ? a.gabWorking : "";
        $("agAtmNote").value = a && a.note ? a.note : "";
        $("agAtmServices").innerHTML = (me.atmServiceChoices || Object.keys(ATM_SERVICES)).map(function (k) {
            var on = atmForm.services.indexOf(k) !== -1;
            return '<button type="button" class="' + (on ? "on" : "") + '" data-s="' + k + '"' + (me.canEdit ? "" : " disabled") + '><i class="bi ' + (on ? "bi-check-square-fill" : "bi-square") + '"></i> ' + esc(ATM_SERVICES[k] || k) + '</button>';
        }).join("");
        $("agAtmServices").querySelectorAll("[data-s]").forEach(function (btn) {
            btn.addEventListener("click", function () {
                var k = btn.dataset.s, i = atmForm.services.indexOf(k);
                if (i === -1) atmForm.services.push(k); else atmForm.services.splice(i, 1);
                btn.classList.toggle("on", i === -1);
                btn.querySelector("i").className = "bi " + (i === -1 ? "bi-check-square-fill" : "bi-square");
            });
        });
        ["agAtmTotal", "agAtmWorking", "agAtmNote", "agAtmSave"].forEach(function (id) { $(id).disabled = !me.canEdit; });
    }

    document.querySelectorAll('[data-atm="status"] button').forEach(function (btn) {
        btn.addEventListener("click", function () {
            atmForm.status = btn.dataset.v;
            document.querySelectorAll('[data-atm="status"] button').forEach(function (x) { x.classList.toggle("on", x === btn); });
            // Raccourcis logiques : hors service → 0 en service ; en service → tous en service.
            var total = $("agAtmTotal").value;
            if (btn.dataset.v === "HORS_SERVICE") $("agAtmWorking").value = 0;
            if (btn.dataset.v === "EN_SERVICE" && total !== "") $("agAtmWorking").value = total;
        });
    });

    $("agAtmSave").addEventListener("click", function () {
        var msg = $("agAtmMsg");
        if (!atmForm.status) { flash(msg, false, "Choisissez l'état des GAB."); return; }
        var total = $("agAtmTotal").value, working = $("agAtmWorking").value;
        sendJson("/api/agence/me/atm", "PUT", {
            status: atmForm.status, services: atmForm.services,
            gabTotal: total === "" ? null : Number(total), gabWorking: working === "" ? null : Number(working), note: $("agAtmNote").value
        }).then(function (a) {
            me.atm = a;
            atmList = null;
            flash(msg, true, "Publié — visible sur tout le site.");
            renderAtmForm(); renderMyCards();
            if (loaded.cartes) Promise.all([loadCards(), loadAtm()]).then(renderCards);
        }).catch(function (e) { flash(msg, false, e.message); });
    });

    // ── Onglet « Mon agence & cartes » : coche de disponibilité + coordonnées ──
    var dispo = { card: null, pin: null, types: [] };

    function renderDispo() {
        var b = myBranch();
        $("agDispoLocked").hidden = !!b;
        $("agDispoMain").hidden = !b;
        if (!b) {
            $("agDispoLocked").innerHTML = '<div class="ag-card-title"><i class="bi bi-geo-alt"></i> Votre agence n\'est pas encore définie</div>' +
                '<p class="mb-0 text-muted">' + (me && me.canChoose ? "Choisissez-la en haut de page puis confirmez : vous pourrez ensuite cocher la disponibilité des cartes et tenir à jour ses coordonnées."
                    : "Seuls les agents d'agence rattachés à une agence peuvent publier sa disponibilité. Demandez votre rattachement à l'administration.") + '</p>';
            return;
        }
        $("agDispoName").textContent = b.name;
        var r = me.cards;
        dispo.card = r ? r.cardStatus : null;
        dispo.pin = r ? r.pinStatus : null;
        dispo.types = r && r.cardTypes ? r.cardTypes.slice() : [];
        $("agDispoLast").innerHTML = r ? '<i class="bi bi-clock-history"></i> Dernière publication : ' + new Date(r.reportDate).toLocaleDateString("fr-FR") + (r.updatedBy ? " — " + esc(r.updatedBy) : "") : '<span class="text-warning">Jamais publiée</span>';
        $("agDispoNote").value = r && r.note ? r.note : "";
        document.querySelectorAll(".ag-seg").forEach(function (seg) {
            var key = seg.dataset.seg;
            seg.querySelectorAll("button").forEach(function (btn) {
                btn.classList.toggle("on", dispo[key] === btn.dataset.v);
                btn.disabled = !me.canEdit;
            });
        });
        $("agTypesPick").innerHTML = (me.cardTypeChoices || []).map(function (t) {
            return '<button type="button" class="' + (dispo.types.indexOf(t) !== -1 ? "on" : "") + '" data-t="' + esc(t) + '"' + (me.canEdit ? "" : " disabled") + '>' +
                '<i class="bi ' + (dispo.types.indexOf(t) !== -1 ? "bi-check-square-fill" : "bi-square") + '"></i> ' + esc(t) + '</button>';
        }).join("");
        $("agTypesPick").querySelectorAll("[data-t]").forEach(function (btn) {
            btn.addEventListener("click", function () {
                var t = btn.dataset.t, i = dispo.types.indexOf(t);
                if (i === -1) dispo.types.push(t); else dispo.types.splice(i, 1);
                btn.classList.toggle("on", i === -1);
                btn.querySelector("i").className = "bi " + (i === -1 ? "bi-check-square-fill" : "bi-square");
            });
        });
        $("agDispoSave").disabled = !me.canEdit;
        $("agBrPhone").value = b.phone || "";
        $("agBrEmail").value = b.email || "";
        $("agBrHours").value = b.openingHours || "";
        $("agBrManager").value = b.managerName || "";
        $("agBrAddress").innerHTML = b.address ? '<i class="bi bi-geo"></i> ' + esc(b.address) : "";
        ["agBrPhone", "agBrEmail", "agBrHours", "agBrManager", "agBrSave", "agDispoNote"].forEach(function (id) { $(id).disabled = !me.canEdit; });
        renderAtmForm();
    }

    document.querySelectorAll(".ag-seg").forEach(function (seg) {
        seg.querySelectorAll("button").forEach(function (btn) {
            btn.addEventListener("click", function () {
                dispo[seg.dataset.seg] = btn.dataset.v;
                seg.querySelectorAll("button").forEach(function (x) { x.classList.toggle("on", x === btn); });
            });
        });
    });

    function flash(el, ok, text) {
        el.className = "small " + (ok ? "text-success fw-semibold" : "text-danger");
        el.innerHTML = (ok ? '<i class="bi bi-check-circle-fill"></i> ' : '<i class="bi bi-exclamation-triangle-fill"></i> ') + esc(text);
    }

    $("agDispoSave").addEventListener("click", function () {
        var msg = $("agDispoMsg");
        if (!dispo.card || !dispo.pin) { flash(msg, false, "Cochez l'état des cartes ET des codes PIN."); return; }
        var btn = $("agDispoSave");
        btn.disabled = true;
        sendJson("/api/agence/me/availability", "PUT", { cardStatus: dispo.card, pinStatus: dispo.pin, cardTypes: dispo.types, note: $("agDispoNote").value })
            .then(function (row) {
                me.cards = row;
                cardReport = null; // la vue « toutes les agences » sera rechargée
                flash(msg, true, "Publié — visible sur tout le site (Base de connaissances, Portail Agence, RAF).");
                renderDispo(); renderMyCards();
                if (loaded.cartes) loadCards().then(renderCards);
            })
            .catch(function (e) { flash(msg, false, e.message); })
            .then(function () { btn.disabled = !me.canEdit; });
    });

    $("agBrSave").addEventListener("click", function () {
        var msg = $("agBrMsg");
        sendJson("/api/agence/me/branch", "PUT", { phone: $("agBrPhone").value, email: $("agBrEmail").value, openingHours: $("agBrHours").value, managerName: $("agBrManager").value })
            .then(function (b) { me.branch = b; flash(msg, true, "Coordonnées enregistrées."); renderAgencyBox(); })
            .catch(function (e) { flash(msg, false, e.message); });
    });

    function renderCards() {
        var q = fold($("agCardFilter").value).trim();
        var rows = (cardReport && cardReport.rows ? cardReport.rows : []).map(function (r) { return { card: r, code: r.agencyCode, name: r.agency }; });
        // Agences qui n'ont publié que l'état de leurs GAB.
        (atmList || []).forEach(function (a) {
            if (!rows.some(function (x) { return x.code && a.agencyCode && x.code.toUpperCase() === a.agencyCode.toUpperCase(); })) {
                rows.push({ card: null, code: a.agencyCode, name: a.agency });
            }
        });
        $("agCardsInfo").textContent = "Situation actuelle : dernière mise à jour de chaque agence (point QA ou publication de l'agence).";
        var list = rows.filter(function (x) {
            return !q || fold([x.name, x.code, x.card ? (x.card.cardTypes || []).join(" ") : ""].join(" ")).indexOf(q) !== -1;
        });
        var mine = myBranch() ? agencyCode(myBranch().name) : null;
        $("agCardsTable").innerHTML = list.length ? '<table class="table table-hover align-middle ag-table"><thead><tr><th>Agence</th><th>Code</th><th>Cartes</th><th>PIN</th><th>GAB</th><th>Types disponibles</th><th>Mis à jour</th></tr></thead><tbody>' +
            list.map(function (x) {
                var r = x.card, a = atmFor(x.code);
                var when = r ? new Date(r.reportDate).toLocaleDateString("fr-FR") : a && a.updatedAt ? new Date(a.updatedAt).toLocaleDateString("fr-FR") : "";
                return '<tr class="' + (mine && x.code === mine ? "mine" : "") + '"><td class="fw-semibold">' + esc(x.name) + '</td><td>' + esc(x.code || "") + '</td>' +
                    '<td>' + (r ? statusChip(r.cardStatus) : '<span class="ag-st unk">—</span>') + '</td><td>' + (r ? statusChip(r.pinStatus) : '<span class="ag-st unk">—</span>') + '</td>' +
                    '<td>' + atmChip(a) + '</td><td>' + (r ? (r.cardTypes || []).map(function (t) { return '<span class="ag-type">' + esc(t) + '</span>'; }).join(" ") : "") + '</td>' +
                    '<td class="small text-muted">' + esc(when) + '</td></tr>';
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
        dispo: renderDispo,
        cas: renderProducts,
        recherche: function () {},
        cartes: function () { Promise.all([cardReport ? null : loadCards(), atmList ? null : loadAtm()]).then(renderCards); },
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
    loadMe();
    loaded.accueil = true;
    var start = prefs.tab && document.querySelector('.ag-pane[data-pane="' + prefs.tab + '"]') ? prefs.tab : "accueil";
    if (start !== "accueil") showTab(start);
})();
