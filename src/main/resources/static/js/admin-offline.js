"use strict";

/** Administration → Maintenance → « Fonctionnement sans Internet » (/api/admin/offline-diagnostic). */
(function () {
    var esc = function (s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; };
    var BADGE = {
        OK: ["bg-success", "Prêt"], WARN: ["bg-warning text-dark", "Dégradé"], KO: ["bg-danger", "Injoignable"],
        OFF: ["bg-secondary", "Coupé"], ON: ["bg-info text-dark", "Internet requis"]
    };

    function rows(list) {
        return '<div class="table-responsive"><table class="table table-sm align-middle mb-3"><tbody>' + list.map(function (c) {
            var b = BADGE[c.status] || ["bg-light text-dark", c.status];
            return "<tr><td style=\"width:38%\"><b>" + esc(c.name) + "</b></td><td style=\"width:110px\"><span class=\"badge " + b[0] + "\">" + b[1] + "</span></td>" +
                "<td class=\"small\">" + esc(c.detail) + (c.fix ? '<div class="text-primary"><i class="bi bi-wrench"></i> ' + esc(c.fix) + "</div>" : "") + "</td></tr>";
        }).join("") + "</tbody></table></div>";
    }

    function run(root) {
        var body = root.querySelector("[data-od-body]");
        body.innerHTML = '<div class="text-muted small"><span class="spinner-border spinner-border-sm"></span> Tests en cours…</div>';
        RccApi.getJson("/api/admin/offline-diagnostic").then(function (r) {
            var color = r.percent >= 90 ? "success" : r.percent >= 60 ? "warning" : "danger";
            var active = r.internet.filter(function (c) { return c.status === "ON"; }).length;
            body.innerHTML =
                '<div class="d-flex flex-wrap gap-3 align-items-center mb-3">' +
                '<div><div class="small text-muted">Mode hors ligne (RCC_OFFLINE)</div><b>' + (r.offlineMode ? '<i class="bi bi-toggle-on text-success"></i> Activé' : '<i class="bi bi-toggle-off text-muted"></i> Désactivé') + "</b></div>" +
                '<div style="min-width:220px;flex:1"><div class="small text-muted">Services locaux prêts : ' + r.ready + " / " + r.total + "</div>" +
                '<div class="progress" style="height:10px"><div class="progress-bar bg-' + color + '" style="width:' + r.percent + '%"></div></div></div>' +
                '<div><div class="small text-muted">Services Internet actifs</div><b class="' + (active ? "text-warning" : "text-success") + '">' + active + "</b></div></div>" +
                '<h6 class="small text-uppercase text-muted">Réseau interne et remplaçants locaux</h6>' + rows(r.internal) +
                '<h6 class="small text-uppercase text-muted">Services hébergés sur Internet</h6>' + rows(r.internet) +
                '<div class="small text-muted">Contrôlé à ' + new Date(r.checkedAt).toLocaleTimeString("fr-FR") + "</div>";
        }).catch(function (e) { body.innerHTML = '<div class="alert alert-danger small mb-0">' + esc(e.message) + "</div>"; });
    }

    document.addEventListener("DOMContentLoaded", function () {
        var root = document.getElementById("offlineDiag");
        if (!root) return;
        root.querySelector("[data-od-run]").addEventListener("click", function () { run(root); });
    });
})();
