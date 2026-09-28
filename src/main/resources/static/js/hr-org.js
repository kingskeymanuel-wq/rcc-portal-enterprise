"use strict";

/**
 * Portail RH — filiale (Côte d'Ivoire / Togo), parcours par population et équipe, performance
 * par équipe et sorties d'agents. API : /api/hr/organisation, /api/hr/performance,
 * /api/hr/departures. Travaille avec hr-parcours.js (window.RccHr : filiale, dossier, rechargement).
 */
window.RccHrOrg = (function () {
    var esc = RccApi.escapeHtml, getJson = RccApi.getJson, sendJson = RccApi.sendJson;
    function $(id) { return document.getElementById(id); }

    var REASONS = { DEMISSION: "Démission", FIN_CONTRAT: "Fin de contrat", FIN_STAGE: "Fin de stage", LICENCIEMENT: "Licenciement",
        MUTATION: "Mutation / changement de poste", ABANDON_POSTE: "Abandon de poste", AUTRE: "Autre" };
    var POP_COLORS = { OUTSOURCE: "#0057B8", STAGIAIRE: "#7B2FF7", STAFF: "#0F9D6C" };

    var state = { country: "CI", org: null, selected: null, perf: null, perfPop: "", exits: [], canWrite: false };
    var assignModal = null, exitModal = null, assignTarget = null;

    function readCountry() {
        try { var c = localStorage.getItem("rccHrCountry"); if (c === "CI" || c === "TG") return c; } catch (e) { /* stockage indisponible */ }
        return "CI";
    }
    function saveCountry(c) { try { localStorage.setItem("rccHrCountry", c); } catch (e) { /* ignore */ } }
    function month() { return ($("hrMonth") && $("hrMonth").value) || new Date().toISOString().slice(0, 7); }
    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }
    function pct(v) { return v == null ? "—" : (Math.round(v * 10) / 10).toString().replace(".", ",") + " %"; }
    function fmtDate(v) { return v ? new Date(v + (String(v).length === 10 ? "T00:00:00" : "")).toLocaleDateString("fr-FR") : "—"; }
    function popLabel(code) { var p = (state.org && state.org.populations || []).filter(function (x) { return x.code === code; })[0]; return p ? p.label : code; }
    function teamLabel(pop, team) {
        var p = (state.org && state.org.populations || []).filter(function (x) { return x.code === pop; })[0];
        var t = p && p.teams.filter(function (x) { return x.code === team; })[0];
        return t ? t.label : "À affecter";
    }

    // ── Filiale ───────────────────────────────────────────────────────────
    function setCountry(c) {
        state.country = c;
        window.RccHr = window.RccHr || {};
        window.RccHr.country = c;
        saveCountry(c);
        document.querySelectorAll("#hrCountrySwitch [data-country]").forEach(function (b) { b.classList.toggle("on", b.getAttribute("data-country") === c); });
        document.querySelectorAll("[data-hro-country-label]").forEach(function (x) { x.textContent = c === "TG" ? "Togo" : "Côte d'Ivoire"; });
    }

    function loadAll() {
        state.selected = null;
        return Promise.all([loadOrg(), loadPerf(), loadExits()]);
    }

    // ── Organisation / parcours ───────────────────────────────────────────
    function loadOrg() {
        $("hroTree").innerHTML = '<div class="hr-empty">Chargement…</div>';
        return getJson("/api/hr/organisation?country=" + state.country).then(function (org) {
            state.org = org;
            renderOverviewPops();
            renderTree();
            renderList();
            var badge = $("tabCountOrg");
            if (badge) badge.textContent = org.toAssign ? org.toAssign + " à affecter" : "";
        }).catch(function (e) { $("hroTree").innerHTML = '<div class="hr-empty text-danger">' + esc(e.message) + '</div>'; });
    }

    function renderOverviewPops() {
        var box = $("hroOverviewPops");
        if (!box || !state.org) return;
        box.innerHTML = state.org.populations.map(function (p) {
            return '<button type="button" class="hro-pop-card" style="--c:' + POP_COLORS[p.code] + '" data-go-pop="' + p.code + '">' +
                '<span class="hro-pop-ico"><i class="bi ' + p.icon + '"></i></span><div><small>' + esc(p.label) + '</small><b>' + p.active + '</b>' +
                '<span class="hro-pop-teams">' + p.teams.map(function (t) { return esc(t.label) + " " + t.active; }).join(" · ") + '</span></div></button>';
        }).join("") + (state.org.toAssign ? '<button type="button" class="hro-pop-card warn" data-go-pop="__none"><span class="hro-pop-ico"><i class="bi bi-exclamation-triangle"></i></span><div><small>À affecter</small><b>' + state.org.toAssign + '</b><span class="hro-pop-teams">collaborateurs sans équipe du parcours</span></div></button>' : "");
        box.querySelectorAll("[data-go-pop]").forEach(function (b) {
            b.addEventListener("click", function () {
                var pop = b.getAttribute("data-go-pop");
                state.selected = pop === "__none" ? { pop: null, team: null, none: true } : { pop: pop, team: null };
                if (window.RccHr && window.RccHr.showTab) window.RccHr.showTab("org");
                renderTree(); renderList();
            });
        });
    }

    function renderTree() {
        var org = state.org;
        if (!org) return;
        $("hroTree").innerHTML = '<div class="hro-tree">' + org.populations.map(function (p) {
            var color = POP_COLORS[p.code];
            return '<section class="hro-pop" style="--c:' + color + '">' +
                '<header><span class="hro-pop-ico"><i class="bi ' + p.icon + '"></i></span><div><h4>' + esc(p.label) + '</h4><small>' + p.active + ' actif(s)' +
                (p.inactive ? ' · ' + p.inactive + ' sorti(s)' : "") + '</small></div>' +
                '<button type="button" class="hro-all' + (state.selected && state.selected.pop === p.code && !state.selected.team ? " on" : "") + '" data-pop="' + p.code + '">Tous</button></header>' +
                '<div class="hro-teams">' + p.teams.map(function (t) {
                    var on = state.selected && state.selected.pop === p.code && state.selected.team === t.code;
                    return '<button type="button" class="hro-team' + (on ? " on" : "") + '" data-pop="' + p.code + '" data-team="' + t.code + '">' +
                        '<i class="bi ' + t.icon + '"></i><span>' + esc(t.label) + '</span><b>' + t.active + '</b></button>';
                }).join("") + '</div></section>';
        }).join("") + '</div>' +
            (org.toAssign ? '<button type="button" class="hro-toassign' + (state.selected && state.selected.none ? " on" : "") + '" data-none="1"><i class="bi bi-exclamation-triangle"></i> ' + org.toAssign +
                ' collaborateur(s) actif(s) à placer dans le parcours — cliquez pour les voir</button>' : "");
        $("hroTree").querySelectorAll("[data-pop]").forEach(function (b) {
            b.addEventListener("click", function () {
                state.selected = { pop: b.getAttribute("data-pop"), team: b.getAttribute("data-team") || null };
                renderTree(); renderList();
                $("hroListCard").scrollIntoView({ behavior: "smooth", block: "start" });
            });
        });
        var none = $("hroTree").querySelector("[data-none]");
        if (none) none.addEventListener("click", function () { state.selected = { none: true }; renderTree(); renderList(); });
    }

    function renderList() {
        var org = state.org;
        if (!org) return;
        var q = ($("hroSearch").value || "").trim().toLowerCase();
        var status = $("hroStatus").value;
        var sel = state.selected;
        var list = org.people.filter(function (p) {
            if (status === "active" && !p.active) return false;
            if (status === "inactive" && p.active) return false;
            if (q) return (p.name + " " + p.username + " " + (p.activity || "")).toLowerCase().indexOf(q) !== -1;
            if (!sel) return false;
            if (sel.none) return !p.team;
            return p.population === sel.pop && (!sel.team || p.team === sel.team);
        });
        $("hroListTitle").innerHTML = '<i class="bi bi-people"></i> ' + (q ? "Recherche « " + esc(q) + " »" : !sel ? "Collaborateurs" : sel.none ? "À placer dans le parcours" :
            esc(popLabel(sel.pop)) + (sel.team ? " — " + esc(teamLabel(sel.pop, sel.team)) : "")) + ' <span class="hro-count">' + list.length + '</span>';
        $("hroListSub").textContent = !sel && !q ? "Sélectionnez une équipe ci-dessus, ou recherchez un nom." : "Placer : corriger population / équipe · Dossier : documents RH · Sortie : retirer l'agent.";
        if (!sel && !q) { $("hroList").innerHTML = ""; return; }
        $("hroList").innerHTML = list.length ? '<div class="hro-people">' + list.map(function (p) {
            return '<div class="hro-person' + (p.active ? "" : " out") + '">' +
                '<span class="hro-av" style="--c:' + (POP_COLORS[p.population] || "#6B7A90") + '">' + esc(initials(p.name)) + '</span>' +
                '<div class="hro-person-main"><b>' + esc(p.name) + '</b><small>' + esc(p.username) + (p.activity ? " · " + esc(p.activity) : "") + '</small>' +
                '<div class="hro-tags"><span class="hro-tag" style="--c:' + (POP_COLORS[p.population] || "#6B7A90") + '">' + esc(popLabel(p.population)) + '</span>' +
                '<span class="hro-tag' + (p.team ? "" : " warn") + '">' + esc(teamLabel(p.population, p.team)) + '</span>' +
                (p.manual ? '<span class="hro-tag muted" title="Placement corrigé par le RH"><i class="bi bi-pencil"></i> RH</span>' : "") +
                (p.contractType ? '<span class="hro-tag muted">' + esc(p.contractType) + (p.contractStatus ? " · " + esc(p.contractStatus) : "") + '</span>' : "") +
                (p.contractEnd ? '<span class="hro-tag muted"><i class="bi bi-calendar-x"></i> fin ' + fmtDate(p.contractEnd) + '</span>' : "") +
                (p.active ? "" : '<span class="hro-tag danger">Sorti</span>') + '</div></div>' +
                '<div class="hro-actions">' +
                (state.canWrite ? '<button type="button" class="hr-btn hr-btn-soft hr-btn-sm" data-assign="' + p.userId + '"><i class="bi bi-diagram-3"></i> Placer</button>' : "") +
                '<button type="button" class="hr-btn hr-btn-soft hr-btn-sm" data-dossier="' + p.userId + '"><i class="bi bi-folder2-open"></i> Dossier</button>' +
                (state.canWrite && p.active ? '<button type="button" class="hr-btn hr-btn-danger hr-btn-sm" data-exit="' + p.userId + '"><i class="bi bi-person-dash"></i> Sortie</button>' : "") +
                '</div></div>';
        }).join("") + '</div>' : '<div class="hr-empty"><i class="bi bi-people"></i>Aucun collaborateur ici.</div>';
        $("hroList").querySelectorAll("[data-assign]").forEach(function (b) { b.addEventListener("click", function () { openAssign(Number(b.getAttribute("data-assign"))); }); });
        $("hroList").querySelectorAll("[data-exit]").forEach(function (b) { b.addEventListener("click", function () { openExit(Number(b.getAttribute("data-exit"))); }); });
        $("hroList").querySelectorAll("[data-dossier]").forEach(function (b) {
            b.addEventListener("click", function () {
                var p = person(Number(b.getAttribute("data-dossier")));
                if (window.RccHr && window.RccHr.openDossier) window.RccHr.openDossier(p.userId, p.name);
            });
        });
    }

    function person(id) { return state.org.people.filter(function (p) { return p.userId === id; })[0]; }

    // ── Placement ─────────────────────────────────────────────────────────
    function openAssign(userId) {
        var p = person(userId);
        assignTarget = { userId: userId, pop: p.population, team: p.team };
        $("hroAssignName").textContent = p.name;
        $("hroAssignCountry").value = state.country;
        $("hroAssignError").textContent = "";
        renderAssign();
        if (!assignModal) assignModal = new bootstrap.Modal($("hroAssignModal"));
        assignModal.show();
    }
    function renderAssign() {
        $("hroAssignPop").innerHTML = state.org.populations.map(function (p) {
            return '<button type="button" class="' + (assignTarget.pop === p.code ? "on" : "") + '" style="--c:' + POP_COLORS[p.code] + '" data-p="' + p.code + '"><i class="bi ' + p.icon + '"></i> ' + esc(p.label) + '</button>';
        }).join("");
        var pop = state.org.populations.filter(function (p) { return p.code === assignTarget.pop; })[0];
        $("hroAssignTeam").innerHTML = (pop ? pop.teams : []).map(function (t) {
            return '<button type="button" class="' + (assignTarget.team === t.code ? "on" : "") + '" data-t="' + t.code + '"><i class="bi ' + t.icon + '"></i> ' + esc(t.label) + '</button>';
        }).join("");
        $("hroAssignPop").querySelectorAll("[data-p]").forEach(function (b) {
            b.addEventListener("click", function () { assignTarget.pop = b.getAttribute("data-p"); assignTarget.team = null; renderAssign(); });
        });
        $("hroAssignTeam").querySelectorAll("[data-t]").forEach(function (b) {
            b.addEventListener("click", function () { assignTarget.team = b.getAttribute("data-t"); renderAssign(); });
        });
    }
    function saveAssign() {
        if (!assignTarget.team) { $("hroAssignError").textContent = "Choisissez l'équipe."; return; }
        var country = $("hroAssignCountry").value;
        sendJson("/api/hr/organisation/" + assignTarget.userId, "PUT", { population: assignTarget.pop, team: assignTarget.team, countryCode: country })
            .then(function () {
                assignModal.hide();
                if (country !== state.country && window.RccHr && window.RccHr.reload) window.RccHr.reload();
                loadOrg(); loadPerf();
            })
            .catch(function (e) { $("hroAssignError").textContent = e.message; });
    }

    // ── Sorties ───────────────────────────────────────────────────────────
    function openExit(userId) {
        var people = state.org.people.filter(function (p) { return p.active; });
        $("hroExitAgents").innerHTML = people.map(function (p) { return '<option value="' + esc(p.name + " — " + p.username) + '">'; }).join("");
        var p = userId ? person(userId) : null;
        $("hroExitAgent").value = p ? p.name + " — " + p.username : "";
        $("hroExitAgent").readOnly = !!p;
        $("hroExitReason").innerHTML = state.org.departureReasons.map(function (r) {
            var def = p && p.population === "STAGIAIRE" ? "FIN_STAGE" : "DEMISSION";
            return '<option value="' + r + '"' + (r === def ? " selected" : "") + '>' + esc(REASONS[r] || r) + '</option>';
        }).join("");
        $("hroExitDate").value = new Date().toISOString().slice(0, 10);
        $("hroExitComment").value = "";
        $("hroExitError").textContent = "";
        showExitInfo();
        if (!exitModal) exitModal = new bootstrap.Modal($("hroExitModal"));
        exitModal.show();
    }
    function exitPerson() {
        var v = $("hroExitAgent").value;
        var username = v.indexOf(" — ") !== -1 ? v.split(" — ").pop().trim() : v.trim();
        return state.org.people.filter(function (p) { return p.active && (p.username === username || p.name === v); })[0];
    }
    function showExitInfo() {
        var p = exitPerson();
        $("hroExitAgentInfo").textContent = p ? popLabel(p.population) + " · " + teamLabel(p.population, p.team) + (p.contractType ? " · " + p.contractType : "") : "";
    }
    function saveExit() {
        var p = exitPerson();
        if (!p) { $("hroExitError").textContent = "Choisissez un agent de la liste."; return; }
        var btn = $("hroExitSave"); btn.disabled = true;
        sendJson("/api/hr/departures", "POST", { userId: p.userId, reason: $("hroExitReason").value, date: $("hroExitDate").value, comment: $("hroExitComment").value })
            .then(function () {
                exitModal.hide();
                loadOrg(); loadExits();
                if (window.RccHr && window.RccHr.reload) window.RccHr.reload();
            })
            .catch(function (e) { $("hroExitError").textContent = e.message; })
            .then(function () { btn.disabled = false; });
    }

    function loadExits() {
        return getJson("/api/hr/departures?country=" + state.country).then(function (list) {
            state.exits = list || [];
            renderExits();
        }).catch(function (e) { $("hroExits").innerHTML = '<div class="hr-empty text-danger">' + esc(e.message) + '</div>'; });
    }
    function renderExits() {
        var list = state.exits, now = new Date(), m = now.toISOString().slice(0, 7), y = String(now.getFullYear());
        var active = list.filter(function (d) { return !d.reintegratedAt; });
        var byReason = {};
        active.forEach(function (d) { byReason[d.reason] = (byReason[d.reason] || 0) + 1; });
        $("hroExitKpis").innerHTML = [
            ["bi-calendar-month", "#E0435B", active.filter(function (d) { return String(d.date).slice(0, 7) === m; }).length, "Sorties ce mois"],
            ["bi-calendar3", "#F59E0B", active.filter(function (d) { return String(d.date).slice(0, 4) === y; }).length, "Sorties " + y],
            ["bi-arrow-counterclockwise", "#0F9D6C", list.filter(function (d) { return d.reintegratedAt; }).length, "Réintégrations"]
        ].map(function (k) { return '<div class="hro-kpi" style="--c:' + k[1] + '"><i class="bi ' + k[0] + '"></i><div><b>' + k[2] + '</b><small>' + k[3] + '</small></div></div>'; }).join("") +
            '<div class="hro-reasons">' + Object.keys(byReason).map(function (r) { return '<span>' + esc(REASONS[r] || r) + ' <b>' + byReason[r] + '</b></span>'; }).join("") + '</div>';
        var badge = $("tabCountExits");
        if (badge) badge.textContent = active.filter(function (d) { return String(d.date).slice(0, 7) === m; }).length || "";
        $("hroExits").innerHTML = list.length ? '<div class="table-responsive"><table class="table table-hover align-middle hr-table"><thead><tr><th>Collaborateur</th><th>Parcours</th><th>Motif</th><th>Date</th><th>Enregistré par</th><th></th></tr></thead><tbody>' +
            list.map(function (d) {
                return '<tr' + (d.reintegratedAt ? ' class="text-muted"' : "") + '><td><b>' + esc(d.name) + '</b><div class="small text-muted">' + esc(d.username || "") + '</div></td>' +
                    '<td>' + (d.population ? esc(popLabel(d.population)) + " · " + esc(teamLabel(d.population, d.team)) : "—") + '</td>' +
                    '<td><span class="hro-tag danger">' + esc(REASONS[d.reason] || d.reason) + '</span>' + (d.comment ? '<div class="small text-muted mt-1">' + esc(d.comment) + '</div>' : "") + '</td>' +
                    '<td>' + fmtDate(d.date) + '</td><td class="small">' + esc(d.recordedBy || "—") + '</td>' +
                    '<td class="text-end">' + (d.reintegratedAt ? '<span class="hro-tag">Réintégré le ' + fmtDate(String(d.reintegratedAt).slice(0, 10)) + '</span>'
                        : (state.canWrite ? '<button type="button" class="hr-btn hr-btn-soft hr-btn-sm" data-reint="' + d.id + '"><i class="bi bi-arrow-counterclockwise"></i> Réintégrer</button>' : "")) + '</td></tr>';
            }).join("") + '</tbody></table></div>' : '<div class="hr-empty"><i class="bi bi-box-arrow-right"></i>Aucune sortie enregistrée pour cette filiale.</div>';
        $("hroExits").querySelectorAll("[data-reint]").forEach(function (b) {
            b.addEventListener("click", function () {
                if (!confirm("Réintégrer ce collaborateur ? Son compte sera réactivé.")) return;
                sendJson("/api/hr/departures/" + b.getAttribute("data-reint") + "/reintegrate", "POST", {})
                    .then(function () { loadExits(); loadOrg(); if (window.RccHr && window.RccHr.reload) window.RccHr.reload(); })
                    .catch(function (e) { alert(e.message); });
            });
        });
    }

    // ── Performance ───────────────────────────────────────────────────────
    function loadPerf() {
        $("hroPerf").innerHTML = '<div class="hr-empty">Chargement…</div>';
        return getJson("/api/hr/performance?country=" + state.country + "&month=" + month()).then(function (p) {
            state.perf = p;
            renderPerf();
        }).catch(function (e) { $("hroPerf").innerHTML = '<div class="hr-empty text-danger">' + esc(e.message) + '</div>'; });
    }
    function renderPerf() {
        var p = state.perf;
        if (!p) return;
        $("hroPerfPops").innerHTML = [["", "Toutes"], ["OUTSOURCE", "Outsource"], ["STAGIAIRE", "Stagiaires"], ["STAFF", "Staff Ecobank"]].map(function (x) {
            return '<button type="button" class="' + (state.perfPop === x[0] ? "on" : "") + '" data-pp="' + x[0] + '">' + x[1] + '</button>';
        }).join("");
        $("hroPerfPops").querySelectorAll("[data-pp]").forEach(function (b) {
            b.addEventListener("click", function () { state.perfPop = b.getAttribute("data-pp"); renderPerf(); });
        });
        var teams = p.teams.filter(function (t) { return !state.perfPop || t.population === state.perfPop; });
        var groups = {};
        teams.forEach(function (t) { (groups[t.population] = groups[t.population] || []).push(t); });
        function gauge(v) {
            if (v == null) return '<span class="hro-g muted">—</span>';
            var c = v >= 80 ? "#0F9D6C" : v >= 60 ? "#F59E0B" : "#E0435B";
            return '<span class="hro-g"><span class="hro-g-bar"><span style="width:' + Math.min(100, v) + '%;background:' + c + '"></span></span><b style="color:' + c + '">' + pct(v) + '</b></span>';
        }
        $("hroPerf").innerHTML = Object.keys(groups).map(function (pop) {
            return '<div class="hro-perf-group" style="--c:' + POP_COLORS[pop] + '"><h5><i class="bi bi-circle-fill"></i> ' + esc(popLabel(pop)) + '</h5>' +
                '<div class="table-responsive"><table class="table align-middle hr-table hro-perf-table"><thead><tr><th>Équipe</th><th class="text-end">Effectif</th><th>Présence</th><th>Score qualité</th><th>Performance</th><th class="text-end">Écoutes</th><th></th></tr></thead><tbody>' +
                groups[pop].map(function (t) {
                    return '<tr role="button" data-perf="' + t.population + '|' + t.team + '"><td><b>' + esc(t.label) + '</b></td><td class="text-end">' + t.headcount + '</td>' +
                        '<td>' + gauge(t.presence) + '</td><td>' + gauge(t.qualityScore) + '</td><td>' + gauge(t.performance) + '</td><td class="text-end">' + t.evaluations + '</td>' +
                        '<td class="text-end"><i class="bi bi-chevron-right text-muted"></i></td></tr>';
                }).join("") + '</tbody></table></div></div>';
        }).join("") || '<div class="hr-empty">Aucune donnée.</div>';
        $("hroPerf").querySelectorAll("[data-perf]").forEach(function (tr) {
            tr.addEventListener("click", function () { openPerfDetail(tr.getAttribute("data-perf")); });
        });
    }
    function openPerfDetail(key) {
        var k = key.split("|");
        var t = state.perf.teams.filter(function (x) { return x.population === k[0] && x.team === k[1]; })[0];
        $("hroPerfDetailCard").classList.remove("d-none");
        $("hroPerfDetailTitle").innerHTML = '<i class="bi bi-people"></i> ' + esc(popLabel(t.population)) + " — " + esc(t.label);
        $("hroPerfDetailSub").textContent = t.headcount + " collaborateur(s) · " + state.perf.period + " · " + (state.country === "TG" ? "Togo" : "Côte d'Ivoire");
        $("hroPerfDetail").innerHTML = t.agents.length ? '<div class="table-responsive"><table class="table table-sm table-hover align-middle hr-table"><thead><tr><th>Collaborateur</th><th class="text-end">Présence</th><th class="text-end">Score qualité</th><th class="text-end">Écoutes</th><th class="text-end">Performance</th></tr></thead><tbody>' +
            t.agents.map(function (a) {
                return '<tr><td><b>' + esc(a.name) + '</b></td><td class="text-end">' + pct(a.presence) + '</td><td class="text-end">' + pct(a.qualityScore) + '</td><td class="text-end">' + a.evaluations + '</td><td class="text-end"><b>' + pct(a.performance) + '</b></td></tr>';
            }).join("") + '</tbody></table></div>' : '<div class="hr-empty">Aucun collaborateur actif dans cette équipe pour ce mois.</div>';
        // Indicateurs métier de l'équipe (appels, DMT, mails, SLA…) — même moteur que les portails Superviseur / Team Leader.
        $("hroPerfBusiness").innerHTML = "";
        if (t.businessTeam && window.RccTeamPerf) {
            RccTeamPerf.render($("hroPerfBusiness"), { team: t.businessTeam, month: month(), countryCode: state.country });
        }
        $("hroPerfDetailCard").scrollIntoView({ behavior: "smooth", block: "start" });
    }

    // ── Démarrage ─────────────────────────────────────────────────────────
    function start(profile) {
        state.canWrite = profile === "RH" || profile === "ADMIN";
        if (!state.canWrite) { var b = $("hroNewExitBtn"); if (b) b.style.display = "none"; }
        loadAll();
    }

    function wire() {
        setCountry(readCountry());
        document.querySelectorAll("#hrCountrySwitch [data-country]").forEach(function (b) {
            b.addEventListener("click", function () {
                var c = b.getAttribute("data-country");
                if (c === state.country) return;
                setCountry(c);
                $("hroPerfDetailCard").classList.add("d-none");
                if (window.RccHr && window.RccHr.reload) window.RccHr.reload();
                loadAll();
            });
        });
        $("hroSearch").addEventListener("input", renderList);
        $("hroStatus").addEventListener("change", renderList);
        $("hroAssignSave").addEventListener("click", saveAssign);
        $("hroExitSave").addEventListener("click", saveExit);
        $("hroExitAgent").addEventListener("input", showExitInfo);
        $("hroNewExitBtn").addEventListener("click", function () { openExit(null); });
        $("hroPerfDetailClose").addEventListener("click", function () { $("hroPerfDetailCard").classList.add("d-none"); });
        if ($("hrMonth")) $("hrMonth").addEventListener("change", loadPerf);
        if ($("refreshHrBtn")) $("refreshHrBtn").addEventListener("click", loadAll);
    }

    document.addEventListener("DOMContentLoaded", wire);
    return { start: start, reload: loadAll };
})();
