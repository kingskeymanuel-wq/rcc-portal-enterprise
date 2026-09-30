"use strict";

/**
 * Administration — organigramme hiérarchique (/api/admin/hierarchy), rangé par filiale puis par niveau :
 * Superviseur (Head RCC) → RH → Head QA et équipe QA → Team Leaders et agents par équipe, puis administrateurs,
 * agences et comptes à classer. Pour chaque personne l'administrateur modifie tout sur place :
 *  - Accès : Agent ou Team Leader d'une équipe (PUT /api/admin/users/{id}/access) ;
 *  - rôles et services : × pour retirer, + pour ajouter ;
 *  - fiche : nom, identifiant, e-mail, filiale, genre, équipe, contrat, compte actif (PUT /api/users/{id}).
 * Les comptes Team Leader uniquement par le champ « équipe menée » sont signalés et corrigeables en groupe.
 * Chaque modification s'applique aussitôt aux portails et resynchronise l'annuaire (événement rcc:users-changed).
 */
(function () {
    var esc = RccApi.escapeHtml;
    var st = { data: null, roles: [], services: [], q: "", country: "", showInactive: false, open: {}, edit: {}, ghostOpen: false };
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
    var TEAMS = [["INBOUND_VOICE", "Inbound Voix"], ["INBOUND_MAIL", "Inbound Mail"], ["TCHAT", "Réseaux sociaux"], ["RAFIKI", "Rafiki"], ["CIB", "CIB"], ["OUTBOUND", "Outbound"], ["TELEVENTE", "Télévente"], ["DIGITALISATION", "Digitalisation"]];
    var ACTIVITIES = ["INBOUND VOICE", "INBOUND MAIL", "INBOUND TCHAT", "INBOUND RAFIKI", "CIB", "OUTBOUND", "RESOLUTION", "QA"];
    var COUNTRIES = { CI: "Côte d'Ivoire", TG: "Togo", SN: "Sénégal", CM: "Cameroun", BJ: "Bénin", ML: "Mali", BF: "Burkina Faso", NE: "Niger", GH: "Ghana", NG: "Nigeria", KE: "Kenya", GN: "Guinée" };

    function $(s) { return root.querySelector(s); }
    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }
    function countryLabel(c) { return COUNTRIES[c] ? COUNTRIES[c] + " (" + c + ")" : (c || "Sans filiale"); }
    function teamLabel(code) { var t = TEAMS.filter(function (x) { return x[0] === code; })[0]; return t ? t[1] : code; }

    function notifyChanged() {
        document.dispatchEvent(new CustomEvent("rcc:users-changed", { detail: { source: "hierarchy" } }));
    }

    function allPeople() {
        var d = st.data, all = [];
        if (!d) return all;
        all = all.concat(d.supervisors, d.rh, d.headQa, d.qa, d.admins, d.agencies, d.unclassified);
        d.teams.forEach(function (t) { all = all.concat(t.leaders, t.agents); });
        return all;
    }

    function visible(p) {
        if (!st.showInactive && !p.active) return false;
        if (st.country && p.country !== st.country) return false;
        if (st.q) {
            var hay = [p.name, p.username, p.email, p.activity, p.branch].concat(p.roles.map(function (r) { return r.name; }), p.services.map(function (s) { return s.name + " " + s.code; })).join(" ").toLowerCase();
            if (hay.indexOf(st.q) === -1) return false;
        }
        return true;
    }

    // ───────────── Carte d'une personne ─────────────

    /** Accès d'encadrement (même liste que le serveur, AdministrationService.PROFILE_LEVELS). */
    var MGMT = [["QA", "Quality Assurance"], ["HEAD_QA", "Head QA (Superviseur QA)"], ["RH", "Ressources Humaines"], ["SUPERVISEUR", "Superviseur · Head RCC"]];

    /** Service agent → équipe (même correspondance que le serveur, AdministrationService.AGENT_TEAMS). */
    var AGENT_SERVICE_TEAM = { AGENT_INBOUND: "INBOUND_VOICE", AGENT_INBOUND_MAIL: "INBOUND_MAIL", AGENT_TCHAT: "TCHAT", AGENT_RAFIKI: "RAFIKI",
        AGENT_CIB: "CIB", AGENT_OUTBOUND: "OUTBOUND", AGENT_TELEVENTE: "TELEVENTE", AGENT_DIGITALISATION: "DIGITALISATION" };

    /** Équipes d'agent réelles de la personne : ses services d'agent, sinon son équipe calculée. */
    function agentTeams(p) {
        var teams = [];
        p.services.forEach(function (s) { var t = AGENT_SERVICE_TEAM[String(s.code || "").toUpperCase()]; if (t && teams.indexOf(t) === -1) teams.push(t); });
        if (!teams.length && /TELEVENTE|TÉLÉVENTE/i.test(p.activity || "")) teams.push("TELEVENTE");
        if (!teams.length && /DIGITAL/i.test(p.activity || "")) teams.push("DIGITALISATION");
        if (!teams.length && p.team && TEAMS.some(function (t) { return t[0] === p.team; })) teams.push(p.team);
        return teams;
    }

    /** Valeur affichée = accès RÉEL (ex. « Agent · Inbound Mail ») : après un changement, le menu montre la nouvelle équipe. */
    function accessValue(p) {
        if (p.level === "TEAM_LEADER") return "TEAM_LEADER:" + (p.team || "");
        if (MGMT.some(function (m) { return m[0] === p.level; })) return p.level + ":";
        if (p.level === "AGENT") {
            var teams = agentTeams(p);
            return teams.length === 1 ? "AGENT:" + teams[0] : "AGENT:";
        }
        return "";
    }

    function accessHtml(p) {
        var editable = p.level !== "ADMIN" && p.level !== "AGENCE";
        if (!editable) {
            return '<div class="ah-tags"><span class="ah-lbl">Accès</span><span class="ah-chip lvl">' + esc(LEVELS[p.level] ? LEVELS[p.level][0] : p.level) +
                '</span><span class="ah-hint">donné par ses rôles / services</span></div>';
        }
        var cur = accessValue(p);
        var several = p.level === "AGENT" && agentTeams(p).length > 1;
        var opts = (p.level === "A_CLASSER" ? '<option value="" selected>— choisir un accès —</option>' : '') +
            '<optgroup label="Agent">' +
            (p.level === "AGENT" && cur === "AGENT:" ? '<option value="" selected>' + (several
                ? "Agent · " + agentTeams(p).map(function (t) { return (TEAMS.filter(function (x) { return x[0] === t; })[0] || [t, t])[1]; }).join(" + ")
                : "Agent · équipe à choisir") + '</option>' : '') +
            TEAMS.map(function (t) {
                var v = "AGENT:" + t[0];
                return '<option value="' + v + '"' + (cur === v ? " selected" : "") + '>Agent · ' + esc(t[1]) + '</option>';
            }).join("") + '</optgroup>' +
            '<optgroup label="Team Leader">' + TEAMS.map(function (t) {
                var v = "TEAM_LEADER:" + t[0];
                return '<option value="' + v + '"' + (cur === v ? " selected" : "") + '>Team Leader · ' + esc(t[1]) + '</option>';
            }).join("") + '</optgroup>' +
            '<optgroup label="Encadrement">' + MGMT.map(function (m) {
                var v = m[0] + ":";
                return '<option value="' + v + '"' + (cur === v ? " selected" : "") + '>' + esc(m[1]) + '</option>';
            }).join("") + '</optgroup>';
        return '<div class="ah-tags"><span class="ah-lbl">Accès</span><select class="ah-access" data-access aria-label="Accès">' + opts + '</select>' +
            (p.leaderSource === "LED_TEAM" ? '<span class="ah-ghost" title="Ni rôle ni service Team Leader : seul le champ « équipe menée » lui donne cet accès"><i class="bi bi-exclamation-triangle-fill"></i> via « équipe menée » seule</span>' : '') +
            '</div>';
    }

    function editFormHtml(p) {
        var e = st.edit[p.id];
        if (!e) return "";
        if (e.loading) return '<div class="ah-edit"><div class="ah-empty">Chargement de la fiche…</div></div>';
        var d = e.detail;
        var act = ACTIVITIES.slice();
        if (d.activity && act.indexOf(d.activity) === -1) act.unshift(d.activity);
        function sel(name, values, cur, labels) {
            return '<select name="' + name + '">' + values.map(function (v, i) {
                return '<option value="' + esc(v) + '"' + (String(cur || "") === v ? " selected" : "") + '>' + esc(labels ? labels[i] : (v || "—")) + '</option>';
            }).join("") + '</select>';
        }
        return '<form class="ah-edit" data-edit-form>' +
            '<label>Nom complet<input name="name" value="' + esc(d.name || "") + '"></label>' +
            '<label>Identifiant (AD)<input name="username" value="' + esc(d.username || "") + '"></label>' +
            '<label>E-mail<input name="email" type="email" value="' + esc(d.email || "") + '"></label>' +
            '<label>Filiale<input name="affiliateBranch" list="ahCountries" value="' + esc(d.affiliateBranch || "") + '" placeholder="K01 (Côte d\'Ivoire), TG…"></label>' +
            '<label>Genre' + sel("gender", ["", "F", "M"], d.gender, ["—", "Femme", "Homme"]) + '</label>' +
            '<label>Équipe (activité)' + sel("activity", [""].concat(act), d.activity, ["— Aucune —"].concat(act)) + '</label>' +
            '<label>Nature du contrat' + sel("contractType", ["", "Ecobank", "Outsource"], d.contractType) + '</label>' +
            '<label>Statut du contrat' + sel("contractStatus", ["", "CDI", "CDD"], d.contractStatus) + '</label>' +
            '<label>Début du contrat<input name="contractStartDate" type="date" value="' + esc(d.contractStartDate || "") + '"></label>' +
            '<label class="ah-check"><input name="active" type="checkbox"' + (d.active ? " checked" : "") + '> Compte actif</label>' +
            '<div class="ah-edit-actions"><button type="submit" class="ah-btn primary"><i class="bi bi-check2"></i> Enregistrer</button>' +
            '<button type="button" class="ah-btn" data-edit-cancel>Annuler</button></div></form>';
    }

    function personHtml(p) {
        var roleOpts = st.roles.filter(function (r) { return !p.roles.some(function (x) { return x.id === r.id; }); });
        var svcOpts = st.services.filter(function (s) { return !p.services.some(function (x) { return x.id === s.id; }); });
        var editing = !!st.edit[p.id];
        return '<div class="ah-person' + (p.active ? "" : " off") + (p.warnings.length && p.active ? " warn" : "") + (editing ? " editing" : "") + '" data-id="' + p.id + '">' +
            '<div class="ah-id"><span class="ah-av ' + (LEVELS[p.level] || LEVELS.A_CLASSER)[2] + '">' + esc(initials(p.name)) + '</span>' +
            '<div class="ah-name"><b>' + esc(p.name) + '</b><small>' + esc(p.username) + ' · ' + esc(p.country) +
            (p.activity ? ' · ' + esc(p.activity) : '') +
            (p.profile ? ' · accès <span class="ah-profile">' + esc(PROFILE[p.profile] || p.profile) + '</span>' : '') + (p.active ? '' : ' · <span class="ah-off">désactivé</span>') + '</small></div>' +
            '<button type="button" class="ah-open" data-edit="' + p.id + '" title="Modifier la fiche (nom, filiale, équipe, contrat, statut)"><i class="bi bi-pencil-square"></i></button>' +
            '<button type="button" class="ah-open" data-open="' + p.id + '" title="Fiche complète (permissions, connexions, suppression)"><i class="bi bi-box-arrow-up-right"></i></button></div>' +
            accessHtml(p) +
            '<div class="ah-tags"><span class="ah-lbl">Rôles</span>' + (p.roles.map(function (r) {
                return '<span class="ah-chip role">' + esc(r.name) + '<button type="button" data-del-role="' + r.id + '" data-label="' + esc(r.name) + '" title="Retirer ce rôle">×</button></span>';
            }).join("") || '<span class="ah-none">aucun</span>') +
            '<select class="ah-add" data-add-role aria-label="Ajouter un rôle"><option value="">+ rôle</option>' + roleOpts.map(function (r) { return '<option value="' + r.id + '">' + esc(r.name) + '</option>'; }).join("") + '</select></div>' +
            '<div class="ah-tags"><span class="ah-lbl">Services</span>' + (p.services.map(function (s) {
                return '<span class="ah-chip svc">' + esc(s.name || s.code) + '<button type="button" data-del-svc="' + s.id + '" data-label="' + esc(s.name || s.code) + '" title="Retirer ce service">×</button></span>';
            }).join("") || '<span class="ah-none">aucun</span>') +
            '<select class="ah-add" data-add-svc aria-label="Ajouter un service"><option value="">+ service</option>' + svcOpts.map(function (s) { return '<option value="' + s.id + '">' + esc(s.name || s.code) + '</option>'; }).join("") + '</select></div>' +
            (p.warnings.length && p.active ? '<ul class="ah-warn">' + p.warnings.map(function (w) { return '<li>' + esc(w) + '</li>'; }).join("") + '</ul>' : '') +
            editFormHtml(p) +
            '</div>';
    }

    // ───────────── Arbre ─────────────

    function group(key, title, icon, cls, people, sub) {
        var list = people.filter(visible);
        if (!list.length) return "";
        var isOpen = st.open[key] !== false;
        return '<section class="ah-level ' + cls + (isOpen ? " open" : "") + '" data-key="' + esc(key) + '">' +
            '<header data-toggle="' + esc(key) + '"><span class="ah-level-ico"><i class="bi ' + icon + '"></i></span><div><h6>' + esc(title) + '</h6>' +
            (sub ? '<small>' + esc(sub) + '</small>' : '') + '</div><span class="ah-count">' + list.length + '</span><i class="bi bi-chevron-down ah-caret"></i></header>' +
            '<div class="ah-people">' + list.map(personHtml).join("") + '</div></section>';
    }

    /** Agents d'une équipe ; l'Outbound est subdivisé en Télévente et Digitalisation. */
    function agentsHtml(code, agents) {
        function block(title, list) {
            return list.length ? '<div class="ah-sub">' + esc(title) + ' <span class="ah-off">(' + list.length + ')</span></div><div class="ah-agents">' + list.map(personHtml).join("") + '</div>' : '';
        }
        if (code !== "OUTBOUND") return block("Agents", agents);
        var tv = [], dg = [], other = [];
        agents.forEach(function (p) {
            var at = agentTeams(p);
            (at.indexOf("TELEVENTE") !== -1 ? tv : at.indexOf("DIGITALISATION") !== -1 ? dg : other).push(p);
        });
        return block("Télévente", tv) + block("Digitalisation", dg) + block("Outbound (sous-équipe à préciser)", other);
    }

    /** Hiérarchie complète d'une filiale (ou de toutes quand pfx est vide). */
    function treeHtml(d, pfx) {
        var html = '<div class="ah-tree">';
        html += group(pfx + "SUP", LEVELS.SUPERVISEUR[0], LEVELS.SUPERVISEUR[1], "sup", d.supervisors, "Pilote les Team Leaders et le Head QA");
        html += '<div class="ah-branch">';
        html += group(pfx + "RH", LEVELS.RH[0], LEVELS.RH[1], "rh", d.rh, "Effectifs, congés, sorties, plannings en direct");
        html += group(pfx + "HQA", LEVELS.HEAD_QA[0], LEVELS.HEAD_QA[1], "hqa", d.headQa, "Pilote l'équipe Quality Assurance");
        html += '<div class="ah-branch">' + group(pfx + "QA", LEVELS.QA[0], LEVELS.QA[1], "qa", d.qa, "Écoutes, évaluations, formations") + '</div>';
        var teams = "";
        d.teams.forEach(function (t) {
            var leaders = t.leaders.filter(visible), agents = t.agents.filter(visible);
            if (!leaders.length && !agents.length) return;
            var key = pfx + "T_" + t.code, isOpen = st.open[key] === true || !!st.q;
            teams += '<section class="ah-team' + (isOpen ? " open" : "") + (t.code === "SANS_EQUIPE" ? " noteam" : "") + '" data-key="' + esc(key) + '">' +
                '<header data-toggle="' + esc(key) + '"><span class="ah-level-ico"><i class="bi bi-people-fill"></i></span><div><h6>' + esc(t.label) + '</h6><small>' +
                (leaders.length ? leaders.map(function (l) { return esc(l.name); }).join(", ") : '<span class="ah-off">aucun Team Leader</span>') + '</small></div>' +
                '<span class="ah-count tl" title="Team Leaders">' + leaders.length + ' TL</span><span class="ah-count">' + agents.length + ' agent(s)</span><i class="bi bi-chevron-down ah-caret"></i></header>' +
                '<div class="ah-people">' + (leaders.length ? '<div class="ah-sub">Team Leader</div>' + leaders.map(personHtml).join("") : '') +
                (agents.length ? agentsHtml(t.code, agents) : '<div class="ah-empty">Aucun agent.</div>') + '</div></section>';
        });
        if (teams) html += '<h5 class="ah-sec"><i class="bi bi-diagram-3-fill"></i> Équipes opérationnelles</h5>' + teams;
        html += '</div>';
        var out = group(pfx + "ADM", LEVELS.ADMIN[0], LEVELS.ADMIN[1], "admin", d.admins, "Administration technique du portail") +
            group(pfx + "AGC", LEVELS.AGENCE[0], LEVELS.AGENCE[1], "agence", d.agencies, "Caissiers et gestionnaires d'agence") +
            group(pfx + "TODO", LEVELS.A_CLASSER[0], LEVELS.A_CLASSER[1], "todo", d.unclassified, "Aucun rôle reconnu : aucun portail");
        if (out) html += '<h5 class="ah-sec"><i class="bi bi-three-dots"></i> Hors hiérarchie</h5>' + out;
        return html + '</div>';
    }

    /** Sous-ensemble de l'organigramme pour une filiale. */
    function forCountry(d, c) {
        function f(list) { return list.filter(function (p) { return p.country === c; }); }
        return {
            supervisors: f(d.supervisors), rh: f(d.rh), headQa: f(d.headQa), qa: f(d.qa), admins: f(d.admins), agencies: f(d.agencies), unclassified: f(d.unclassified),
            teams: d.teams.map(function (t) { return { code: t.code, label: t.label, leaders: f(t.leaders), agents: f(t.agents) }; })
        };
    }

    function countries() {
        var seen = {};
        allPeople().forEach(function (p) { if (p.country) seen[p.country] = (seen[p.country] || 0) + 1; });
        return Object.keys(seen).sort(function (a, b) { return a === "CI" ? -1 : b === "CI" ? 1 : countryLabel(a).localeCompare(countryLabel(b)); });
    }

    function renderCountrySeg() {
        var seg = $("[data-country]");
        seg.innerHTML = '<button type="button" data-v=""' + (st.country ? "" : ' class="on"') + '>Toutes filiales</button>' + countries().map(function (c) {
            return '<button type="button" data-v="' + esc(c) + '"' + (st.country === c ? ' class="on"' : '') + '>' + esc(COUNTRIES[c] || c) + '</button>';
        }).join("");
        $("#ahCountries").innerHTML = countries().concat(Object.keys(COUNTRIES)).filter(function (c, i, a) { return a.indexOf(c) === i; })
            .map(function (c) { return '<option value="' + esc(c) + '">' + esc(COUNTRIES[c] || c) + '</option>'; }).join("");
    }

    function renderCounts() {
        var c = {}, warn = 0;
        allPeople().filter(function (p) { return (st.showInactive || p.active) && (!st.country || p.country === st.country); }).forEach(function (p) {
            c[p.level] = (c[p.level] || 0) + 1;
            if (p.active && p.warnings.length) warn++;
        });
        $(".ah-counts").innerHTML = [["SUPERVISEUR", "Superviseurs"], ["RH", "RH"], ["HEAD_QA", "Head QA"], ["QA", "QA / formateurs"], ["TEAM_LEADER", "Team Leaders"], ["AGENT", "Agents"], ["A_CLASSER", "À classer"]].map(function (k) {
            var l = LEVELS[k[0]];
            return '<div class="ah-kpi ' + l[2] + '"><i class="bi ' + l[1] + '"></i><b>' + (c[k[0]] || 0) + '</b><span>' + k[1] + '</span></div>';
        }).join("") + '<div class="ah-kpi warnk"><i class="bi bi-exclamation-triangle-fill"></i><b>' + warn + '</b><span>à corriger</span></div>';
    }

    function ghosts() {
        return allPeople().filter(function (p) { return p.active && p.leaderSource === "LED_TEAM" && (!st.country || p.country === st.country); });
    }

    function renderGhosts() {
        var g = ghosts(), box = $(".ah-ghosts");
        if (!g.length) { box.innerHTML = ""; return; }
        box.innerHTML = '<div class="ah-ghostbar"><i class="bi bi-person-exclamation"></i><div><b>' + g.length + ' compte(s) Team Leader sans rôle ni service Team Leader</b>' +
            '<small>Leur accès Team Leader vient seulement du champ « équipe menée ». Cochez ceux qui ne sont pas Team Leader et repassez-les en agent.</small></div>' +
            '<button type="button" class="ah-btn" data-ghost-toggle>' + (st.ghostOpen ? "Masquer" : "Revoir la liste") + '</button></div>' +
            (st.ghostOpen ? '<div class="ah-ghostlist">' + g.map(function (p) {
                return '<label><input type="checkbox" value="' + p.id + '" checked> <b>' + esc(p.name) + '</b> <small>' + esc(p.username) + ' · ' + esc(p.country) +
                    ' · équipe menée : ' + esc(teamLabel(p.ledTeam || p.team || "")) + ' · rôles : ' + esc(p.roles.map(function (r) { return r.name; }).join(", ") || "aucun") +
                    ' · services : ' + esc(p.services.map(function (s) { return s.name || s.code; }).join(", ") || "aucun") + '</small></label>';
            }).join("") + '<div class="ah-edit-actions"><button type="button" class="ah-btn primary" data-ghost-fix><i class="bi bi-person-down"></i> Repasser la sélection en agent</button></div></div>' : '');
    }

    function render() {
        var d = st.data;
        if (!d) return;
        renderCountrySeg();
        renderCounts();
        renderGhosts();
        var html;
        var list = countries();
        if (!st.country && list.length > 1) {
            html = list.map(function (c) {
                var sub = forCountry(d, c), key = "F_" + c, isOpen = st.open[key] !== false;
                var n = allPeople().filter(function (p) { return p.country === c && visible(p); }).length;
                if (!n) return "";
                return '<section class="ah-filiale' + (isOpen ? " open" : "") + '" data-key="' + esc(key) + '">' +
                    '<header data-toggle="' + esc(key) + '"><span class="ah-level-ico"><i class="bi bi-building"></i></span><div><h6>' + esc(countryLabel(c)) + '</h6>' +
                    '<small>Organigramme de la filiale</small></div><span class="ah-count">' + n + ' personne(s)</span><i class="bi bi-chevron-down ah-caret"></i></header>' +
                    '<div class="ah-people">' + treeHtml(sub, c + "_") + '</div></section>';
            }).join("");
        } else {
            html = treeHtml(st.country ? forCountry(d, st.country) : d, (st.country || "ALL") + "_");
        }
        $(".ah-body").innerHTML = html || '<div class="ah-empty">Personne ne correspond à ces filtres.</div>';
    }

    function load() {
        return RccApi.getJson("/api/admin/hierarchy").then(function (d) { st.data = d; render(); })
            .catch(function (e) { $(".ah-body").innerHTML = '<div class="ah-empty text-danger">' + esc(e.message) + '</div>'; });
    }

    function call(method, url, body) {
        return fetch(url, { method: method, credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { var m = t; try { m = JSON.parse(t).error.message; } catch (e) { /* texte brut */ } throw new Error(m || "HTTP " + res.status); });
                return res.status === 204 ? null : res.json().catch(function () { return null; });
            });
    }

    /** Modification réussie : organigramme rechargé, annuaire resynchronisé. */
    function done(msg) {
        flash(msg, true);
        notifyChanged();
        return load();
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
        return allPeople().filter(function (p) { return p.id === id; })[0];
    }

    function openEdit(p) {
        if (st.edit[p.id]) { delete st.edit[p.id]; render(); return; }
        st.edit[p.id] = { loading: true };
        render();
        RccApi.getJson("/api/users/" + p.id).then(function (d) { st.edit[p.id] = { detail: d }; render(); })
            .catch(function (e) { delete st.edit[p.id]; render(); flash(e.message, false); });
    }

    function saveEdit(form) {
        var p = personOf(form), e = st.edit[p.id];
        if (!e || !e.detail) return;
        var v = function (n) { return form.elements[n].value.trim(); };
        var payload = {
            name: v("name") || null, username: v("username") || null, email: v("email") || null,
            affiliateBranch: v("affiliateBranch"), gender: v("gender"), activity: v("activity"),
            contractType: v("contractType"), contractStatus: v("contractStatus"), contractStartDate: v("contractStartDate") || null
        };
        var wantActive = form.elements.active.checked;
        var btn = form.querySelector("[type=submit]");
        btn.disabled = true;
        call("PUT", "/api/users/" + p.id, payload)
            .then(function () { if (wantActive !== !!e.detail.active) return call("POST", "/api/users/" + p.id + (wantActive ? "/enable" : "/disable")); })
            .then(function () { delete st.edit[p.id]; return done("Fiche de " + (payload.name || p.name) + " enregistrée."); })
            .catch(function (err) { btn.disabled = false; flash(err.message, false); });
    }

    function mount(el) {
        root = el;
        root.innerHTML =
            '<div class="ah-head"><div><h5 class="mb-1"><i class="bi bi-diagram-2-fill"></i> Organigramme — par filiale et hiérarchie</h5>' +
            '<small class="text-muted">Filiale → Superviseur (Head RCC) → RH → Head QA → Team Leaders → Agents. Accès, rôles, services et fiche de chacun modifiables sur place ' +
            '(<i class="bi bi-pencil-square"></i>) — effet immédiat sur les portails, annuaire synchronisé.</small></div>' +
            '<div class="ah-tools"><div class="ah-search"><i class="bi bi-search"></i><input type="search" placeholder="Nom, identifiant, rôle, service…"></div>' +
            '<div class="ah-seg" data-country></div>' +
            '<label class="ah-inactive"><input type="checkbox"> Comptes désactivés</label>' +
            '<button type="button" class="ah-expand">Tout déplier</button></div></div>' +
            '<datalist id="ahCountries"></datalist>' +
            '<div class="ah-counts"></div><div class="ah-flash"></div><div class="ah-ghosts"></div><div class="ah-body"><div class="ah-empty">Chargement de l\'organigramme…</div></div>';
        $(".ah-search input").addEventListener("input", function () { st.q = this.value.trim().toLowerCase(); render(); });
        $(".ah-inactive input").addEventListener("change", function () { st.showInactive = this.checked; render(); });
        $("[data-country]").addEventListener("click", function (e) {
            var b = e.target.closest("[data-v]"); if (!b) return;
            st.country = b.getAttribute("data-v"); render();
        });
        $(".ah-expand").addEventListener("click", function () {
            var open = this.textContent === "Tout déplier";
            root.querySelectorAll("[data-key]").forEach(function (s) { st.open[s.getAttribute("data-key")] = open; });
            this.textContent = open ? "Tout replier" : "Tout déplier";
            render();
        });
        root.addEventListener("click", function (e) {
            if (e.target.closest("[data-ghost-toggle]")) { st.ghostOpen = !st.ghostOpen; renderGhosts(); return; }
            if (e.target.closest("[data-ghost-fix]")) {
                var ids = Array.prototype.map.call(root.querySelectorAll(".ah-ghostlist input:checked"), function (i) { return Number(i.value); });
                if (!ids.length) { flash("Cochez au moins un compte.", false); return; }
                if (!confirm("Repasser " + ids.length + " compte(s) en agent ?\n\nLe champ « équipe menée » est effacé : ils perdent le portail Team Leader et retrouvent leur portail agent immédiatement.")) return;
                call("POST", "/api/admin/hierarchy/clear-led-team", { userIds: ids })
                    .then(function (r) { st.ghostOpen = false; return done((r && r.fixed || 0) + " compte(s) repassé(s) en agent."); })
                    .catch(function (err) { flash(err.message, false); });
                return;
            }
            var tg = e.target.closest("[data-toggle]");
            if (tg) {
                var k = tg.getAttribute("data-toggle"), sec = tg.parentNode;
                st.open[k] = !sec.classList.contains("open");
                sec.classList.toggle("open", st.open[k]);
                return;
            }
            var ed = e.target.closest("[data-edit]");
            if (ed) { openEdit(personOf(ed)); return; }
            if (e.target.closest("[data-edit-cancel]")) { delete st.edit[personOf(e.target).id]; render(); return; }
            var op = e.target.closest("[data-open]");
            if (op) {
                if (window.RccDirectory && window.RccDirectory.openUserDetail) window.RccDirectory.openUserDetail(op.getAttribute("data-open"));
                else flash("Fiche disponible dans l'annuaire ci-dessous.", false);
                return;
            }
            var dr = e.target.closest("[data-del-role]"), ds = e.target.closest("[data-del-svc]");
            if (!dr && !ds) return;
            var p = personOf(e.target), b = dr || ds, label = b.getAttribute("data-label");
            if (!confirm("Retirer " + (dr ? "le rôle" : "le service") + " « " + label + " » à " + p.name + " ?\n\nSon accès aux portails est mis à jour immédiatement.")) return;
            b.disabled = true;
            call("DELETE", "/api/admin/users/" + p.id + (dr ? "/roles/" + b.getAttribute("data-del-role") : "/services/" + b.getAttribute("data-del-svc")))
                .then(function () { return done((dr ? "Rôle" : "Service") + " « " + label + " » retiré à " + p.name + "."); })
                .catch(function (err) { b.disabled = false; flash(err.message, false); });
        });
        root.addEventListener("submit", function (e) {
            var f = e.target.closest("[data-edit-form]");
            if (!f) return;
            e.preventDefault();
            saveEdit(f);
        });
        root.addEventListener("change", function (e) {
            var acc = e.target.closest("[data-access]");
            if (acc) {
                if (!acc.value) return;
                var pa = personOf(acc), parts = acc.value.split(":"), lvl = parts[0], team = parts[1] || null;
                var mg = MGMT.filter(function (m) { return m[0] === lvl; })[0];
                var what = mg ? mg[1] : lvl === "TEAM_LEADER" ? "Team Leader · " + teamLabel(team) : "Agent" + (team ? " · " + teamLabel(team) : "");
                var detail = mg ? "Rôle et service « " + mg[1] + " » attribués ; son équipe d'agent, son accès Team Leader et son ancien accès d'encadrement sont retirés."
                    : lvl === "TEAM_LEADER"
                    ? "Cette personne mène l'équipe " + teamLabel(team) + " (service Team Leader attribué, autres équipes menées retirées)."
                    : "Rôle, service et équipe « " + teamLabel(team) + " » attribués, son ancienne équipe d'agent retirée ; plus aucun accès Team Leader.";
                if (!confirm("Accès de " + pa.name + " : " + what + " ?\n\n" + detail + "\nEffet immédiat sur ses portails.")) { render(); return; }
                acc.disabled = true;
                call("PUT", "/api/admin/users/" + pa.id + "/access", { level: lvl, team: team })
                    .then(function () { return done("Accès de " + pa.name + " : " + what + "."); })
                    .catch(function (err) { acc.disabled = false; flash(err.message, false); render(); });
                return;
            }
            var sel = e.target.closest("[data-add-role],[data-add-svc]");
            if (!sel || !sel.value) return;
            var p = personOf(sel), isRole = sel.hasAttribute("data-add-role"), label = sel.selectedOptions[0].textContent, id = Number(sel.value);
            sel.disabled = true;
            call("POST", "/api/admin/users/" + p.id + (isRole ? "/roles" : "/services"), isRole ? { roleId: id } : { serviceId: id })
                .then(function () { return done((isRole ? "Rôle" : "Service") + " « " + label + " » ajouté à " + p.name + "."); })
                .catch(function (err) { sel.disabled = false; sel.value = ""; flash(err.message, false); });
        });
        // Modification faite dans l'annuaire (fiche, rôles, statut…) : l'organigramme se recharge.
        document.addEventListener("rcc:users-changed", function (e) {
            if (e.detail && e.detail.source === "hierarchy") return;
            clearTimeout(load.t);
            load.t = setTimeout(load, 250);
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
