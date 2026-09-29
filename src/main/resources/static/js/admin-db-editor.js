"use strict";

/**
 * Administration — « Base de données » : les tables des comptes éditées directement dans le portail,
 * sans SQL Server Management Studio (/api/admin/data). Onglets USERS, USER_ROLES, USER_SERVICES, ROLES, SERVICES.
 *  - Cliquer une cellule pour la modifier : Entrée ou clic ailleurs enregistre en base, Échap annule.
 *  - Liaisons : nom de la personne et du rôle/service à côté des ID ; ajout d'un rôle/service à plusieurs personnes.
 *  - Chaque écriture est en base aussitôt, tracée dans le journal, et resynchronise organigramme et annuaire.
 */
(function () {
    var esc = RccApi.escapeHtml;
    var st = { tables: [], table: "USERS", q: "", page: 0, size: 100, sort: null, desc: false, data: null, options: null, form: false };
    var root;

    function $(s) { return root.querySelector(s); }

    function api(method, url, body) {
        return fetch(url, { method: method, credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { var m = t; try { m = JSON.parse(t).error.message; } catch (e) { /* texte brut */ } throw new Error(m || "HTTP " + res.status); });
                return res.status === 204 ? null : res.json();
            });
    }

    function flash(msg, ok) {
        var f = $(".dbx-flash");
        f.textContent = msg;
        f.className = "dbx-flash show " + (ok ? "ok" : "ko");
        clearTimeout(flash.t);
        flash.t = setTimeout(function () { f.className = "dbx-flash"; }, ok ? 3500 : 8000);
    }

    function changed() {
        document.dispatchEvent(new CustomEvent("rcc:users-changed", { detail: { source: "data" } }));
    }

    function isLink() { return st.table === "USER_ROLES" || st.table === "USER_SERVICES"; }

    function cellText(v, col) {
        if (v === null || v === undefined) return '<i class="dbx-null">NULL</i>';
        if (col.type === "bit") return v === true || v === 1 ? "True" : "False";
        return esc(String(v));
    }

    function render() {
        var d = st.data;
        $(".dbx-tabs").innerHTML = st.tables.map(function (t) {
            return '<button type="button" data-tab="' + esc(t.name) + '"' + (t.name === st.table ? ' class="on"' : '') + '>' + esc(t.title) + ' <small>dbo.' + esc(t.name) + '</small></button>';
        }).join("");
        $(".dbx-clean").style.display = isLink() ? "" : "none";
        renderForm();
        if (!d) return;
        var cols = d.columns;
        var pages = Math.max(1, Math.ceil(d.total / d.size));
        $(".dbx-info").textContent = d.total + " ligne(s) · page " + (d.page + 1) + " / " + pages;
        $(".dbx-prev").disabled = d.page <= 0;
        $(".dbx-next").disabled = d.page + 1 >= pages;
        $(".dbx-grid").innerHTML = '<table><thead><tr><th></th>' + cols.map(function (c) {
            var sortable = c.type !== "computed";
            return '<th' + (sortable ? ' data-sort="' + esc(c.name) + '"' : '') + ' class="' + (c.type === "computed" ? "calc" : "") + '" title="' + esc(c.type + (c.maxLength ? "(" + (c.maxLength < 0 ? "max" : c.maxLength) + ")" : "") + (c.nullable ? ", NULL autorisé" : ", obligatoire") + (c.editable ? "" : ", lecture seule")) + '">' +
                esc(c.name) + (st.sort === c.name ? (st.desc ? " ▾" : " ▴") : "") + '</th>';
        }).join("") + '</tr></thead><tbody>' + (d.rows.map(function (r) {
            return '<tr data-id="' + esc(String(r.ID)) + '"><td class="dbx-act"><button type="button" data-del title="Supprimer cette ligne"><i class="bi bi-trash"></i></button></td>' +
                cols.map(function (c) {
                    return '<td data-col="' + esc(c.name) + '" class="' + (c.editable ? "ed" : "ro") + (c.type === "computed" ? " calc" : "") + '">' + cellText(r[c.name], c) + '</td>';
                }).join("") + '</tr>';
        }).join("") || '<tr><td colspan="' + (cols.length + 1) + '" class="dbx-empty">Aucune ligne.</td></tr>') + '</tbody></table>';
    }

    function optionList(list, label) {
        return list.map(function (o) { return '<option value="' + o.id + '">' + esc(label(o)) + '</option>'; }).join("");
    }

    function renderForm() {
        var box = $(".dbx-form");
        if (!st.form) { box.innerHTML = ""; return; }
        var o = st.options || { users: [], roles: [], services: [] };
        var html;
        if (isLink()) {
            var roles = st.table === "USER_ROLES";
            html = '<label>' + (roles ? "Rôle" : "Service") + '<select name="target"><option value="">— choisir —</option>' +
                optionList(roles ? o.roles : o.services, function (x) { return x.name + " (ID " + x.id + ")"; }) + '</select></label>' +
                '<label class="wide">Personnes <input type="search" name="uq" placeholder="Filtrer par nom ou identifiant…"></label>' +
                '<div class="dbx-users">' + o.users.map(function (u) {
                    return '<label data-hay="' + esc(((u.name || "") + " " + (u.username || "")).toLowerCase()) + '"><input type="checkbox" value="' + u.id + '"> ' + esc(u.name || u.username) + ' <small>' + esc(u.username || "") + ' · ID ' + u.id + '</small></label>';
                }).join("") + '</div>';
        } else if (st.table === "USERS") {
            html = ["USERNAME", "NAME", "EMAIL", "AFFILIATE_BRANCH", "GENDER", "ACTIVITY"].map(function (n) {
                return '<label>' + n + (n === "USERNAME" ? " *" : "") + '<input name="' + n + '"></label>';
            }).join("");
        } else {
            html = ["NAME", "DESCRIPTION"].concat(st.table === "SERVICES" ? ["CODE"] : []).map(function (n) {
                return '<label>' + n + (n === "NAME" ? " *" : "") + '<input name="' + n + '"></label>';
            }).join("");
        }
        box.innerHTML = '<form data-new>' + html + '<div class="dbx-form-actions"><button type="submit" class="ah-btn primary"><i class="bi bi-plus-lg"></i> Enregistrer en base</button>' +
            '<button type="button" class="ah-btn" data-cancel>Annuler</button></div></form>';
    }

    function load() {
        var url = "/api/admin/data/" + st.table + "?page=" + st.page + "&size=" + st.size + (st.q ? "&q=" + encodeURIComponent(st.q) : "") +
            (st.sort ? "&sort=" + encodeURIComponent(st.sort) + "&desc=" + st.desc : "");
        return RccApi.getJson(url).then(function (d) { st.data = d; render(); })
            .catch(function (e) { st.data = null; render(); $(".dbx-grid").innerHTML = '<div class="dbx-empty text-danger">' + esc(e.message) + '</div>'; });
    }

    function loadOptions() {
        return RccApi.getJson("/api/admin/data/options").then(function (o) { st.options = o; renderForm(); }).catch(function () { /* formulaire vide */ });
    }

    function colOf(name) { return st.data.columns.filter(function (c) { return c.name === name; })[0]; }

    function editCell(td) {
        if (td.querySelector(".dbx-in")) return;
        var col = colOf(td.getAttribute("data-col")), id = td.parentNode.getAttribute("data-id");
        var row = st.data.rows.filter(function (r) { return String(r.ID) === id; })[0];
        var cur = row[col.name];
        var input;
        if (col.type === "bit") {
            input = document.createElement("select");
            input.innerHTML = (col.nullable ? '<option value="">NULL</option>' : '') + '<option value="true">True</option><option value="false">False</option>';
            input.value = cur === null || cur === undefined ? "" : String(cur === true || cur === 1);
        } else {
            input = document.createElement("input");
            if (col.type === "date") { input.type = "date"; input.value = cur ? String(cur).substring(0, 10) : ""; }
            else input.value = cur === null || cur === undefined ? "" : String(cur);
            if (col.maxLength && col.maxLength > 0) input.maxLength = col.maxLength;
            input.placeholder = col.nullable ? "vide = NULL" : "obligatoire";
        }
        input.className = "dbx-in";
        var before = input.value, done = false;
        td.innerHTML = "";
        td.appendChild(input);
        input.focus();
        function finish(save) {
            if (done) return;
            done = true;
            if (!save || input.value === before) { td.innerHTML = cellText(cur, col); return; }
            td.classList.add("saving");
            api("PATCH", "/api/admin/data/" + st.table + "/" + id, { column: col.name, value: input.value })
                .then(function (fresh) {
                    flash("Enregistré en base : dbo." + st.table + " #" + id + " — " + col.name + ".", true);
                    changed();
                    if (fresh && fresh.ID !== undefined) { st.data.rows[st.data.rows.indexOf(row)] = fresh; render(); } else load();
                })
                .catch(function (e) { td.classList.remove("saving"); td.innerHTML = cellText(cur, col); flash(e.message, false); });
        }
        input.addEventListener("keydown", function (e) {
            if (e.key === "Enter") { e.preventDefault(); finish(true); }
            if (e.key === "Escape") finish(false);
        });
        input.addEventListener("blur", function () { finish(true); });
        if (col.type === "bit") input.addEventListener("change", function () { finish(true); });
    }

    function submitNew(form) {
        var body = {};
        if (isLink()) {
            var roles = st.table === "USER_ROLES";
            body[roles ? "ROLES_ID" : "SERVICE_ID"] = form.elements.target.value;
            body.USER_IDS = Array.prototype.map.call(form.querySelectorAll(".dbx-users input:checked"), function (i) { return Number(i.value); });
        } else {
            Array.prototype.forEach.call(form.querySelectorAll("input[name]"), function (i) { if (i.value.trim()) body[i.name] = i.value.trim(); });
        }
        var btn = form.querySelector("[type=submit]");
        btn.disabled = true;
        api("POST", "/api/admin/data/" + st.table, body)
            .then(function (r) {
                flash(r && r.added !== undefined ? r.added + " liaison(s) enregistrée(s) en base." : "Nouvelle ligne enregistrée en base (ID " + (r && r.ID) + ").", true);
                st.form = false;
                changed();
                loadOptions();
                return load();
            })
            .catch(function (e) { btn.disabled = false; flash(e.message, false); });
    }

    function mount(el) {
        root = el;
        root.innerHTML = '<div class="dbx-head"><div><h5 class="mb-1"><i class="bi bi-database-gear"></i> Base de données — comptes, rôles et services</h5>' +
            '<small class="text-muted">Les tables dbo.USERS, USER_ROLES, USER_SERVICES, ROLES et SERVICES, modifiables ici sans SQL Server : cliquez une cellule, ' +
            'Entrée enregistre en base, Échap annule. Mot de passe et secret MFA ne sont jamais affichés.</small></div></div>' +
            '<div class="dbx-tabs"></div>' +
            '<div class="dbx-tools"><div class="ah-search"><i class="bi bi-search"></i><input type="search" placeholder="Rechercher (nom, identifiant, ID, rôle…)"></div>' +
            '<button type="button" class="ah-btn primary dbx-add"><i class="bi bi-plus-lg"></i> Nouvelle ligne</button>' +
            '<button type="button" class="ah-btn dbx-clean" title="Supprime les liaisons en double (même personne, même rôle/service) et celles qui pointent vers un compte ou un rôle supprimé"><i class="bi bi-magic"></i> Nettoyer doublons</button>' +
            '<button type="button" class="ah-btn dbx-reload"><i class="bi bi-arrow-clockwise"></i></button>' +
            '<span class="dbx-info"></span><button type="button" class="ah-btn dbx-prev">‹</button><button type="button" class="ah-btn dbx-next">›</button></div>' +
            '<div class="dbx-flash"></div><div class="dbx-form"></div><div class="dbx-grid"><div class="dbx-empty">Chargement…</div></div>';
        var qTimer;
        $(".dbx-tools input").addEventListener("input", function () {
            var v = this.value.trim();
            clearTimeout(qTimer);
            qTimer = setTimeout(function () { st.q = v; st.page = 0; load(); }, 300);
        });
        $(".dbx-add").addEventListener("click", function () { st.form = !st.form; renderForm(); if (st.form && !st.options) loadOptions(); });
        $(".dbx-reload").addEventListener("click", load);
        $(".dbx-prev").addEventListener("click", function () { st.page--; load(); });
        $(".dbx-next").addEventListener("click", function () { st.page++; load(); });
        $(".dbx-clean").addEventListener("click", function () {
            if (!confirm("Supprimer de la base les liaisons en double et celles qui pointent vers un compte, un rôle ou un service supprimé ?")) return;
            api("POST", "/api/admin/data/links/clean").then(function (r) { flash(r.removed + " liaison(s) supprimée(s) de la base.", true); changed(); load(); })
                .catch(function (e) { flash(e.message, false); });
        });
        root.addEventListener("click", function (e) {
            var tab = e.target.closest("[data-tab]");
            if (tab) { st.table = tab.getAttribute("data-tab"); st.page = 0; st.sort = null; st.desc = false; st.form = false; st.data = null; render(); load(); return; }
            var th = e.target.closest("[data-sort]");
            if (th) { var s = th.getAttribute("data-sort"); st.desc = st.sort === s ? !st.desc : false; st.sort = s; load(); return; }
            if (e.target.closest("[data-cancel]")) { st.form = false; renderForm(); return; }
            var del = e.target.closest("[data-del]");
            if (del) {
                var id = del.closest("tr").getAttribute("data-id");
                var warn = st.table === "USERS" ? "\n\nLe compte est supprimé définitivement (ses rôles, services et sessions aussi). S'il a un historique, la base refusera : désactivez-le plutôt (ACCOUNT_ENABLED = False)." : "";
                if (!confirm("Supprimer la ligne " + id + " de dbo." + st.table + " ?" + warn)) return;
                api("DELETE", "/api/admin/data/" + st.table + "/" + id).then(function () { flash("Ligne " + id + " supprimée de la base.", true); changed(); load(); })
                    .catch(function (err) { flash(err.message, false); });
                return;
            }
            var td = e.target.closest("td.ed");
            if (td && !e.target.closest(".dbx-in")) editCell(td);
        });
        root.addEventListener("input", function (e) {
            if (e.target.name !== "uq") return;
            var q = e.target.value.trim().toLowerCase();
            root.querySelectorAll(".dbx-users label").forEach(function (l) { l.style.display = !q || l.getAttribute("data-hay").indexOf(q) !== -1 ? "" : "none"; });
        });
        root.addEventListener("submit", function (e) {
            var f = e.target.closest("[data-new]");
            if (!f) return;
            e.preventDefault();
            submitNew(f);
        });
        // Modification faite dans l'organigramme ou l'annuaire : la table affichée est relue.
        document.addEventListener("rcc:users-changed", function (e) {
            if (e.detail && e.detail.source === "data") return;
            clearTimeout(mount.t);
            mount.t = setTimeout(load, 400);
        });
        RccApi.getJson("/api/admin/data").then(function (t) { st.tables = t; render(); return load(); })
            .catch(function (e) { $(".dbx-grid").innerHTML = '<div class="dbx-empty text-danger">' + esc(e.message) + '</div>'; });
    }

    document.addEventListener("DOMContentLoaded", function () {
        var el = document.getElementById("adminDbEditor");
        if (!el) return;
        window.RccSession.init().then(function (s) { if (s && s.profile === "ADMIN") mount(el); else el.style.display = "none"; });
    });
})();
