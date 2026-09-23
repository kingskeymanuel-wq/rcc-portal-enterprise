"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };

    var getJson = RccApi.getJson;

    var escapeHtml = RccApi.escapeHtml;

    // Seuls les outils qui ont une vraie page derrière sont listés ici — voir la
    // note plus bas sur /workflow, /qa, /reports, /audit (liens sidebar sans
    // page réelle pour l'instant, volontairement absents de cette grille).
    // Dégradés de marque Ecobank (pas de photo de stock externe — l'ancienne API
    // Unsplash Source utilisée ici avant a été fermée en 2023, images cassées).
    var TOOLS = [
        { title: "Utilisateurs", desc: "Comptes et autorisations", href: "/users", icon: "bi-people-fill",
            gradient: "linear-gradient(135deg,#0057B8,#003E8A)", roles: ["ADMIN"] },
        { title: "Procédures", desc: "Parcours interactifs, fichiers partagés", href: "/procedures", icon: "bi-list-check",
            gradient: "linear-gradient(135deg,#0EA5A5,#0B7A7A)", roles: ["AGENT", "QA", "ADMIN"] },
        { title: "Masques de mail", desc: "Modèles de réponse", href: "/mail-templates", icon: "bi-envelope-fill",
            gradient: "linear-gradient(135deg,#F59E0B,#B45309)", roles: ["AGENT", "QA", "ADMIN"] },
        { title: "Knowledge Base", desc: "Réseau d'agences, offres, tarifs", href: "/knowledge", icon: "bi-book-fill",
            gradient: "linear-gradient(135deg,#6366F1,#4338CA)", roles: ["AGENT", "QA", "ADMIN"] },
        { title: "Formation", desc: "Cours et auto-diagnostics", href: "/training", icon: "bi-mortarboard-fill",
            gradient: "linear-gradient(135deg,#10B981,#047857)", roles: ["AGENT", "QA", "ADMIN"] },
        { title: "Suivi de shift", desc: "Chronologie de présence", href: "/shift", icon: "bi-clock-history",
            gradient: "linear-gradient(135deg,#EC4899,#BE185D)", roles: ["QA", "ADMIN"] },
        { title: "Administration", desc: "Rôles, services, attribution", href: "/administration", icon: "bi-gear-fill",
            gradient: "linear-gradient(135deg,#64748B,#334155)", roles: ["ADMIN"] }
    ];

    function renderTools(profile) {
        var container = $("toolsGrid");
        var visible = TOOLS.filter(function (t) { return t.roles.indexOf(profile) !== -1; });

        if (!visible.length) {
            container.innerHTML = '<p class="text-muted">Aucun outil disponible pour votre profil.</p>';
            return;
        }

        container.innerHTML = visible.map(function (t) {
            return '<div class="col-lg-3 col-md-4 col-sm-6">' +
                '<a href="' + t.href + '" class="card dashboard-card shadow-sm h-100 text-decoration-none text-dark">' +
                '<div class="card-img-top d-flex align-items-center justify-content-center" ' +
                'style="height:110px;border-radius:12px 12px 0 0;background:' + t.gradient + ';">' +
                '<i class="bi ' + t.icon + ' text-white" style="font-size:2.2rem;"></i></div>' +
                '<div class="card-body">' +
                '<i class="bi ' + t.icon + ' fs-4 text-primary mb-2 d-block"></i>' +
                '<h6>' + escapeHtml(t.title) + '</h6>' +
                '<p class="text-muted small mb-0">' + escapeHtml(t.desc) + '</p>' +
                '</div>' +
                '</a>' +
                '</div>';
        }).join("");
    }

    function loadKpi() {
        getJson("/admin/dashboard").then(function (data) {
            document.getElementById("dashboardCards").innerHTML = `
        <div class="col-lg-3 col-md-6">
          <div class="card kpi"><div class="card-body">
            <small>Utilisateurs</small><h2>${data.users}</h2>
            <i class="bi bi-people-fill text-primary fs-2"></i>
          </div></div>
        </div>
        <div class="col-lg-3 col-md-6">
          <div class="card kpi"><div class="card-body">
            <small>Rôles</small><h2>${data.roles}</h2>
            <i class="bi bi-shield-lock-fill text-success fs-2"></i>
          </div></div>
        </div>
        <div class="col-lg-3 col-md-6">
          <div class="card kpi"><div class="card-body">
            <small>Services</small><h2>${data.services}</h2>
            <i class="bi bi-grid-fill text-warning fs-2"></i>
          </div></div>
        </div>
        <div class="col-lg-3 col-md-6">
          <div class="card kpi"><div class="card-body">
            <small>Sessions actives</small><h2>${data.activeSessions}</h2>
            <i class="bi bi-broadcast text-danger fs-2"></i>
          </div></div>
        </div>
      `;
        }).catch(function (e) { console.error(e); });
    }

    // ===== Bannière d'accueil (hero) =====

    var currentProfile = null;

    function isAdminOrQa() { return currentProfile === "ADMIN" || currentProfile === "QA"; }

    function loadHero() {
        fetch("/api/site-banner", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : null;
        }).then(function (banner) {
            if (!banner) return;
            if (banner.headline) document.getElementById("heroHeadline").textContent = banner.headline;
            if (banner.subheadline) document.getElementById("heroSubheadline").textContent = banner.subheadline;
            if (banner.imageUrl) {
                document.getElementById("heroBanner").style.backgroundImage =
                    "linear-gradient(rgba(0,20,50,.35),rgba(0,20,50,.35)), url('" + banner.imageUrl + "')";
            }
            var cta = document.getElementById("heroCtaBtn");
            if (banner.ctaLabel && banner.ctaUrl) {
                cta.textContent = banner.ctaLabel;
                cta.href = banner.ctaUrl;
                cta.style.display = "";
            } else {
                cta.style.display = "none";
            }
        }).catch(function (e) { console.error(e); });
    }

    function wireHeroEditing() {
        document.getElementById("editHeroBtn").addEventListener("click", function () {
            document.getElementById("heroHeadlineInput").value = document.getElementById("heroHeadline").textContent;
            document.getElementById("heroSubheadlineInput").value = document.getElementById("heroSubheadline").textContent;
            var cta = document.getElementById("heroCtaBtn");
            document.getElementById("heroCtaLabelInput").value = cta.style.display === "none" ? "" : cta.textContent;
            document.getElementById("heroCtaUrlInput").value = cta.style.display === "none" ? "" : cta.getAttribute("href");
            document.getElementById("heroEditCard").style.display = "";
            document.getElementById("heroEditCard").scrollIntoView({ behavior: "smooth" });
        });

        document.getElementById("cancelHeroBtn").addEventListener("click", function () {
            document.getElementById("heroEditCard").style.display = "none";
        });

        document.getElementById("saveHeroBtn").addEventListener("click", function () {
            var payload = {
                headline: document.getElementById("heroHeadlineInput").value.trim() || null,
                subheadline: document.getElementById("heroSubheadlineInput").value.trim() || null,
                ctaLabel: document.getElementById("heroCtaLabelInput").value.trim() || null,
                ctaUrl: document.getElementById("heroCtaUrlInput").value.trim() || null
            };
            fetch("/api/site-banner", {
                method: "PUT", credentials: "same-origin",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            }).then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                document.getElementById("heroEditCard").style.display = "none";
                loadHero();
            }).catch(function (e) { alert("Erreur : " + e.message); });
        });

        document.getElementById("heroImageInput").addEventListener("change", function () {
            var file = this.files[0];
            if (!file) return;
            var formData = new FormData();
            formData.append("file", file);
            fetch("/api/site-banner/image", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function () { loadHero(); })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    var newsEditor = null;
    var newsEditingId = null;

    function loadNews() {
        fetch("/api/news", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : [];
        }).then(function (articles) {
            var grid = document.getElementById("newsGrid");
            if (!articles.length) {
                grid.innerHTML = '<div class="col-12"><p class="text-muted text-center">Aucune actualité pour le moment.</p></div>';
                return;
            }
            grid.innerHTML = articles.map(function (a) {
                var img = a.imageUrl
                    ? '<div style="height:260px;background:url(\'' + a.imageUrl + '\') center/cover;border-radius:.6rem .6rem 0 0;"></div>'
                    : '<div style="height:260px;background:linear-gradient(135deg,var(--eco-blue),#0F9D6C);border-radius:.6rem .6rem 0 0;display:flex;align-items:center;justify-content:center;"><i class="bi bi-newspaper text-white" style="font-size:3.5rem;"></i></div>';
                var adminBtns = isAdminOrQa()
                    ? '<div class="d-flex gap-1 mt-3">' +
                      '<button class="btn btn-sm btn-outline-secondary edit-news-btn" data-id="' + a.newsId + '"><i class="bi bi-pencil"></i> Modifier</button>' +
                      '<button class="btn btn-sm btn-outline-danger delete-news-btn" data-id="' + a.newsId + '"><i class="bi bi-trash"></i></button>' +
                      '</div>' : "";
                return '<div class="col-md-6">' +
                    '<div class="card dashboard-card shadow-sm h-100">' + img +
                    '<div class="card-body p-4">' +
                    '<h4 class="card-title mb-3">' + escapeHtml(a.title) + '</h4>' +
                    '<div style="max-height:150px;overflow:hidden;position:relative;">' + a.contentHtml + '</div>' +
                    '<button class="btn btn-sm btn-link px-0 read-more-news-btn" data-id="' + a.newsId + '">Lire la suite →</button>' +
                    adminBtns +
                    '</div></div></div>';
            }).join("");

            Array.prototype.forEach.call(grid.querySelectorAll(".read-more-news-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var article = articles.filter(function (a) { return a.newsId === Number(btn.dataset.id); })[0];
                    if (!article) return;
                    document.getElementById("newsReadTitle").textContent = article.title;
                    document.getElementById("newsReadImage").src = article.imageUrl || "";
                    document.getElementById("newsReadImage").style.display = article.imageUrl ? "" : "none";
                    document.getElementById("newsReadContent").innerHTML = article.contentHtml;
                    new bootstrap.Modal(document.getElementById("newsReadModal")).show();
                });
            });

            Array.prototype.forEach.call(grid.querySelectorAll(".delete-news-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Supprimer cette actualité ?")) return;
                    fetch("/api/news/" + btn.dataset.id, { method: "DELETE", credentials: "same-origin" })
                        .then(function () { loadNews(); });
                });
            });
            Array.prototype.forEach.call(grid.querySelectorAll(".edit-news-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var article = articles.filter(function (a) { return a.newsId === Number(btn.dataset.id); })[0];
                    if (!article) return;
                    openNewsForm(article);
                });
            });
        }).catch(function (e) { console.error(e); });
    }

    function escapeHtml(s) {
        var div = document.createElement("div");
        div.textContent = s == null ? "" : String(s);
        return div.innerHTML;
    }

    function openNewsForm(article) {
        newsEditingId = article ? article.newsId : null;
        document.getElementById("newsFormTitle").textContent = article ? "Modifier l'actualité" : "Nouvelle actualité";
        document.getElementById("newsTitleInput").value = article ? article.title : "";
        var preview = document.getElementById("newsImagePreview");
        if (article && article.imageUrl) {
            preview.src = article.imageUrl;
            preview.style.display = "";
        } else {
            preview.style.display = "none";
        }
        document.getElementById("newsImageInput").dataset.url = article && article.imageUrl ? article.imageUrl : "";
        newsEditor = window.RccRichText.create(document.getElementById("newsContentEditor"), article ? article.contentHtml : "");
        document.getElementById("newsFormCard").classList.remove("d-none");
        document.getElementById("newsFormCard").scrollIntoView({ behavior: "smooth" });
    }

    function wireNews() {
        document.getElementById("addNewsBtn").addEventListener("click", function () { openNewsForm(null); });
        document.getElementById("cancelNewsBtn").addEventListener("click", function () {
            document.getElementById("newsFormCard").classList.add("d-none");
        });

        document.getElementById("newsImageInput").addEventListener("change", function () {
            var input = this;
            var file = input.files[0];
            if (!file) return;
            var formData = new FormData();
            formData.append("file", file);
            fetch("/api/news/upload-image", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) { return res.json(); })
                .then(function (result) {
                    input.dataset.url = result.url;
                    var preview = document.getElementById("newsImagePreview");
                    preview.src = result.url;
                    preview.style.display = "";
                })
                .catch(function (e) { alert("Erreur : " + e.message); });
        });

        document.getElementById("saveNewsBtn").addEventListener("click", function () {
            var payload = {
                title: document.getElementById("newsTitleInput").value.trim(),
                contentHtml: newsEditor ? newsEditor.getHtml().trim() : "",
                imageUrl: document.getElementById("newsImageInput").dataset.url || null,
                sortOrder: 0
            };
            if (!payload.title || !payload.contentHtml) { alert("Titre et contenu sont obligatoires."); return; }

            var url = newsEditingId ? "/api/news/" + newsEditingId : "/api/news";
            var method = newsEditingId ? "PUT" : "POST";
            fetch(url, {
                method: method, credentials: "same-origin",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            }).then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                document.getElementById("newsFormCard").classList.add("d-none");
                loadNews();
            }).catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    window.RccSession.init().then(function (session) {
        if (!session) return;
        currentProfile = session.profile;

        document.getElementById("personalGreeting").textContent =
            "Bonjour, " + (session.user.name || session.user.username) + " 👋";

        if (isAdminOrQa()) {
            document.getElementById("editHeroBtn").style.display = "";
            document.getElementById("heroImageLabel").style.display = "";
        }
        if (isAdminOrQa()) {
            document.getElementById("addNewsBtn").classList.remove("d-none");
        }

        renderTools(session.profile);

        if (session.profile === "AGENT" || session.profile === "QA") {
            document.getElementById("externalToolsBtn").classList.remove("d-none");
            window.RccExternalTools.render("externalToolsGrid");
        }

        if (session.profile === "QA" || session.profile === "ADMIN") {
            document.getElementById("dashboardCards").style.display = "";
            loadKpi();
        }
    });

    wireHeroEditing();
    wireNews();
    loadHero();
    loadNews();
})();