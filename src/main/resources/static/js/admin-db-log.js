"use strict";

/**
 * Administration — preuve que chaque changement est en base :
 *  - « Correctifs de données » (/api/admin/data-patches) : appliqués une fois au démarrage, résultat détaillé
 *    enregistré en base, relançables d'un clic ;
 *  - « Modifications enregistrées en base » (/api/admin/changes) : journal d'audit relu depuis la base, avec
 *    l'état de la personne juste après chaque modification. Rafraîchi après chaque changement (rcc:users-changed).
 */
(function () {
    var esc = RccApi.escapeHtml;

    function fmt(d) {
        if (!d) return "—";
        var t = new Date(String(d).replace(" ", "T"));
        return isNaN(t) ? String(d) : t.toLocaleString("fr-FR");
    }

    function loadPatches(box) {
        RccApi.getJson("/api/admin/data-patches").then(function (list) {
            box.innerHTML = list.map(function (p) {
                return '<div class="border rounded-3 p-3 mb-2">' +
                    '<div class="d-flex justify-content-between align-items-start gap-2 flex-wrap"><div><b>' + esc(p.title) + '</b>' +
                    '<div class="small text-muted">' + esc(p.description) + '</div></div>' +
                    '<button type="button" class="btn btn-sm btn-outline-primary" data-run="' + esc(p.code) + '"><i class="bi bi-arrow-repeat"></i> ' +
                    (p.appliedAt ? "Réappliquer" : "Appliquer") + '</button></div>' +
                    (p.appliedAt
                        ? '<div class="small mt-2"><span class="badge text-bg-success">Enregistré en base</span> le ' + esc(fmt(p.appliedAt)) + ' par ' + esc(p.appliedBy || "—") + '</div>' +
                          '<details class="mt-2"><summary class="small">Détail de ce qui a été écrit en base</summary><pre class="small mb-0 mt-2" style="white-space:pre-wrap;max-height:320px;overflow:auto">' + esc(p.result || "") + '</pre></details>'
                        : '<div class="small mt-2"><span class="badge text-bg-warning">Pas encore appliqué</span></div>') +
                    '</div>';
            }).join("") || '<p class="text-muted small mb-0">Aucun correctif.</p>';
        }).catch(function (e) { box.innerHTML = '<p class="text-danger small mb-0">' + esc(e.message) + '</p>'; });
    }

    function loadChanges(box) {
        RccApi.getJson("/api/admin/changes").then(function (list) {
            box.innerHTML = list.length ? '<div class="table-responsive" style="max-height:360px;overflow:auto"><table class="table table-sm small mb-0"><thead><tr><th>Quand</th><th>Qui</th><th>Modification (état relu en base)</th></tr></thead><tbody>' +
                list.slice(0, 100).map(function (l) {
                    return '<tr><td class="text-nowrap">' + esc(fmt(l.createdAt)) + '</td><td>' + esc(l.username) + '</td><td>' + esc(l.details) + '</td></tr>';
                }).join("") + '</tbody></table></div>'
                : '<p class="text-muted small mb-0">Aucune modification enregistrée pour l\'instant.</p>';
        }).catch(function (e) { box.innerHTML = '<p class="text-danger small mb-0">' + esc(e.message) + '</p>'; });
    }

    function mount(el) {
        el.innerHTML = '<div class="card dashboard-card shadow-sm mb-4"><div class="card-body">' +
            '<h5 class="card-title"><i class="bi bi-database-check"></i> Correctifs de données</h5>' +
            '<p class="text-muted small">Écrits directement dans la base de données, une seule fois ; le détail de chaque écriture est conservé.</p>' +
            '<div data-patches><p class="text-muted small">Chargement…</p></div>' +
            '<hr><div class="d-flex justify-content-between align-items-center mb-2"><h6 class="mb-0"><i class="bi bi-journal-check"></i> Modifications enregistrées en base</h6>' +
            '<button type="button" class="btn btn-sm btn-outline-secondary" data-refresh><i class="bi bi-arrow-clockwise"></i></button></div>' +
            '<p class="text-muted small">Chaque changement fait dans Administration (rôles, services, accès, fiches, comptes…) est écrit en base puis relu ici.</p>' +
            '<div data-changes><p class="text-muted small">Chargement…</p></div></div></div>';
        var patches = el.querySelector("[data-patches]"), changes = el.querySelector("[data-changes]");
        loadPatches(patches);
        loadChanges(changes);
        el.querySelector("[data-refresh]").addEventListener("click", function () { loadChanges(changes); });
        el.addEventListener("click", function (e) {
            var b = e.target.closest("[data-run]");
            if (!b) return;
            if (!confirm("Appliquer ce correctif dans la base de données maintenant ?")) return;
            b.disabled = true;
            fetch("/api/admin/data-patches/" + encodeURIComponent(b.getAttribute("data-run")) + "/run", { method: "POST", credentials: "same-origin" })
                .then(function (res) { if (!res.ok) return res.text().then(function (t) { throw new Error(t || "HTTP " + res.status); }); })
                .then(function () {
                    loadPatches(patches);
                    loadChanges(changes);
                    document.dispatchEvent(new CustomEvent("rcc:users-changed", { detail: { source: "patch" } }));
                })
                .catch(function (err) { b.disabled = false; alert("Erreur : " + err.message); });
        });
        document.addEventListener("rcc:users-changed", function () {
            clearTimeout(mount.t);
            mount.t = setTimeout(function () { loadChanges(changes); }, 600);
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        var el = document.getElementById("adminDbLog");
        if (!el) return;
        window.RccSession.init().then(function (s) { if (s && s.profile === "ADMIN") mount(el); else el.style.display = "none"; });
    });
})();
