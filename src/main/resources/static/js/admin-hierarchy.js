"use strict";

/**
 * Administration — organigramme hiérarchique (/api/admin/hierarchy) :
 * Superviseur (Head RCC) → RH → Head QA et équipe QA → Team Leaders et agents par équipe, puis administrateurs,
 * agences et comptes à classer. Chaque personne affiche ses rôles et services : × pour retirer, + pour ajouter.
 * Chaque modification s'applique aussitôt aux portails (accès relu à chaque requête).
 */
(function () {
    var esc = RccApi.escapeHtml;
    var st = { data: null, roles: [], services: [], q: "", country: "", showInactive: false, open: {} };
    var root;

    var LEVELS = {
        SUPERVISEUR: ["Superviseur · Head RCC", "bi-binoculars-fill", "sup"],
        RH: ["Ressources Humaines", "bi-person-vcard-fill", "rh"],
        HEAD_QA: ["Head QA", "bi-patch-check-fill", "hqa"],
        QA: ["Quality Assurance & formateurs", "bi-headset", "qa"],
        TEAM_LEADER: ["Team Leader", "bi-person-badge-fill", "tl"],
        AGENT: ["Agent", "bi-person-fill", "agent"],
        ADMIN: ["Administrateurs", "bi-shield-lock-fill", "admin"],
        AGENCE: ["Agences", "bi-shop-window", "agence"],
        A_CLASSER: ["À classer", "bi-question-diamond-fill", "todo"]
    };
    var PROFILE = { ADMIN: "Administrateur", SUPERVISOR: "Superviseur", RH: "RH", TEAM_LEADER: "Team Leader", AGENCE: "Agence", AGENT: "Agent", EXCELLIAM: "Excelliam" };

    function $(s) { return root.querySelector(s); }
    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }

    function visible(p) {
        if (!st.showInactive && !p.active) return false;
        if (st.country && p.country !== st.country) return false;
        if (st.q) {
            var hay = [p.name, p.username, p.email].concat(p.roles.map(function (r) { return r.name; }), p.services.map(function (s) { return s.name + " " + s.code; })).join(" ").toLowerCase();
            if (hay.indexOf(st.q) === -1) return false;
        }
        return true;
    }

    function personHtml(p) {
        var roleOpts = st.roles.filter(function (r) { return !p.roles.some(function (x) { return x.id === r.id; }); });
        var svcOpts = st.services.filter(function (s) { return !p.services.some(function (x) { return x.id === s.id; }); });
        return '<div class="ah-person' + (p.active ? "" : " off") + (p.warnings.length && p.active ? " warn" : "") + '" data-id="' + p.id + '">' +
            '<div class="ah-id"><span class="ah-av ' + (LEVELS[p.level] || LEVELS.A_CLASSER)[2] + '">' + esc(initials(p.name)) + '</span>' +
            '<div class="ah-name"><b>' + esc(p.name) + '</b><small>' + esc(p.username) + ' · ' + esc(p.country) +
            (p.profile ? ' · accès <span class="ah-profile">' + esc(PROFILE[p.profile] || p.profile) + '</span>' : '') + (p.active ? '' : ' · <span class="ah-off">désactivé</span>') + '</small></div>' +
            '<button type="button" class="ah-open" data-open="' + p.id + '" title="Fiche complète"><i class="bi bi-box-arrow-up-right"></i></button></div>' +
            '<div class="ah-tags"><span class="ah-lbl">Rôles</span>' + (p.roles.map(function (r) {
                return '<span class="ah-chip role">' + esc(r.name) + '<button type="button" data-del-role="' + r.id + '" data-label="' + esc(r.name) + '" title="Retirer ce rôle">×</button></span>';
            }).join("") || '<span class="ah-none">aucun</span>') +
            '<select class="ah-add" data-add-role aria-label="Ajouter un rôle"><option value="">+ rôle</option>' + roleOpts.map(function (r) { return '<option value="' + r.id + '">' + esc(r.name) + '</option>'; }).join("") + '</select></div>' +
            '<div class="ah-tags"><span class="ah-lbl">Services</span>' + (p.services.map(function (s) {
                return '<span class="ah-chip svc">' + esc(s.name || s.code) + '<button type="button" data-del-svc="' + s.id + '" data-label="' + esc(s.name || s.code) + '" title="Retirer ce service">×</button></span>';
            }).join("") || '<span class="ah-none">aucun</span>') +
            '<select class="ah-add" data-add-svc aria-label="Ajouter un service"><option value="">+ service</option>' + svcOpts.map(function (s) { return '<option value="' + s.id + '">' + esc(s.name || s.code) + '</option>'; }).join("") + '</select></div>' +
            (p.warnings.length && p.active ? '<ul class="ah-warn">' + p.warnings.map(function (w) { return '<li>' + esc(w) + '</li>'; }).join("") + '</ul>' : '') +
            '</div>';
    }

    function group(key, title, icon, cls, people, sub) {
        var list = people.filter(visible);
        if (!list.length && (st.q || st.country)) return "";
        var isOpen = st.open[key] !== false;
        return '<section class="ah-level ' + cls + (isOpen ? " open" : "") + '" data-key="' + esc(key) + '">' +
            '<header data-toggle="' + esc(key) + '"><span class="ah-level-ico"><i class="bi ' + icon + '"></i></span><div><h6>' + esc(title) + '</h6>' +
            (sub ? '<small>' + esc(sub) + '</small>' : '') + '</div><span class="ah-count">' + list.length + '</span><i class="bi bi-chevron-down ah-caret"></i></header>' +
            '<div class="ah-people">' + (list.map(personHtml).join("") || '<div class="ah-empty">Personne à ce niveau.</div>') + '</div></section>';
    }

    function render() {
        var d = st.data;
        if (!d) return;
        var c = d.counts;
        $(".ah-counts").innerHTML = [["SUPERVISEUR", "Superviseurs"], ["RH", "RH"], ["HEAD_QA", "Head QA"], ["QA", "QA / formateurs"], ["TEAM_LEADER", "Team Leaders"], ["AGENT", "Agents"], ["A_CLASSER", "À classer"]].map(function (k) {
            var l = LEVELS[k[0]];
            return '<div class="ah-kpi ' + l[2] + '"><i class="bi ' + l[1] + '"></i><b>' + (c[k[0]] || 0) + '</b><span>' + k[1] + '</span></div>';
        }).join("") + '<div class="ah-kpi warnk"><i class="bi bi-exclamation-triangle-fill"></i><b>' + (c.WARNINGS || 0) + '</b><span>à corriger</span></div>';

        var html = '<div class="ah-tree">';
        html += group("SUP", LEVELS.SUPERVISEUR[0], LEVELS.SUPERVISEUR[1], "sup", d.supervisors, "Pilote les Team Leaders et le Head QA");
        html += '<div class="ah-branch">';
        html += group("RH", LEVELS.RH[0], LEVELS.RH[1], "rh", d.rh, "Effectifs, congés, sorties, plannings en direct");
        html += group("HQA", LEVELS.HEAD_QA[0], LEVELS.HEAD_QA[1], "hqa", d.headQa, "Pilote l'équipe Quality Assurance");
        html += '<div class="ah-branch">' + group("QA", LEVELS.QA[0], LEVELS.QA[1], "qa", d.qa, "Écoutes, évaluations, formations") + '</div>';
        html += '<h5 class="ah-sec"><i class="bi bi-diagram-3-fill"></i> Équipes opérationnelles</h5>';
        d.teams.forEach(function (t) {
            var leaders = t.leaders.filter(visible), agents = t.agents.filter(visible);
            if (!leaders.length && !agents.length && (st.q || st.country)) return;
            var key = "T_" + t.code, isOpen = st.open[key] === true;
            html += '<section class="ah-team' + (isOpen ? " open" : "") + (t.code === "SANS_EQUIPE" ? " noteam" : "") + '" data-key="' + key + '">' +
                '<header data-toggle="' + key + '"><span class="ah-level-ico"><i class="bi bi-people-fill"></i></span><div><h6>' + esc(t.label) + '</h6><small>' +
                (leaders.length ? leaders.map(function (l) { return esc(l.name); }).join(", ") : '<span class="ah-off">aucun Team Leader</span>') + '</small></div>' +
                '<span class="ah-count tl" title="Team Leaders">' + leaders.length + ' TL</span><span class="ah-count">' + agents.length + ' agent(s)</span><i class="bi bi-chevron-down ah-caret"></i></header>' +
                '<div class="ah-people">' + (leaders.length ? '<div class="ah-sub">Team Leader</div>' + leaders.map(personHtml).join("") : '') +
                (agents.length ? '<div class="ah-sub">Agents</div><div class="ah-agents">' + agents.map(personHtml).join("") + '</div>' : '<div class="ah-empty">Aucun agent.</div>') + '</div></section>';
        });
        html += '</div>';
        html += '<h5 class="ah-sec"><i class="bi bi-three-dots"></i> Hors hiérarchie</h5>';
        html += group("ADM", LEVELS.ADMIN[0], LEVELS.ADMIN[1], "admin", d.admins, "Administration technique du portail");
        html += group("AGC", LEVELS.AGENCE[0], LEVELS.AGENCE[1], "agence", d.agencies, "Caissiers et gestionnaires d'agence");
        html += group("TODO", LEVELS.A_CLASSER[0], LEVELS.A_CLASSER[1], "todo", d.unclassified, "Aucun rôle reconnu : aucun portail");
        html += '</div>';
        $(".ah-body").innerHTML = html;
    }

    function load() {
        return RccApi.getJson("/api/admin/hierarchy").then(function (d) { st.data = d; render(); })
            .catch(function (e) { $(".ah-body").innerHTML = '<div class="ah-empty text-danger">' + esc(e.message) + '</div>'; });
    }

    function call(method, url, body) {
        return fetch(url, { method: method, credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined })
            .then(function (res) { if (!res.ok) return res.text().then(function (t) { var m = t; try { m = JSON.parse(t).error.message; } catch (e) { /* texte brut */ } throw new Error(m || "HTTP " + res.status); }); });
    }

    function flash(msg, ok) {
        var f = $(".ah-flash");
        f.textContent = msg;
        f.className = "ah-flash show " + (ok ? "ok" : "ko");
        clearTimeout(flash.t);
        flash.t = setTimeout(function () { f.className = "ah-flash"; }, 4000);
    }

    function personOf(el) {
        var id = Number(el.closest(".ah-person").getAttribute("data-id"));
        var all = [];
        var d = st.data;
        all = all.concat(d.supervisors, d.rh, d.headQa, d.qa, d.admins, d.agencies, d.unclassified);
        d.teams.forEach(function (t) { all = all.concat(t.leaders, t.agents); });
        return all.filter(function (p) { return p.id === id; })[0];
    }

    function mount(el) {
        root = el;
        root.innerHTML =
            '<div class="ah-head"><div><h5 class="mb-1"><i class="bi bi-diagram-2-fill"></i> Organigramme — par équipe hiérarchique</h5>' +
            '<small class="text-muted">Superviseur (Head RCC) → RH → Head QA → Team Leaders → Agents. Rôles et services de chacun : × pour retirer, + pour ajouter — effet immédiat sur les portails.</small></div>' +
            '<div class="ah-tools"><div class="ah-search"><i class="bi bi-search"></i><input type="search" placeholder="Nom, identifiant, rôle, service…"></div>' +
            '<div class="ah-seg" data-country><button type="button" class="on" data-v="">Toutes filiales</button><button type="button" data-v="CI">Côte d\'Ivoire</button><button type="button" data-v="TG">Togo</button></div>' +
            '<label class="ah-inactive"><input type="checkbox"> Comptes désactivés</label>' +
            '<button type="button" class="ah-expand">Tout déplier</button></div></div>' +
            '<div class="ah-counts"></div><div class="ah-flash"></div><div class="ah-body"><div class="ah-empty">Chargement de l\'organigramme…</div></div>';
        $(".ah-search input").addEventListener("input", function () { st.q = this.value.trim().toLowerCase(); render(); });
        $(".ah-inactive input").addEventListener("change", function () { st.showInactive = this.checked; render(); });
        $("[data-country]").addEventListener("click", function (e) {
            var b = e.target.closest("[data-v]"); if (!b) return;
            this.querySelectorAll("button").forEach(function (x) { x.classList.toggle("on", x === b); });
            st.country = b.getAttribute("data-v"); render();
        });
        $(".ah-expand").addEventListener("click", function () {
            var open = this.textContent === "Tout déplier";
            root.querySelectorAll("[data-key]").forEach(function (s) { st.open[s.getAttribute("data-key")] = open; });
            this.textContent = open ? "Tout replier" : "Tout déplier";
            render();
        });
        root.addEventListener("click", function (e) {
            var tg = e.target.closest("[data-toggle]");
            if (tg) {
                var k = tg.getAttribute("data-toggle"), sec = tg.parentNode;
                st.open[k] = !sec.classList.contains("open");
                sec.classList.toggle("open", st.open[k]);
                return;
            }
            var op = e.target.closest("[data-open]");
            if (op) {
                var btn = document.querySelector('.view-detail-btn[data-id="' + op.getAttribute("data-open") + '"]');
                if (btn) btn.click(); else flash("Fiche disponible dans l'annuaire ci-dessous.", false);
                return;
            }
            var dr = e.target.closest("[data-del-role]"), ds = e.target.closest("[data-del-svc]");
            if (!dr && !ds) return;
            var p = personOf(e.target), b = dr || ds, label = b.getAttribute("data-label");
            if (!confirm("Retirer " + (dr ? "le rôle" : "le service") + " « " + label + " » à " + p.name + " ?\n\nSon accès aux portails est mis à jour immédiatement.")) return;
            b.disabled = true;
            call("DELETE", "/api/admin/users/" + p.id + (dr ? "/roles/" + b.getAttribute("data-del-role") : "/services/" + b.getAttribute("data-del-svc")))
                .then(function () { flash((dr ? "Rôle" : "Service") + " « " + label + " » retiré à " + p.name + ".", true); return load(); })
                .catch(function (err) { b.disabled = false; flash(err.message, false); });
        });
        root.addEventListener("change", function (e) {
            var sel = e.target.closest("[data-add-role],[data-add-svc]");
            if (!sel || !sel.value) return;
            var p = personOf(sel), isRole = sel.hasAttribute("data-add-role"), label = sel.selectedOptions[0].textContent, id = Number(sel.value);
            sel.disabled = true;
            call("POST", "/api/admin/users/" + p.id + (isRole ? "/roles" : "/services"), isRole ? { roleId: id } : { serviceId: id })
                .then(function () { flash((isRole ? "Rôle" : "Service") + " « " + label + " » ajouté à " + p.name + ".", true); return load(); })
                .catch(function (err) { sel.disabled = false; sel.value = ""; flash(err.message, false); });
        });
        Promise.all([RccApi.getJson("/api/admin/roles").catch(function () { return []; }), RccApi.getJson("/api/admin/services").catch(function () { return []; })]).then(function (r) {
            st.roles = r[0].slice().sort(function (a, b) { return String(a.name).localeCompare(b.name); });
            st.services = r[1].filter(function (s) { return s.enabled !== false; }).sort(function (a, b) { return String(a.name || a.code).localeCompare(b.name || b.code); });
            return load();
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        var el = document.getElementById("adminHierarchy");
        if (!el) return;
        window.RccSession.init().then(function (s) { if (s && s.profile === "ADMIN") mount(el); else el.style.display = "none"; });
    });
})();
