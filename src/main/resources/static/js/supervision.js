"use strict";

/**
 * Workflow de supervision (/api/supervision).
 *  - RccSupervision.mountManager(root) : Superviseur (Team Leaders + Head QA) ou Head QA (agents QA, formateurs) —
 *    tableau de bord de l'activité de chaque sous-responsable, missions à confier et à valider (tableau par étape).
 *  - RccSupervision.mountAssignee(root) : « Mes missions » du sous-responsable (prise en charge, rendu, échanges).
 */
window.RccSupervision = (function () {
    var esc = RccApi.escapeHtml;
    var STATUS = {
        A_FAIRE: ["À faire", "bi-circle", "todo"],
        EN_COURS: ["En cours", "bi-play-circle-fill", "doing"],
        A_VALIDER: ["À valider", "bi-hourglass-split", "review"],
        A_REPRENDRE: ["À reprendre", "bi-arrow-counterclockwise", "rework"],
        VALIDEE: ["Validée", "bi-check-circle-fill", "done"],
        ANNULEE: ["Annulée", "bi-x-circle", "cancel"]
    };
    var PRIORITY = { BASSE: "Basse", NORMALE: "Normale", HAUTE: "Haute", URGENTE: "Urgente" };
    var CATEGORIES = ["Planning", "Qualité", "Performance", "Équipe / RH", "Formation", "Reporting", "Autre"];
    var ACTIONS = { CREATION: "a créé la mission", START: "a pris en charge", SUBMIT: "a rendu la mission", VALIDATE: "a validé",
        REWORK: "a demandé une reprise", CANCEL: "a annulé", COMMENT: "a commenté" };
    var LEVEL = { RED: ["À surveiller", "bi-exclamation-octagon-fill"], AMBER: ["À suivre", "bi-exclamation-triangle-fill"], GREEN: ["À jour", "bi-check-circle-fill"] };

    function initials(n) { return String(n || "?").trim().split(/\s+/).slice(0, 2).map(function (x) { return x[0]; }).join("").toUpperCase(); }
    function fmtDate(d) { return d ? new Date(d + (String(d).length === 10 ? "T00:00:00" : "")).toLocaleDateString("fr-FR", { day: "2-digit", month: "short" }) : ""; }
    function ago(iso) {
        if (!iso) return "jamais";
        var min = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
        if (min < 2) return "à l'instant";
        if (min < 60) return "il y a " + min + " min";
        var h = Math.round(min / 60);
        if (h < 24) return "il y a " + h + " h";
        var d = Math.round(h / 24);
        return "il y a " + d + " j";
    }

    // ── Boîte de dialogue partagée ──
    var dlg = null;
    function dialog(title, bodyHtml, okLabel, onOk) {
        if (!dlg) {
            var el = document.createElement("div");
            el.className = "modal fade sp-modal";
            el.tabIndex = -1;
            el.innerHTML = '<div class="modal-dialog modal-dialog-centered modal-dialog-scrollable"><div class="modal-content">' +
                '<div class="modal-header"><h5 class="modal-title"></h5><button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Fermer"></button></div>' +
                '<div class="modal-body"></div><div class="modal-footer"><div class="sp-dlg-error text-danger small me-auto"></div>' +
                '<button type="button" class="btn btn-light" data-bs-dismiss="modal">Fermer</button><button type="button" class="btn btn-primary sp-dlg-ok"></button></div></div></div>';
            document.body.appendChild(el);
            dlg = { el: el, modal: new bootstrap.Modal(el), onOk: null };
            el.querySelector(".sp-dlg-ok").addEventListener("click", function () {
                if (!dlg.onOk) return;
                var btn = this;
                btn.disabled = true;
                el.querySelector(".sp-dlg-error").textContent = "";
                Promise.resolve(dlg.onOk(el.querySelector(".modal-body"))).then(function (close) {
                    if (close !== false) dlg.modal.hide();
                }).catch(function (e) { el.querySelector(".sp-dlg-error").textContent = e.message; })
                    .finally(function () { btn.disabled = false; });
            });
        }
        dlg.el.querySelector(".modal-title").innerHTML = title;
        dlg.el.querySelector(".modal-body").innerHTML = bodyHtml;
        dlg.el.querySelector(".sp-dlg-error").textContent = "";
        var ok = dlg.el.querySelector(".sp-dlg-ok");
        ok.style.display = okLabel ? "" : "none";
        ok.textContent = okLabel || "";
        dlg.onOk = onOk;
        dlg.modal.show();
        return dlg.el.querySelector(".modal-body");
    }

    function commentDialog(title, placeholder, required, send) {
        dialog(title, '<textarea class="form-control sp-comment" rows="4" maxlength="1000" placeholder="' + esc(placeholder) + '"></textarea>', "Envoyer", function (body) {
            var v = body.querySelector(".sp-comment").value.trim();
            if (required && !v) return Promise.reject(new Error("Ce commentaire est obligatoire."));
            return send(v);
        });
    }

    function act(task, action, comment) {
        return RccApi.sendJson("/api/supervision/tasks/" + task.id + "/action", "POST", { action: action, comment: comment || null });
    }

    function showHistory(task) {
        var body = dialog('<i class="bi bi-clock-history"></i> ' + esc(task.title), '<div class="sp-empty">Chargement…</div>', null, null);
        RccApi.getJson("/api/supervision/tasks/" + task.id + "/events").then(function (events) {
            body.innerHTML = (task.details ? '<p class="sp-details">' + esc(task.details) + '</p>' : '') +
                '<ol class="sp-timeline">' + events.map(function (e) {
                    return '<li class="' + esc(e.action.toLowerCase()) + '"><b>' + esc(e.actorName) + '</b> ' + esc(ACTIONS[e.action] || e.action) +
                        '<small>' + new Date(e.at).toLocaleString("fr-FR", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) + '</small>' +
                        (e.comment ? '<p>' + esc(e.comment) + '</p>' : '') + '</li>';
                }).join("") + '</ol>';
        }).catch(function (e) { body.innerHTML = '<div class="text-danger">' + esc(e.message) + '</div>'; });
    }

    function taskCard(t, mode) {
        var s = STATUS[t.status] || [t.status, "bi-dot", "todo"];
        var btns = [];
        if (mode === "manager") {
            if (t.status === "A_VALIDER") {
                btns.push('<button type="button" class="sp-btn ok" data-act="VALIDATE"><i class="bi bi-check2"></i> Valider</button>');
                btns.push('<button type="button" class="sp-btn warn" data-act="REWORK"><i class="bi bi-arrow-counterclockwise"></i> À reprendre</button>');
            }
            if (["A_FAIRE", "EN_COURS", "A_VALIDER", "A_REPRENDRE"].indexOf(t.status) !== -1) btns.push('<button type="button" class="sp-btn ghost" data-act="CANCEL" title="Annuler"><i class="bi bi-x-lg"></i></button>');
        } else {
            if (t.status === "A_FAIRE" || t.status === "A_REPRENDRE") btns.push('<button type="button" class="sp-btn primary" data-act="START"><i class="bi bi-play-fill"></i> Prendre en charge</button>');
            if (["A_FAIRE", "EN_COURS", "A_REPRENDRE"].indexOf(t.status) !== -1) btns.push('<button type="button" class="sp-btn ok" data-act="SUBMIT"><i class="bi bi-send"></i> Rendre</button>');
        }
        btns.push('<button type="button" class="sp-btn ghost" data-act="COMMENT" title="Commenter"><i class="bi bi-chat-left-text"></i></button>');
        btns.push('<button type="button" class="sp-btn ghost" data-act="HISTORY" title="Historique"><i class="bi bi-clock-history"></i></button>');
        var note = t.status === "A_REPRENDRE" && t.managerComment ? '<div class="sp-note warn"><i class="bi bi-arrow-counterclockwise"></i> ' + esc(t.managerComment) + '</div>'
            : t.status === "A_VALIDER" && t.assigneeComment ? '<div class="sp-note"><i class="bi bi-chat-quote"></i> ' + esc(t.assigneeComment) + '</div>' : "";
        return '<article class="sp-task ' + s[2] + (t.overdue ? " overdue" : "") + '" data-id="' + t.id + '">' +
            '<div class="sp-task-top"><span class="sp-prio ' + esc(String(t.priority).toLowerCase()) + '">' + esc(PRIORITY[t.priority] || t.priority) + '</span>' +
            (t.category ? '<span class="sp-cat">' + esc(t.category) + '</span>' : '') +
            (t.dueDate ? '<span class="sp-due' + (t.overdue ? " late" : "") + '"><i class="bi bi-calendar-event"></i> ' + fmtDate(t.dueDate) + '</span>' : '') + '</div>' +
            '<h6>' + esc(t.title) + '</h6>' +
            (mode === "manager" ? '<div class="sp-who"><span class="sp-av sm">' + esc(initials(t.assigneeName)) + '</span>' + esc(t.assigneeName) + '</div>'
                : '<div class="sp-who"><i class="bi bi-person-badge"></i> Confiée par ' + esc(t.managerName) + '</div>') +
            (mode !== "manager" && t.details ? '<p class="sp-details">' + esc(t.details) + '</p>' : '') + note +
            '<div class="sp-task-foot"><span class="sp-status ' + s[2] + '"><i class="bi ' + s[1] + '"></i> ' + s[0] + '</span><div class="sp-actions">' + btns.join("") + '</div></div></article>';
    }

    function wireTaskActions(container, tasks, mode, reload) {
        container.addEventListener("click", function (e) {
            var b = e.target.closest("[data-act]");
            if (!b) return;
            var card = b.closest(".sp-task");
            var t = tasks().filter(function (x) { return String(x.id) === card.getAttribute("data-id"); })[0];
            if (!t) return;
            var a = b.getAttribute("data-act");
            if (a === "HISTORY") return showHistory(t);
            if (a === "COMMENT") return commentDialog('<i class="bi bi-chat-left-text"></i> Commenter', "Votre message…", true, function (v) { return act(t, "COMMENT", v).then(reload); });
            if (a === "SUBMIT") return commentDialog('<i class="bi bi-send"></i> Rendre la mission', "Qu'avez-vous fait ? Résultat, liens, points restants…", true, function (v) { return act(t, "SUBMIT", v).then(reload); });
            if (a === "REWORK") return commentDialog('<i class="bi bi-arrow-counterclockwise"></i> Demander une reprise', "Ce qui doit être repris…", true, function (v) { return act(t, "REWORK", v).then(reload); });
            if (a === "VALIDATE") return commentDialog('<i class="bi bi-check2-circle"></i> Valider la mission', "Commentaire (facultatif)…", false, function (v) { return act(t, "VALIDATE", v).then(reload); });
            if (a === "CANCEL" && !confirm("Annuler la mission « " + t.title + " » ?")) return;
            b.disabled = true;
            act(t, a).then(reload).catch(function (err) { alert(err.message); b.disabled = false; });
        });
    }

    // ═════════════════════════ Responsable ═════════════════════════

    function mountManager(root, opts) {
        opts = opts || {};
        var st = { scope: opts.scope || null, board: null, tasks: [], view: "board", filter: "" };
        root.classList.add("sp-root");
        root.innerHTML =
            '<div class="sp-head"><div><span class="sp-kicker"><i class="bi bi-diagram-3-fill"></i> Workflow de supervision</span>' +
            '<h4 class="sp-title">Mes sous-responsables</h4><p class="sp-sub">Activité réelle de chacun, missions confiées et validations.</p></div>' +
            '<div class="sp-head-actions"><div class="sp-scopes"></div><button type="button" class="sp-new"><i class="bi bi-plus-lg"></i> Nouvelle mission</button></div></div>' +
            '<div class="sp-totals"></div>' +
            '<div class="sp-views"><button type="button" class="on" data-view="board"><i class="bi bi-people"></i> Tableau de bord</button>' +
            '<button type="button" data-view="tasks"><i class="bi bi-kanban"></i> Missions <b class="sp-count-validate"></b></button>' +
            '<span class="sp-updated"></span></div>' +
            '<div class="sp-body"><div class="sp-empty">Chargement…</div></div>';
        var body = root.querySelector(".sp-body");

        function load() {
            var q = st.scope ? "?scope=" + st.scope : "";
            return Promise.all([RccApi.getJson("/api/supervision/board" + q), RccApi.getJson("/api/supervision/tasks" + q)]).then(function (r) {
                st.board = r[0]; st.tasks = r[1]; st.scope = r[0].scope;
                render();
            }).catch(function (e) { body.innerHTML = '<div class="sp-empty text-danger">' + esc(e.message) + '</div>'; });
        }

        function render() {
            var b = st.board;
            root.querySelector(".sp-scopes").innerHTML = b.scopes.length > 1 ? b.scopes.map(function (s) {
                return '<button type="button" data-scope="' + s + '"' + (s === b.scope ? ' class="on"' : '') + '>' + (s === "QA" ? "Équipe QA" : "Team Leaders") + '</button>';
            }).join("") : "";
            root.querySelector(".sp-title").textContent = b.scopeLabel;
            var t = b.totals;
            root.querySelector(".sp-totals").innerHTML = [
                ["people", "bi-people-fill", t.members, "sous-responsables", ""],
                ["red", "bi-exclamation-octagon-fill", t.RED, "à surveiller", "RED"],
                ["amber", "bi-exclamation-triangle-fill", t.AMBER, "à suivre", "AMBER"],
                ["green", "bi-check-circle-fill", t.GREEN, "à jour", "GREEN"],
                ["blue", "bi-list-task", t.openMissions, "missions ouvertes", ""],
                ["violet", "bi-hourglass-split", t.toValidate, "à valider", ""],
                ["late", "bi-alarm-fill", t.overdueMissions, "en retard", ""]
            ].map(function (k) {
                return '<button type="button" class="sp-total ' + k[0] + (k[4] && st.filter === k[4] ? " on" : "") + '"' + (k[4] ? ' data-level="' + k[4] + '"' : '') +
                    '><i class="bi ' + k[1] + '"></i><b>' + k[2] + '</b><span>' + k[3] + '</span></button>';
            }).join("");
            root.querySelector(".sp-count-validate").textContent = t.toValidate || "";
            root.querySelector(".sp-updated").textContent = "Actualisé à " + new Date(b.generatedAt).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
            root.querySelectorAll(".sp-views [data-view]").forEach(function (x) { x.classList.toggle("on", x.getAttribute("data-view") === st.view); });
            if (st.view === "board") renderBoard(); else renderTasks();
        }

        function renderBoard() {
            var members = st.board.members.filter(function (m) { return !st.filter || m.level === st.filter; });
            if (!members.length) { body.innerHTML = '<div class="sp-empty"><i class="bi bi-people"></i> Aucun sous-responsable' + (st.filter ? " dans cette catégorie" : "") + '.</div>'; return; }
            body.innerHTML = '<div class="sp-grid">' + members.map(function (m) {
                var lv = LEVEL[m.level] || LEVEL.GREEN;
                return '<article class="sp-member ' + m.level.toLowerCase() + '" data-user="' + m.userId + '">' +
                    '<header><span class="sp-av">' + esc(initials(m.name)) + '</span><div class="sp-member-id"><b>' + esc(m.name) + '</b>' +
                    '<small>' + esc(m.role) + ' · ' + esc(m.teamLabel) + '</small></div>' +
                    '<span class="sp-level ' + m.level.toLowerCase() + '"><i class="bi ' + lv[1] + '"></i> ' + lv[0] + '</span></header>' +
                    '<div class="sp-login"><i class="bi bi-box-arrow-in-right"></i> Dernière connexion : <b>' + esc(ago(m.lastLogin)) + '</b></div>' +
                    '<div class="sp-inds">' + m.indicators.map(function (i) {
                        return '<div class="sp-ind ' + i.level.toLowerCase() + '"><b>' + esc(i.value) + '</b><span>' + esc(i.label) + '</span></div>';
                    }).join("") + '</div>' +
                    (m.issues.length ? '<ul class="sp-issues">' + m.issues.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join("") + '</ul>'
                        : '<div class="sp-allgood"><i class="bi bi-hand-thumbs-up"></i> Rien à signaler</div>') +
                    '<footer><span class="sp-miss"><i class="bi bi-list-task"></i> ' + m.openMissions + ' mission(s)' +
                    (m.toValidate ? ' · <b class="review">' + m.toValidate + ' à valider</b>' : '') + (m.overdueMissions ? ' · <b class="late">' + m.overdueMissions + ' en retard</b>' : '') + '</span>' +
                    '<button type="button" class="sp-btn primary" data-assign="' + m.userId + '"><i class="bi bi-send-plus"></i> Confier une mission</button></footer></article>';
            }).join("") + '</div>';
        }

        function renderTasks() {
            var cols = [["A_FAIRE", "À faire"], ["EN_COURS", "En cours"], ["A_VALIDER", "À valider"], ["A_REPRENDRE", "À reprendre"], ["DONE", "Terminées (30 j)"]];
            body.innerHTML = st.tasks.length ? '<div class="sp-kanban">' + cols.map(function (c) {
                var list = st.tasks.filter(function (t) { return c[0] === "DONE" ? (t.status === "VALIDEE" || t.status === "ANNULEE") : t.status === c[0]; });
                return '<section class="sp-col ' + c[0].toLowerCase() + '"><h6>' + c[1] + ' <span>' + list.length + '</span></h6>' +
                    (list.map(function (t) { return taskCard(t, "manager"); }).join("") || '<div class="sp-col-empty">—</div>') + '</section>';
            }).join("") + '</div>'
                : '<div class="sp-empty"><i class="bi bi-kanban"></i> Aucune mission pour l\'instant. Confiez la première avec « Nouvelle mission ».</div>';
        }

        function openNew(preselect) {
            var members = st.board.members;
            var today = new Date().toISOString().slice(0, 10);
            dialog('<i class="bi bi-send-plus"></i> Nouvelle mission',
                '<label class="form-label small fw-bold">Responsable(s)</label><div class="sp-pick">' + members.map(function (m) {
                    return '<label><input type="checkbox" value="' + m.userId + '"' + (String(m.userId) === String(preselect) ? " checked" : "") + '><span class="sp-av sm">' + esc(initials(m.name)) +
                        '</span><span>' + esc(m.name) + '<small>' + esc(m.role) + ' · ' + esc(m.teamLabel) + '</small></span></label>';
                }).join("") + '</div>' +
                '<label class="form-label small fw-bold mt-3">Intitulé</label><input class="form-control sp-f-title" maxlength="200" placeholder="Ex. Publier le planning de novembre">' +
                '<label class="form-label small fw-bold mt-3">Consignes</label><textarea class="form-control sp-f-details" rows="3" maxlength="2000" placeholder="Attendus, livrable, points d\'attention…"></textarea>' +
                '<div class="row g-2 mt-1"><div class="col-sm-6"><label class="form-label small fw-bold">Thème</label><select class="form-select sp-f-cat">' +
                CATEGORIES.map(function (c) { return '<option>' + esc(c) + '</option>'; }).join("") + '</select></div>' +
                '<div class="col-sm-6"><label class="form-label small fw-bold">Échéance</label><input type="date" class="form-control sp-f-due" min="' + today + '"></div></div>' +
                '<label class="form-label small fw-bold mt-3">Priorité</label><div class="sp-prio-pick">' + Object.keys(PRIORITY).map(function (p) {
                    return '<label><input type="radio" name="spPrio" value="' + p + '"' + (p === "NORMALE" ? " checked" : "") + '><span class="sp-prio ' + p.toLowerCase() + '">' + PRIORITY[p] + '</span></label>';
                }).join("") + '</div>',
                "Confier la mission", function (b) {
                    var ids = Array.prototype.map.call(b.querySelectorAll(".sp-pick input:checked"), function (i) { return Number(i.value); });
                    var payload = { scope: st.scope, assigneeUserIds: ids, title: b.querySelector(".sp-f-title").value.trim(),
                        details: b.querySelector(".sp-f-details").value.trim(), category: b.querySelector(".sp-f-cat").value,
                        priority: (b.querySelector('input[name="spPrio"]:checked') || {}).value, dueDate: b.querySelector(".sp-f-due").value || null };
                    if (!ids.length) return Promise.reject(new Error("Choisissez au moins un responsable."));
                    if (!payload.title) return Promise.reject(new Error("Indiquez l'intitulé de la mission."));
                    return RccApi.sendJson("/api/supervision/tasks", "POST", payload).then(function () { st.view = "tasks"; return load(); });
                });
        }

        root.addEventListener("click", function (e) {
            var v = e.target.closest("[data-view]");
            if (v) { st.view = v.getAttribute("data-view"); render(); return; }
            var sc = e.target.closest("[data-scope]");
            if (sc) { st.scope = sc.getAttribute("data-scope"); st.filter = ""; load(); return; }
            var lv = e.target.closest("[data-level]");
            if (lv) { st.filter = st.filter === lv.getAttribute("data-level") ? "" : lv.getAttribute("data-level"); st.view = "board"; render(); return; }
            var as = e.target.closest("[data-assign]");
            if (as) { openNew(as.getAttribute("data-assign")); return; }
            if (e.target.closest(".sp-new")) openNew(null);
        });
        wireTaskActions(body, function () { return st.tasks; }, "manager", load);
        setInterval(function () { if (!document.hidden && root.offsetParent !== null) load(); }, 120000);
        return load();
    }

    // ═════════════════════════ Sous-responsable ═════════════════════════

    function mountAssignee(root) {
        var st = { tasks: [] };
        function load() {
            return RccApi.getJson("/api/supervision/tasks/mine").then(function (tasks) {
                st.tasks = tasks || [];
                if (!st.tasks.length) { root.innerHTML = ""; root.hidden = true; return; }
                root.hidden = false;
                root.classList.add("sp-root", "sp-mine");
                var open = st.tasks.filter(function (t) { return ["A_FAIRE", "EN_COURS", "A_REPRENDRE"].indexOf(t.status) !== -1; }).length;
                root.innerHTML = '<div class="sp-head"><div><span class="sp-kicker"><i class="bi bi-inbox-fill"></i> Workflow de supervision</span>' +
                    '<h4 class="sp-title">Mes missions <span class="sp-badge">' + open + ' à traiter</span></h4><p class="sp-sub">Confiées par votre responsable — prenez-les en charge puis rendez-les pour validation.</p></div></div>' +
                    '<div class="sp-mine-list">' + st.tasks.map(function (t) { return taskCard(t, "assignee"); }).join("") + '</div>';
            }).catch(function () { root.hidden = true; });
        }
        wireTaskActions(root, function () { return st.tasks; }, "assignee", load);
        setInterval(function () { if (!document.hidden) load(); }, 120000);
        return load();
    }

    return { mountManager: mountManager, mountAssignee: mountAssignee };
})();
