"use strict";

/**
 * Visionneuse commune du portail — ouvre dans une FENÊTRE (modale) au lieu d'un nouvel onglet :
 * un article de la Base de connaissances (contenu + pièces jointes), une procédure (étapes), une
 * formation, ou un fichier (PDF et images affichés directement, autres fichiers à télécharger).
 * Utilisée par la Base de connaissances et par la recherche globale.
 */
window.RccViewer = (function () {
    var modal = null;
    var modalEl = null;
    var history = [];   // vues précédentes (retour article ← fichier)

    function escapeHtml(s) {
        var div = document.createElement("div");
        div.textContent = s == null ? "" : String(s);
        return div.innerHTML;
    }

    function getJson(url) {
        return fetch(url, { credentials: "same-origin" }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || ("HTTP " + res.status))); });
            return res.json();
        });
    }

    function ensureModal() {
        if (modalEl) return;
        modalEl = document.createElement("div");
        modalEl.className = "modal fade";
        modalEl.id = "rccViewerModal";
        modalEl.tabIndex = -1;
        // Au-dessus des autres fenêtres (recherche, article) : la visionneuse s'ouvre par-dessus.
        modalEl.style.zIndex = "1075";
        modalEl.innerHTML =
            '<div class="modal-dialog modal-xl modal-dialog-scrollable modal-fullscreen-lg-down">' +
            '<div class="modal-content">' +
            '<div class="modal-header">' +
            '<button type="button" class="btn btn-sm btn-outline-secondary me-2" id="rccViewerBack" style="display:none;"><i class="bi bi-arrow-left"></i></button>' +
            '<h5 class="modal-title text-truncate" id="rccViewerTitle"></h5>' +
            '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Fermer"></button>' +
            '</div>' +
            '<div class="modal-body" id="rccViewerBody"></div>' +
            '<div class="modal-footer py-2" id="rccViewerFooter"></div>' +
            '</div></div>';
        document.body.appendChild(modalEl);
        modal = new bootstrap.Modal(modalEl);
        modalEl.addEventListener("shown.bs.modal", function () {
            var backdrops = document.querySelectorAll(".modal-backdrop");
            if (backdrops.length > 1) backdrops[backdrops.length - 1].style.zIndex = "1070";
        });
        modalEl.addEventListener("hidden.bs.modal", function () {
            history = [];
            document.getElementById("rccViewerBody").innerHTML = ""; // arrête la lecture d'un PDF/vidéo
            // Une autre fenêtre reste ouverte dessous : garder le défilement bloqué sur la page.
            if (document.querySelector(".modal.show")) document.body.classList.add("modal-open");
        });
        document.getElementById("rccViewerBack").addEventListener("click", function () {
            var previous = history.pop();
            if (previous) previous(true);
        });
    }

    function show(title, bodyHtml, footerHtml, isBack) {
        ensureModal();
        document.getElementById("rccViewerTitle").textContent = title || "";
        document.getElementById("rccViewerBody").innerHTML = bodyHtml;
        document.getElementById("rccViewerFooter").innerHTML = footerHtml || "";
        document.getElementById("rccViewerFooter").style.display = footerHtml ? "" : "none";
        document.getElementById("rccViewerBack").style.display = history.length ? "" : "none";
        if (!modalEl.classList.contains("show")) modal.show();
        if (!isBack) document.getElementById("rccViewerBody").scrollTop = 0;
    }

    function loading(title) {
        show(title || "Chargement…", '<div class="text-center text-muted py-5"><span class="spinner-border spinner-border-sm"></span> Chargement…</div>');
    }

    function error(e) {
        show("Erreur", '<div class="alert alert-danger mb-0">Impossible d\'ouvrir cet élément : ' + escapeHtml(e.message) + "</div>");
    }

    function isPdf(f) {
        return f.mimeType === "application/pdf" || /\.pdf($|\?)/i.test(f.fileName || f.url || "");
    }

    function isImage(f) {
        return (f.mimeType && f.mimeType.indexOf("image/") === 0) || /\.(png|jpe?g|gif|webp|bmp|svg)($|\?)/i.test(f.fileName || f.url || "");
    }

    var DV_CSS = ".dv-body{background:#f4f6fa;border-radius:.6rem;padding:1rem;max-height:75vh;overflow:auto}" +
        ".dv-doc{background:#fff;max-width:860px;margin:0 auto;padding:2rem 2.2rem;border-radius:.4rem;box-shadow:0 4px 18px rgba(0,32,84,.08);font-size:.95rem;line-height:1.55;color:#1f2a3d}" +
        ".dv-doc h3,.dv-doc h4,.dv-doc h5{color:#0B3D91;font-weight:800;margin:1rem 0 .5rem}.dv-doc p{margin:0 0 .45rem}.dv-gap{height:.4rem}" +
        ".dv-table-wrap{overflow:auto;margin:.6rem 0}.dv-table{border-collapse:collapse;width:100%;font-size:.85rem;background:#fff}" +
        ".dv-table td,.dv-table th{border:1px solid #dfe5ee;padding:.3rem .5rem;vertical-align:top}.dv-table th{background:#eef3fb;color:#0B3D91;text-align:left}" +
        ".dv-grid tr:first-child td{background:#eef3fb;font-weight:700}.dv-table td.num{text-align:right;font-variant-numeric:tabular-nums}.dv-more{text-align:center;color:#8a97ab}" +
        ".dv-tabs{display:flex;flex-wrap:wrap;gap:.3rem;margin-bottom:.6rem}.dv-tabs button{border:1px solid #dce4f0;background:#fff;border-radius:999px;padding:.25rem .8rem;font-size:.8rem;font-weight:700}" +
        ".dv-tabs button.on{background:#0057B8;color:#fff;border-color:#0057B8}" +
        ".dv-slides{display:flex;flex-direction:column;gap:1rem;max-width:900px;margin:0 auto}.dv-slide{position:relative;background:#fff;border-radius:.6rem;padding:1.4rem 1.6rem;box-shadow:0 4px 18px rgba(0,32,84,.08);aspect-ratio:16/9;overflow:auto}" +
        ".dv-slide h4{color:#0B3D91;font-weight:800}.dv-slide-no{position:absolute;top:.5rem;right:.8rem;font-size:.75rem;color:#8a97ab;font-weight:700}" +
        ".dv-img{max-width:100%;height:auto;display:block;margin:.5rem auto;border-radius:.3rem}.dv-pre{white-space:pre-wrap;background:#fff;padding:1rem;border-radius:.4rem}" +
        ".dv-note{font-size:.78rem;color:#8a5a00;background:#fff6e5;border-radius:.4rem;padding:.35rem .6rem;margin-bottom:.8rem}.dv-warn{background:#fdecec;color:#b42318;padding:1rem;border-radius:.5rem}";

    function ensureDocStyles() {
        if (document.getElementById("rccDocViewerCss")) return;
        var st = document.createElement("style");
        st.id = "rccDocViewerCss";
        st.textContent = DV_CSS;
        document.head.appendChild(st);
    }

    /**
     * Fichier : tout s'ouvre directement dans la fenêtre, sans téléchargement — PDF et images tels quels,
     * Word, Excel, PowerPoint, texte et CSV convertis en aperçu par le serveur (/api/kb/files/preview).
     */
    function openFile(file, fromBack) {
        var url = file.url;
        var name = file.fileName || url;
        if (file.mimeType === "text/uri-list" || /^https?:\/\//i.test(url) && url.indexOf(window.location.origin) !== 0) {
            // Lien externe : la plupart des sites interdisent leur affichage dans une fenêtre intégrée.
            show(name, '<div class="text-center py-4"><i class="bi bi-link-45deg fs-1 text-primary"></i>' +
                '<p class="mb-3">Ce document est un lien vers un site externe, qui ne peut pas s\'afficher dans le portail.</p>' +
                '<a class="btn btn-primary" href="' + escapeHtml(url) + '" target="_blank" rel="noopener">Ouvrir le lien</a></div>', "", fromBack);
            return;
        }
        if (isPdf(file)) {
            show(name, '<iframe src="' + escapeHtml(url) + '#view=FitH" title="' + escapeHtml(name) + '" ' +
                'style="width:100%;height:78vh;border:0;border-radius:.5rem;background:#f1f3f5;"></iframe>', "", fromBack);
            return;
        }
        if (isImage(file)) {
            show(name, '<div class="text-center"><img src="' + escapeHtml(url) + '" alt="' + escapeHtml(name) + '" class="img-fluid rounded" style="max-height:78vh;"></div>', "", fromBack);
            return;
        }
        ensureDocStyles();
        show(name, '<div class="text-center text-muted py-5"><span class="spinner-border spinner-border-sm"></span> Ouverture du document…</div>', "", fromBack);
        getJson("/api/kb/files/preview?url=" + encodeURIComponent(url) + "&name=" + encodeURIComponent(name)).then(function (p) {
            var body;
            if (p.kind === "html" && p.html) body = '<div class="dv-body">' + p.html + "</div>";
            else if (p.kind === "pdf") body = '<iframe src="' + escapeHtml(url) + '#view=FitH" style="width:100%;height:78vh;border:0;border-radius:.5rem;"></iframe>';
            else if (p.kind === "image") body = '<div class="text-center"><img src="' + escapeHtml(url) + '" class="img-fluid rounded" style="max-height:78vh;" alt=""></div>';
            else body = '<div class="text-center py-4 text-muted"><i class="bi bi-file-earmark fs-1"></i><p class="mt-2 mb-0">Ce type de fichier ne peut pas être affiché.</p></div>';
            show(name, body, "", true);
            var bodyEl = document.getElementById("rccViewerBody");
            bodyEl.addEventListener("click", function (e) {
                var t = e.target.closest("[data-dv-sheet]");
                if (!t) return;
                bodyEl.querySelectorAll("[data-dv-sheet]").forEach(function (x) { x.classList.toggle("on", x === t); });
                bodyEl.querySelectorAll("[data-dv-sheet-pane]").forEach(function (pane) {
                    pane.hidden = pane.getAttribute("data-dv-sheet-pane") !== t.getAttribute("data-dv-sheet");
                });
            });
        }).catch(function (e) {
            show(name, '<div class="alert alert-warning mb-0">Impossible d\'afficher ce document : ' + escapeHtml(e.message) + "</div>", "", true);
        });
    }

    function attachmentsHtml(attachments) {
        if (!attachments || !attachments.length) return "";
        return '<h6 class="mt-4 mb-2"><i class="bi bi-paperclip"></i> Fichiers joints</h6><div class="list-group">' +
            attachments.map(function (a, i) {
                var icon = a.mimeType === "text/uri-list" ? "bi-link-45deg" : isPdf({ mimeType: a.mimeType, fileName: a.fileName }) ? "bi-file-earmark-pdf"
                    : isImage({ mimeType: a.mimeType, fileName: a.fileName }) ? "bi-image" : "bi-file-earmark-text";
                return '<button type="button" class="list-group-item list-group-item-action rcc-viewer-attachment" data-index="' + i + '">' +
                    '<i class="bi ' + icon + '"></i> ' + escapeHtml(a.fileName) + "</button>";
            }).join("") + "</div>";
    }

    function wireAttachments(attachments, reopen) {
        Array.prototype.forEach.call(document.querySelectorAll("#rccViewerBody .rcc-viewer-attachment"), function (btn) {
            btn.addEventListener("click", function () {
                var a = attachments[Number(btn.getAttribute("data-index"))];
                history.push(reopen);
                openFile({ url: a.storageUrl, fileName: a.fileName, mimeType: a.mimeType });
            });
        });
    }

    /**
     * Article de la Base de connaissances : contenu + pièces jointes. S'il n'a pas de contenu
     * et une seule pièce jointe (dossier de fichiers), le fichier est affiché directement.
     */
    function openArticle(articleId, options) {
        options = options || {};
        loading();
        Promise.all([getJson("/api/kb/articles/" + articleId), getJson("/api/kb/articles/" + articleId + "/attachments").catch(function () { return []; })])
            .then(function (r) {
                var article = r[0], attachments = r[1] || [];
                var title = article.title + (article.countryCode ? " [" + article.countryCode + "]" : "");
                var plain = (article.contentHtml || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
                var reopen = function (back) { render(back); };
                function render(back) {
                    var meta = [article.categoryTitle, article.countryLabel || "Toutes filiales", article.serviceName].filter(Boolean).join(" · ");
                    show(title, '<div class="small text-muted mb-3">' + escapeHtml(meta) + "</div>" +
                        '<div class="rcc-viewer-content">' + (article.contentHtml || '<p class="text-muted">Aucun contenu.</p>') + "</div>" +
                        attachmentsHtml(attachments),
                        '<a class="btn btn-sm btn-outline-primary" href="/knowledge?article=' + article.articleId + '"><i class="bi bi-box-arrow-in-right"></i> Voir dans la Base de connaissances</a>', back);
                    wireAttachments(attachments, reopen);
                }
                if (options.openSingleFile !== false && attachments.length === 1 && plain.length < 120) {
                    history.push(reopen);
                    openFile({ url: attachments[0].storageUrl, fileName: attachments[0].fileName, mimeType: attachments[0].mimeType });
                } else {
                    render(false);
                }
            })
            .catch(error);
    }

    function openProcedure(procedureId) {
        loading();
        getJson("/api/procedures/" + procedureId)
            .then(function (p) {
                var attachments = p.attachments || [];
                var meta = [p.serviceName, p.countryCode, p.level, p.responsibleTeam ? "Équipe : " + p.responsibleTeam : null,
                    p.slaDelay ? "Délai : " + p.slaDelay : null].filter(Boolean).join(" · ");
                var steps = (p.steps || []).map(function (s) { return "<li class=\"mb-2\">" + escapeHtml(s) + "</li>"; }).join("");
                var reopen = function (back) { render(back); };
                function render(back) {
                    show(p.title, '<div class="small text-muted mb-3">' + escapeHtml(meta) + "</div>" +
                        (steps ? "<ol>" + steps + "</ol>" : '<p class="text-muted">Étapes pas encore rédigées.</p>') + attachmentsHtml(attachments),
                        '<a class="btn btn-sm btn-outline-primary" href="/procedures?openProcedureId=' + p.id + '"><i class="bi bi-box-arrow-in-right"></i> Ouvrir dans Procédures</a>', back);
                    wireAttachments(attachments, reopen);
                }
                render(false);
            })
            .catch(error);
    }

    function openCourse(courseId, title, description) {
        show(title || "Formation", "<p>" + escapeHtml(description || "") + "</p>",
            '<a class="btn btn-sm btn-primary" href="/training?openCourseId=' + courseId + '"><i class="bi bi-play-circle"></i> Suivre la formation</a>');
    }

    /** Ouvre un résultat de recherche (article, procédure, formation) dans la visionneuse. */
    function openResult(r) {
        history = [];
        if (r.sourceType === "ARTICLE") return openArticle(r.id);
        if (r.sourceType === "PROCEDURE") return openProcedure(r.id);
        if (r.sourceType === "COURSE") return openCourse(r.id, r.title, r.snippet);
        window.location.href = "/games";
    }

    // Tout lien de fichier marqué data-rcc-view="file" s'ouvre dans la fenêtre au lieu d'un nouvel
    // onglet (Ctrl/Cmd+clic ou clic molette gardent le comportement du navigateur).
    document.addEventListener("click", function (evt) {
        var link = evt.target.closest ? evt.target.closest('a[data-rcc-view="file"]') : null;
        if (!link || evt.ctrlKey || evt.metaKey || evt.shiftKey || evt.button !== 0) return;
        evt.preventDefault();
        history = [];
        openFile({ url: link.getAttribute("href"), fileName: link.getAttribute("data-name"), mimeType: link.getAttribute("data-mime") });
    });

    return { openFile: openFile, openArticle: openArticle, openProcedure: openProcedure, openResult: openResult };
})();
