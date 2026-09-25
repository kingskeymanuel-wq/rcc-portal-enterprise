"use strict";

/**
 * Boîte à outils du Portail Agence — 100 % LOCALE : tous les calculs se font dans le
 * navigateur, rien n'est envoyé au serveur ni enregistré (aucun localStorage pour les saisies),
 * aucune donnée client n'est modifiée. Fonctions pures exposées sur window.AgenceTools.calc
 * (testables), interfaces sur window.AgenceTools.render(id, container).
 */
(function () {

    // ── Calculs purs ───────────────────────────────────────────────────────

    /** Coupures FCFA (BCEAO) : billets puis pièces. */
    var DENOMS = [
        { v: 10000, kind: "Billet" }, { v: 5000, kind: "Billet" }, { v: 2000, kind: "Billet" }, { v: 1000, kind: "Billet" }, { v: 500, kind: "Billet" },
        { v: 500, kind: "Pièce" }, { v: 250, kind: "Pièce" }, { v: 200, kind: "Pièce" }, { v: 100, kind: "Pièce" }, { v: 50, kind: "Pièce" },
        { v: 25, kind: "Pièce" }, { v: 10, kind: "Pièce" }, { v: 5, kind: "Pièce" }, { v: 1, kind: "Pièce" }
    ];

    function billetage(counts) {
        var total = 0, lines = [];
        DENOMS.forEach(function (d, i) {
            var n = Math.max(0, Math.floor(Number(counts[i]) || 0));
            if (n) lines.push({ value: d.v, kind: d.kind, count: n, subtotal: n * d.v });
            total += n * d.v;
        });
        return { total: total, lines: lines };
    }

    /** Rendu de monnaie optimal (système FCFA canonique : l'algorithme glouton est optimal). */
    function change(due, received) {
        due = Math.round(Number(due) || 0);
        received = Math.round(Number(received) || 0);
        var rest = received - due;
        if (rest < 0) return { amount: rest, parts: [], missing: -rest };
        var parts = [], r = rest;
        var seen = {};
        DENOMS.forEach(function (d) {
            var key = d.kind + d.v;
            if (seen[key]) return;
            seen[key] = true;
            var n = Math.floor(r / d.v);
            if (n > 0) { parts.push({ value: d.v, kind: d.kind, count: n }); r -= n * d.v; }
        });
        return { amount: rest, parts: parts, missing: 0 };
    }

    /** Prêt à échéances constantes. rate = taux annuel en %. */
    function loan(principal, annualRatePct, months, feePct) {
        var P = Number(principal) || 0, n = Math.floor(Number(months) || 0), r = (Number(annualRatePct) || 0) / 100 / 12;
        if (P <= 0 || n <= 0) return null;
        var m = r === 0 ? P / n : P * r / (1 - Math.pow(1 + r, -n));
        var rows = [], balance = P, totalInterest = 0;
        for (var k = 1; k <= n; k++) {
            var interest = balance * r;
            var capital = m - interest;
            if (k === n) capital = balance;
            balance = Math.max(0, balance - capital);
            totalInterest += interest;
            rows.push({ k: k, payment: capital + interest, interest: interest, capital: capital, balance: balance });
        }
        var fees = P * (Number(feePct) || 0) / 100;
        return { monthly: m, totalInterest: totalInterest, fees: fees, totalCost: totalInterest + fees, totalPaid: P + totalInterest + fees, rows: rows };
    }

    /** Parité fixe officielle : 1 EUR = 655,957 XOF. */
    var EUR_XOF = 655.957;

    function convert(amount, from, to, customRatePerUnitXof) {
        var a = Number(amount) || 0;
        function toXof(v, cur) { return cur === "XOF" ? v : cur === "EUR" ? v * EUR_XOF : v * (Number(customRatePerUnitXof) || 0); }
        function fromXof(v, cur) { return cur === "XOF" ? v : cur === "EUR" ? v / EUR_XOF : (Number(customRatePerUnitXof) ? v / Number(customRatePerUnitXof) : NaN); }
        return fromXof(toXof(a, from), to);
    }

    /** ISO 7064 mod 97-10 sur une chaîne alphanumérique (lettres A=10 … Z=35). */
    function mod97(str) {
        var expanded = "";
        for (var i = 0; i < str.length; i++) {
            var c = str.charAt(i);
            expanded += /[A-Z]/.test(c) ? String(c.charCodeAt(0) - 55) : c;
        }
        var rem = 0;
        for (var j = 0; j < expanded.length; j += 7) rem = Number(String(rem) + expanded.substring(j, j + 7)) % 97;
        return rem;
    }

    var IBAN_LENGTHS = { CI: 28, SN: 28, BJ: 28, BF: 28, ML: 28, NE: 28, TG: 28, GW: 25, CM: 27, GA: 27, CG: 27, TD: 27, CF: 27, GQ: 27,
        FR: 27, BE: 16, DE: 22, GB: 22, ES: 24, IT: 27, PT: 25, CH: 21, MA: 28, TN: 24, MZ: 25, CV: 25, ST: 25, BI: 27, CD: 27 };

    function checkIban(raw) {
        var iban = String(raw || "").toUpperCase().replace(/[\s-]/g, "");
        if (!/^[A-Z]{2}\d{2}[A-Z0-9]{8,30}$/.test(iban)) return { valid: false, iban: iban, reason: "Format attendu : 2 lettres pays + 2 chiffres de contrôle + identifiant (ex. CI93 CI059 01001 …)." };
        var country = iban.slice(0, 2);
        var expected = IBAN_LENGTHS[country];
        if (expected && iban.length !== expected) return { valid: false, iban: iban, country: country, reason: "Longueur " + iban.length + " au lieu de " + expected + " pour " + country + "." };
        var ok = mod97(iban.slice(4) + iban.slice(0, 4)) === 1;
        return { valid: ok, iban: iban, country: country, formatted: iban.replace(/(.{4})/g, "$1 ").trim(),
            reason: ok ? "Clé de contrôle correcte." : "Clé de contrôle incorrecte : une erreur de saisie est probable." };
    }

    /** Construit l'IBAN à partir du RIB (code banque + guichet + compte + clé RIB). */
    function ibanFromRib(country, rib) {
        var c = String(country || "CI").toUpperCase();
        var bban = String(rib || "").toUpperCase().replace(/[\s-]/g, "");
        if (!/^[A-Z0-9]{10,30}$/.test(bban)) return null;
        var check = 98 - mod97(bban + c + "00");
        return c + (check < 10 ? "0" + check : String(check)) + bban;
    }

    /** Contrôle de Luhn d'un numéro de carte + réseau probable. */
    function luhn(raw) {
        var digits = String(raw || "").replace(/\D/g, "");
        if (digits.length < 12 || digits.length > 19) return { valid: false, length: digits.length, masked: mask(digits), network: null };
        var sum = 0, dbl = false;
        for (var i = digits.length - 1; i >= 0; i--) {
            var d = Number(digits.charAt(i));
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d; dbl = !dbl;
        }
        var net = /^4/.test(digits) ? "Visa" : /^(5[1-5]|2(2[2-9]|[3-6]\d|7[01]|720))/.test(digits) ? "Mastercard"
            : /^3[47]/.test(digits) ? "American Express" : /^62/.test(digits) ? "UnionPay" : "Autre réseau / carte locale";
        return { valid: sum % 10 === 0, length: digits.length, masked: mask(digits), network: net };
        function mask(d) { return d.length <= 4 ? d : "•••• •••• •••• " + d.slice(-4); }
    }

    function isWeekend(d) { var w = d.getDay(); return w === 0 || w === 6; }

    function addBusinessDays(start, n, holidays) {
        var d = new Date(start.getFullYear(), start.getMonth(), start.getDate());
        var set = {};
        (holidays || []).forEach(function (h) { set[h] = true; });
        var left = Math.max(0, Math.floor(n));
        while (left > 0) {
            d.setDate(d.getDate() + 1);
            if (!isWeekend(d) && !set[iso(d)]) left--;
        }
        return d;
    }

    function businessDaysBetween(a, b, holidays) {
        var set = {};
        (holidays || []).forEach(function (h) { set[h] = true; });
        var from = new Date(a.getFullYear(), a.getMonth(), a.getDate()), to = new Date(b.getFullYear(), b.getMonth(), b.getDate());
        var sign = 1;
        if (to < from) { var t = from; from = to; to = t; sign = -1; }
        var n = 0;
        while (from < to) { from.setDate(from.getDate() + 1); if (!isWeekend(from) && !set[iso(from)]) n++; }
        return sign * n;
    }

    function iso(d) {
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
    }

    /** Masque les données sensibles d'un texte avant de le transmettre (compte, carte, IBAN, téléphone, e-mail). */
    function maskSensitive(text) {
        var s = String(text || "");
        s = s.replace(/\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{4}){3,7}(?:[ ]?[A-Z0-9]{1,4})?\b/g, function (m) {
            var c = m.replace(/\s/g, ""); return c.slice(0, 4) + " •••• " + c.slice(-4);
        });
        s = s.replace(/(?<![+\d])\b\d(?:[ -]?\d){12,18}\b/g, function (m) { var d = m.replace(/\D/g, ""); return "•••• " + d.slice(-4); });
        s = s.replace(/([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\.[A-Za-z]{2,})/g, "$1•••@$2");
        s = s.replace(/(?:\+\d{3}[ .-]?)?\b\d{2}(?:[ .-]?\d{2}){3,4}\b/g, function (m) {
            var d = m.replace(/\D/g, ""); if (d.length < 8) return m; return "•••• " + d.slice(-2);
        });
        s = s.replace(/\b\d{8,12}\b/g, function (m) { return "••••" + m.slice(-4); });
        return s;
    }

    var calc = { DENOMS: DENOMS, billetage: billetage, change: change, loan: loan, convert: convert, EUR_XOF: EUR_XOF, mod97: mod97,
        checkIban: checkIban, ibanFromRib: ibanFromRib, luhn: luhn, addBusinessDays: addBusinessDays, businessDaysBetween: businessDaysBetween,
        maskSensitive: maskSensitive, iso: iso };

    // ── Interfaces ─────────────────────────────────────────────────────────

    function fmt(n, dec) {
        if (n == null || isNaN(n)) return "—";
        return Number(n).toLocaleString("fr-FR", { minimumFractionDigits: dec || 0, maximumFractionDigits: dec || 0 });
    }
    function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
    function copy(text, btn) {
        var done = function () { if (!btn) return; var o = btn.innerHTML; btn.innerHTML = '<i class="bi bi-check2"></i> Copié'; setTimeout(function () { btn.innerHTML = o; }, 1300); };
        if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, function () { prompt("Copiez :", text); });
        else prompt("Copiez :", text);
    }

    var TOOLS = {
        billetage: {
            label: "Billetage", icon: "bi-cash-stack", color: "#2E7D32", desc: "Compter la caisse par coupure",
            render: function (el) {
                el.innerHTML = '<div class="ag-tool-grid"><div><table class="table table-sm ag-denoms"><thead><tr><th>Coupure</th><th style="width:110px">Nombre</th><th class="text-end">Montant</th></tr></thead><tbody>' +
                    DENOMS.map(function (d, i) {
                        return '<tr><td><span class="ag-denom ' + (d.kind === "Billet" ? "note" : "coin") + '">' + fmt(d.v) + '</span> <small class="text-muted">' + d.kind + '</small></td>' +
                            '<td><input type="number" min="0" class="form-control form-control-sm" data-i="' + i + '" inputmode="numeric"></td><td class="text-end" data-sub="' + i + '">0</td></tr>';
                    }).join("") + '</tbody></table></div>' +
                    '<div class="ag-result-card"><div class="ag-kpi-label">Total compté</div><div class="ag-kpi" id="bilTotal">0 FCFA</div>' +
                    '<label class="form-label small mt-3">Solde théorique (système)</label><input id="bilExpected" type="number" class="form-control" placeholder="Montant attendu">' +
                    '<div id="bilGap" class="ag-gap mt-2"></div>' +
                    '<div class="d-flex gap-2 mt-3"><button class="btn btn-outline-primary btn-sm" id="bilCopy"><i class="bi bi-clipboard"></i> Copier le bordereau</button>' +
                    '<button class="btn btn-outline-secondary btn-sm" id="bilReset"><i class="bi bi-eraser"></i> Remettre à zéro</button></div></div></div>';
                function update() {
                    var counts = [];
                    el.querySelectorAll("[data-i]").forEach(function (inp) { counts[Number(inp.dataset.i)] = inp.value; });
                    var r = billetage(counts);
                    DENOMS.forEach(function (d, i) { el.querySelector('[data-sub="' + i + '"]').textContent = fmt((Math.floor(Number(counts[i]) || 0)) * d.v); });
                    el.querySelector("#bilTotal").textContent = fmt(r.total) + " FCFA";
                    var exp = el.querySelector("#bilExpected").value;
                    var gap = el.querySelector("#bilGap");
                    if (exp === "") { gap.innerHTML = ""; }
                    else {
                        var g = r.total - Number(exp);
                        gap.className = "ag-gap mt-2 " + (g === 0 ? "ok" : g > 0 ? "plus" : "minus");
                        gap.innerHTML = g === 0 ? '<i class="bi bi-check-circle-fill"></i> Caisse équilibrée'
                            : '<i class="bi bi-exclamation-triangle-fill"></i> ' + (g > 0 ? "Excédent" : "Manquant") + " : " + fmt(Math.abs(g)) + " FCFA";
                    }
                    return r;
                }
                el.addEventListener("input", update);
                el.querySelector("#bilReset").addEventListener("click", function () { el.querySelectorAll("input").forEach(function (i) { i.value = ""; }); update(); });
                el.querySelector("#bilCopy").addEventListener("click", function (e) {
                    var r = update();
                    var txt = "BORDEREAU DE BILLETAGE — " + new Date().toLocaleString("fr-FR") + "\n" +
                        r.lines.map(function (l) { return l.kind + " " + fmt(l.value) + " x " + l.count + " = " + fmt(l.subtotal); }).join("\n") +
                        "\nTOTAL : " + fmt(r.total) + " FCFA";
                    copy(txt, e.currentTarget);
                });
                update();
            }
        },
        rendu: {
            label: "Rendu de monnaie", icon: "bi-coin", color: "#F57C00", desc: "Combien rendre, en quelles coupures",
            render: function (el) {
                el.innerHTML = '<div class="ag-tool-grid"><div class="row g-2 align-content-start"><div class="col-6"><label class="form-label small">Montant dû</label><input id="chDue" type="number" class="form-control form-control-lg"></div>' +
                    '<div class="col-6"><label class="form-label small">Montant reçu</label><input id="chRec" type="number" class="form-control form-control-lg"></div></div>' +
                    '<div class="ag-result-card"><div class="ag-kpi-label">À rendre</div><div class="ag-kpi" id="chOut">—</div><div id="chParts" class="ag-parts"></div></div></div>';
                function up() {
                    var d = el.querySelector("#chDue").value, rv = el.querySelector("#chRec").value;
                    if (d === "" || rv === "") { el.querySelector("#chOut").textContent = "—"; el.querySelector("#chParts").innerHTML = ""; return; }
                    var r = change(d, rv);
                    if (r.missing) { el.querySelector("#chOut").innerHTML = '<span class="text-danger">Manque ' + fmt(r.missing) + " FCFA</span>"; el.querySelector("#chParts").innerHTML = ""; return; }
                    el.querySelector("#chOut").textContent = fmt(r.amount) + " FCFA";
                    el.querySelector("#chParts").innerHTML = r.parts.map(function (p) {
                        return '<span class="ag-denom ' + (p.kind === "Billet" ? "note" : "coin") + '">' + p.count + " × " + fmt(p.value) + "</span>";
                    }).join("") || '<span class="text-muted small">Compte juste.</span>';
                }
                el.addEventListener("input", up);
            }
        },
        pret: {
            label: "Simulateur de prêt", icon: "bi-piggy-bank", color: "#0057B8", desc: "Mensualité et tableau d'amortissement",
            render: function (el) {
                el.innerHTML = '<div class="ag-tool-grid"><div class="row g-2 align-content-start">' +
                    '<div class="col-6"><label class="form-label small">Montant (FCFA)</label><input id="lnP" type="number" class="form-control" value="5000000"></div>' +
                    '<div class="col-6"><label class="form-label small">Taux annuel (%)</label><input id="lnR" type="number" step="0.01" class="form-control" value="9"></div>' +
                    '<div class="col-6"><label class="form-label small">Durée (mois)</label><input id="lnN" type="number" class="form-control" value="36"></div>' +
                    '<div class="col-6"><label class="form-label small">Frais de dossier (%)</label><input id="lnF" type="number" step="0.01" class="form-control" value="1"></div>' +
                    '<div class="col-12 small text-muted">Simulation indicative à échéances constantes — les conditions réelles (taux, assurance, frais) sont celles de la grille en vigueur.</div></div>' +
                    '<div class="ag-result-card"><div class="ag-kpi-label">Mensualité</div><div class="ag-kpi" id="lnM">—</div>' +
                    '<div class="ag-mini-kpis"><div><small>Intérêts</small><b id="lnI">—</b></div><div><small>Frais</small><b id="lnFe">—</b></div><div><small>Coût total</small><b id="lnC">—</b></div></div>' +
                    '<div class="d-flex gap-2 mt-3"><button class="btn btn-outline-primary btn-sm" id="lnTableBtn"><i class="bi bi-table"></i> Tableau d\'amortissement</button>' +
                    '<button class="btn btn-outline-secondary btn-sm" id="lnPrint"><i class="bi bi-printer"></i> Imprimer</button></div></div></div>' +
                    '<div id="lnTable" class="table-responsive mt-3" hidden></div>';
                var last = null;
                function up() {
                    last = loan(el.querySelector("#lnP").value, el.querySelector("#lnR").value, el.querySelector("#lnN").value, el.querySelector("#lnF").value);
                    el.querySelector("#lnM").textContent = last ? fmt(last.monthly) + " FCFA" : "—";
                    el.querySelector("#lnI").textContent = last ? fmt(last.totalInterest) : "—";
                    el.querySelector("#lnFe").textContent = last ? fmt(last.fees) : "—";
                    el.querySelector("#lnC").textContent = last ? fmt(last.totalCost) : "—";
                    if (!el.querySelector("#lnTable").hidden) table();
                }
                function table() {
                    var t = el.querySelector("#lnTable");
                    if (!last) { t.innerHTML = ""; return; }
                    t.innerHTML = '<table class="table table-sm table-striped"><thead><tr><th>#</th><th class="text-end">Échéance</th><th class="text-end">Intérêts</th><th class="text-end">Capital</th><th class="text-end">Restant dû</th></tr></thead><tbody>' +
                        last.rows.map(function (r) { return "<tr><td>" + r.k + '</td><td class="text-end">' + fmt(r.payment) + '</td><td class="text-end">' + fmt(r.interest) + '</td><td class="text-end">' + fmt(r.capital) + '</td><td class="text-end">' + fmt(r.balance) + "</td></tr>"; }).join("") + "</tbody></table>";
                }
                el.addEventListener("input", up);
                el.querySelector("#lnTableBtn").addEventListener("click", function () { var t = el.querySelector("#lnTable"); t.hidden = !t.hidden; if (!t.hidden) table(); });
                el.querySelector("#lnPrint").addEventListener("click", function () {
                    if (!last) return;
                    var w = window.open("", "_blank");
                    if (!w) return;
                    table();
                    w.document.write('<html><head><title>Simulation de prêt</title><style>body{font:13px Arial;margin:24px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:4px 6px;text-align:right}th:first-child,td:first-child{text-align:left}</style></head><body>' +
                        "<h2>Simulation de prêt — Ecobank</h2><p>Montant : " + fmt(el.querySelector("#lnP").value) + " FCFA · Taux : " + esc(el.querySelector("#lnR").value) + " % · Durée : " + esc(el.querySelector("#lnN").value) +
                        " mois<br><b>Mensualité : " + fmt(last.monthly) + " FCFA</b> · Coût total : " + fmt(last.totalCost) + " FCFA</p>" + el.querySelector("#lnTable").innerHTML +
                        "<p><i>Simulation indicative, non contractuelle.</i></p></body></html>");
                    w.document.close(); w.print();
                });
                up();
            }
        },
        devises: {
            label: "Convertisseur", icon: "bi-currency-exchange", color: "#00838F", desc: "XOF ↔ EUR (parité fixe) et autres devises",
            render: function (el) {
                el.innerHTML = '<div class="ag-tool-grid"><div class="row g-2 align-content-start">' +
                    '<div class="col-12"><label class="form-label small">Montant</label><input id="fxA" type="number" class="form-control form-control-lg" value="100"></div>' +
                    '<div class="col-5"><select id="fxFrom" class="form-select"><option>EUR</option><option>XOF</option><option value="AUTRE">Autre devise</option></select></div>' +
                    '<div class="col-2 text-center"><button class="btn btn-light" id="fxSwap" title="Inverser"><i class="bi bi-arrow-left-right"></i></button></div>' +
                    '<div class="col-5"><select id="fxTo" class="form-select"><option>XOF</option><option>EUR</option><option value="AUTRE">Autre devise</option></select></div>' +
                    '<div class="col-12" id="fxCustomWrap" hidden><label class="form-label small">Cours du jour : 1 unité de l\'autre devise = … XOF (grille de change de l\'agence)</label><input id="fxRate" type="number" step="0.0001" class="form-control" placeholder="ex. 605,50 pour 1 USD"></div>' +
                    '<div class="col-12 small text-muted">Parité fixe officielle : 1 EUR = 655,957 XOF. Pour les autres devises, saisissez le cours affiché par l\'agence (aucune donnée externe).</div></div>' +
                    '<div class="ag-result-card"><div class="ag-kpi-label">Résultat</div><div class="ag-kpi" id="fxOut">—</div></div></div>';
                function up() {
                    var from = el.querySelector("#fxFrom").value, to = el.querySelector("#fxTo").value;
                    el.querySelector("#fxCustomWrap").hidden = from !== "AUTRE" && to !== "AUTRE";
                    var v = convert(el.querySelector("#fxA").value, from, to, el.querySelector("#fxRate").value);
                    el.querySelector("#fxOut").textContent = isNaN(v) ? "Saisissez le cours" : fmt(v, to === "XOF" ? 0 : 2) + " " + (to === "AUTRE" ? "(autre devise)" : to);
                }
                el.addEventListener("input", up);
                el.addEventListener("change", up);
                el.querySelector("#fxSwap").addEventListener("click", function () { var f = el.querySelector("#fxFrom"), t = el.querySelector("#fxTo"), x = f.value; f.value = t.value; t.value = x; up(); });
                up();
            }
        },
        delais: {
            label: "Délais ouvrés", icon: "bi-calendar2-week", color: "#6A1B9A", desc: "Date de traitement annoncée au client",
            render: function (el) {
                var today = iso(new Date());
                el.innerHTML = '<div class="ag-tool-grid"><div class="row g-2 align-content-start">' +
                    '<div class="col-6"><label class="form-label small">Date de départ</label><input id="dlStart" type="date" class="form-control" value="' + today + '"></div>' +
                    '<div class="col-6"><label class="form-label small">Délai (jours ouvrés)</label><input id="dlN" type="number" class="form-control" value="3"></div>' +
                    '<div class="col-12"><label class="form-label small">Jours fériés à exclure (AAAA-MM-JJ, séparés par des virgules)</label><input id="dlHol" class="form-control" placeholder="2026-12-25, 2027-01-01"></div>' +
                    '<div class="col-12"><hr class="my-2"><label class="form-label small">… ou nombre de jours ouvrés jusqu\'au</label><input id="dlEnd" type="date" class="form-control"></div></div>' +
                    '<div class="ag-result-card"><div class="ag-kpi-label">Réponse attendue le</div><div class="ag-kpi" id="dlOut">—</div><div id="dlBetween" class="small mt-2"></div>' +
                    '<button class="btn btn-outline-primary btn-sm mt-3" id="dlCopy"><i class="bi bi-chat-quote"></i> Copier la phrase pour le client</button></div></div>';
                function hol() { return el.querySelector("#dlHol").value.split(/[,; ]+/).filter(function (x) { return /^\d{4}-\d{2}-\d{2}$/.test(x); }); }
                function up() {
                    var s = el.querySelector("#dlStart").value;
                    if (!s) return;
                    var d = addBusinessDays(new Date(s + "T00:00:00"), Number(el.querySelector("#dlN").value) || 0, hol());
                    el.querySelector("#dlOut").textContent = d.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
                    var e = el.querySelector("#dlEnd").value;
                    el.querySelector("#dlBetween").textContent = e ? businessDaysBetween(new Date(s + "T00:00:00"), new Date(e + "T00:00:00"), hol()) + " jour(s) ouvré(s) jusqu'au " + new Date(e + "T00:00:00").toLocaleDateString("fr-FR") : "";
                }
                el.addEventListener("input", up);
                el.querySelector("#dlCopy").addEventListener("click", function (ev) {
                    copy("Votre demande sera traitée au plus tard le " + el.querySelector("#dlOut").textContent + ". Nous restons à votre disposition.", ev.currentTarget);
                });
                up();
            }
        },
        kyc: {
            label: "Checklist ouverture", icon: "bi-list-check", color: "#1565C0", desc: "Pièces à contrôler (indicatif)",
            render: function (el) {
                var LISTS = {
                    Particulier: ["Pièce d'identité en cours de validité (CNI, passeport, carte consulaire)", "Justificatif de domicile récent", "Photo d'identité récente",
                        "Justificatif de revenus (bulletin de salaire, attestation de travail…)", "Formulaire d'ouverture complété et signé", "Spécimen de signature",
                        "Contrôle des listes de sanctions / PPE effectué", "Consentement e-banking / carte recueilli"],
                    Entreprise: ["Statuts à jour", "Extrait RCCM récent", "Numéro de compte contribuable (DFE / NIF)", "PV de nomination des dirigeants et pouvoirs des signataires",
                        "Pièces d'identité des signataires", "Identification des bénéficiaires effectifs", "Justificatif du siège social", "Formulaire d'ouverture signé et cacheté",
                        "Contrôle des listes de sanctions / PPE effectué"]
                };
                el.innerHTML = '<div class="ag-kyc-head"><div class="btn-group" role="group">' + Object.keys(LISTS).map(function (k, i) {
                    return '<button class="btn btn-outline-primary' + (i === 0 ? " active" : "") + '" data-k="' + k + '">' + k + "</button>";
                }).join("") + '</div><div class="ag-kyc-progress"><span id="kyP"></span></div></div><div id="kyList" class="ag-kyc-list"></div>' +
                    '<p class="small text-muted mt-2 mb-0"><i class="bi bi-info-circle"></i> Liste indicative d\'aide-mémoire — la procédure d\'ouverture de compte en vigueur fait foi. Rien n\'est enregistré.</p>';
                var current = "Particulier";
                function draw() {
                    el.querySelector("#kyList").innerHTML = LISTS[current].map(function (item, i) {
                        return '<label class="ag-kyc-item"><input type="checkbox" data-i="' + i + '"><span>' + esc(item) + "</span></label>";
                    }).join("");
                    prog();
                }
                function prog() {
                    var all = el.querySelectorAll("#kyList input"), on = el.querySelectorAll("#kyList input:checked");
                    var pct = all.length ? Math.round(on.length / all.length * 100) : 0;
                    el.querySelector("#kyP").style.width = pct + "%";
                    el.querySelector("#kyP").textContent = on.length + "/" + all.length;
                }
                el.addEventListener("change", prog);
                el.querySelectorAll("[data-k]").forEach(function (b) {
                    b.addEventListener("click", function () {
                        current = b.dataset.k;
                        el.querySelectorAll("[data-k]").forEach(function (x) { x.classList.toggle("active", x === b); });
                        draw();
                    });
                });
                draw();
            }
        }
    };

    var ORDER = {
        CAISSIER: ["billetage", "rendu", "devises", "delais", "pret", "kyc"],
        GESTIONNAIRE: ["pret", "kyc", "delais", "devises", "billetage", "rendu"]
    };

    window.AgenceTools = {
        calc: calc,
        tools: TOOLS,
        order: function (mode) { return ORDER[mode] || ORDER.GESTIONNAIRE; },
        render: function (id, container) {
            var t = TOOLS[id];
            if (!t) return;
            container.innerHTML = "";
            var box = document.createElement("div");
            box.className = "ag-tool";
            container.appendChild(box);
            t.render(box);
        }
    };
})();
