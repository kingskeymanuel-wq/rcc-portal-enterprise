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

    var BADGE = { OK: ["success", "OK"], INFO: ["secondary", "Info"], ATTENTION: ["warning", "Attention"], A_CORRIGER: ["warning", "À corriger"], KO: ["danger", "Bloquant"] };

    function runHealth(box) {
        box.innerHTML = '<p class="text-muted small">Contrôle en cours sur la base…</p>';
        RccApi.getJson("/api/admin/db-health").then(function (r) {
            box.innerHTML = '<div class="alert ' + (r.ok ? "alert-success" : "alert-danger") + ' small py-2 mb-2">' +
                (r.ok ? '<i class="bi bi-check-circle-fill"></i> Le portail écrit bien dans la base <b>' + esc(r.database) + '</b> (serveur ' + esc(r.server) + ').'
                    : '<i class="bi bi-x-octagon-fill"></i> Des écritures sont bloquées : voir les points « Bloquant » ci-dessous.') + '</div>' +
                '<ul class="list-group list-group-flush small">' + r.checks.map(function (c) {
                    var b = BADGE[c.status] || ["secondary", c.status];
                    return '<li class="list-group-item px-0 d-flex gap-2 align-items-start"><span class="badge text-bg-' + b[0] + '">' + esc(b[1]) + '</span>' +
                        '<div class="flex-grow-1"><b>' + esc(c.label) + '</b><div class="text-muted">' + esc(c.detail) + '</div></div>' +
                        (c.action ? '<button type="button" class="btn btn-sm btn-outline-primary" data-fix="' + esc(c.action) + '">Corriger</button>' : '') + '</li>';
                }).join("") + '</ul>';
        }).catch(function (e) { box.innerHTML = '<p class="text-danger small mb-0">Contrôle impossible : ' + esc(e.message) + '</p>'; });
    }

    function mount(el) {
        el.innerHTML = '<div class="card dashboard-card shadow-sm mb-4"><div class="card-body">' +
            '<div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2"><h5 class="card-title mb-0"><i class="bi bi-activity"></i> Écriture dans la base de données</h5>' +
            '<button type="button" class="btn btn-sm btn-primary" data-health><i class="bi bi-shield-check"></i> Contrôler maintenant</button></div>' +
            '<p class="text-muted small">Vérifie en direct que chaque action de l\'Administration arrive dans la base : base et compte SQL utilisés, droits sur chaque table, test d\'écriture réel (annulé aussitôt), données qui faussent les accès.</p>' +
            '<div data-health-box class="mb-3"></div><hr>' +
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
        var healthBox = el.querySelector("[data-health-box]");
        runHealth(healthBox);
        el.querySelector("[data-health]").addEventListener("click", function () { runHealth(healthBox); });
        el.addEventListener("click", function (e) {
            var fx = e.target.closest("[data-fix]");
            if (fx) {
                if (!confirm("Appliquer cette correction dans la base de données ?")) return;
                fx.disabled = true;
                fetch("/api/admin/db-health/fix/" + encodeURIComponent(fx.getAttribute("data-fix")), { method: "POST", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) return res.text().then(function (t) { throw new Error(t || "HTTP " + res.status); }); return res.json(); })
                    .then(function (r) {
                        document.dispatchEvent(new CustomEvent("rcc:users-changed", { detail: { source: "patch" } }));
                        alert(r.fixed + " ligne(s) corrigée(s) en base.");
                        runHealth(healthBox);
                    })
                    .catch(function (err) { fx.disabled = false; alert("Erreur : " + err.message); });
                return;
            }
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
