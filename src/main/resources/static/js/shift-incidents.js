"use strict";

/**
 * Alertes de shift — absences, dépassements de pause, débordements (/api/shift/incidents).
 * Team Leader : justifie chaque incident de son équipe (motif + commentaire). Superviseur, RH, admin : consultation,
 * toutes équipes, avec l'état de la justification.
 *
 *   RccShiftIncidents.mount(el, { canJustify: true })  — liste complète
 *   RccShiftIncidents.count()                          — Promise<nombre d'incidents à justifier> (badges, bandeau)
 */
window.RccShiftIncidents = (function () {
    var esc = function (s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; };
    var TYPES = {
        ABSENCE: { label: "Absence", icon: "bi-person-x-fill", cls: "si-abs" },
        DEBORDEMENT: { label: "Débordement", icon: "bi-hourglass-bottom", cls: "si-over" },
        PAUSE: { label: "Dépassement de pause", icon: "bi-cup-hot-fill", cls: "si-pause" }
    };
    var TEAMS = { INBOUND_VOICE: "Inbound Voix", INBOUND_MAIL: "Inbound Mail", TCHAT: "Réseaux sociaux", RAFIKI: "Rafiki", CIB: "CIB",
        OUTBOUND: "Outbound", TELEVENTE: "Télévente", DIGITALISATION: "Digitalisation" };
    var reasons = null, modal = null;

    function getJson(url) { return RccApi.getJson(url); }
    function fmtMin(m) { return m < 60 ? m + " min" : Math.floor(m / 60) + " h " + String(m % 60).padStart(2, "0"); }
    function fmtDate(iso) { return new Date(iso + "T00:00:00").toLocaleDateString("fr-FR", { weekday: "short", day: "2-digit", month: "2-digit" }); }
    function iso(d) { return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }

    function count() {
        return getJson("/api/shift/incidents").then(function (r) { return r.toJustify || 0; }).catch(function () { return 0; });
    }

    function mount(root, opts) {
        opts = opts || {};
        var st = { days: 7, filter: "todo", type: "", team: "", data: null };
        root.innerHTML = '<div class="si">' +
            '<div class="si-head"><div><h6 class="mb-0"><i class="bi bi-bell-fill"></i> Alertes de shift</h6>' +
            '<small class="text-muted">' + (opts.canJustify ? "Justifiez chaque absence, dépassement de pause et débordement de vos agents." :
                "Absences, dépassements de pause et débordements de toutes les équipes, avec la justification du Team Leader.") + '</small></div>' +
            '<div class="si-tools"><select class="form-select form-select-sm" data-si="days"><option value="1">Aujourd\'hui</option><option value="7" selected>7 derniers jours</option><option value="30">30 derniers jours</option></select>' +
            (opts.teamFilter ? '<select class="form-select form-select-sm" data-si="team"><option value="">Toutes les équipes</option>' +
                Object.keys(TEAMS).map(function (k) { return '<option value="' + k + '">' + TEAMS[k] + '</option>'; }).join("") + '</select>' : '') +
            '</div></div><div class="si-kpis"></div><div class="si-filters"></div><div class="si-list"><div class="text-muted small p-3">Chargement…</div></div></div>';

        function load() {
            var to = new Date(), from = new Date(); from.setDate(to.getDate() - (st.days - 1));
            root.querySelector(".si-list").innerHTML = '<div class="text-muted small p-3"><span class="spinner-border spinner-border-sm"></span> Chargement…</div>';
            getJson("/api/shift/incidents?from=" + iso(from) + "&to=" + iso(to) + (st.team ? "&team=" + st.team : "")).then(function (r) {
                st.data = r; draw();
                if (opts.onCount) opts.onCount(r.toJustify || 0);
            }).catch(function (e) { root.querySelector(".si-list").innerHTML = '<div class="alert alert-danger small">' + esc(e.message) + '</div>'; });
        }

        function draw() {
            var r = st.data, list = r.incidents.filter(function (i) {
                if (st.type && i.type !== st.type) return false;
                if (st.filter === "todo") return !i.justification;
                if (st.filter === "done") return !!i.justification;
                return true;
            });
            root.querySelector(".si-kpis").innerHTML =
                kpi("todo", "bi-exclamation-octagon-fill", r.toJustify, "à justifier", r.toJustify ? "si-k-alert" : "si-k-ok") +
                Object.keys(TYPES).map(function (t) { return kpi("t:" + t, TYPES[t].icon, r.byType[t] || 0, TYPES[t].label.toLowerCase(), TYPES[t].cls); }).join("");
            root.querySelector(".si-filters").innerHTML = ["todo:À justifier", "done:Justifiés", "all:Tous"].map(function (x) {
                var p = x.split(":"); return '<button type="button" class="' + (st.filter === p[0] ? "on" : "") + '" data-si-filter="' + p[0] + '">' + p[1] + '</button>';
            }).join("") + (st.type ? '<button type="button" class="on" data-si-type="">' + esc(TYPES[st.type].label) + ' <i class="bi bi-x"></i></button>' : '');
            root.querySelector(".si-list").innerHTML = list.length ? list.map(row).join("") :
                '<div class="si-empty"><i class="bi bi-check2-circle"></i> ' + (st.filter === "todo" ? "Aucun incident à justifier sur la période." : "Aucun incident.") + '</div>';
        }

        function kpi(key, icon, n, label, cls) {
            return '<button type="button" class="si-kpi ' + cls + '" data-si-kpi="' + key + '"><i class="bi ' + icon + '"></i><b>' + n + '</b><span>' + esc(label) + '</span></button>';
        }

        function row(i) {
            var t = TYPES[i.type], j = i.justification;
            var status = j ? '<div class="si-just"><i class="bi bi-check-circle-fill"></i> <b>' + esc(j.reason) + '</b><span>' + esc(j.comment) + '</span><small>par ' + esc(j.by) +
                    (j.at ? " · " + new Date(j.at).toLocaleString("fr-FR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }) : "") + '</small></div>'
                : i.ongoing ? '<span class="si-wait"><i class="bi bi-broadcast"></i> En cours</span>'
                : opts.canJustify ? '<button type="button" class="btn btn-sm btn-primary" data-si-justify="' + esc(i.key) + '"><i class="bi bi-pencil-square"></i> Justifier</button>'
                : '<span class="si-wait"><i class="bi bi-hourglass-split"></i> En attente du Team Leader</span>';
            return '<div class="si-row ' + (j ? "done" : "") + '"><span class="si-type ' + t.cls + '"><i class="bi ' + t.icon + '"></i> ' + t.label + (i.minutes ? '<b>+' + fmtMin(i.minutes) + '</b>' : '') + '</span>' +
                '<div class="si-who"><b>' + esc(i.name) + '</b><small>' + esc(fmtDate(i.date)) + (i.team ? " · " + esc(TEAMS[i.team] || i.team) : "") + '</small><span>' + esc(i.detail) + '</span></div>' +
                '<div class="si-state">' + (j && opts.canJustify ? status + '<button type="button" class="btn btn-sm btn-link" data-si-justify="' + esc(i.key) + '">Modifier</button>' : status) + '</div></div>';
        }

        function openJustify(key) {
            var i = st.data.incidents.filter(function (x) { return x.key === key; })[0];
            if (!i) return;
            (reasons ? Promise.resolve(reasons) : getJson("/api/shift/incidents/reasons").then(function (r) { reasons = r; return r; })).then(function (rs) {
                if (!modal) {
                    var el = document.createElement("div");
                    el.className = "modal fade"; el.tabIndex = -1;
                    el.innerHTML = '<div class="modal-dialog modal-dialog-centered"><div class="modal-content"><div class="modal-header"><h6 class="modal-title"><i class="bi bi-pencil-square"></i> Justifier l\'incident</h6>' +
                        '<button type="button" class="btn-close" data-bs-dismiss="modal"></button></div><div class="modal-body"></div><div class="modal-footer">' +
                        '<button type="button" class="btn btn-light" data-bs-dismiss="modal">Annuler</button><button type="button" class="btn btn-primary" data-si-save><i class="bi bi-check2"></i> Enregistrer</button></div></div></div>';
                    document.body.appendChild(el);
                    modal = { el: el, bs: new bootstrap.Modal(el) };
                }
                var t = TYPES[i.type], j = i.justification || {};
                modal.el.querySelector(".modal-body").innerHTML =
                    '<div class="si-row mb-3"><span class="si-type ' + t.cls + '"><i class="bi ' + t.icon + '"></i> ' + t.label + (i.minutes ? '<b>+' + fmtMin(i.minutes) + '</b>' : '') + '</span>' +
                    '<div class="si-who"><b>' + esc(i.name) + '</b><small>' + esc(fmtDate(i.date)) + '</small><span>' + esc(i.detail) + '</span></div></div>' +
                    '<label class="form-label small fw-semibold">Motif</label><select class="form-select mb-2" data-si-reason><option value="">— Choisir —</option>' +
                    rs.map(function (x) { return '<option' + (x === j.reason ? " selected" : "") + '>' + esc(x) + '</option>'; }).join("") + '</select>' +
                    '<label class="form-label small fw-semibold">Justification</label><textarea class="form-control" rows="3" maxlength="1000" data-si-comment placeholder="Ce qui s\'est passé, échange avec l\'agent, pièce fournie…">' + esc(j.comment || "") + '</textarea>' +
                    '<div class="small text-danger mt-2" data-si-err></div>';
                var save = modal.el.querySelector("[data-si-save]");
                save.onclick = function () {
                    var reason = modal.el.querySelector("[data-si-reason]").value, comment = modal.el.querySelector("[data-si-comment]").value.trim();
                    var err = modal.el.querySelector("[data-si-err]");
                    if (!reason) { err.textContent = "Choisissez un motif."; return; }
                    if (comment.length < 5) { err.textContent = "Précisez la justification (5 caractères minimum)."; return; }
                    save.disabled = true;
                    RccApi.sendJson("/api/shift/incidents/justify", "POST", { username: i.username, date: i.date, type: i.type, reason: reason, comment: comment })
                        .then(function () { modal.bs.hide(); load(); })
                        .catch(function (e) { err.textContent = e.message; })
                        .then(function () { save.disabled = false; });
                };
                modal.bs.show();
            });
        }

        root.addEventListener("click", function (e) {
            var b = e.target.closest("button");
            if (!b || !root.contains(b)) return;
            if (b.dataset.siFilter) { st.filter = b.dataset.siFilter; draw(); }
            else if (b.dataset.siType != null) { st.type = b.dataset.siType; draw(); }
            else if (b.dataset.siKpi) {
                var k = b.dataset.siKpi;
                if (k === "todo") { st.filter = "todo"; st.type = ""; } else { st.type = k.slice(2); st.filter = "all"; }
                draw();
            } else if (b.dataset.siJustify) openJustify(b.dataset.siJustify);
        });
        root.addEventListener("change", function (e) {
            if (e.target.dataset.si === "days") { st.days = Number(e.target.value); load(); }
            if (e.target.dataset.si === "team") { st.team = e.target.value; load(); }
        });
        load();
        return { reload: load };
    }

    return { mount: mount, count: count };
})();
