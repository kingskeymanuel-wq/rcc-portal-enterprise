"use strict";

/**
 * Administration → Équipes & accès → « Horaires des shifts » : le catalogue M, M2, M3, M4, A, N… (dbo.SHIFT_CODES).
 * Chaque ligne se modifie sur place ; « Enregistrer » écrit en base et, si coché, met aussi à jour les plannings
 * déjà saisis avec ce code à partir de la date choisie. Un nouveau shift s'ajoute avec la dernière ligne.
 */
(function () {
    var esc = RccApi.escapeHtml;
    var root;

    function today() {
        var d = new Date();
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }

    function rowHtml(s, isNew) {
        return '<tr data-code="' + esc(s.code || "") + '"' + (isNew ? ' class="adm-new-shift"' : '') + '>' +
            '<td>' + (isNew ? '<input class="form-control form-control-sm" name="code" placeholder="ex. M5" maxlength="6" style="width:80px">' : '<b>' + esc(s.code) + '</b>') + '</td>' +
            '<td><input class="form-control form-control-sm" name="label" value="' + esc(s.label || "") + '" placeholder="auto (ex. Matin 07h-16h)"></td>' +
            '<td><input type="time" class="form-control form-control-sm" name="start" value="' + esc(s.start || "") + '"></td>' +
            '<td><input type="time" class="form-control form-control-sm" name="end" value="' + esc(s.end || "") + '"></td>' +
            '<td><input type="color" class="form-control form-control-sm form-control-color" name="color" value="' + esc(s.color || "#8FB8EC") + '"></td>' +
            '<td><input type="number" class="form-control form-control-sm" name="sortOrder" value="' + esc(String(s.sortOrder || "")) + '" style="width:70px"></td>' +
            '<td class="text-center"><input type="checkbox" class="form-check-input" name="active"' + (s.active !== false ? " checked" : "") + '></td>' +
            '<td>' + (s.overnight ? '<span class="badge text-bg-dark" title="Se termine le lendemain">nuit</span>' : '') + '</td>' +
            '<td><button type="button" class="btn btn-sm ' + (isNew ? "btn-success" : "btn-primary") + '" data-save>' + (isNew ? "Ajouter" : "Enregistrer") + '</button></td></tr>';
    }

    function load() {
        RccApi.getJson("/api/shift-codes?all=true").then(function (list) {
            root.querySelector("tbody").innerHTML = list.map(function (s) { return rowHtml(s, false); }).join("") + rowHtml({}, true);
        }).catch(function (e) { root.querySelector("tbody").innerHTML = '<tr><td colspan="9" class="text-danger">' + esc(e.message) + '</td></tr>'; });
    }

    function save(tr) {
        var v = function (n) { var el = tr.querySelector('[name="' + n + '"]'); return el ? (el.type === "checkbox" ? el.checked : el.value.trim()) : null; };
        var code = tr.classList.contains("adm-new-shift") ? v("code") : tr.getAttribute("data-code");
        if (!code) { alert("Indiquez le code du shift (ex. M5)."); return; }
        if (!v("start") || !v("end")) { alert("Indiquez l'heure de début et l'heure de fin."); return; }
        var apply = root.querySelector("[name=applyToPlanned]").checked;
        var body = { label: v("label"), start: v("start"), end: v("end"), color: v("color"), sortOrder: v("sortOrder") ? Number(v("sortOrder")) : null,
            active: v("active"), applyToPlanned: apply, applyFrom: root.querySelector("[name=applyFrom]").value || null };
        var btn = tr.querySelector("[data-save]");
        btn.disabled = true;
        fetch("/api/admin/shift-codes/" + encodeURIComponent(code), { method: "PUT", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { var m = t; try { m = JSON.parse(t).error.message; } catch (e) { /* texte */ } throw new Error(m || "HTTP " + res.status); });
                return res.json();
            })
            .then(function (r) {
                var f = root.querySelector(".adm-shift-flash");
                f.className = "adm-shift-flash alert alert-success small py-2";
                f.textContent = "Shift " + r.shift.code + " enregistré en base : " + r.shift.label + " (" + r.shift.start + "–" + r.shift.end + ")" +
                    (apply ? " — " + r.planningUpdated + " jour(s) de planning mis à jour." : ".");
                load();
            })
            .catch(function (e) {
                btn.disabled = false;
                var f = root.querySelector(".adm-shift-flash");
                f.className = "adm-shift-flash alert alert-danger small py-2";
                f.textContent = e.message;
            });
    }

    function mount(el) {
        root = el;
        root.innerHTML = '<div class="card dashboard-card shadow-sm mb-4"><div class="card-header"><i class="bi bi-clock-history"></i> Horaires des shifts</div><div class="card-body">' +
            '<p class="text-muted small mb-2">Codes utilisés dans les plannings (import, planning du Team Leader, export Excel, suivi de présence). Modifiez un horaire puis « Enregistrer » : c\'est écrit en base aussitôt.</p>' +
            '<div class="d-flex flex-wrap align-items-center gap-2 mb-2 small"><label class="form-check mb-0"><input type="checkbox" class="form-check-input" name="applyToPlanned" checked> ' +
            'Mettre aussi à jour les plannings déjà saisis avec ce code à partir du</label><input type="date" class="form-control form-control-sm" name="applyFrom" style="width:auto" value="' + today() + '"></div>' +
            '<div class="adm-shift-flash"></div>' +
            '<div class="table-responsive"><table class="table table-sm align-middle mb-0"><thead><tr><th>Code</th><th>Libellé</th><th>Début</th><th>Fin</th><th>Couleur</th><th>Ordre</th><th>Actif</th><th></th><th></th></tr></thead>' +
            '<tbody><tr><td colspan="9" class="text-muted">Chargement…</td></tr></tbody></table></div></div></div>';
        root.addEventListener("click", function (e) {
            var b = e.target.closest("[data-save]");
            if (b) save(b.closest("tr"));
        });
        load();
    }

    document.addEventListener("DOMContentLoaded", function () {
        var el = document.getElementById("adminShiftCodes");
        if (!el) return;
        window.RccSession.init().then(function (s) { if (s && s.profile === "ADMIN") mount(el); else el.style.display = "none"; });
    });
})();
