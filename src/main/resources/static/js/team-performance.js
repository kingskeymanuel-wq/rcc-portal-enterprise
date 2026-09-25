"use strict";

/**
 * Tableau de performance avec les indicateurs PROPRES à l'équipe (Inbound Voix, Inbound Mail /
 * Rafiki, CIB, Outbound) — API GET /api/team-performance. Utilisé par le Portail Team Leader
 * (son équipe) et le Portail Superviseur (toutes les équipes).
 *
 * RccTeamPerf.render(container, { team, month | from/to, countryCode, onRowClick(username) })
 */
window.RccTeamPerf = (function () {
    var esc = RccApi.escapeHtml;

    function fmt(v, unit) {
        if (v === null || v === undefined) return "—";
        if (unit === "PCT") return (Math.round(v * 10) / 10).toString().replace(".", ",") + " %";
        if (unit === "SECONDS") { var m = Math.floor(v / 60), s = Math.round(v % 60); return m + " min " + String(s).padStart(2, "0"); }
        if (unit === "MINUTES") return v >= 60 ? Math.floor(v / 60) + " h " + String(Math.round(v % 60)).padStart(2, "0") : Math.round(v) + " min";
        return Math.round(v).toLocaleString("fr-FR");
    }

    function render(container, opts) {
        container.innerHTML = '<p class="text-muted small">Chargement des indicateurs…</p>';
        var q = [];
        if (opts.team) q.push("team=" + encodeURIComponent(opts.team));
        if (opts.month) q.push("month=" + encodeURIComponent(opts.month));
        if (opts.from && opts.to) q.push("from=" + opts.from + "&to=" + opts.to);
        if (opts.countryCode) q.push("countryCode=" + encodeURIComponent(opts.countryCode));
        return RccApi.getJson("/api/team-performance?" + q.join("&")).then(function (p) {
            var tiles = p.columns.map(function (c) {
                return '<div class="tp-tile" title="' + esc(c.hint || "") + '"><small>' + esc(c.label) + ' <i class="bi ' + (c.higherIsBetter ? "bi-arrow-up-short" : "bi-arrow-down-short") + '"></i></small><b>' + fmt(p.teamValues[c.key], c.unit) + '</b></div>';
            }).join("");
            // Meilleur / moins bon de l'équipe mis en évidence dans chaque colonne.
            var best = {}, worst = {};
            p.columns.forEach(function (c) {
                var vals = p.rows.map(function (r) { return r.values[c.key]; }).filter(function (v) { return v !== null && v !== undefined; });
                if (vals.length < 2) return;
                var hi = Math.max.apply(null, vals), lo = Math.min.apply(null, vals);
                if (hi === lo) return;
                best[c.key] = c.higherIsBetter ? hi : lo;
                worst[c.key] = c.higherIsBetter ? lo : hi;
            });
            var table = p.rows.length ? '<div class="table-responsive"><table class="table table-hover table-sm align-middle tp-table"><thead><tr><th>Agent</th>' +
                p.columns.map(function (c) { return '<th class="text-end" title="' + esc(c.hint || "") + '">' + esc(c.label) + '</th>'; }).join("") +
                '<th class="text-end">Performance globale</th></tr></thead><tbody>' +
                p.rows.map(function (r) {
                    return '<tr' + (opts.onRowClick ? ' role="button" data-tp-user="' + esc(r.username) + '"' : "") + '><td><b>' + esc(r.name) + '</b>' + (opts.decorateName ? opts.decorateName(r) : "") + '</td>' +
                        p.columns.map(function (c) {
                            var v = r.values[c.key];
                            var cls = v !== null && v !== undefined && v === best[c.key] ? " tp-good" : (v !== null && v !== undefined && v === worst[c.key] ? " tp-bad" : "");
                            return '<td class="tp-v' + cls + '">' + fmt(v, c.unit) + '</td>';
                        }).join("") +
                        '<td class="tp-v">' + fmt(r.performanceGlobale, "PCT") + '</td></tr>';
                }).join("") + '</tbody></table></div>'
                : '<p class="text-muted small">Aucun agent de cette équipe sur la période.</p>';
            container.innerHTML = '<div class="tp-head"><h6><i class="bi bi-speedometer2"></i> Indicateurs ' + esc(p.teamLabel) + ' — ' + esc(p.period) + '</h6>' +
                '<small class="text-muted">Vert : meilleur de l\'équipe · rouge : à accompagner · « — » : indicateur non importé</small></div>' +
                '<div class="tp-tiles">' + tiles + '</div>' + table;
            if (opts.onRowClick) {
                container.querySelectorAll("[data-tp-user]").forEach(function (tr) {
                    tr.addEventListener("click", function () { opts.onRowClick(tr.getAttribute("data-tp-user")); });
                });
            }
            return p;
        }).catch(function (e) {
            container.innerHTML = '<p class="text-danger small">Indicateurs indisponibles : ' + esc(e.message) + '</p>';
        });
    }

    return { render: render, format: fmt };
})();
