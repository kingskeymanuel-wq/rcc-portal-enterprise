"use strict";

/** Administration → rattachement des agents d'agence à leur agence (Portail Agence). */
(function () {
    var card = document.getElementById("agencyAssignCard");
    if (!card) return;
    var getJson = RccApi.getJson, sendJson = RccApi.sendJson, esc = RccApi.escapeHtml;
    var branches = [];

    function options(selected) {
        return '<option value="">— Aucune (détacher) —</option>' + branches.map(function (b) {
            return '<option value="' + b.id + '"' + (b.id === selected ? " selected" : "") + '>' + esc(b.name) + '</option>';
        }).join("");
    }

    function load() {
        getJson("/api/agence/assignments").then(function (rows) {
            var box = document.getElementById("agAssignList");
            box.innerHTML = rows.length ? '<table class="table table-sm align-middle mb-0"><thead><tr><th>Agent</th><th>Agence</th><th>Rattaché par</th><th></th></tr></thead><tbody>' +
                rows.map(function (r) {
                    return '<tr><td><b>' + esc(r.name || r.username) + '</b><br><small class="text-muted">' + esc(r.username) + '</small></td>' +
                        '<td><select class="form-select form-select-sm" data-user="' + esc(r.username) + '">' + options(r.branchId) + '</select></td>' +
                        '<td class="text-muted">' + esc(r.assignedBy || "") + '</td>' +
                        '<td><button class="btn btn-sm btn-outline-primary" data-save="' + esc(r.username) + '">Enregistrer</button></td></tr>';
                }).join("") + '</tbody></table>' : '<p class="text-muted mb-0">Aucun agent d\'agence rattaché pour le moment.</p>';
            box.querySelectorAll("[data-save]").forEach(function (btn) {
                btn.addEventListener("click", function () {
                    var u = btn.getAttribute("data-save");
                    var v = box.querySelector('select[data-user="' + u + '"]').value;
                    save(u, v ? Number(v) : null);
                });
            });
        }).catch(function () { card.hidden = true; }); // non-admin : carte masquée
    }

    function save(username, branchId) {
        sendJson("/api/agence/users/" + encodeURIComponent(username) + "/agency", "PUT", { branchId: branchId })
            .then(load).catch(function (e) { alert("Erreur : " + e.message); });
    }

    document.getElementById("agAssignForm").addEventListener("submit", function (e) {
        e.preventDefault();
        var u = document.getElementById("agAssignUser").value.trim();
        var b = document.getElementById("agAssignBranch").value;
        if (!u || !b) { alert("Identifiant et agence obligatoires."); return; }
        save(u, Number(b));
    });

    getJson("/api/bank-branches?country=CI").then(function (list) {
        branches = (list || []).slice().sort(function (a, b) { return (a.name || "").localeCompare(b.name || ""); });
        document.getElementById("agAssignBranch").innerHTML = '<option value="">Choisir…</option>' + branches.map(function (b) {
            return '<option value="' + b.id + '">' + esc(b.name) + '</option>';
        }).join("");
        load();
    }).catch(load);
})();
