"use strict";

/**
 * « Point par agence » de l'onglet Disponibilité des cartes : tableau quotidien de la filiale
 * (DATE | AGENCE | CARTE | CODE | TYPE DE CARTE). Les agents voient, agence par agence, si la
 * carte ET le code PIN sont disponibles et quelles gammes sont en stock ; QA colle le tableau
 * reçu tel quel ou saisit une agence. API : /api/card-availability/agencies.
 */
window.CardAgencyReport = (function () {

    var root = null, state = { country: null, canEdit: false, report: null, date: null, search: "", type: "" };
    var editModal = null, pasteModal = null;

    function esc(s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; }

    function request(method, url, body) {
        return fetch(url, {
            method: method, credentials: "same-origin",
            headers: body ? { "Content-Type": "application/json" } : {},
            body: body ? JSON.stringify(body) : undefined
        }).then(function (res) {
            if (res.status === 204) return null;
            return res.text().then(function (t) {
                var data = null;
                try { data = t ? JSON.parse(t) : null; } catch (ignore) {}
                if (!res.ok) return Promise.reject(new Error(data && data.error && data.error.message ? data.error.message : (data && data.message) || ("HTTP " + res.status)));
                return data;
            });
        });
    }

    function frDate(iso, long) {
        if (!iso) return "—";
        var d = new Date(iso + "T00:00:00");
        return long ? d.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long", year: "numeric" }) : d.toLocaleDateString("fr-FR");
    }

    var STATUS = {
        OK: { cls: "ok", icon: "bi-check-circle-fill", label: "OK" },
        FAIBLE: { cls: "low", icon: "bi-exclamation-circle-fill", label: "Stock faible" },
        RUPTURE: { cls: "out", icon: "bi-x-circle-fill", label: "Rupture" }
    };

    function toast(msg, error) {
        var t = document.getElementById("caToast");
        if (!t) { t = document.createElement("div"); t.id = "caToast"; t.className = "ca-toast"; document.body.appendChild(t); }
        t.textContent = msg;
        t.classList.toggle("error", !!error);
        t.classList.add("show");
        clearTimeout(toast.timer);
        toast.timer = setTimeout(function () { t.classList.remove("show"); }, 3000);
    }

    function ensureRoot() {
        if (root) return root;
        var host = document.getElementById("kbCardAvContent");
        if (!host) return null;
        root = document.createElement("section");
        root.className = "ca-report";
        root.innerHTML =
            '<div class="ca-head">' +
            '<div class="ca-head-ic"><i class="bi bi-shop"></i></div>' +
            '<div class="flex-grow-1 min-w-0"><div class="ca-eyebrow">Point par agence</div><h6 class="mb-0" id="caTitle">Disponibilité en agence</h6></div>' +
            '<select class="form-select form-select-sm ca-date" id="caDate" title="Historique des points"></select>' +
            '<button type="button" class="btn btn-sm btn-light ca-qa" id="caPasteBtn"><i class="bi bi-clipboard-plus"></i> Coller le point du jour</button>' +
            '<button type="button" class="btn btn-sm btn-light ca-qa" id="caAddBtn"><i class="bi bi-plus-lg"></i> Agence</button>' +
            '</div>' +
            '<div class="ca-summary" id="caSummary"></div>' +
            '<div class="ca-tools"><div class="ca-search"><i class="bi bi-search"></i><input id="caSearch" placeholder="Rechercher une agence ou un code (ex. K27)…"></div>' +
            '<div class="ca-types" id="caTypes"></div></div>' +
            '<div class="ca-grid" id="caGrid"></div>';
        host.insertBefore(root, host.firstChild);
        root.querySelector("#caSearch").addEventListener("input", function () { state.search = this.value.trim().toLowerCase(); render(); });
        root.querySelector("#caDate").addEventListener("change", function () { fetchReport(this.value); });
        root.querySelector("#caPasteBtn").addEventListener("click", openPaste);
        root.querySelector("#caAddBtn").addEventListener("click", function () { openEdit(null); });
        return root;
    }

    function load(country, canEdit) {
        if (!ensureRoot()) return;
        state.country = country;
        state.canEdit = !!canEdit;
        state.search = ""; state.type = "";
        root.querySelector("#caSearch").value = "";
        Array.prototype.forEach.call(root.querySelectorAll(".ca-qa"), function (b) { b.style.display = state.canEdit ? "" : "none"; });
        fetchReport(null);
    }

    function fetchReport(date) {
        root.querySelector("#caGrid").innerHTML = '<div class="ca-skel"></div><div class="ca-skel"></div><div class="ca-skel"></div>';
        // État des GAB publié par chaque agence (Portail Agence) — affiché sur la même fiche.
        var atmReq = request("GET", "/api/agence/atm?country=" + encodeURIComponent(state.country)).catch(function () { return []; });
        Promise.all([request("GET", "/api/card-availability/agencies?country=" + encodeURIComponent(state.country) + (date ? "&date=" + date : "")), atmReq])
            .then(function (res) { state.report = res[0]; state.atm = res[1] || []; render(); })
            .catch(function (e) {
                root.querySelector("#caGrid").innerHTML = '<div class="alert alert-warning small mb-0">Point par agence indisponible : ' + esc(e.message) + '</div>';
            });
    }

    function render() {
        var r = state.report;
        if (!r) return;
        var dateSel = root.querySelector("#caDate");
        dateSel.innerHTML = ((r.dates || []).length ? '<option value=""' + (r.current ? " selected" : "") + '>Situation actuelle</option>' : "") +
            ((r.dates || []).map(function (d) {
                return '<option value="' + d + '"' + (!r.current && d === r.reportDate ? " selected" : "") + '>Point du ' + frDate(d) + '</option>';
            }).join("") || '<option>Aucun point</option>');
        dateSel.disabled = !(r.dates || []).length;
        // Situation actuelle = dernière mise à jour de CHAQUE agence (point QA ou coche de l'agence elle-même).
        root.querySelector("#caTitle").textContent = !r.reportDate ? "Aucun point transmis pour cette filiale"
            : r.current ? "Situation actuelle — dernière mise à jour de chaque agence" : "Situation au " + frDate(r.reportDate, true);

        var rows = r.rows || [];
        var ready = rows.filter(function (x) { return x.cardStatus === "OK" && x.pinStatus === "OK"; }).length;
        var out = rows.filter(function (x) { return x.cardStatus === "RUPTURE" || x.pinStatus === "RUPTURE"; }).length;
        var low = rows.length - ready - out;
        root.querySelector("#caSummary").innerHTML = rows.length
            ? '<span class="ca-chip"><b>' + rows.length + '</b> agence' + (rows.length > 1 ? "s" : "") + '</span>' +
              '<span class="ca-chip ok"><i class="bi bi-check2-all"></i> <b>' + ready + '</b> prête' + (ready > 1 ? "s" : "") + ' à délivrer (carte + code)</span>' +
              (low ? '<span class="ca-chip low"><i class="bi bi-exclamation-triangle"></i> <b>' + low + '</b> stock faible</span>' : "") +
              (out ? '<span class="ca-chip out"><i class="bi bi-x-octagon"></i> <b>' + out + '</b> en rupture</span>' : "")
            : "";

        var types = [];
        rows.forEach(function (x) { (x.cardTypes || []).forEach(function (t) { if (types.indexOf(t) === -1) types.push(t); }); });
        root.querySelector("#caTypes").innerHTML = types.length
            ? '<button type="button" class="' + (!state.type ? "active" : "") + '" data-type="">Toutes les cartes</button>' + types.map(function (t) {
                var n = rows.filter(function (x) { return (x.cardTypes || []).indexOf(t) !== -1; }).length;
                return '<button type="button" class="' + (state.type === t ? "active" : "") + '" data-type="' + esc(t) + '">' + esc(t) + ' <span>' + n + '</span></button>';
            }).join("") : "";
        Array.prototype.forEach.call(root.querySelectorAll("#caTypes [data-type]"), function (b) {
            b.addEventListener("click", function () { state.type = b.getAttribute("data-type"); render(); });
        });

        var shown = rows.filter(function (x) {
            var hay = (x.agency + " " + (x.agencyCode || "")).toLowerCase();
            return (!state.search || hay.indexOf(state.search) !== -1) && (!state.type || (x.cardTypes || []).indexOf(state.type) !== -1);
        });
        var grid = root.querySelector("#caGrid");
        if (!rows.length) {
            grid.innerHTML = '<div class="ca-empty"><i class="bi bi-inboxes"></i><b>Aucun point de disponibilité pour cette filiale</b>' +
                (state.canEdit ? 'Utilisez « Coller le point du jour » pour importer le tableau reçu (DATE | AGENCE | CARTE | CODE | TYPE DE CARTE).' : 'La QA publiera ici le point transmis par la filiale.') + '</div>';
            return;
        }
        grid.innerHTML = shown.length ? shown.map(function (x, i) {
            var both = x.cardStatus === "OK" && x.pinStatus === "OK";
            var worst = x.cardStatus === "RUPTURE" || x.pinStatus === "RUPTURE" ? "out" : (both ? "ok" : "low");
            return '<article class="ca-card ca-' + worst + '" style="animation-delay:' + Math.min(i * 50, 500) + 'ms">' +
                '<div class="ca-card-top"><span class="ca-pin"><i class="bi bi-geo-alt-fill"></i></span>' +
                '<div class="min-w-0 flex-grow-1"><b>' + esc(x.agency) + '</b>' + (x.agencyCode ? '<span class="ca-code">' + esc(x.agencyCode) + '</span>' : "") +
                '<small>' + (both ? "Carte et code disponibles : délivrance possible" : worst === "out" ? "Délivrance impossible pour l'instant" : "Disponibilité limitée") + '</small></div>' +
                (state.canEdit ? '<div class="ca-actions"><button type="button" class="btn btn-sm btn-light" data-edit="' + x.id + '" title="Modifier"><i class="bi bi-pencil"></i></button>' +
                    '<button type="button" class="btn btn-sm btn-light text-danger" data-del="' + x.id + '" title="Supprimer"><i class="bi bi-trash"></i></button></div>' : "") +
                '</div>' +
                '<div class="ca-status">' + pill("Carte", x.cardStatus, "bi-credit-card-2-front") + pill("Code PIN", x.pinStatus, "bi-key") + atmPill(x.agencyCode) + '</div>' +
                '<div class="ca-card-types">' + ((x.cardTypes || []).map(function (t) { return '<span class="' + (t === state.type ? "hl" : "") + '">' + esc(t) + '</span>'; }).join("") || '<em>Gammes non précisées</em>') + '</div>' +
                (x.note ? '<div class="ca-note"><i class="bi bi-info-circle"></i> ' + esc(x.note) + '</div>' : "") +
                '<div class="ca-upd"><i class="bi bi-clock-history"></i> Mis à jour le ' + frDate(x.reportDate) + (x.updatedBy ? " par " + esc(x.updatedBy) : "") + '</div>' +
                '</article>';
        }).join("") : '<div class="ca-empty"><i class="bi bi-search"></i><b>Aucune agence ne correspond</b>Modifiez la recherche ou le filtre de carte.</div>';
        Array.prototype.forEach.call(grid.querySelectorAll("[data-edit]"), function (b) {
            b.addEventListener("click", function () { openEdit(rows.filter(function (x) { return String(x.id) === b.getAttribute("data-edit"); })[0]); });
        });
        Array.prototype.forEach.call(grid.querySelectorAll("[data-del]"), function (b) {
            b.addEventListener("click", function () {
                if (!confirm("Supprimer cette agence du point ?")) return;
                request("DELETE", "/api/card-availability/agencies/" + b.getAttribute("data-del")).then(function () { toast("Agence retirée du point"); fetchReport(state.report.current ? null : state.report.reportDate); })
                    .catch(function (e) { toast("Erreur : " + e.message, true); });
            });
        });
    }

    var ATM_LABEL = { EN_SERVICE: ["ok", "bi-check-circle-fill", "En service"], PARTIEL: ["low", "bi-exclamation-triangle-fill", "Partiel"],
        SANS_BILLETS: ["low", "bi-cash", "Sans billets"], HORS_SERVICE: ["out", "bi-x-octagon-fill", "Hors service"] };
    function atmPill(code) {
        var a = (state.atm || []).filter(function (x) { return code && x.agencyCode && x.agencyCode.toUpperCase() === String(code).toUpperCase(); })[0];
        if (!a) return "";
        var s = ATM_LABEL[a.status] || ["low", "bi-question-circle", a.status];
        return '<span class="ca-pill ' + s[0] + '" title="Publié par l\'agence' + (a.updatedAt ? " le " + new Date(a.updatedAt).toLocaleString("fr-FR") : "") + '"><i class="bi bi-cash-coin"></i> GAB <b><i class="bi ' + s[1] + '"></i> ' + s[2] +
            (a.gabTotal != null && a.gabWorking != null ? " " + a.gabWorking + "/" + a.gabTotal : "") + '</b></span>';
    }

    function pill(label, status, icon) {
        var s = STATUS[status] || STATUS.RUPTURE;
        return '<span class="ca-pill ' + s.cls + '"><i class="bi ' + icon + '"></i> ' + label + ' <b><i class="bi ' + s.icon + '"></i> ' + s.label + '</b></span>';
    }

    // ── Modales QA ─────────────────────────────────────────────────────────────

    function modalShell(id, title, icon, bodyHtml, okLabel) {
        var wrap = document.createElement("div");
        wrap.innerHTML = '<div class="modal fade ca-modal" id="' + id + '" tabindex="-1"><div class="modal-dialog modal-dialog-centered modal-lg"><div class="modal-content">' +
            '<div class="ca-modal-head"><span class="ca-head-ic"><i class="bi ' + icon + '"></i></span><h5 class="mb-0 flex-grow-1">' + title + '</h5>' +
            '<button type="button" class="btn-close btn-close-white" data-bs-dismiss="modal"></button></div>' +
            '<div class="modal-body">' + bodyHtml + '</div>' +
            '<div class="modal-footer"><span class="small text-danger me-auto" data-err></span><button type="button" class="btn btn-light" data-bs-dismiss="modal">Annuler</button>' +
            '<button type="button" class="btn btn-primary" data-ok><i class="bi bi-check2-circle"></i> ' + okLabel + '</button></div></div></div></div>';
        document.body.appendChild(wrap.firstChild);
        return document.getElementById(id);
    }

    function today() { var d = new Date(); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }

    function statusSelect(name) {
        return '<div class="ca-seg" data-name="' + name + '">' + ["OK", "FAIBLE", "RUPTURE"].map(function (s) {
            return '<button type="button" data-v="' + s + '" class="' + STATUS[s].cls + '">' + STATUS[s].label + '</button>';
        }).join("") + '</div>';
    }

    function segValue(el, name, value) {
        var seg = el.querySelector('.ca-seg[data-name="' + name + '"]');
        if (value !== undefined) Array.prototype.forEach.call(seg.querySelectorAll("button"), function (b) { b.classList.toggle("active", b.getAttribute("data-v") === value); });
        var a = seg.querySelector("button.active");
        return a ? a.getAttribute("data-v") : "OK";
    }

    var KNOWN_TYPES = ["CLASSIC", "GOLD", "PLATINIUM", "XPRESS", "MX", "PRÉPAYÉE", "INFINITE", "BUSINESS"];

    function openEdit(row) {
        var m = document.getElementById("caEditModal");
        if (!m) {
            m = modalShell("caEditModal", "Agence", "bi-shop",
                '<div class="row g-3"><div class="col-md-4"><label class="form-label small fw-bold">Date du point</label><input type="date" class="form-control" id="caFDate"></div>' +
                '<div class="col-md-5"><label class="form-label small fw-bold">Agence</label><input class="form-control" id="caFAgency" placeholder="NIANGON"></div>' +
                '<div class="col-md-3"><label class="form-label small fw-bold">Code</label><input class="form-control" id="caFCode" placeholder="K27"></div>' +
                '<div class="col-md-6"><label class="form-label small fw-bold">Carte</label>' + statusSelect("card") + '</div>' +
                '<div class="col-md-6"><label class="form-label small fw-bold">Code PIN</label>' + statusSelect("pin") + '</div>' +
                '<div class="col-12"><label class="form-label small fw-bold">Types de carte disponibles</label><div class="ca-typepick" id="caFTypes"></div>' +
                '<input class="form-control form-control-sm mt-2" id="caFTypeOther" placeholder="Autre type (séparés par des virgules)"></div>' +
                '<div class="col-12"><label class="form-label small fw-bold">Précision (facultatif)</label><input class="form-control" id="caFNote" placeholder="Ex. livraison attendue jeudi"></div></div>',
                "Enregistrer");
            Array.prototype.forEach.call(m.querySelectorAll(".ca-seg button"), function (b) {
                b.addEventListener("click", function () { segValue(m, b.parentNode.getAttribute("data-name"), b.getAttribute("data-v")); });
            });
            m.querySelector("[data-ok]").addEventListener("click", saveEdit);
            editModal = new bootstrap.Modal(m);
        }
        m.setAttribute("data-id", row ? row.id : "");
        m.querySelector("h5").textContent = row ? "Modifier " + row.agency : "Ajouter une agence au point";
        m.querySelector("[data-err]").textContent = "";
        m.querySelector("#caFDate").value = row ? row.reportDate : (state.report && state.report.reportDate) || today();
        m.querySelector("#caFAgency").value = row ? row.agency : "";
        m.querySelector("#caFCode").value = row ? (row.agencyCode || "") : "";
        m.querySelector("#caFNote").value = row ? (row.note || "") : "";
        segValue(m, "card", row ? row.cardStatus : "OK");
        segValue(m, "pin", row ? row.pinStatus : "OK");
        var types = KNOWN_TYPES.slice();
        ((state.report && state.report.allCardTypes) || []).forEach(function (t) { if (types.indexOf(t) === -1) types.push(t); });
        var selected = row ? row.cardTypes || [] : [];
        m.querySelector("#caFTypes").innerHTML = types.map(function (t) {
            return '<label><input type="checkbox" value="' + esc(t) + '"' + (selected.indexOf(t) !== -1 ? " checked" : "") + '><span>' + esc(t) + '</span></label>';
        }).join("");
        m.querySelector("#caFTypeOther").value = "";
        editModal.show();
    }

    function saveEdit() {
        var m = document.getElementById("caEditModal");
        var id = m.getAttribute("data-id");
        var types = Array.prototype.map.call(m.querySelectorAll("#caFTypes input:checked"), function (i) { return i.value; });
        m.querySelector("#caFTypeOther").value.split(",").forEach(function (t) { if (t.trim()) types.push(t.trim()); });
        var body = {
            countryCode: state.country, reportDate: m.querySelector("#caFDate").value, agency: m.querySelector("#caFAgency").value.trim(),
            agencyCode: m.querySelector("#caFCode").value.trim() || null, cardStatus: segValue(m, "card"), pinStatus: segValue(m, "pin"),
            cardTypes: types, note: m.querySelector("#caFNote").value.trim() || null
        };
        if (!body.reportDate || !body.agency) { m.querySelector("[data-err]").textContent = "Date et agence obligatoires."; return; }
        request(id ? "PUT" : "POST", "/api/card-availability/agencies" + (id ? "/" + id : ""), body).then(function () {
            editModal.hide();
            toast("Agence enregistrée ✓");
            fetchReport(state.report && state.report.current ? null : body.reportDate);
        }).catch(function (e) { m.querySelector("[data-err]").textContent = e.message; });
    }

    function openPaste() {
        var m = document.getElementById("caPasteModal");
        if (!m) {
            m = modalShell("caPasteModal", "Coller le point du jour", "bi-clipboard-plus",
                '<p class="small text-muted">Copiez le tableau reçu (Excel, e-mail, WhatsApp) et collez-le tel quel. Colonnes attendues : ' +
                '<b>DATE | AGENCE | CARTE | CODE | TYPE DE CARTE</b>. La date peut n\'apparaître que sur la première ligne. Statuts : OK, FAIBLE, RUPTURE (ou KO).</p>' +
                '<textarea class="form-control ca-paste" id="caPasteText" rows="10" placeholder="24-09-2026\tNIANGON K27\tOK\tOK\tCLASSIC, PLATINIUM, XPRESS, MX, GOLD&#10;\tAGHIEN K10\tOK\tOK\tCLASSIC, PLATINIUM, MX, GOLD"></textarea>' +
                '<div class="d-flex align-items-center gap-2 mt-2"><label class="small text-muted">Date si absente du tableau</label><input type="date" class="form-control form-control-sm" style="max-width:170px" id="caPasteDate"></div>' +
                '<div id="caPasteWarn" class="small mt-2"></div>',
                "Importer");
            m.querySelector("[data-ok]").addEventListener("click", function () {
                var text = m.querySelector("#caPasteText").value;
                if (!text.trim()) { m.querySelector("[data-err]").textContent = "Collez le tableau à importer."; return; }
                request("POST", "/api/card-availability/agencies/paste", { countryCode: state.country, text: text, defaultDate: m.querySelector("#caPasteDate").value || null })
                    .then(function (res) {
                        var warn = m.querySelector("#caPasteWarn");
                        warn.innerHTML = (res.warnings || []).map(function (w) { return '<div class="text-warning-emphasis"><i class="bi bi-exclamation-triangle"></i> ' + esc(w) + '</div>'; }).join("");
                        if (res.imported) {
                            toast(res.imported + " agence" + (res.imported > 1 ? "s" : "") + " importée" + (res.imported > 1 ? "s" : "") + " ✓");
                            fetchReport(null);
                            if (!(res.warnings || []).length) pasteModal.hide();
                        } else {
                            m.querySelector("[data-err]").textContent = "Aucune ligne importée.";
                        }
                    }).catch(function (e) { m.querySelector("[data-err]").textContent = e.message; });
            });
            pasteModal = new bootstrap.Modal(m);
        }
        m.querySelector("[data-err]").textContent = "";
        m.querySelector("#caPasteWarn").innerHTML = "";
        m.querySelector("#caPasteText").value = "";
        m.querySelector("#caPasteDate").value = today();
        pasteModal.show();
        setTimeout(function () { m.querySelector("#caPasteText").focus(); }, 300);
    }

    return { load: load };
})();
