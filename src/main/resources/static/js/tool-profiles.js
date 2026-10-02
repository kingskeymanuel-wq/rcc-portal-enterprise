"use strict";

/**
 * Outils & Portails → « Profils » : accès de chaque agent aux outils, en pourcentage du catalogue
 * (external-tools.js : 100 % = tous les outils). Rangé par filiale puis par équipe ; un clic ouvre le profil
 * de l'agent (modifiable par son Team Leader ou l'administrateur). Données : /api/tools/profiles.
 *
 *   RccToolProfiles.mount(el)
 */
window.RccToolProfiles = (function () {
    var esc = function (s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; };
    var COUNTRIES = { CI: ["RCC ECI", "Côte d'Ivoire", "🇨🇮"], TG: ["RCC ETG", "Togo", "🇹🇬"] };
    var modal = null;

    function catalog() { return window.RccExternalTools ? window.RccExternalTools.catalog() : []; }
    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }
    function pct(p, total) {
        if (!total) return 0;
        var codes = {}; catalog().forEach(function (t) { codes[t.code] = 1; });
        var n = (p.tools || []).filter(function (c) { return codes[c]; }).length;
        return Math.round(n * 100 / total);
    }
    function owned(p) {
        var codes = {}; catalog().forEach(function (t) { codes[t.code] = 1; });
        return (p.tools || []).filter(function (c) { return codes[c]; }).length;
    }
    function level(v) { return v >= 80 ? "tp-hi" : v >= 50 ? "tp-mid" : v >= 25 ? "tp-low" : "tp-none"; }

    function mount(root) {
        var st = { data: null, country: "", q: "", total: catalog().length };
        root.innerHTML = '<div class="tp"><div class="tp-head">' +
            '<div class="tp-search"><i class="bi bi-search"></i><input type="search" placeholder="Rechercher un agent…" data-tp-q></div>' +
            '<div class="tp-seg" data-tp-country><button type="button" class="on" data-v="">Toutes les filiales</button><button type="button" data-v="CI">🇨🇮 RCC ECI</button><button type="button" data-v="TG">🇹🇬 RCC ETG</button></div>' +
            '</div><div class="tp-sum"></div><div class="tp-body"><div class="text-muted small p-3">Chargement des profils…</div></div></div>';

        function load() {
            RccApi.getJson("/api/tools/profiles").then(function (r) { st.data = r; draw(); })
                .catch(function (e) { root.querySelector(".tp-body").innerHTML = '<div class="alert alert-danger small">' + esc(e.message) + '</div>'; });
        }

        function draw() {
            var q = st.q.toLowerCase(), total = st.total;
            var list = st.data.profiles.filter(function (p) {
                return (!st.country || p.country === st.country) && (!q || (p.name + " " + p.username + " " + p.teamLabel).toLowerCase().indexOf(q) !== -1);
            });
            var avg = list.length ? Math.round(list.reduce(function (s, p) { return s + pct(p, total); }, 0) / list.length) : 0;
            var full = list.filter(function (p) { return pct(p, total) >= 80; }).length;
            root.querySelector(".tp-sum").innerHTML =
                '<div><b>' + list.length + '</b><span>agent(s)</span></div><div><b>' + avg + ' %</b><span>accès moyen</span></div>' +
                '<div><b>' + full + '</b><span>autonome(s) ≥ 80 %</span></div><div><b>' + total + '</b><span>outils au catalogue = 100 %</span></div>';
            if (!list.length) { root.querySelector(".tp-body").innerHTML = '<div class="tp-empty">Aucun agent dans ce périmètre.</div>'; return; }
            var byCountry = {};
            list.forEach(function (p) {
                var c = p.country || "CI";
                byCountry[c] = byCountry[c] || {};
                (byCountry[c][p.team] = byCountry[c][p.team] || { label: p.teamLabel, people: [] }).people.push(p);
            });
            root.querySelector(".tp-body").innerHTML = Object.keys(byCountry).sort().map(function (c) {
                var co = COUNTRIES[c] || [c, c, ""];
                return '<section class="tp-country"><h6>' + co[2] + ' ' + esc(co[0]) + ' <small>' + esc(co[1]) + '</small></h6>' +
                    Object.keys(byCountry[c]).map(function (t) {
                        var team = byCountry[c][t], tAvg = Math.round(team.people.reduce(function (s, p) { return s + pct(p, total); }, 0) / team.people.length);
                        team.people.sort(function (a, b) { return (a.level === "TEAM_LEADER" ? -1 : 0) - (b.level === "TEAM_LEADER" ? -1 : 0) || pct(b, total) - pct(a, total) || a.name.localeCompare(b.name); });
                        return '<div class="tp-team"><header><b>' + esc(team.label) + '</b><span>' + team.people.length + ' agent(s) · moyenne ' + tAvg + ' %</span>' +
                            '<div class="tp-bar sm"><i class="' + level(tAvg) + '" style="width:' + tAvg + '%"></i></div></header><div class="tp-grid">' +
                            team.people.map(function (p) {
                                var v = pct(p, total);
                                return '<button type="button" class="tp-card" data-tp-open="' + p.userId + '"><span class="tp-av">' + esc(initials(p.name)) + '</span>' +
                                    '<span class="tp-id"><b>' + esc(p.name) + '</b><small>' + (p.level === "TEAM_LEADER" ? '<em>Team Leader</em> · ' : '') + owned(p) + ' / ' + total + ' outils</small>' +
                                    '<span class="tp-bar"><i class="' + level(v) + '" style="width:' + v + '%"></i></span></span><span class="tp-pct ' + level(v) + '">' + v + ' %</span></button>';
                            }).join("") + '</div></div>';
                    }).join("") + '</section>';
            }).join("");
        }

        root.addEventListener("click", function (e) {
            var b = e.target.closest("button");
            if (!b || !root.contains(b)) return;
            if (b.closest("[data-tp-country]")) {
                st.country = b.dataset.v;
                root.querySelectorAll("[data-tp-country] button").forEach(function (x) { x.classList.toggle("on", x === b); });
                draw();
            } else if (b.dataset.tpOpen) {
                var p = st.data.profiles.filter(function (x) { return String(x.userId) === b.dataset.tpOpen; })[0];
                if (p) openProfile(p, function (updated) { Object.assign(p, updated); draw(); });
            }
        });
        root.querySelector("[data-tp-q]").addEventListener("input", function () { st.q = this.value.trim(); draw(); });
        load();
    }

    /** Profil d'un agent : pourcentage, outils par catégorie ; cases à cocher si l'utilisateur peut le modifier. */
    function openProfile(p, onSaved) {
        if (!modal) {
            var el = document.createElement("div");
            el.className = "modal fade tp-modal"; el.tabIndex = -1;
            el.innerHTML = '<div class="modal-dialog modal-lg modal-dialog-centered modal-dialog-scrollable"><div class="modal-content">' +
                '<div class="modal-header"><h6 class="modal-title"><i class="bi bi-person-badge"></i> Profil outils</h6><button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>' +
                '<div class="modal-body"></div><div class="modal-footer"></div></div></div>';
            document.body.appendChild(el);
            // Fenêtre ouverte par-dessus « Outils & Portails » : elle et son fond passent devant.
            el.addEventListener("show.bs.modal", function () { setTimeout(function () { var bd = document.querySelectorAll(".modal-backdrop"); if (bd.length > 1) bd[bd.length - 1].classList.add("tp-backdrop"); }, 0); });
            modal = { el: el, bs: new bootstrap.Modal(el) };
        }
        var cat = catalog(), total = cat.length, has = {};
        (p.tools || []).forEach(function (c) { has[c] = 1; });
        function render() {
            var n = cat.filter(function (t) { return has[t.code]; }).length, v = total ? Math.round(n * 100 / total) : 0;
            var co = COUNTRIES[p.country] || [p.country || "", "", ""];
            var groups = {};
            cat.forEach(function (t) { (groups[t.category] = groups[t.category] || { icon: t.icon, color: t.color, tools: [] }).tools.push(t); });
            modal.el.querySelector(".modal-body").innerHTML =
                '<div class="tp-prof"><span class="tp-av lg">' + esc(initials(p.name)) + '</span><div><h5>' + esc(p.name) + '</h5>' +
                '<small>' + esc(p.username) + (p.email ? " · " + esc(p.email) : "") + '</small><div class="tp-tags"><span>' + esc(p.teamLabel) + '</span><span>' + co[2] + " " + esc(co[0]) + '</span>' +
                (p.level === "TEAM_LEADER" ? '<span>Team Leader</span>' : '') + '</div></div>' +
                '<div class="tp-ring ' + level(v) + '" style="--v:' + v + '"><b>' + v + ' %</b><small>' + n + ' / ' + total + '</small></div></div>' +
                (p.canEdit ? '<div class="tp-hint"><i class="bi bi-pencil"></i> Cochez les outils auxquels l\'agent a accès, puis enregistrez.</div>' : '') +
                Object.keys(groups).map(function (g) {
                    var grp = groups[g];
                    return '<div class="tp-cat"><h6><i class="bi ' + esc(grp.icon) + '" style="color:' + esc(grp.color) + '"></i> ' + esc(g) + '</h6><div class="tp-tools">' +
                        grp.tools.map(function (t) {
                            var on = !!has[t.code];
                            return p.canEdit
                                ? '<label class="tp-tool ' + (on ? "on" : "") + '"><input type="checkbox" data-tp-tool="' + esc(t.code) + '"' + (on ? " checked" : "") + '> <span><b>' + esc(t.name) + '</b><small>' + esc(t.host) + '</small></span></label>'
                                : '<div class="tp-tool ' + (on ? "on" : "off") + '"><i class="bi ' + (on ? "bi-check-circle-fill" : "bi-x-circle") + '"></i><span><b>' + esc(t.name) + '</b><small>' + esc(t.host) + '</small></span></div>';
                        }).join("") + '</div></div>';
                }).join("");
            modal.el.querySelector(".modal-footer").innerHTML =
                '<small class="text-muted me-auto">' + (p.updatedBy ? "Mis à jour par " + esc(p.updatedBy) + (p.updatedAt ? " le " + new Date(p.updatedAt).toLocaleDateString("fr-FR") : "") : "Aucun accès enregistré pour l'instant.") + '</small>' +
                (p.canEdit ? '<span class="text-danger small" data-tp-err></span><button type="button" class="btn btn-primary" data-tp-save><i class="bi bi-check2"></i> Enregistrer</button>' : '') +
                '<button type="button" class="btn btn-light" data-bs-dismiss="modal">Fermer</button>';
        }
        render();
        modal.el.onchange = function (e) {
            var c = e.target.dataset.tpTool;
            if (!c) return;
            if (e.target.checked) has[c] = 1; else delete has[c];
            var keepScroll = modal.el.querySelector(".modal-body").scrollTop;
            render();
            modal.el.querySelector(".modal-body").scrollTop = keepScroll;
        };
        modal.el.onclick = function (e) {
            var save = e.target.closest("[data-tp-save]");
            if (!save) return;
            save.disabled = true;
            RccApi.sendJson("/api/tools/profiles/" + p.userId, "PUT", { tools: Object.keys(has) }).then(function (r) {
                p.tools = r.tools; p.updatedBy = r.updatedBy; p.updatedAt = r.updatedAt;
                if (onSaved) onSaved(r);
                modal.bs.hide();
            }).catch(function (err) { modal.el.querySelector("[data-tp-err]").textContent = err.message; save.disabled = false; });
        };
        modal.bs.show();
    }

    // Onglet « Profils » (Accueil, Team Leader, Superviseur) : monté à sa première ouverture.
    document.addEventListener("shown.bs.tab", function (e) {
        var id = e.target && e.target.getAttribute("data-tp-mount"), el = id && document.getElementById(id);
        if (el && !el.getAttribute("data-tp-mounted")) { el.setAttribute("data-tp-mounted", "1"); mount(el); }
    });

    return { mount: mount, openProfile: openProfile };
})();
