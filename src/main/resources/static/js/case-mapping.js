"use strict";

/**
 * « Cartographier des cas » — explorateur plein écran de la table de classification CRM :
 * l'agent tape ce que dit le client (ou choisit un produit) et obtient en un clic le Type
 * d'incident, la Catégorie, la Sous-catégorie et l'option FCR à saisir, avec la conduite à tenir.
 * Données : window.RCC_CASE_MAP (case-mapping-data.js). Favoris / récents : localStorage.
 */
(function () {

    var DATA = window.RCC_CASE_MAP || [];
    var FAV_KEY = "rcc.caseMap.favs";
    var RECENT_KEY = "rcc.caseMap.recent";

    var FCR = {
        R: { label: "Résolu au 1er contact", crm: "Yes, Customer's Issue is resolved", cls: "ok", icon: "bi-check-circle-fill",
             advice: "Traitez la demande pendant l'appel puis clôturez la requête au premier contact." },
        A: { label: "Autorisation requise", crm: "Yes, Requires Authorization", cls: "auth", icon: "bi-shield-lock-fill",
             advice: "Créez la requête et transmettez-la pour autorisation — ne la clôturez pas vous-même. Informez le client du délai." },
        O: { label: "Autorisation requise (RCC Outbound)", crm: "Yes, Requires Authorization (Rcc outbound)", cls: "auth", icon: "bi-telephone-outbound-fill",
             advice: "Enregistrez la demande de rappel : elle est prise en charge par l'équipe RCC Outbound." },
        G: { label: "Selon votre profil GTP", crm: "", cls: "cond", icon: "bi-signpost-split-fill",
             advice: "La résolution dépend de vos accès GTP.",
             branches: [
                 { when: "Vous avez le profil GTP", crm: "Yes, Customer's Issue is resolved", cls: "ok" },
                 { when: "Vous n'avez pas les accès GTP", crm: "Yes, Requires Authorization", cls: "auth" }] },
        L: { label: "Selon le linkage", crm: "", cls: "cond", icon: "bi-signpost-split-fill",
             advice: "Le code FCR dépend du résultat du linkage.",
             branches: [
                 { when: "Le linkage est effectué", crm: "Yes, Requires Authorization", cls: "auth" },
                 { when: "Le linkage ne peut pas être effectué", crm: "Yes, Customer's Issue is resolved", cls: "ok" }] }
    };

    var ACRONYMS = ["EOL", "GAB", "ATM", "PIN", "CVV", "VBV", "B2W", "P2P", "SMS", "CNI", "SWIFT", "USD", "GTP", "RCC", "CIB", "QR", "USSD", "NIP", "INF"];
    var WORD_FIX = { "ecobank": "Ecobank", "cashxpress": "CashXpress", "xpresscash": "XpressCash", "omnilite": "Omnilite", "omniplus": "Omniplus",
        "visa": "Visa", "mastercard": "Mastercard", "senegal": "Sénégal", "benin": "Bénin", "togo": "Togo", "pret": "prêt", "prets": "prêts",
        "egaree": "égarée", "egares": "égarés", "expiree": "expirée", "capturee": "capturée", "delivre": "délivré", "reedition": "réédition",
        "magnetique": "magnétique", "liee": "liée", "prepayee": "prépayée", "supplementaire": "supplémentaire", "securite": "sécurité",
        "reinitialisation": "réinitialisation", "deverrouillage": "déverrouillage", "deverouillage": "déverrouillage", "desactivation": "désactivation",
        "creation": "création", "verification": "vérification", "releve": "relevé", "generale": "générale", "deplafonnement": "déplafonnement",
        "telephonique": "téléphonique", "a": "à", "delai": "délai", "validite": "validité", "cout": "coût", "recus": "reçus", "emission": "émission",
        "disponibilite": "disponibilité", "confrere": "confrère", "correspondante": "correspondante", "mise": "mise", "jeton": "jeton" };

    function pretty(title) {
        var words = title.toLowerCase().split(/(\s+|\/|\(|\)|,|\|)/);
        var first = true;
        return words.map(function (w) {
            if (!w.trim() || /^[\s/(),|]+$/.test(w)) return w;
            var up = w.toUpperCase().replace(/'/g, "");
            if (ACRONYMS.indexOf(up) !== -1) { first = false; return w.toUpperCase(); }
            var m = /^(d'|l')?(.*)$/.exec(w);
            var core = WORD_FIX[m[2]] || m[2];
            var out = (m[1] || "") + core;
            if (first) { out = out.charAt(0).toUpperCase() + out.slice(1); first = false; }
            return out;
        }).join("");
    }

    function fold(s) {
        return (s || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9 ]+/g, " ");
    }

    function esc(s) {
        return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
            return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
        });
    }

    function load(key) { try { return JSON.parse(localStorage.getItem(key) || "[]"); } catch (e) { return []; } }
    function save(key, v) { try { localStorage.setItem(key, JSON.stringify(v)); } catch (e) { /* stockage indisponible */ } }

    // Index à plat
    var ALL = [];
    DATA.forEach(function (p) {
        p.cases.forEach(function (c) {
            c.product = p;
            c.pretty = pretty(c.t);
            c.hay = fold([c.t, c.pretty, c.type, c.cat, c.sub, p.label, FCR[c.fcr].label].join(" "));
            ALL.push(c);
        });
    });
    var BY_ID = {};
    ALL.forEach(function (c) { BY_ID[c.id] = c; });

    var state = { q: "", product: null, type: null, fcr: null, favOnly: false, selected: null };
    var root;

    function build() {
        root = document.createElement("div");
        root.className = "cm-overlay";
        root.id = "caseMapOverlay";
        root.setAttribute("role", "dialog");
        root.setAttribute("aria-modal", "true");
        root.setAttribute("aria-label", "Cartographie des cas");
        root.hidden = true;
        var resolved = ALL.filter(function (c) { return c.fcr === "R"; }).length;
        root.innerHTML =
            '<div class="cm-shell">' +
            '<header class="cm-head">' +
            '  <div class="cm-orbs" aria-hidden="true"><span></span><span></span><span></span></div>' +
            '  <div class="cm-head-top">' +
            '    <div class="cm-brand"><span class="cm-brand-ico"><i class="bi bi-diagram-3-fill"></i></span>' +
            '      <div><div class="cm-kicker">Procédure de traitement</div><h3>Cartographie des cas</h3></div></div>' +
            '    <button type="button" class="cm-close" id="cmClose" aria-label="Fermer"><i class="bi bi-x-lg"></i></button>' +
            '  </div>' +
            '  <div class="cm-search"><i class="bi bi-search"></i>' +
            '    <input id="cmSearch" type="search" autocomplete="off" placeholder="Que dit le client ? ex. « carte avalée », « reset mobile app », « code swift »…">' +
            '    <kbd>/</kbd></div>' +
            '  <div class="cm-kpis">' +
            '    <span class="cm-kpi"><b>' + ALL.length + '</b> cas cartographiés</span>' +
            '    <span class="cm-kpi"><b>' + DATA.length + '</b> produits</span>' +
            '    <span class="cm-kpi ok"><b>' + Math.round(resolved / ALL.length * 100) + ' %</b> résolus au 1er contact</span>' +
            '  </div>' +
            '</header>' +
            '<div class="cm-body">' +
            '  <nav class="cm-products" id="cmProducts" aria-label="Produits"></nav>' +
            '  <section class="cm-results">' +
            '    <div class="cm-filters" id="cmFilters"></div>' +
            '    <div class="cm-list" id="cmList"></div>' +
            '  </section>' +
            '  <aside class="cm-detail" id="cmDetail"></aside>' +
            '</div>' +
            '</div>';
        document.body.appendChild(root);

        root.querySelector("#cmClose").addEventListener("click", close);
        var search = root.querySelector("#cmSearch");
        search.addEventListener("input", function () { state.q = search.value; renderList(); });
        root.addEventListener("keydown", function (e) {
            if (e.key === "Escape") {
                if (state.selected && window.innerWidth < 1200) { state.selected = null; renderDetail(); renderList(); }
                else close();
            }
            if (e.key === "/" && document.activeElement !== search) { e.preventDefault(); search.focus(); }
        });
        renderProducts();
        renderFilters();
        renderList();
        renderDetail();
    }

    function renderProducts() {
        var nav = root.querySelector("#cmProducts");
        var favs = load(FAV_KEY);
        nav.innerHTML =
            '<button type="button" class="cm-prod' + (!state.product && !state.favOnly ? " active" : "") + '" data-prod="" style="--c:#0057B8">' +
            '<span class="cm-prod-ico"><i class="bi bi-grid-3x3-gap-fill"></i></span><span class="cm-prod-name">Tous les produits</span><span class="cm-prod-n">' + ALL.length + '</span></button>' +
            '<button type="button" class="cm-prod' + (state.favOnly ? " active" : "") + '" data-fav="1" style="--c:#FFB300">' +
            '<span class="cm-prod-ico"><i class="bi bi-star-fill"></i></span><span class="cm-prod-name">Mes favoris</span><span class="cm-prod-n">' + favs.length + '</span></button>' +
            DATA.map(function (p, i) {
                var auth = p.cases.filter(function (c) { return c.fcr !== "R"; }).length;
                var okPct = Math.round((p.cases.length - auth) / p.cases.length * 100);
                return '<button type="button" class="cm-prod' + (state.product === p.key ? " active" : "") + '" data-prod="' + p.key + '" style="--c:' + p.color + ';animation-delay:' + (i * 30) + 'ms">' +
                    '<span class="cm-prod-ico"><i class="bi ' + p.icon + '"></i></span>' +
                    '<span class="cm-prod-name">' + esc(p.label) + '<span class="cm-prod-bar"><span style="width:' + okPct + '%"></span></span></span>' +
                    '<span class="cm-prod-n">' + p.cases.length + '</span></button>';
            }).join("");
        Array.prototype.forEach.call(nav.querySelectorAll(".cm-prod"), function (b) {
            b.addEventListener("click", function () {
                state.favOnly = b.getAttribute("data-fav") === "1";
                state.product = state.favOnly ? null : (b.getAttribute("data-prod") || null);
                renderProducts();
                renderList();
            });
        });
    }

    function renderFilters() {
        var box = root.querySelector("#cmFilters");
        function chip(group, value, label, icon) {
            var on = state[group] === value;
            return '<button type="button" class="cm-chip' + (on ? " on" : "") + '" data-g="' + group + '" data-v="' + value + '">' +
                (icon ? '<i class="bi ' + icon + '"></i> ' : "") + label + '</button>';
        }
        box.innerHTML =
            '<span class="cm-filter-label">Type</span>' +
            chip("type", "Enquête", "Enquête", "bi-question-circle") + chip("type", "Demande", "Demande", "bi-pencil-square") +
            chip("type", "Commentaires", "Commentaire", "bi-chat-heart") +
            '<span class="cm-filter-sep"></span><span class="cm-filter-label">FCR</span>' +
            chip("fcr", "R", "Résolu au 1er contact", "bi-check-circle") + chip("fcr", "A", "Autorisation requise", "bi-shield-lock") +
            chip("fcr", "G", "Conditionnel", "bi-signpost-split");
        Array.prototype.forEach.call(box.querySelectorAll(".cm-chip"), function (c) {
            c.addEventListener("click", function () {
                var g = c.getAttribute("data-g"), v = c.getAttribute("data-v");
                state[g] = state[g] === v ? null : v;
                renderFilters();
                renderList();
            });
        });
    }

    function matches(c) {
        if (state.favOnly && load(FAV_KEY).indexOf(c.id) === -1) return false;
        if (state.product && c.product.key !== state.product) return false;
        if (state.type && c.type !== state.type) return false;
        if (state.fcr) {
            if (state.fcr === "A" && c.fcr !== "A" && c.fcr !== "O") return false;
            if (state.fcr === "G" && c.fcr !== "G" && c.fcr !== "L") return false;
            if (state.fcr === "R" && c.fcr !== "R") return false;
        }
        var q = fold(state.q).trim();
        if (!q) return true;
        return q.split(/\s+/).every(function (w) { return c.hay.indexOf(w) !== -1; });
    }

    function highlight(text) {
        var q = fold(state.q).trim();
        if (!q) return esc(text);
        var words = q.split(/\s+/).filter(function (w) { return w.length > 1; });
        var folded = fold(text);
        var marks = [];
        words.forEach(function (w) {
            var i = folded.indexOf(w);
            if (i !== -1) marks.push([i, i + w.length]);
        });
        if (!marks.length) return esc(text);
        marks.sort(function (a, b) { return a[0] - b[0]; });
        var out = "", pos = 0;
        marks.forEach(function (m) {
            if (m[0] < pos) return;
            out += esc(text.slice(pos, m[0])) + "<mark>" + esc(text.slice(m[0], m[1])) + "</mark>";
            pos = m[1];
        });
        return out + esc(text.slice(pos));
    }

    function fcrBadge(code) {
        var f = FCR[code];
        return '<span class="cm-fcr ' + f.cls + '"><i class="bi ' + f.icon + '"></i> ' + f.label + '</span>';
    }

    function caseRow(c, i) {
        var fav = load(FAV_KEY).indexOf(c.id) !== -1;
        return '<button type="button" class="cm-case' + (state.selected === c.id ? " sel" : "") + '" data-id="' + c.id + '" style="--c:' + c.product.color + ';animation-delay:' + Math.min(i, 12) * 22 + 'ms">' +
            '<span class="cm-case-ico"><i class="bi ' + c.product.icon + '"></i></span>' +
            '<span class="cm-case-main"><span class="cm-case-title">' + highlight(c.pretty) + '</span>' +
            '<span class="cm-case-path"><span class="cm-type t-' + fold(c.type).trim() + '">' + esc(c.type) + '</span> ' +
            highlight(c.cat) + ' <i class="bi bi-chevron-right"></i> ' + highlight(c.sub) + '</span></span>' +
            fcrBadge(c.fcr) +
            (fav ? '<i class="bi bi-star-fill cm-case-fav"></i>' : "") +
            '</button>';
    }

    function renderList() {
        var list = root.querySelector("#cmList");
        var rows = ALL.filter(matches);
        var html = "";
        var noFilter = !state.q.trim() && !state.product && !state.type && !state.fcr && !state.favOnly;
        if (noFilter) {
            var recent = load(RECENT_KEY).map(function (id) { return BY_ID[id]; }).filter(Boolean).slice(0, 5);
            if (recent.length) {
                html += '<div class="cm-group"><div class="cm-group-head"><i class="bi bi-clock-history"></i> Consultés récemment</div>' +
                    recent.map(caseRow).join("") + '</div>';
            }
        }
        if (!rows.length) {
            html += '<div class="cm-empty"><i class="bi bi-binoculars"></i><div>Aucun cas ne correspond.</div>' +
                '<small>Essayez un autre mot (ex. « carte », « transfert », « mot de passe ») ou retirez un filtre.</small></div>';
        } else {
            var groups = [];
            var byProd = {};
            rows.forEach(function (c) {
                if (!byProd[c.product.key]) { byProd[c.product.key] = []; groups.push(c.product); }
                byProd[c.product.key].push(c);
            });
            html += '<div class="cm-count">' + rows.length + ' cas' + (state.q.trim() ? ' pour « ' + esc(state.q.trim()) + ' »' : "") + '</div>';
            var k = 0;
            groups.forEach(function (p) {
                html += '<div class="cm-group"><div class="cm-group-head" style="--c:' + p.color + '"><i class="bi ' + p.icon + '"></i> ' + esc(p.label) +
                    ' <span>' + byProd[p.key].length + '</span></div>' +
                    byProd[p.key].map(function (c) { return caseRow(c, k++); }).join("") + '</div>';
            });
        }
        list.innerHTML = html;
        Array.prototype.forEach.call(list.querySelectorAll(".cm-case"), function (b) {
            b.addEventListener("click", function () { select(b.getAttribute("data-id")); });
        });
    }

    function select(id) {
        state.selected = id;
        var recent = load(RECENT_KEY).filter(function (x) { return x !== id; });
        recent.unshift(id);
        save(RECENT_KEY, recent.slice(0, 8));
        Array.prototype.forEach.call(root.querySelectorAll(".cm-case"), function (b) { b.classList.toggle("sel", b.getAttribute("data-id") === id); });
        renderDetail();
    }

    function copyBtn(value, label) {
        return '<button type="button" class="cm-copy" data-copy="' + esc(value) + '" title="Copier « ' + esc(value) + ' »"><i class="bi bi-clipboard"></i><span>' + (label || "Copier") + '</span></button>';
    }

    function renderDetail() {
        var box = root.querySelector("#cmDetail");
        var c = BY_ID[state.selected];
        root.classList.toggle("has-detail", !!c);
        if (!c) {
            box.innerHTML = '<div class="cm-detail-empty"><div class="cm-radar"><span></span><span></span><span></span><i class="bi bi-cursor-fill"></i></div>' +
                '<h5>Choisissez un cas</h5><p>La fiche affiche le chemin exact à saisir dans le CRM et la conduite à tenir.</p>' +
                '<div class="cm-legend">' + fcrBadge("R") + fcrBadge("A") + fcrBadge("G") + '</div></div>';
            return;
        }
        var f = FCR[c.fcr];
        var fav = load(FAV_KEY).indexOf(c.id) !== -1;
        var fcrStep = f.branches
            ? f.branches.map(function (b) {
                return '<div class="cm-branch ' + b.cls + '"><div class="cm-branch-when"><i class="bi bi-arrow-return-right"></i> ' + esc(b.when) + '</div>' +
                    '<div class="cm-step-val">' + esc(b.crm) + copyBtn(b.crm) + '</div></div>';
            }).join("")
            : '<div class="cm-step-val">' + esc(f.crm) + copyBtn(f.crm) + '</div>';
        var steps = [
            { n: 1, label: "Type d'incident", val: c.type },
            { n: 2, label: "Catégorie", val: c.cat },
            { n: 3, label: "Sous-catégorie", val: c.sub }
        ];
        var all = c.type + " > " + c.cat + " > " + c.sub + (f.crm ? " > " + f.crm : "");
        box.innerHTML =
            '<div class="cm-card" style="--c:' + c.product.color + '">' +
            '<div class="cm-card-top">' +
            '  <button type="button" class="cm-back" id="cmBack"><i class="bi bi-arrow-left"></i> Retour</button>' +
            '  <span class="cm-card-prod"><i class="bi ' + c.product.icon + '"></i> ' + esc(c.product.label) + '</span>' +
            '  <button type="button" class="cm-star' + (fav ? " on" : "") + '" id="cmStar" title="Ajouter aux favoris"><i class="bi ' + (fav ? "bi-star-fill" : "bi-star") + '"></i></button>' +
            '</div>' +
            '<h4 class="cm-card-title">' + esc(c.pretty) + '</h4>' +
            '<div class="cm-advice ' + f.cls + '"><i class="bi ' + f.icon + '"></i><div><b>' + esc(f.label) + '</b><br>' + esc(f.advice) + '</div></div>' +
            '<div class="cm-steps">' +
            steps.map(function (s) {
                return '<div class="cm-step" style="animation-delay:' + (s.n * 80) + 'ms"><span class="cm-step-n">' + s.n + '</span><div class="cm-step-body">' +
                    '<div class="cm-step-label">' + s.label + '</div><div class="cm-step-val">' + esc(s.val) + copyBtn(s.val) + '</div></div></div>';
            }).join("") +
            '<div class="cm-step" style="animation-delay:320ms"><span class="cm-step-n">4</span><div class="cm-step-body"><div class="cm-step-label">Option FCR</div>' + fcrStep + '</div></div>' +
            '</div>' +
            '<button type="button" class="cm-copy-all" data-copy="' + esc(all) + '"><i class="bi bi-clipboard-check"></i> Copier tout le chemin CRM</button>' +
            '<div class="cm-original">Libellé d\'origine : <code>' + esc(c.t) + '</code></div>' +
            '</div>';
        box.querySelector("#cmBack").addEventListener("click", function () { state.selected = null; renderDetail(); renderList(); });
        box.querySelector("#cmStar").addEventListener("click", function () {
            var favs = load(FAV_KEY), i = favs.indexOf(c.id);
            if (i === -1) favs.push(c.id); else favs.splice(i, 1);
            save(FAV_KEY, favs);
            renderDetail(); renderProducts(); renderList();
        });
        Array.prototype.forEach.call(box.querySelectorAll("[data-copy]"), function (b) {
            b.addEventListener("click", function () {
                var v = b.getAttribute("data-copy");
                var done = function () {
                    b.classList.add("copied");
                    var icon = b.querySelector("i");
                    var old = icon.className;
                    icon.className = "bi bi-check2";
                    setTimeout(function () { b.classList.remove("copied"); icon.className = old; }, 1300);
                };
                if (navigator.clipboard) navigator.clipboard.writeText(v).then(done, function () { prompt("Copiez :", v); });
                else prompt("Copiez :", v);
            });
        });
    }

    var lastFocus = null;

    function open(query) {
        if (!root) build();
        lastFocus = document.activeElement;
        root.hidden = false;
        document.body.classList.add("cm-lock");
        requestAnimationFrame(function () { root.classList.add("show"); });
        var search = root.querySelector("#cmSearch");
        if (query) { search.value = query; state.q = query; renderList(); }
        setTimeout(function () { search.focus(); }, 120);
    }

    function close() {
        if (!root) return;
        root.classList.remove("show");
        document.body.classList.remove("cm-lock");
        setTimeout(function () { root.hidden = true; if (lastFocus && lastFocus.focus) lastFocus.focus(); }, 250);
    }

    window.RccCaseMap = { open: open, close: close, pretty: pretty, all: ALL };

    var btn = document.getElementById("caseMapBtn");
    if (btn) btn.addEventListener("click", function () { open(); });
})();
