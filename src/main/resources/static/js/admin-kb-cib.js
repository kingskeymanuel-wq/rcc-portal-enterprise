"use strict";

/**
 * Administration → Base CIB : rubriques de la base de connaissance CIB (catégories d'équipe « CIB ») et leurs fichiers.
 * Dépôt direct par l'administrateur (quick-upload), sans passer par la QA. Base séparée de la base générale.
 */
(function () {
    var esc = function (s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; };
    var loaded = false;

    function fileIcon(name) {
        var n = (name || "").toLowerCase();
        if (/\.pdf$/.test(n)) return "bi-file-earmark-pdf text-danger";
        if (/\.(docx?|odt)$/.test(n)) return "bi-file-earmark-word text-primary";
        if (/\.(xlsx?|csv)$/.test(n)) return "bi-file-earmark-excel text-success";
        if (/\.(pptx?)$/.test(n)) return "bi-file-earmark-slides text-warning";
        if (/\.(png|jpe?g|gif|webp)$/.test(n)) return "bi-file-earmark-image";
        return "bi-file-earmark";
    }

    /** Fichiers d'une rubrique = pièces jointes de ses articles. */
    function filesOf(categoryId) {
        return RccApi.getJson("/api/kb/articles?categoryId=" + categoryId).then(function (articles) {
            return Promise.all(articles.map(function (a) {
                return RccApi.getJson("/api/kb/articles/" + a.articleId + "/attachments").catch(function () { return []; });
            })).then(function (lists) { return [].concat.apply([], lists); });
        });
    }

    function renderCategory(c) {
        return '<div class="border rounded-3 p-3 mb-3" data-cib-cat="' + c.categoryId + '">' +
            '<div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">' +
            '<div class="fw-bold"><i class="bi ' + esc(c.icon || "bi-folder2") + '"></i> ' + esc(c.title) +
            ' <span class="text-muted small fw-normal">(' + esc(c.code) + ')</span></div>' +
            '<div class="d-flex gap-2 flex-wrap">' +
            '<label class="btn btn-sm btn-success mb-0"><i class="bi bi-upload"></i> Ajouter des fichiers' +
            '<input type="file" multiple class="d-none" data-cib-upload="' + c.categoryId + '"></label>' +
            '<button type="button" class="btn btn-sm btn-outline-danger" data-cib-del="' + c.categoryId + '" data-title="' + esc(c.title) + '"><i class="bi bi-trash"></i></button>' +
            '</div></div>' +
            '<div class="small" data-cib-files="' + c.categoryId + '"><span class="text-muted">Chargement des fichiers…</span></div></div>';
    }

    function renderFiles(categoryId) {
        var box = document.querySelector('[data-cib-files="' + categoryId + '"]');
        if (!box) return;
        filesOf(categoryId).then(function (files) {
            if (!files.length) { box.innerHTML = '<span class="text-muted">Aucun fichier pour l\'instant.</span>'; return; }
            box.innerHTML = '<ul class="list-unstyled mb-0">' + files.map(function (f) {
                return '<li class="d-flex justify-content-between align-items-center border-top py-1 gap-2">' +
                    '<a href="' + esc(f.storageUrl) + '" target="_blank" rel="noopener"><i class="bi ' + fileIcon(f.fileName) + '"></i> ' + esc(f.fileName) + '</a>' +
                    '<span class="d-flex align-items-center gap-2"><span class="text-muted">' + (f.createdAt ? new Date(f.createdAt).toLocaleDateString("fr-FR") : "") + '</span>' +
                    '<button type="button" class="btn btn-sm btn-link text-danger p-0" data-cib-rm="' + f.id + '" data-cat="' + categoryId + '" title="Retirer"><i class="bi bi-x-circle"></i></button></span></li>';
            }).join("") + "</ul>";
        }).catch(function (e) { box.innerHTML = '<span class="text-danger">' + esc(e.message) + "</span>"; });
    }

    function load(root) {
        var body = root.querySelector("[data-cib-body]");
        RccApi.getJson("/api/kb/categories?space=CIB").then(function (cats) {
            if (!cats.length) {
                body.innerHTML = '<div class="alert alert-light border small mb-0"><i class="bi bi-info-circle"></i> Aucune rubrique CIB. ' +
                    'Créez-en une avec « Nouvelle rubrique », puis ajoutez-y vos fichiers (PDF, Word, Excel, images…).</div>';
                return;
            }
            body.innerHTML = cats.map(renderCategory).join("");
            cats.forEach(function (c) { renderFiles(c.categoryId); });
        }).catch(function (e) { body.innerHTML = '<div class="alert alert-danger small mb-0">' + esc(e.message) + "</div>"; });
    }

    function upload(categoryId, files, input) {
        var label = input.closest("label");
        label.classList.add("disabled");
        var list = Array.prototype.slice.call(files);
        var failed = [];
        var chain = list.reduce(function (p, f) {
            return p.then(function () {
                var fd = new FormData();
                fd.append("file", f);
                return fetch("/api/kb/categories/" + categoryId + "/quick-upload", { method: "POST", credentials: "same-origin", body: fd })
                    .then(function (res) { if (!res.ok) return res.text().then(function (t) { throw new Error(t || ("HTTP " + res.status)); }); })
                    .catch(function () { failed.push(f.name); });
            });
        }, Promise.resolve());
        chain.then(function () {
            label.classList.remove("disabled");
            input.value = "";
            if (failed.length) alert("Non envoyé(s) : " + failed.join(", "));
            renderFiles(categoryId);
        });
    }

    function wire(root) {
        var form = root.querySelector("[data-cib-form]");
        root.querySelector("[data-cib-new]").addEventListener("click", function () { form.hidden = false; form.code.focus(); });
        root.querySelector("[data-cib-cancel]").addEventListener("click", function () { form.hidden = true; form.reset(); });
        form.addEventListener("submit", function (e) {
            e.preventDefault();
            var code = form.code.value.trim().toUpperCase().replace(/[^A-Z0-9_]+/g, "_");
            var title = form.title.value.trim();
            if (!code || !title) return;
            RccApi.sendJson("/api/kb/categories", "POST", { code: code, title: title, icon: form.icon.value.trim() || "bi-briefcase", team: "CIB", sortOrder: 0 })
                .then(function () { form.reset(); form.hidden = true; load(root); })
                .catch(function (err) { alert("Erreur : " + err.message); });
        });
        root.addEventListener("change", function (e) {
            var input = e.target.closest("[data-cib-upload]");
            if (input && input.files.length) upload(input.getAttribute("data-cib-upload"), input.files, input);
        });
        root.addEventListener("click", function (e) {
            var rm = e.target.closest("[data-cib-rm]");
            if (rm) {
                if (!confirm("Retirer ce fichier de la base CIB ?")) return;
                fetch("/api/kb/attachments/" + rm.getAttribute("data-cib-rm"), { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); renderFiles(rm.getAttribute("data-cat")); })
                    .catch(function (err) { alert("Erreur : " + err.message); });
                return;
            }
            var del = e.target.closest("[data-cib-del]");
            if (del) {
                if (!confirm("Supprimer la rubrique « " + del.getAttribute("data-title") + " » et tous ses fichiers ?")) return;
                fetch("/api/kb/categories/" + del.getAttribute("data-cib-del") + "?force=true", { method: "DELETE", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); load(root); })
                    .catch(function (err) { alert("Erreur : " + err.message); });
            }
        });
    }

    function ensureLoaded() {
        var root = document.getElementById("cibKb");
        if (!root || loaded) return;
        loaded = true;
        wire(root);
        load(root);
    }

    document.addEventListener("DOMContentLoaded", function () {
        var tab = document.querySelector('#adminTabs [data-tab="cib"]');
        if (tab) tab.addEventListener("click", ensureLoaded);
        if (location.hash === "#cib") ensureLoaded();
    });
})();
