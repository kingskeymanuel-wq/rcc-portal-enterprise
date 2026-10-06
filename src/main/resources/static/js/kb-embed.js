"use strict";

/**
 * Base de connaissance intégrée à un portail (Outbound Digitalisation, Télévente, Team Leader Outbound) :
 * recherche, rubriques et articles de la base commune (/api/kb), ouverts dans la visionneuse du portail
 * (RccViewer) sans quitter la page, et les « Pas à pas » animés (RccGuide). Le portail garde tout ce qu'il
 * avait ; « Ouvrir la base complète » mène à /knowledge.
 */
window.RccKbEmbed = (function () {
    var esc = function (s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; };

    function getJson(url) {
        return fetch(url, { credentials: "same-origin" }).then(function (r) {
            if (!r.ok) throw new Error("HTTP " + r.status);
            return r.json();
        });
    }

    function plain(html, max) {
        var t = String(html || "").replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/\s+/g, " ").trim();
        return t.length > max ? t.slice(0, max).trim() + "…" : t;
    }

    function openArticle(id) {
        if (window.RccViewer && window.RccViewer.openArticle) window.RccViewer.openArticle(id);
        else window.open("/knowledge?article=" + encodeURIComponent(id), "_blank", "noopener");
    }

    function mount(root) {
        if (!root || root.getAttribute("data-kb-mounted")) return;
        root.setAttribute("data-kb-mounted", "1");
        root.innerHTML =
            '<div class="kbe-head">' +
            '<div class="kbe-search"><i class="bi bi-search"></i><input type="search" placeholder="Rechercher un produit, une procédure, un tarif, un script…" aria-label="Rechercher dans la base de connaissance"></div>' +
            '<div class="kbe-seg" role="group"><button type="button" class="on" data-kbe-view="kb"><i class="bi bi-journal-bookmark"></i> Articles</button>' +
            '<button type="button" data-kbe-view="guides"><i class="bi bi-phone"></i> Pas à pas</button></div>' +
            '<a class="btn btn-sm btn-outline-primary" href="/knowledge" target="_blank" rel="noopener"><i class="bi bi-box-arrow-up-right"></i> Ouvrir la base complète</a></div>' +
            '<div class="kbe-crumb"></div><div class="kbe-body"><div class="kbe-empty">Chargement de la base de connaissance…</div></div>' +
            '<div class="kbe-guides" style="display:none;"></div>';
        var body = root.querySelector(".kbe-body"), crumb = root.querySelector(".kbe-crumb"), input = root.querySelector(".kbe-search input");
        var guides = root.querySelector(".kbe-guides"), categories = [], timer = null, seq = 0;

        function showCategories() {
            crumb.innerHTML = "";
            if (!categories.length) { body.innerHTML = '<div class="kbe-empty">Aucune rubrique dans la base de connaissance.</div>'; return; }
            body.innerHTML = '<div class="kbe-cats">' + categories.map(function (c) {
                return '<button type="button" class="kbe-cat" data-cat="' + c.categoryId + '">' +
                    (c.imageUrl ? '<span class="kbe-cat-img" style="background-image:url(\'' + esc(c.imageUrl) + '\')"></span>'
                        : '<span class="kbe-cat-ic"><i class="bi ' + esc(c.icon || "bi-folder2-open") + '"></i></span>') +
                    '<b>' + esc(c.title) + '</b></button>';
            }).join("") + '</div>';
        }

        function showArticles(list, title, back) {
            crumb.innerHTML = back ? '<button type="button" class="kbe-back"><i class="bi bi-arrow-left"></i> Rubriques</button> <b>' + esc(title) + '</b>'
                : '<b>' + esc(title) + '</b>';
            if (!list.length) { body.innerHTML = '<div class="kbe-empty">Aucun article.</div>'; return; }
            body.innerHTML = '<div class="kbe-list">' + list.map(function (a) {
                return '<button type="button" class="kbe-art" data-art="' + a.articleId + '"><span><b>' + esc(a.title) + '</b>' +
                    '<small>' + esc([a.categoryTitle, a.countryLabel || (a.countryCode ? a.countryCode : "Toutes filiales")].filter(Boolean).join(" · ")) + '</small>' +
                    '<em>' + esc(plain(a.contentHtml, 160)) + '</em></span><i class="bi bi-chevron-right"></i></button>';
            }).join("") + '</div>';
        }

        function openCategory(id) {
            var c = categories.filter(function (x) { return String(x.categoryId) === String(id); })[0];
            body.innerHTML = '<div class="kbe-empty">Chargement…</div>';
            var country = (window.RccFiliale && window.RccFiliale.get && window.RccFiliale.get()) || "CI";
            // Articles de la filiale et articles valables partout, sans doublon.
            Promise.all([getJson("/api/kb/articles?categoryId=" + id + "&countryCode=" + encodeURIComponent(country)).catch(function () { return []; }),
                getJson("/api/kb/articles?categoryId=" + id).catch(function () { return []; })]).then(function (r) {
                var seen = {}, list = [];
                r[0].concat(r[1]).forEach(function (a) { if (!seen[a.articleId]) { seen[a.articleId] = 1; list.push(a); } });
                showArticles(list, c ? c.title : "Rubrique", true);
            });
        }

        function search(q) {
            var my = ++seq;
            if (q.length < 2) { showCategories(); return; }
            body.innerHTML = '<div class="kbe-empty">Recherche…</div>';
            getJson("/api/kb/articles/search?q=" + encodeURIComponent(q)).then(function (list) {
                if (my === seq) showArticles(list || [], (list || []).length + " résultat(s) pour « " + q + " »", true);
            }).catch(function (e) { if (my === seq) body.innerHTML = '<div class="kbe-empty text-danger">Recherche indisponible : ' + esc(e.message) + '</div>'; });
        }

        root.addEventListener("click", function (e) {
            var cat = e.target.closest("[data-cat]"), art = e.target.closest("[data-art]"), view = e.target.closest("[data-kbe-view]");
            if (cat) openCategory(cat.getAttribute("data-cat"));
            else if (art) openArticle(art.getAttribute("data-art"));
            else if (e.target.closest(".kbe-back")) { input.value = ""; showCategories(); }
            else if (view) {
                var g = view.getAttribute("data-kbe-view") === "guides";
                root.querySelectorAll("[data-kbe-view]").forEach(function (b) { b.classList.toggle("on", b === view); });
                guides.style.display = g ? "" : "none";
                body.style.display = crumb.style.display = g ? "none" : "";
                root.querySelector(".kbe-search").style.visibility = g ? "hidden" : "";
                if (g && !guides.getAttribute("data-mounted") && window.RccGuide && window.RccGuide.mountLibrary) {
                    guides.setAttribute("data-mounted", "1");
                    // Team Leader CIB : pas à pas Omni ; autres équipes : pas à pas habituels.
                    getJson("/api/kb/space").then(function (r) { return r && r.space === "CIB" ? "CIB" : "GENERAL"; })
                        .catch(function () { return "GENERAL"; })
                        .then(function (aud) { window.RccGuide.mountLibrary(guides, { audience: aud }); });
                } else if (g && !window.RccGuide) guides.innerHTML = '<div class="kbe-empty">Pas à pas disponibles dans la base complète (onglet « Pas à pas »).</div>';
            }
        });
        input.addEventListener("input", function () { clearTimeout(timer); var q = input.value.trim(); timer = setTimeout(function () { search(q); }, 300); });

        getJson("/api/kb/categories").then(function (list) {
            categories = (list || []).slice().sort(function (a, b) { return (a.sortOrder || 0) - (b.sortOrder || 0); });
            showCategories();
        }).catch(function (e) { body.innerHTML = '<div class="kbe-empty text-danger">Base de connaissance indisponible : ' + esc(e.message) + '</div>'; });
    }

    return { mount: mount };
})();
