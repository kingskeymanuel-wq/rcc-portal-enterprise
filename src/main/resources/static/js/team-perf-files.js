"use strict";

/**
 * Fichiers de performance par équipe (API /api/team-perf-files).
 *  - RccPerfFiles.mountImport(container) : portail QA — choix de l'équipe, indicateurs pris en compte,
 *    fichier + période, aperçu (colonnes reconnues, agents rattachés), enregistrement, tableau de l'équipe.
 *  - RccPerfFiles.mountMine(container) : l'agent voit ses propres chiffres, son rang et la moyenne de l'équipe.
 */
window.RccPerfFiles = (function () {
    var esc = RccApi.escapeHtml, getJson = RccApi.getJson;
    var LEVELS = {
        GOOD: { cls: "good", label: "Objectif atteint", icon: "bi-emoji-smile" },
        WARN: { cls: "warn", label: "Proche de l'objectif", icon: "bi-emoji-neutral" },
        BAD: { cls: "bad", label: "À améliorer", icon: "bi-emoji-frown" }
    };

    function errorOf(res) {
        return res.text().then(function (t) {
            var msg = t;
            try { var p = JSON.parse(t); if (p && p.error && p.error.message) msg = p.error.message; } catch (e) { /* texte brut */ }
            return Promise.reject(new Error(msg || "HTTP " + res.status));
        });
    }

    function fmt(v, unit) {
        if (v == null || v === "") return "—";
        if (unit === "TEXT") return esc(String(v));
        if (unit === "PCT") return Math.round(v) + " %";
        if (unit === "SECONDS") { var m = Math.floor(v / 60), s = Math.round(v % 60); return m + ":" + String(s).padStart(2, "0"); }
        if (unit === "RATE") return String(Math.round(v));
        return (Math.round(v * 10) / 10).toLocaleString("fr-FR");
    }
    function fmtDate(d) { return d ? new Date(d + "T00:00:00").toLocaleDateString("fr-FR", { day: "2-digit", month: "short" }) : "—"; }
    function period(from, to) { return from ? "du " + fmtDate(from) + " au " + fmtDate(to) + " " + String(to).slice(0, 4) : "période non trouvée"; }
    function levelCls(l) { return l && LEVELS[l] ? " tpf-" + LEVELS[l].cls : ""; }

    /** Tableau façon rapport hebdo : productivité colorée, totaux en bas. */
    function table(fields, rows, totals, opts) {
        opts = opts || {};
        var head = '<th class="tpf-name">Agent</th>' + fields.map(function (f) {
            return '<th title="' + esc(f.computed ? "Calculé : " + f.formula : f.label) + '">' + esc(f.label) + (f.computed ? ' <i class="bi bi-calculator"></i>' : "") + '</th>';
        }).join("");
        var body = rows.map(function (r) {
            var who = '<b>' + esc(r.agentName) + '</b>' + (opts.showMatch
                ? (r.userId ? '<small class="tpf-ok"><i class="bi bi-person-check"></i> ' + (r.portalName && r.portalName.toLowerCase() !== String(r.agentName).toLowerCase()
                    ? esc(r.portalName) + ' (' + esc(r.username || "") + ')' : 'lié au compte ' + esc(r.username || "")) + '</small>'
                    : '<small class="tpf-ko"><i class="bi bi-person-x"></i> non trouvé dans le portail</small>') : "");
            var notes = (r.notes || []).filter(function (n) { return n.indexOf("non trouvé") === -1 && n.indexOf("non rattaché") === -1; });
            if (notes.length) who += '<small class="tpf-note"><i class="bi bi-info-circle"></i> ' + esc(notes.join(" · ")) + '</small>';
            return '<tr><td class="tpf-name">' + who + '</td>' + fields.map(function (f) {
                var v = r.values[f.key];
                var cls = f.key === "productivity" ? " tpf-prod" + levelCls(r.level) : (f.key === "totalActivities" || f.key === "avgPerDay" ? " tpf-strong" : "");
                return '<td class="' + cls + '">' + fmt(v, f.unit) + '</td>';
            }).join("") + '</tr>';
        }).join("");
        var foot = totals && Object.keys(totals).length ? '<tfoot><tr><td class="tpf-name">Total général</td>' + fields.map(function (f) {
            return '<td>' + (totals[f.key] != null ? fmt(totals[f.key], f.unit) : "") + '</td>';
        }).join("") + '</tr></tfoot>' : "";
        return '<div class="table-responsive tpf-table-wrap"><table class="tpf-table"><thead><tr>' + head + '</tr></thead><tbody>' + body + '</tbody>' + foot + '</table></div>' +
            '<div class="tpf-legend"><span class="tpf-good">≥ 100 % objectif atteint</span><span class="tpf-warn">90 – 99 %</span><span class="tpf-bad">&lt; 90 % à accompagner</span></div>';
    }

    // ───────────── Portail QA ─────────────

    function mountImport(root) {
        var state = { catalog: [], team: null, preview: null, file: null };
        root.innerHTML =
            '<div class="tpf">' +
            '<div class="tpf-steps">' +
            '<section class="tpf-step"><h6><span>1</span> Équipe concernée</h6><div class="tpf-teams" data-teams></div>' +
            '<div class="tpf-kpis-title">Indicateurs pris en compte pour cette équipe</div><div class="tpf-kpis" data-kpis><em>Choisissez une équipe.</em></div></section>' +
            '<section class="tpf-step"><h6><span>2</span> Fichier et période</h6>' +
            '<label class="tpf-drop" data-drop><input type="file" data-file accept=".xlsx,.xlsm,.xls,.csv,.txt,.pptx,.pdf,.htm,.html,image/*">' +
            '<i class="bi bi-cloud-arrow-up"></i><b data-file-name>Déposez ou choisissez le fichier</b><small>Excel, CSV, PowerPoint du rapport hebdo, PDF…</small></label>' +
            '<div class="tpf-period"><label>Du <input type="date" class="form-control form-control-sm" data-from></label>' +
            '<label>au <input type="date" class="form-control form-control-sm" data-to></label>' +
            '<label>Filiale <select class="form-select form-select-sm" data-country><option value="">—</option><option value="CI">Côte d\'Ivoire</option><option value="TG">Togo</option></select></label></div>' +
            '<small class="text-muted d-block mb-2">La période est lue dans le titre du fichier (« du 21 - 27 Septembre ») : laissez vide pour la détecter.</small>' +
            '<div class="d-flex flex-wrap gap-2"><button type="button" class="btn btn-primary btn-sm" data-analyse disabled><i class="bi bi-search"></i> Analyser le fichier</button>' +
            '<button type="button" class="btn btn-warning btn-sm fw-semibold" data-dispatch disabled title="Fichier consolidé de plusieurs équipes : chaque agent est envoyé dans son équipe">' +
            '<i class="bi bi-diagram-3"></i> Dispatching</button></div>' +
            '<small class="text-muted d-block mt-1">Fichier consolidé de plusieurs équipes ? « Dispatching » répartit automatiquement chaque agent dans son équipe.</small></section>' +
            '</div>' +
            '<div data-preview></div>' +
            '<div class="tpf-sheet-card"><div class="tpf-sheet-head"><div><h6 class="mb-0"><i class="bi bi-table"></i> Performances de l\'équipe <span data-sheet-team></span></h6>' +
            '<small class="text-muted" data-sheet-sub></small></div><div class="d-flex gap-2 align-items-center"><select class="form-select form-select-sm" data-periods></select>' +
            '<button type="button" class="btn btn-outline-danger btn-sm" data-delete title="Supprimer cet import"><i class="bi bi-trash"></i></button></div></div>' +
            '<div data-sheet></div></div></div>';
        function q(s) { return root.querySelector(s); }

        function renderTeams() {
            q("[data-teams]").innerHTML = state.catalog.map(function (t) {
                return '<button type="button" class="' + (state.team && state.team.code === t.code ? "on" : "") + '" data-team="' + t.code + '">' + esc(t.label) + '</button>';
            }).join("");
            q("[data-teams]").querySelectorAll("[data-team]").forEach(function (b) {
                b.addEventListener("click", function () { selectTeam(b.getAttribute("data-team")); });
            });
            q("[data-kpis]").innerHTML = state.team ? state.team.fields.map(function (f) {
                return '<span class="tpf-kpi' + (f.computed ? " computed" : "") + '" title="' + esc(f.computed ? "Calculé si absent du fichier : " + f.formula : "Colonne du fichier") + '">' +
                    (f.computed ? '<i class="bi bi-calculator"></i> ' : "") + esc(f.label) + '</span>';
            }).join("") : "<em>Choisissez une équipe.</em>";
            q("[data-analyse]").disabled = !(state.team && state.file);
            q("[data-dispatch]").disabled = !state.file;
        }
        function selectTeam(code) {
            state.team = state.catalog.filter(function (t) { return t.code === code; })[0];
            state.preview = null;
            q("[data-preview]").innerHTML = "";
            renderTeams();
            loadSheet();
        }

        q("[data-file]").addEventListener("change", function () {
            state.file = this.files[0] || null;
            q("[data-file-name]").textContent = state.file ? state.file.name : "Déposez ou choisissez le fichier";
            q("[data-drop]").classList.toggle("has-file", !!state.file);
            renderTeams();
        });

        function send(dryRun) {
            var fd = new FormData();
            fd.append("file", state.file);
            var url = "/api/team-perf-files/import?team=" + state.team.code + "&dryRun=" + dryRun +
                (q("[data-from]").value ? "&from=" + q("[data-from]").value : "") + (q("[data-to]").value ? "&to=" + q("[data-to]").value : "") +
                (q("[data-country]").value ? "&countryCode=" + q("[data-country]").value : "");
            return fetch(url, { method: "POST", credentials: "same-origin", body: fd }).then(function (res) { return res.ok ? res.json() : errorOf(res); });
        }

        q("[data-analyse]").addEventListener("click", function () {
            var box = q("[data-preview]");
            box.innerHTML = '<div class="tpf-msg">Analyse du fichier…</div>';
            send(true).then(function (r) {
                state.preview = r;
                if (r.from && !q("[data-from]").value) q("[data-from]").value = r.from;
                if (r.to && !q("[data-to]").value) q("[data-to]").value = r.to;
                renderPreview();
            }).catch(function (e) { box.innerHTML = '<div class="tpf-msg error"><i class="bi bi-exclamation-triangle"></i> ' + esc(e.message) + '</div>'; });
        });

        // ───── Dispatching d'un fichier consolidé ─────
        function sendDispatch(dryRun) {
            var fd = new FormData();
            fd.append("file", state.file);
            var url = "/api/team-perf-files/dispatch?dryRun=" + dryRun +
                (q("[data-from]").value ? "&from=" + q("[data-from]").value : "") + (q("[data-to]").value ? "&to=" + q("[data-to]").value : "") +
                (q("[data-country]").value ? "&countryCode=" + q("[data-country]").value : "");
            return fetch(url, { method: "POST", credentials: "same-origin", body: fd }).then(function (res) { return res.ok ? res.json() : errorOf(res); });
        }

        q("[data-dispatch]").addEventListener("click", function () {
            var box = q("[data-preview]");
            box.innerHTML = '<div class="tpf-msg">Dispatching du fichier : lecture des tableaux de chaque équipe…</div>';
            sendDispatch(true).then(function (r) {
                if (r.from && !q("[data-from]").value) q("[data-from]").value = r.from;
                if (r.to && !q("[data-to]").value) q("[data-to]").value = r.to;
                renderDispatch(r);
            }).catch(function (e) { box.innerHTML = '<div class="tpf-msg error"><i class="bi bi-exclamation-triangle"></i> ' + esc(e.message) + '</div>'; });
        });

        function renderDispatch(r) {
            var box = q("[data-preview]");
            var total = r.teams.reduce(function (n, t) { return n + t.lines.length; }, 0);
            var chips = r.teams.map(function (t) {
                return '<a href="#tpf-d-' + t.team + '" class="tpf-pill ok">' + esc(t.teamLabel) + ' : ' + t.lines.length + ' agent(s)</a>';
            }).join("") + (r.unassigned.length ? '<a href="#tpf-d-none" class="tpf-pill ko">' + r.unassigned.length + ' non réparti(s)</a>' : "");
            var sections = r.teams.map(function (t) {
                var cols = Object.keys(t.recognizedColumns).map(function (h) {
                    return '<span class="tpf-map">' + esc(h || "(sans titre)") + ' <i class="bi bi-arrow-right"></i> <b>' + esc(t.recognizedColumns[h]) + '</b></span>';
                }).join("");
                return '<div class="mt-3" id="tpf-d-' + t.team + '"><h6 class="mb-1"><i class="bi bi-people"></i> ' + esc(t.teamLabel) +
                    ' <span class="tpf-pill">' + t.lines.length + ' agent(s)</span> <span class="tpf-pill ok">' + t.matched + ' rattaché(s)</span>' +
                    (t.unmatched ? ' <span class="tpf-pill ko">' + t.unmatched + ' non trouvé(s)</span>' : "") + '</h6>' +
                    '<div class="tpf-cols"><small>Colonnes reconnues :</small>' + cols + '</div>' + table(t.fields, t.lines, null, { showMatch: true }) + '</div>';
            }).join("");
            var none = r.unassigned.length ? '<div class="mt-3" id="tpf-d-none"><h6 class="mb-1 text-danger"><i class="bi bi-question-circle"></i> Agents non répartis (non enregistrés)</h6>' +
                '<ul class="small mb-0">' + r.unassigned.map(function (l) {
                    return '<li><b>' + esc(l.agentName) + '</b> — ' + esc((l.notes || [])[0] || "") + '</li>';
                }).join("") + '</ul></div>' : "";
            box.innerHTML = '<div class="tpf-preview">' +
                '<div class="tpf-preview-head"><div><h6><i class="bi bi-diagram-3"></i> Dispatching — ' + total + ' agent(s) répartis dans ' + r.teams.length + ' équipe(s), ' + period(r.from, r.to) +
                (r.periodDetected ? ' <span class="tpf-pill ok">période lue dans le fichier</span>' : "") + '</h6><div class="tpf-pills">' + chips + '</div></div>' +
                '<div class="d-flex gap-2"><button type="button" class="btn btn-outline-secondary btn-sm" data-cancel>Annuler</button>' +
                '<button type="button" class="btn btn-success btn-sm" data-save-dispatch' + (r.from && r.teams.length ? "" : " disabled") + '><i class="bi bi-check2-circle"></i> Enregistrer le dispatching</button></div></div>' +
                (r.from ? "" : '<div class="tpf-msg error">Indiquez la période (du … au …) ci-dessus puis relancez le dispatching.</div>') +
                '<div class="tpf-msg"><i class="bi bi-info-circle"></i> Chaque agent va dans l\'équipe dont il remplit les indicateurs propres (appels émis, mails, chats…) ; à défaut, dans l\'équipe de son compte. ' +
                'Enregistrer remplace, pour chaque équipe, un import précédent de la même période.</div>' +
                sections + none + '</div>';
            box.querySelector("[data-cancel]").addEventListener("click", function () { box.innerHTML = ""; });
            box.querySelector("[data-save-dispatch]").addEventListener("click", function () {
                var btn = this; btn.disabled = true;
                sendDispatch(false).then(function (res) {
                    box.innerHTML = '<div class="tpf-msg ok"><i class="bi bi-check-circle-fill"></i> Dispatching enregistré, ' + period(res.from, res.to) + ' : ' +
                        res.teams.map(function (t) { return esc(t.teamLabel) + ' (' + t.lines.length + ' agent(s)' + (t.replaced ? ", remplace l'import précédent" : "") + ')'; }).join(", ") +
                        '. Chaque agent rattaché voit ses chiffres dans son portail et a reçu une notification.' +
                        (res.unassigned.length ? ' ' + res.unassigned.length + ' agent(s) non réparti(s) : à rattacher à leur équipe dans le portail puis relancer.' : "") + '</div>';
                    loadSheet(res.from + "|" + res.to);
                }).catch(function (e) { btn.disabled = false; alert(e.message); });
            });
        }

        function renderPreview() {
            var r = state.preview, box = q("[data-preview]");
            var cols = Object.keys(r.recognizedColumns).map(function (h) {
                return '<span class="tpf-map">' + esc(h || "(sans titre)") + ' <i class="bi bi-arrow-right"></i> <b>' + esc(r.recognizedColumns[h]) + '</b></span>';
            }).join("");
            box.innerHTML = '<div class="tpf-preview">' +
                '<div class="tpf-preview-head"><div><h6><i class="bi bi-eye"></i> Aperçu — ' + esc(r.teamLabel) + ', ' + period(r.from, r.to) +
                (r.periodDetected ? ' <span class="tpf-pill ok">période lue dans le fichier</span>' : "") + '</h6>' +
                '<div class="tpf-pills"><span class="tpf-pill">' + r.lines.length + ' agent(s) lu(s)</span><span class="tpf-pill ok">' + r.matched + ' rattaché(s)</span>' +
                (r.unmatched ? '<span class="tpf-pill ko">' + r.unmatched + ' non trouvé(s)</span>' : "") + '</div></div>' +
                '<div class="d-flex gap-2"><button type="button" class="btn btn-outline-secondary btn-sm" data-cancel>Annuler</button>' +
                '<button type="button" class="btn btn-success btn-sm" data-save' + (r.from ? "" : " disabled") + '><i class="bi bi-check2-circle"></i> Enregistrer ces performances</button></div></div>' +
                (r.from ? "" : '<div class="tpf-msg error">Indiquez la période (du … au …) ci-dessus puis relancez l\'analyse.</div>') +
                '<div class="tpf-cols"><small>Colonnes reconnues :</small>' + cols + '</div>' +
                (r.missingFields.length ? '<div class="tpf-cols"><small>Absents du fichier :</small>' + r.missingFields.map(function (m) { return '<span class="tpf-map muted">' + esc(m) + '</span>'; }).join("") + '</div>' : "") +
                (r.ignoredColumns.length ? '<div class="tpf-cols"><small>Colonnes ignorées :</small>' + r.ignoredColumns.map(function (m) { return '<span class="tpf-map muted">' + esc(m) + '</span>'; }).join("") + '</div>' : "") +
                table(r.fields, r.lines, null, { showMatch: true }) + '</div>';
            box.querySelector("[data-cancel]").addEventListener("click", function () { state.preview = null; box.innerHTML = ""; });
            box.querySelector("[data-save]").addEventListener("click", function () {
                var btn = this; btn.disabled = true;
                send(false).then(function (res) {
                    box.innerHTML = '<div class="tpf-msg ok"><i class="bi bi-check-circle-fill"></i> ' + res.lines.length + ' ligne(s) enregistrée(s) pour ' + esc(res.teamLabel) + ', ' + period(res.from, res.to) +
                        (res.replaced ? " (remplace l'import précédent de cette semaine)" : "") + '. Chaque agent rattaché voit maintenant ses chiffres dans son portail et a reçu une notification.</div>';
                    loadSheet(res.from + "|" + res.to);
                }).catch(function (e) { btn.disabled = false; alert(e.message); });
            });
        }

        function loadSheet(select) {
            if (!state.team) return;
            var sel = q("[data-periods]"), key = select || sel.value || "";
            var p = key ? key.split("|") : [];
            q("[data-sheet-team]").textContent = "— " + state.team.label;
            getJson("/api/team-perf-files/sheet?team=" + state.team.code + (p[0] ? "&from=" + p[0] + "&to=" + p[1] : "")).then(function (s) {
                sel.innerHTML = s.periods.length ? s.periods.map(function (x) {
                    return '<option value="' + x.from + "|" + x.to + '" data-batch="' + esc(x.batchId || "") + '"' + (x.from === s.from ? " selected" : "") + '>' + period(x.from, x.to) + ' (' + x.agents + ')</option>';
                }).join("") : '<option value="">Aucun import</option>';
                q("[data-delete]").style.display = s.periods.length ? "" : "none";
                q("[data-sheet-sub]").textContent = s.from ? s.rows.length + " agent(s) · " + period(s.from, s.to) : "Aucun fichier importé pour cette équipe.";
                q("[data-sheet]").innerHTML = s.rows.length ? table(s.fields, s.rows, s.totals, { showMatch: true })
                    : '<div class="tpf-empty"><i class="bi bi-inbox"></i> Importez le premier fichier de cette équipe ci-dessus.</div>';
            }).catch(function (e) { q("[data-sheet]").innerHTML = '<div class="tpf-msg error">' + esc(e.message) + '</div>'; });
        }
        q("[data-periods]").addEventListener("change", function () { loadSheet(); });
        q("[data-delete]").addEventListener("click", function () {
            var opt = q("[data-periods]").selectedOptions[0];
            if (!opt || !opt.getAttribute("data-batch") || !confirm("Supprimer les performances importées pour cette semaine ?")) return;
            fetch("/api/team-perf-files/batches/" + encodeURIComponent(opt.getAttribute("data-batch")), { method: "DELETE", credentials: "same-origin" })
                .then(function (res) { return res.ok ? null : errorOf(res); })
                .then(function () { q("[data-periods]").innerHTML = ""; loadSheet(""); })
                .catch(function (e) { alert(e.message); });
        });

        getJson("/api/team-perf-files/catalog").then(function (c) {
            state.catalog = c;
            if (!c.length) { root.innerHTML = '<div class="tpf-msg error">Aucune équipe à importer pour votre profil.</div>'; return; }
            selectTeam(c.some(function (t) { return t.code === "INBOUND_MAIL"; }) ? "INBOUND_MAIL" : c[0].code);
        }).catch(function (e) { root.innerHTML = '<div class="tpf-msg error">' + esc(e.message) + '</div>'; });
    }

    // ───────────── Agent ─────────────

    function mountMine(root, opts) {
        opts = opts || {};
        var shown = null, idx = 0;
        load();
        // Mise à jour automatique : un nouvel import de la QA ou du Team Leader apparaît sans recharger la page.
        setInterval(function () { if (!document.hidden) load(); }, 5 * 60 * 1000);

        function load() {
        getJson("/api/team-perf-files/me").then(function (p) {
            var sig = JSON.stringify(p.weeks && p.weeks.map(function (w) { return [w.from, w.values]; }));
            if (sig === shown) return;
            shown = sig;
            if (opts.onData) opts.onData(p);
            if (!p.weeks || !p.weeks.length) {
                if (opts.emptyHtml) { root.style.display = ""; root.innerHTML = opts.emptyHtml; } else root.style.display = "none";
                return;
            }
            root.style.display = "";
            idx = 0;
            function render() {
                var w = p.weeks[idx], lv = LEVELS[w.level] || null;
                var prod = w.values.productivity;
                var deg = prod == null ? 0 : Math.min(100, prod) * 3.6;
                var tiles = p.fields.filter(function (f) { return f.key !== "productivity" && w.values[f.key] != null && w.values[f.key] !== ""; }).map(function (f) {
                    var avg = w.teamAverages[f.key];
                    var cmp = "";
                    if (avg != null && f.unit !== "TEXT" && f.key !== "targetPerDay" && f.key !== "daysWorked") {
                        var better = f.higherIsBetter ? w.values[f.key] >= avg : w.values[f.key] <= avg;
                        cmp = '<small class="' + (better ? "up" : "down") + '"><i class="bi ' + (better ? "bi-arrow-up-right" : "bi-arrow-down-right") + '"></i> moy. équipe ' + fmt(avg, f.unit) + '</small>';
                    }
                    return '<div class="tpf-tile"><span>' + esc(f.label) + '</span><b>' + fmt(w.values[f.key], f.unit) + '</b>' + cmp + '</div>';
                }).join("");
                var trend = p.weeks.slice(0, 8).reverse().map(function (x) {
                    var v = x.values.productivity;
                    return '<div class="tpf-bar' + levelCls(x.level) + (x === w ? " on" : "") + '" title="' + period(x.from, x.to) + ' : ' + fmt(v, "PCT") + '">' +
                        '<span style="height:' + Math.max(6, Math.min(100, (v || 0) / 1.5)) + '%"></span><small>' + fmtDate(x.from) + '</small></div>';
                }).join("");
                root.innerHTML = '<div class="tpf-mine">' +
                    '<div class="tpf-mine-head"><div><h5><i class="bi bi-speedometer2"></i> Mes performances — ' + esc(p.teamLabel) + '</h5>' +
                    '<small>Rapport hebdo importé par la QA · ' + period(w.from, w.to) + '</small></div>' +
                    '<select class="form-select form-select-sm" data-week>' + p.weeks.map(function (x, i) {
                        return '<option value="' + i + '"' + (i === idx ? " selected" : "") + '>Semaine ' + period(x.from, x.to) + '</option>';
                    }).join("") + '</select></div>' +
                    '<div class="tpf-mine-body">' +
                    '<div class="tpf-ring' + levelCls(w.level) + '" style="--deg:' + deg + 'deg"><div><b>' + fmt(prod, "PCT") + '</b><small>Productivité</small></div></div>' +
                    '<div class="tpf-mine-status">' + (lv ? '<span class="tpf-status' + levelCls(w.level) + '"><i class="bi ' + lv.icon + '"></i> ' + lv.label + '</span>' : "") +
                    (w.rank ? '<div class="tpf-rank"><b>' + w.rank + '<sup>' + (w.rank === 1 ? "er" : "e") + '</sup></b> sur ' + w.teamSize + ' agents de l\'équipe</div>' : "") +
                    (w.values.avgPerDay != null && w.values.targetPerDay != null ? '<div class="tpf-target">' + fmt(w.values.avgPerDay, "RATE") + ' activités / jour pour un objectif de ' + fmt(w.values.targetPerDay, "RATE") + '</div>' : "") +
                    '</div>' +
                    (p.weeks.length > 1 ? '<div class="tpf-trend">' + trend + '</div>' : "") +
                    '</div><div class="tpf-tiles">' + tiles + '</div></div>';
                root.querySelector("[data-week]").addEventListener("change", function () { idx = Number(this.value); render(); });
            }
            render();
        }).catch(function () { if (!opts.emptyHtml) root.style.display = "none"; });
        }
    }

    return { mountImport: mountImport, mountMine: mountMine, table: table };
})();
