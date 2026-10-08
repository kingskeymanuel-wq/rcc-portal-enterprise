"use strict";

/**
 * Bibliothèque de dispatching de la Base de connaissances (QA / administrateur).
 * On dépose un ZIP (gardé dans la bibliothèque), « Aperçu » montre où ira chaque fichier, « Dispatching » range
 * réellement les fichiers dans leurs rubriques et filiales : nouveaux fichiers ajoutés, fichiers du même titre
 * remplacés si leur contenu a changé, fichiers identiques laissés. Un nouveau ZIP + « Dispatching » = mise à jour.
 */
window.RccKbDispatch = (function () {
    var esc = function (s) { return RccApi.escapeHtml(s); };
    var modal, bs;
    var ACTIONS = {
        ADD: ["Ajouté", "bg-success"], UPDATE: ["Mis à jour", "bg-primary"], UNCHANGED: ["Inchangé", "bg-secondary"],
        SKIP: ["Ignoré", "bg-warning text-dark"], REMOVE: ["Retiré", "bg-danger"]
    };
    var PLANNED = { ADD: "À ajouter", UPDATE: "À mettre à jour", UNCHANGED: "Inchangé", SKIP: "Ignoré", REMOVE: "À retirer" };

    function q(sel) { return modal.querySelector(sel); }

    function fmtSize(b) { return b > 1048576 ? (b / 1048576).toFixed(1).replace(".", ",") + " Mo" : Math.max(1, Math.round(b / 1024)) + " Ko"; }
    function fmtDate(d) {
        if (!d) return "—";
        var x = new Date(d);
        return isNaN(x.getTime()) ? d : x.toLocaleString("fr-FR", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" });
    }

    function send(url, method) {
        return fetch(url, { method: method, credentials: "same-origin" }).then(function (res) {
            return res.text().then(function (t) {
                var data = null;
                try { data = t ? JSON.parse(t) : null; } catch (e) { /* réponse vide */ }
                if (!res.ok) throw new Error((data && (data.message || (data.error && data.error.message))) || ("Erreur " + res.status));
                return data;
            });
        });
    }

    function build() {
        if (modal) return;
        var wrap = document.createElement("div");
        wrap.innerHTML =
            '<div class="modal fade" id="kbDispatchModal" tabindex="-1"><div class="modal-dialog modal-xl modal-dialog-scrollable"><div class="modal-content">' +
            '<div class="modal-header"><h5 class="modal-title"><i class="bi bi-diagram-3"></i> Bibliothèque de dispatching</h5>' +
            '<button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>' +
            '<div class="modal-body">' +
            '<div class="row g-3">' +
            '<div class="col-lg-7"><div class="border rounded-3 p-3 h-100" id="kbdDrop" style="border-style:dashed !important;background:#F6F9FF;cursor:pointer;text-align:center">' +
            '<i class="bi bi-file-earmark-zip" style="font-size:2.2rem;color:#0057B8"></i><div class="fw-semibold mt-1">Déposez un ZIP ici ou cliquez pour le choisir</div>' +
            '<div class="small text-muted">Il est gardé dans la bibliothèque : vous le dispatchez quand vous voulez.</div>' +
            '<input type="file" accept=".zip,application/zip" class="d-none" id="kbdFile">' +
            '<div class="progress mt-2" style="height:6px;display:none" id="kbdProg"><div class="progress-bar" style="width:0%"></div></div>' +
            '<div class="small mt-2" id="kbdUpMsg"></div></div></div>' +
            '<div class="col-lg-5"><div class="border rounded-3 p-3 h-100 small">' +
            '<div class="fw-semibold mb-1"><i class="bi bi-info-circle"></i> Comment ranger le ZIP</div>' +
            '<div><code>Rubrique/fichier.pdf</code> → la rubrique (créée si elle n\'existe pas)</div>' +
            '<div><code>Rubrique/Côte d\'Ivoire/fichier.pdf</code> → rubrique + filiale (ECI, Togo, TG…)</div>' +
            '<div><code>CIB/Rubrique/fichier.pdf</code> → base CIB</div>' +
            '<div class="mt-1 text-muted">Même titre = même fichier : « Procédure carte v2.pdf » remplace « Procédure carte.pdf ». Un fichier identique n\'est pas re-déposé.</div>' +
            '</div></div></div>' +
            '<div class="d-flex flex-wrap align-items-center gap-3 mt-3">' +
            '<div class="btn-group btn-group-sm" role="group" id="kbdSpace">' +
            '<button type="button" class="btn btn-outline-primary" data-space="GENERAL"><i class="bi bi-book"></i> Base générale</button>' +
            '<button type="button" class="btn btn-outline-primary" data-space="CIB"><i class="bi bi-buildings"></i> Base CIB</button></div>' +
            '<div class="form-check form-switch mb-0"><input class="form-check-input" type="checkbox" id="kbdRemove">' +
            '<label class="form-check-label small" for="kbdRemove">Retirer les fichiers déjà dispatchés qui ne sont plus dans le ZIP (rubriques du ZIP uniquement)</label></div></div>' +
            '<h6 class="mt-3 mb-2">Bibliothèque</h6><div id="kbdList"><p class="text-muted small">Chargement…</p></div>' +
            '<div id="kbdReport" class="mt-3"></div>' +
            '</div></div></div></div>';
        document.body.appendChild(wrap.firstChild);
        modal = document.getElementById("kbDispatchModal");
        bs = new bootstrap.Modal(modal);

        var drop = q("#kbdDrop"), input = q("#kbdFile");
        drop.addEventListener("click", function (e) { if (e.target !== input) input.click(); });
        input.addEventListener("change", function () { if (input.files[0]) upload(input.files[0]); input.value = ""; });
        ["dragenter", "dragover"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.style.background = "#E6EEFF"; }); });
        ["dragleave", "drop"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.style.background = "#F6F9FF"; }); });
        drop.addEventListener("drop", function (e) { if (e.dataTransfer.files[0]) upload(e.dataTransfer.files[0]); });
        Array.prototype.forEach.call(modal.querySelectorAll("#kbdSpace [data-space]"), function (b) {
            b.addEventListener("click", function () { setSpace(b.getAttribute("data-space")); });
        });
    }

    var space = "GENERAL";
    function setSpace(s) {
        space = s === "CIB" ? "CIB" : "GENERAL";
        Array.prototype.forEach.call(modal.querySelectorAll("#kbdSpace [data-space]"), function (b) {
            b.classList.toggle("active", b.getAttribute("data-space") === space);
        });
    }

    function upload(file) {
        var msg = q("#kbdUpMsg"), prog = q("#kbdProg"), bar = prog.firstChild;
        if (!/\.zip$/i.test(file.name)) { msg.innerHTML = '<span class="text-danger">Choisissez un fichier .zip.</span>'; return; }
        var fd = new FormData();
        fd.append("file", file);
        var xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/kb/dispatch/packages");
        xhr.withCredentials = true;
        prog.style.display = "";
        bar.style.width = "0%";
        msg.innerHTML = '<span class="text-muted">Envoi de « ' + esc(file.name) + ' » (' + fmtSize(file.size) + ')…</span>';
        xhr.upload.onprogress = function (e) { if (e.lengthComputable) bar.style.width = Math.round(e.loaded / e.total * 100) + "%"; };
        xhr.onload = function () {
            prog.style.display = "none";
            var data = null;
            try { data = JSON.parse(xhr.responseText); } catch (e) { /* texte brut */ }
            if (xhr.status >= 200 && xhr.status < 300) {
                msg.innerHTML = '<span class="text-success"><i class="bi bi-check-circle"></i> « ' + esc(data.fileName) + " » ajouté à la bibliothèque (" +
                    data.fileCount + " fichier(s)). Cliquez « Aperçu » puis « Dispatching ».</span>";
                loadList().then(function () { analyse(data.id); });
            } else {
                msg.innerHTML = '<span class="text-danger">' + esc((data && (data.message || (data.error && data.error.message))) || ("Erreur " + xhr.status)) + "</span>";
            }
        };
        xhr.onerror = function () { prog.style.display = "none"; msg.innerHTML = '<span class="text-danger">Connexion interrompue pendant l\'envoi.</span>'; };
        xhr.send(fd);
    }

    function loadList() {
        var box = q("#kbdList");
        return RccApi.getJson("/api/kb/dispatch/packages").then(function (list) {
            if (!list.length) { box.innerHTML = '<p class="text-muted small mb-0">Aucun ZIP dans la bibliothèque. Déposez-en un ci-dessus.</p>'; return; }
            box.innerHTML = '<div class="table-responsive"><table class="table table-sm align-middle mb-0"><thead><tr><th>ZIP</th><th>Fichiers</th><th>Déposé</th>' +
                '<th>Dernier dispatching</th><th></th></tr></thead><tbody>' + list.map(function (p) {
                    return "<tr><td><i class=\"bi bi-file-earmark-zip text-primary\"></i> <strong>" + esc(p.fileName) + '</strong><div class="small text-muted">' + fmtSize(p.sizeBytes) +
                        "</div></td><td>" + p.fileCount + '</td><td class="small">' + fmtDate(p.uploadedAt) + "<div class=\"text-muted\">" + esc(p.uploadedBy || "") + "</div></td>" +
                        '<td class="small">' + (p.lastDispatchAt ? fmtDate(p.lastDispatchAt) + " · " + esc(p.lastDispatchBy || "") + '<div class="text-muted">' + esc(p.lastSummary || "") + "</div>" : '<span class="text-muted">Jamais</span>') +
                        '</td><td class="text-end text-nowrap">' +
                        '<button type="button" class="btn btn-sm btn-outline-secondary" data-kbd-analyse="' + p.id + '"><i class="bi bi-eye"></i> Aperçu</button> ' +
                        '<button type="button" class="btn btn-sm btn-primary" data-kbd-dispatch="' + p.id + '"><i class="bi bi-diagram-3"></i> Dispatching</button> ' +
                        (p.lastDispatchAt ? '<button type="button" class="btn btn-sm btn-outline-primary" data-kbd-report="' + p.id + '" title="Dernier compte rendu"><i class="bi bi-list-check"></i></button> ' : "") +
                        '<button type="button" class="btn btn-sm btn-outline-danger" data-kbd-del="' + p.id + '" title="Retirer de la bibliothèque"><i class="bi bi-trash"></i></button></td></tr>';
                }).join("") + "</tbody></table></div>";
            wire(box, "data-kbd-analyse", analyse);
            wire(box, "data-kbd-dispatch", dispatch);
            wire(box, "data-kbd-report", function (id) { RccApi.getJson("/api/kb/dispatch/packages/" + id + "/report").then(showReport).catch(fail); });
            wire(box, "data-kbd-del", function (id) {
                if (!window.confirm("Retirer ce ZIP de la bibliothèque ? Les fichiers déjà dispatchés restent dans la Base de connaissances.")) return;
                send("/api/kb/dispatch/packages/" + id, "DELETE").then(function () { q("#kbdReport").innerHTML = ""; loadList(); }).catch(fail);
            });
        }).catch(function (e) { box.innerHTML = '<p class="text-danger small mb-0">' + esc(e.message) + "</p>"; });
    }

    function wire(box, attr, fn) {
        Array.prototype.forEach.call(box.querySelectorAll("[" + attr + "]"), function (b) {
            b.addEventListener("click", function () { fn(+b.getAttribute(attr)); });
        });
    }

    function fail(e) { q("#kbdReport").innerHTML = '<div class="alert alert-danger">' + esc(e.message) + "</div>"; }

    function params() { return "?space=" + space + "&removeMissing=" + (q("#kbdRemove").checked ? "true" : "false"); }

    function busy(text) {
        q("#kbdReport").innerHTML = '<div class="text-muted"><span class="spinner-border spinner-border-sm"></span> ' + esc(text) + "</div>";
    }

    function analyse(id) {
        busy("Analyse du ZIP…");
        send("/api/kb/dispatch/packages/" + id + "/analyse" + params(), "POST").then(showReport).catch(fail);
    }

    function dispatch(id) {
        if (q("#kbdRemove").checked && !window.confirm("Les fichiers dispatchés auparavant et absents de ce ZIP seront retirés de leurs rubriques. Continuer ?")) return;
        busy("Dispatching en cours : rangement des fichiers dans les rubriques…");
        send("/api/kb/dispatch/packages/" + id + "/dispatch" + params(), "POST").then(function (r) {
            showReport(r);
            loadList();
            document.dispatchEvent(new CustomEvent("rcc:kb-updated", { detail: r }));
        }).catch(fail);
    }

    var filter = null;
    function showReport(r) {
        var box = q("#kbdReport");
        var counts = [["ADD", r.added], ["UPDATE", r.updated], ["UNCHANGED", r.unchanged], ["SKIP", r.skipped], ["REMOVE", r.removed]];
        var head = r.applied
            ? '<div class="alert alert-success mb-2"><i class="bi bi-check-circle"></i> <strong>Dispatching terminé</strong> — « ' + esc(r.packageName) + " »" +
              (r.newCategories ? " · " + r.newCategories + " rubrique(s) créée(s)" : "") + '. Les agents concernés ont reçu une notification. ' +
              '<button type="button" class="btn btn-sm btn-success ms-2" id="kbdReload"><i class="bi bi-arrow-clockwise"></i> Voir la base à jour</button></div>'
            : '<div class="alert alert-info mb-2"><i class="bi bi-eye"></i> <strong>Aperçu</strong> — « ' + esc(r.packageName) + ' » : rien n\'est encore modifié. ' +
              (r.newCategories ? r.newCategories + " rubrique(s) seront créées. " : "") +
              '<button type="button" class="btn btn-sm btn-primary ms-2" id="kbdGo"><i class="bi bi-diagram-3"></i> Lancer le dispatching</button></div>';
        filter = null;
        box.innerHTML = head + '<div class="d-flex flex-wrap gap-2 mb-2" id="kbdFilters">' + counts.map(function (c) {
            var a = ACTIONS[c[0]];
            return '<button type="button" class="btn btn-sm btn-light border" data-f="' + c[0] + '"><span class="badge ' + a[1] + '">' + c[1] + "</span> " +
                (r.applied ? a[0] : PLANNED[c[0]]) + "</button>";
        }).join("") + '<button type="button" class="btn btn-sm btn-link" data-f="">Tout afficher</button></div><div id="kbdItems"></div>';
        var rows = function () {
            var list = r.items.filter(function (it) { return !filter || it.action === filter; });
            q("#kbdItems").innerHTML = list.length ? '<div class="table-responsive" style="max-height:420px;overflow:auto"><table class="table table-sm align-middle mb-0">' +
                "<thead><tr><th>Fichier</th><th>Rubrique</th><th>Filiale</th><th>Action</th><th>Détail</th></tr></thead><tbody>" +
                list.map(function (it) {
                    var a = ACTIONS[it.action] || [it.action, "bg-light text-dark"];
                    return "<tr><td><strong>" + esc(it.fileName) + "</strong>" + (it.path && it.path !== it.fileName ? '<div class="small text-muted">' + esc(it.path) + "</div>" : "") +
                        "</td><td>" + (it.category ? esc(it.category) + (it.newCategory ? ' <span class="badge bg-info text-dark">nouvelle</span>' : "") +
                            (it.space === "CIB" ? ' <span class="badge bg-dark">CIB</span>' : "") : '<span class="text-muted">—</span>') +
                        "</td><td>" + (it.country ? esc(it.country) : '<span class="text-muted">Toutes</span>') + '</td><td><span class="badge ' + a[1] + '">' +
                        esc(r.applied ? a[0] : (PLANNED[it.action] || a[0])) + '</span></td><td class="small text-muted">' + esc(it.detail || "") + "</td></tr>";
                }).join("") + "</tbody></table></div>" : '<p class="text-muted small">Aucun fichier dans cette catégorie.</p>';
        };
        rows();
        Array.prototype.forEach.call(box.querySelectorAll("#kbdFilters [data-f]"), function (b) {
            b.addEventListener("click", function () { filter = b.getAttribute("data-f") || null; rows(); });
        });
        var go = q("#kbdGo");
        if (go) go.addEventListener("click", function () { dispatch(r.packageId); });
        var rl = q("#kbdReload");
        if (rl) rl.addEventListener("click", function () { window.location.reload(); });
    }

    function open() {
        build();
        setSpace(window.RccKbSpace === "CIB" ? "CIB" : "GENERAL");
        q("#kbdReport").innerHTML = "";
        q("#kbdUpMsg").innerHTML = "";
        loadList();
        bs.show();
    }

    return { open: open };
})();
