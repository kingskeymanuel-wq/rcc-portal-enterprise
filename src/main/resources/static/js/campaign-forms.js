"use strict";

/**
 * Formulaires de campagne Outbound (version 2) — moteur et rendu partagés par le portail agent (appel en cours),
 * le concepteur du Team Leader (aperçu en direct) et les résultats.
 *
 * RccForm.evaluate(form, answers)  → { visible, errors, answers, score, maxScore, scorePct, suggestedStatus, suggestedLabel, answered, answerable }
 *   Même calcul que le serveur (CampaignFormEngine) : le serveur refait tout à l'enregistrement.
 * RccForm.render(root, form, opts) → { answers(), evaluation(), validate(), setAnswers(map), destroy() }
 *   opts : { answers, context: { client: { name, phone, account }, agent: { name }, campaign, extra: {} }, onChange(ev), paged, draftKey }
 *
 * Réponses : { idQuestion: "valeur" } ; listes (choix multiples, classement) et grilles en JSON dans la valeur.
 */
window.RccForm = (function () {
    var STATUSES = { PENDING: "À appeler", GREEN: "Interaction", RED: "Pas de réponse", YELLOW: "RDV pris" };
    var TYPES = {
        SHORT_TEXT: ["Texte court", "bi-input-cursor-text"], LONG_TEXT: ["Paragraphe", "bi-text-paragraph"],
        SINGLE: ["Choix unique", "bi-ui-radios"], MULTIPLE: ["Cases à cocher", "bi-ui-checks"], DROPDOWN: ["Liste déroulante", "bi-menu-button-wide"],
        YES_NO: ["Oui / Non", "bi-toggle-on"], SCALE: ["Échelle linéaire", "bi-sliders"], RATING: ["Note (étoiles)", "bi-star-half"],
        NPS: ["Recommandation 0–10 (NPS)", "bi-speedometer2"], NUMBER: ["Nombre", "bi-123"], AMOUNT: ["Montant (FCFA)", "bi-cash-coin"],
        DATE: ["Date", "bi-calendar-event"], TIME: ["Heure", "bi-clock"], PHONE: ["Téléphone", "bi-telephone"], EMAIL: ["E-mail", "bi-envelope-at"],
        MATRIX: ["Grille de choix", "bi-grid-3x3"], RANKING: ["Classement", "bi-sort-numeric-down"], CONSENT: ["Consentement", "bi-patch-check"],
        STATEMENT: ["Texte d'information", "bi-info-circle"]
    };
    var CHOICE = { SINGLE: 1, MULTIPLE: 1, DROPDOWN: 1, RANKING: 1 };
    var EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/;

    function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
    function list(v) { try { var x = JSON.parse(v); return Array.isArray(x) ? x.map(String) : null; } catch (e) { return null; } }
    function map(v) { try { var x = JSON.parse(v); return x && typeof x === "object" && !Array.isArray(x) ? x : null; } catch (e) { return null; } }
    function num(v) { if (v == null) return null; var s = String(v).replace(/[\s\u00A0]/g, "").replace(",", "."); return s !== "" && !isNaN(Number(s)) ? Number(s) : null; }
    function isEmpty(v, type) {
        if (v == null || String(v).trim() === "") return true;
        if (type === "CONSENT") return String(v).toLowerCase() !== "true";
        return v === "[]" || v === "{}";
    }
    function questions(form) { var out = []; (form.sections || []).forEach(function (s) { (s.questions || []).forEach(function (q) { out.push(q); }); }); return out; }
    function findOption(q, v) {
        if (v == null) return null;
        var t = String(v).trim().toLowerCase();
        return (q.options || []).filter(function (o) { return String(o.label).toLowerCase() === t || o.id === String(v).trim(); })[0] || null;
    }
    function today() { var d = new Date(); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }
    function relDate(s) { return !s ? null : s === "today" ? today() : s; }

    // ───────────── Conditions ─────────────

    function rule(r, answer) {
        var op = r.op || "eq";
        var empty = answer == null || String(answer).trim() === "" || answer === "[]" || answer === "{}";
        if (op === "empty") return empty;
        if (op === "notempty") return !empty;
        if (empty) return op === "neq" || op === "nin" || op === "ncontains";
        var values = Array.isArray(r.value) ? r.value.map(String) : r.value == null ? [] : [String(r.value)];
        var got = String(answer).charAt(0) === "[" ? (list(answer) || [answer]) : [String(answer)];
        var lc = function (s) { return String(s).toLowerCase(); };
        var inVals = function (g) { return values.some(function (v) { return lc(v) === lc(g); }); };
        switch (op) {
            case "eq": return values.length > 0 && got.length === 1 && lc(got[0]) === lc(values[0]);
            case "neq": return !values.length || got.length !== 1 || lc(got[0]) !== lc(values[0]);
            case "in": return got.some(inVals);
            case "nin": return !got.some(inVals);
            case "contains": return values.length > 0 && (got.some(function (g) { return lc(g) === lc(values[0]); }) || lc(answer).indexOf(lc(values[0])) !== -1);
            case "ncontains": return !values.length || lc(answer).indexOf(lc(values[0])) === -1;
            default:
                if (!values.length) return false;
                var a = num(got[0]), b = num(values[0]), c;
                if (a == null || b == null) c = got[0] < values[0] ? -1 : got[0] > values[0] ? 1 : 0;
                else c = a < b ? -1 : a > b ? 1 : 0;
                return op === "gt" ? c > 0 : op === "gte" ? c >= 0 : op === "lt" ? c < 0 : c <= 0;
        }
    }

    function matches(cond, answers) {
        if (!cond || !cond.rules || !cond.rules.length) return true;
        var any = String(cond.logic || "all").toLowerCase() === "any";
        for (var i = 0; i < cond.rules.length; i++) {
            var ok = rule(cond.rules[i], answers[cond.rules[i].q]);
            if (any && ok) return true;
            if (!any && !ok) return false;
        }
        return !any;
    }

    // ───────────── Contrôles et score (miroir de CampaignFormEngine) ─────────────

    function validate(q, v) {
        var type = q.type, label = q.label, val = q.validation || {}, custom = val.message;
        var m = function (fallback) { return custom || fallback; };
        if (type === "SINGLE" || type === "DROPDOWN" || type === "YES_NO") {
            if (!findOption(q, v) && !(q.allowOther && String(v).indexOf("Autre:") === 0)) return "Choisissez une réponse proposée.";
        } else if (type === "MULTIPLE") {
            var l = list(v);
            if (!l) return "Réponse illisible.";
            for (var i = 0; i < l.length; i++) if (!findOption(q, l[i]) && !(q.allowOther && l[i].indexOf("Autre:") === 0)) return "Choix non proposé : " + l[i];
            if (val.minSelect && l.length < val.minSelect) return m("Choisissez au moins " + val.minSelect + " réponse(s).");
            if (val.maxSelect && l.length > val.maxSelect) return m("Au plus " + val.maxSelect + " réponse(s).");
        } else if (type === "RANKING") {
            var r = list(v);
            if (!r || r.length !== (q.options || []).length) return "Classez tous les choix.";
        } else if (type === "MATRIX") {
            var mm = map(v);
            if (!mm) return "Réponse illisible.";
            if (q.required && (q.rows || []).some(function (row) { return !mm[row.label]; })) return "Répondez à chaque ligne.";
        } else if (type === "SCALE" || type === "RATING" || type === "NPS") {
            var n = num(v), min = type === "NPS" ? 0 : (q.scale || {}).min || 1, max = type === "NPS" ? 10 : (q.scale || {}).max || 5;
            if (n == null || n < min || n > max || Math.round(n) !== n) return "Une valeur de " + min + " à " + max + ".";
        } else if (type === "NUMBER" || type === "AMOUNT") {
            var x = num(v);
            if (x == null) return m("Un nombre est attendu.");
            if (val.min != null && x < Number(val.min)) return m("Au moins " + fmtNum(val.min) + ".");
            if (val.max != null && x > Number(val.max)) return m("Au plus " + fmtNum(val.max) + ".");
        } else if (type === "EMAIL") {
            if (!EMAIL.test(v)) return m("Adresse e-mail invalide.");
        } else if (type === "PHONE") {
            if (!/^\+?\d{8,15}$/.test(String(v).replace(/[\s.\-()]/g, ""))) return m("Numéro invalide (8 à 15 chiffres).");
        } else if (type === "DATE") {
            if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return "Date invalide.";
            var mn = relDate(val.minDate), mx = relDate(val.maxDate);
            if (mn && v < mn) return m("Pas avant le " + frDate(mn) + ".");
            if (mx && v > mx) return m("Pas après le " + frDate(mx) + ".");
        } else if (type === "TIME") {
            if (!/^\d{2}:\d{2}/.test(v)) return "Heure invalide.";
        } else if (type === "CONSENT") {
            if (String(v).toLowerCase() !== "true") return "À accepter.";
        }
        if (type === "SHORT_TEXT" || type === "LONG_TEXT") {
            if (val.minLength && v.length < val.minLength) return m("Au moins " + val.minLength + " caractères.");
            if (val.maxLength && v.length > val.maxLength) return m("Au plus " + val.maxLength + " caractères.");
            if (val.pattern) { try { if (!new RegExp("^(?:" + val.pattern + ")$").test(v)) return m("Format non respecté."); } catch (e) { /* motif invalide : ignoré */ } }
        }
        return null;
    }

    function selected(q, v) {
        if (v == null) return [];
        var vals = q.type === "MULTIPLE" ? (list(v) || []) : [v];
        return vals.map(function (x) { return findOption(q, x); }).filter(Boolean);
    }

    function scoreOf(q, v) {
        var type = q.type;
        if ((CHOICE[type] && type !== "RANKING") || type === "YES_NO") {
            var best = 0, sumPos = 0;
            (q.options || []).forEach(function (o) { var s = Number(o.score) || 0; best = Math.max(best, s); if (s > 0) sumPos += s; });
            var got = 0;
            selected(q, v).forEach(function (o) { got += Number(o.score) || 0; });
            return [got, type === "MULTIPLE" ? sumPos : best];
        }
        var w = Number(q.weight) || 0;
        if (!w) return [0, 0];
        var max = type === "NPS" ? 10 : ((q.scale || {}).max || (q.validation || {}).max || 0);
        var n = v == null ? null : num(v);
        return [n == null ? 0 : Math.round(Math.min(n, max || n) * w), Math.round(max * w)];
    }

    function evaluate(form, raw) {
        raw = raw || {};
        var clean = {}, errors = {}, visible = {}, visibleSections = [], score = 0, max = 0, answered = 0, answerable = 0, optOutcome = null, optLabel = null;
        (form.sections || []).forEach(function (s) {
            if (!matches(s.visibleIf, clean)) return;
            visibleSections.push(s.id);
            (s.questions || []).forEach(function (q) {
                if (!matches(q.visibleIf, clean)) return;
                visible[q.id] = true;
                if (q.type === "STATEMENT") return;
                answerable++;
                var v = raw[q.id] == null ? null : String(raw[q.id]).trim();
                var empty = isEmpty(v, q.type);
                if (empty) { if (q.required) errors[q.id] = "Obligatoire."; }
                else {
                    var err = validate(q, v);
                    if (err) errors[q.id] = err; else { clean[q.id] = v; answered++; }
                }
                var sm = scoreOf(q, empty ? null : clean[q.id]);
                score += sm[0]; max += sm[1];
                if (!optOutcome && clean[q.id] != null) {
                    selected(q, clean[q.id]).some(function (o) { if (o.outcome) { optOutcome = o.outcome; optLabel = o.label; return true; } return false; });
                }
            });
        });
        var pct = max > 0 ? Math.max(0, Math.min(100, Math.round(score * 100 / max))) : null;
        var status = null, label = null;
        (form.outcomes || []).some(function (o) { if (matches(o.when, clean)) { status = o.status; label = o.label || STATUSES[o.status]; return true; } return false; });
        if (!status && optOutcome) { status = optOutcome; label = "Réponse « " + optLabel + " »"; }
        var th = Number((form.settings || {}).qualifiedThreshold) || 0;
        if (!status && pct != null && th > 0 && pct >= th) { status = "GREEN"; label = "Lead qualifié (" + pct + " %)"; }
        return { visible: visible, visibleSections: visibleSections, errors: errors, answers: clean, score: score, maxScore: max, scorePct: pct,
            suggestedStatus: status, suggestedLabel: label, answered: answered, answerable: answerable, valid: !Object.keys(errors).length };
    }

    // ───────────── Variables : {{client.prenom}}, {{agent.prenom}}, {{campagne}}, {{q:id}}, {{extra:Colonne}} ─────────────

    function firstName(name) {
        var words = String(name || "").trim().split(/\s+/).filter(Boolean);
        var mixed = words.filter(function (w) { return w.length > 1 && w !== w.toUpperCase(); })[0];
        if (mixed) return mixed;
        return words.length > 1 ? words[1].charAt(0) + words[1].slice(1).toLowerCase() : (words[0] || "");
    }

    function pipe(text, ctx, answers, form) {
        if (!text) return "";
        ctx = ctx || {};
        var client = ctx.client || {}, agent = ctx.agent || {};
        return String(text).replace(/\{\{\s*([^}]+?)\s*\}\}/g, function (all, key) {
            var k = key.toLowerCase();
            if (k === "client.prenom") return firstName(client.name) || "Madame, Monsieur";
            if (k === "client.nom" || k === "client.name") return client.name || "";
            if (k === "client.telephone" || k === "client.phone") return client.phone || "";
            if (k === "agent.prenom") return firstName(agent.name) || "votre conseiller";
            if (k === "agent.nom") return agent.name || "";
            if (k === "campagne") return ctx.campaign || "notre offre";
            if (k.indexOf("q:") === 0) {
                var q = form ? questions(form).filter(function (x) { return x.id === key.slice(2); })[0] : null;
                var v = answers ? answers[key.slice(2)] : null;
                return v == null ? "…" : readable(q, v);
            }
            if (k.indexOf("extra:") === 0) { var ex = ctx.extra || {}; var name = key.slice(6); return ex[name] != null ? ex[name] : ""; }
            return all;
        });
    }

    function readable(q, v) {
        if (v == null) return "";
        if (q && (q.type === "MULTIPLE" || q.type === "RANKING")) return (list(v) || [v]).join(", ");
        if (q && q.type === "MATRIX") { var m = map(v) || {}; return Object.keys(m).map(function (k) { return k + " : " + m[k]; }).join(" · "); }
        if (q && q.type === "AMOUNT") return fmtNum(v) + " FCFA";
        if (q && q.type === "CONSENT") return String(v) === "true" ? "Accepté" : "";
        if (q && q.type === "DATE") return frDate(v);
        return String(v);
    }

    function fmtNum(v) { var n = num(v); return n == null ? String(v) : n.toLocaleString("fr-FR"); }
    function frDate(iso) { var p = String(iso).split("-"); return p.length === 3 ? p[2] + "/" + p[1] + "/" + p[0] : iso; }

    function prefillValue(q, ctx) {
        if (!q.prefill || !ctx) return null;
        var c = ctx.client || {};
        if (q.prefill === "client.phone") return c.phone || null;
        if (q.prefill === "client.name") return c.name || null;
        if (q.prefill.indexOf("extra:") === 0) { var ex = ctx.extra || {}; return ex[q.prefill.slice(6)] || null; }
        return null;
    }

    // ───────────── Rendu (agent et aperçu) ─────────────

    function storage() { try { return window.localStorage; } catch (e) { return null; } }

    function render(root, form, opts) {
        opts = opts || {};
        var ctx = opts.context || {};
        var answers = {};
        Object.keys(opts.answers || {}).forEach(function (k) { if (opts.answers[k] != null) answers[k] = String(opts.answers[k]); });
        var store = storage(), draftKey = opts.draftKey ? "rccForm:" + opts.draftKey : null;
        if (draftKey && store) {
            try { var d = JSON.parse(store.getItem(draftKey) || "null"); if (d) Object.keys(d).forEach(function (k) { if (answers[k] == null) answers[k] = d[k]; }); } catch (e) { /* brouillon illisible */ }
        }
        questions(form).forEach(function (q) { if (answers[q.id] == null) { var p = prefillValue(q, ctx); if (p) answers[q.id] = p; } });
        var paged = opts.paged !== false, step = 0, touched = {}, showAllErrors = false, ev = evaluate(form, answers);

        function saveDraft() { if (draftKey && store) { try { store.setItem(draftKey, JSON.stringify(answers)); } catch (e) { /* stockage plein */ } } }

        function changed(id, value, rerender) {
            if (value == null || value === "") delete answers[id]; else answers[id] = value;
            touched[id] = true;
            ev = evaluate(form, answers);
            saveDraft();
            if (rerender) draw(); else refreshMeta();
            if (opts.onChange) opts.onChange(ev);
        }

        function sections() { return (form.sections || []).filter(function (s) { return ev.visibleSections.indexOf(s.id) !== -1; }); }

        function inputHtml(q) {
            var v = answers[q.id] == null ? "" : answers[q.id], id = esc(q.id), t = q.type;
            var opt = function (o, on, kind, i) {
                return '<button type="button" class="rf-opt' + (on ? " on" : "") + '" data-' + kind + '="' + id + '" data-v="' + esc(o.label) + '">' +
                    '<span class="rf-key">' + (i < 9 ? i + 1 : "") + '</span><span class="rf-mark ' + (kind === "multi" ? "sq" : "") + '"></span><span>' + esc(o.label) + '</span></button>';
            };
            if (t === "SHORT_TEXT") return '<input class="form-control rf-in" data-text="' + id + '" value="' + esc(v) + '" placeholder="' + esc(q.placeholder || "Votre réponse") + '">';
            if (t === "LONG_TEXT") return '<textarea class="form-control rf-in" rows="3" data-text="' + id + '" placeholder="' + esc(q.placeholder || "Votre réponse") + '">' + esc(v) + '</textarea>' +
                ((q.validation || {}).maxLength ? '<div class="rf-count"><span data-count="' + id + '">' + String(v).length + '</span> / ' + q.validation.maxLength + '</div>' : '');
            if (t === "EMAIL") return '<input type="email" class="form-control rf-in" data-text="' + id + '" value="' + esc(v) + '" placeholder="nom@exemple.com">';
            if (t === "PHONE") return '<input type="tel" class="form-control rf-in" data-text="' + id + '" value="' + esc(v) + '" placeholder="+225 07 00 00 00 00">';
            if (t === "NUMBER") return '<input type="text" inputmode="decimal" class="form-control rf-in rf-num" data-text="' + id + '" value="' + esc(v) + '">';
            if (t === "AMOUNT") return '<div class="input-group rf-amount"><input type="text" inputmode="numeric" class="form-control rf-in" data-amount="' + id + '" value="' + esc(v === "" ? "" : fmtNum(v)) + '"><span class="input-group-text">FCFA</span></div>';
            if (t === "DATE") return '<input type="date" class="form-control rf-in rf-short" data-text="' + id + '" value="' + esc(v) + '"' + ((q.validation || {}).minDate ? ' min="' + esc(relDate(q.validation.minDate)) + '"' : '') + '>';
            if (t === "TIME") return '<input type="time" class="form-control rf-in rf-short" data-text="' + id + '" value="' + esc(v) + '">';
            if (t === "SINGLE" || t === "YES_NO") {
                var other = q.allowOther && String(v).indexOf("Autre:") === 0;
                return '<div class="rf-opts' + (t === "YES_NO" ? " rf-yn" : "") + '">' + (q.options || []).map(function (o, i) { return opt(o, findOption(q, v) === o, "single", i); }).join("") +
                    (q.allowOther ? '<div class="rf-other"><input class="form-control form-control-sm" data-other="' + id + '" placeholder="Autre…" value="' + esc(other ? v.slice(6) : "") + '"></div>' : '') + '</div>';
            }
            if (t === "MULTIPLE") {
                var sel = list(v) || [];
                var otherVal = sel.filter(function (x) { return x.indexOf("Autre:") === 0; })[0];
                return '<div class="rf-opts">' + (q.options || []).map(function (o, i) {
                    return opt(o, sel.some(function (x) { return x.toLowerCase() === String(o.label).toLowerCase(); }), "multi", i);
                }).join("") + (q.allowOther ? '<div class="rf-other"><input class="form-control form-control-sm" data-other-multi="' + id + '" placeholder="Autre…" value="' + esc(otherVal ? otherVal.slice(6) : "") + '"></div>' : '') + '</div>';
            }
            if (t === "DROPDOWN") {
                var many = (q.options || []).length > 8;
                return (many ? '<input class="form-control form-control-sm rf-filter mb-1" data-filter="' + id + '" placeholder="Rechercher parmi ' + q.options.length + ' choix…">' : '') +
                    '<select class="form-select rf-in" data-select="' + id + '"><option value="">— Choisir —</option>' + (q.options || []).map(function (o) {
                        return '<option' + (findOption(q, v) === o ? " selected" : "") + '>' + esc(o.label) + '</option>';
                    }).join("") + '</select>';
            }
            if (t === "SCALE" || t === "NPS" || t === "RATING") {
                var min = t === "NPS" ? 0 : (q.scale || {}).min || 1, max = t === "NPS" ? 10 : (q.scale || {}).max || 5, cur = num(v), cells = "";
                for (var n = min; n <= max; n++) {
                    var cls = t === "NPS" ? (n <= 6 ? " det" : n <= 8 ? " pas" : " pro") : "";
                    cells += t === "RATING"
                        ? '<button type="button" class="rf-star' + (cur != null && n <= cur ? " on" : "") + '" data-scale="' + id + '" data-v="' + n + '" title="' + n + '"><i class="bi bi-star-fill"></i></button>'
                        : '<button type="button" class="rf-cell' + cls + (cur === n ? " on" : "") + '" data-scale="' + id + '" data-v="' + n + '">' + n + '</button>';
                }
                var sc = q.scale || {};
                return '<div class="rf-scale ' + (t === "RATING" ? "stars" : "") + '">' + cells + '</div>' +
                    (sc.minLabel || sc.maxLabel || t === "NPS" ? '<div class="rf-scale-lbl"><span>' + esc(sc.minLabel || (t === "NPS" ? "Pas du tout" : "")) + '</span><span>' + esc(sc.maxLabel || (t === "NPS" ? "Certainement" : "")) + '</span></div>' : '');
            }
            if (t === "MATRIX") {
                var mv = map(v) || {};
                return '<div class="table-responsive"><table class="rf-matrix"><thead><tr><th></th>' + (q.options || []).map(function (o) { return '<th>' + esc(o.label) + '</th>'; }).join("") + '</tr></thead><tbody>' +
                    (q.rows || []).map(function (r) {
                        return '<tr><th>' + esc(r.label) + '</th>' + (q.options || []).map(function (o) {
                            return '<td><button type="button" class="rf-dot' + (mv[r.label] === o.label ? " on" : "") + '" data-matrix="' + id + '" data-row="' + esc(r.label) + '" data-v="' + esc(o.label) + '" aria-label="' + esc(r.label + " : " + o.label) + '"></button></td>';
                        }).join("") + '</tr>';
                    }).join("") + '</tbody></table></div>';
            }
            if (t === "RANKING") {
                var order = list(v) || (q.options || []).map(function (o) { return o.label; });
                return '<ol class="rf-rank">' + order.map(function (lbl, i) {
                    return '<li><span class="rf-rank-n">' + (i + 1) + '</span><span class="flex-grow-1">' + esc(lbl) + '</span>' +
                        '<button type="button" class="btn btn-sm btn-light" data-rank="' + id + '" data-i="' + i + '" data-dir="-1" ' + (i === 0 ? "disabled" : "") + '><i class="bi bi-arrow-up"></i></button>' +
                        '<button type="button" class="btn btn-sm btn-light" data-rank="' + id + '" data-i="' + i + '" data-dir="1" ' + (i === order.length - 1 ? "disabled" : "") + '><i class="bi bi-arrow-down"></i></button></li>';
                }).join("") + '</ol>' + (v ? "" : '<div class="rf-hint"><button type="button" class="btn btn-sm btn-outline-primary" data-rank-ok="' + id + '">Valider cet ordre</button></div>');
            }
            if (t === "CONSENT") return '<button type="button" class="rf-opt rf-consent' + (String(v) === "true" ? " on" : "") + '" data-consent="' + id + '"><span class="rf-mark sq"></span><span>' + esc(q.label) + '</span></button>';
            return "";
        }

        function questionHtml(q) {
            var err = ev.errors[q.id], showErr = err && (touched[q.id] || showAllErrors) && !(err === "Obligatoire." && !showAllErrors);
            if (q.type === "STATEMENT") {
                return '<div class="rf-q rf-statement"><i class="bi bi-info-circle-fill"></i><div><b>' + esc(pipe(q.label, ctx, answers, form)) + '</b>' +
                    (q.help ? '<div>' + esc(pipe(q.help, ctx, answers, form)) + '</div>' : '') + '</div></div>';
            }
            return '<div class="rf-q' + (showErr ? " err" : "") + '" data-q="' + esc(q.id) + '">' +
                (q.type === "CONSENT" ? "" : '<label class="rf-label">' + esc(pipe(q.label, ctx, answers, form)) + (q.required ? ' <span class="rf-req">*</span>' : '') + '</label>') +
                (q.help ? '<div class="rf-help">' + esc(pipe(q.help, ctx, answers, form)) + '</div>' : '') +
                inputHtml(q) + '<div class="rf-err" data-err="' + esc(q.id) + '">' + (showErr ? '<i class="bi bi-exclamation-circle"></i> ' + esc(err === "Obligatoire." ? "Réponse obligatoire." : err) : "") + '</div></div>';
        }

        function sectionHtml(s) {
            var qs = (s.questions || []).filter(function (q) { return ev.visible[q.id]; });
            return '<section class="rf-section">' +
                '<header><h6>' + esc(pipe(s.title, ctx, answers, form)) + '</h6>' + (s.description ? '<p>' + esc(pipe(s.description, ctx, answers, form)) + '</p>' : '') + '</header>' +
                (s.script ? '<div class="rf-script"><i class="bi bi-chat-quote-fill"></i><div><small>À dire au client</small>' + esc(pipe(s.script, ctx, answers, form)) + '</div></div>' : '') +
                (qs.length ? qs.map(questionHtml).join("") : '<div class="rf-empty">Aucune question dans cette section pour ces réponses.</div>') + '</section>';
        }

        function sectionErrors(s) {
            return (s.questions || []).filter(function (q) { return ev.visible[q.id] && ev.errors[q.id]; });
        }

        function metaHtml() {
            var pct = ev.answerable ? Math.round(ev.answered * 100 / ev.answerable) : 0;
            return '<div class="rf-progress"><div style="width:' + pct + '%"></div></div>' +
                '<div class="rf-meta"><span><b>' + ev.answered + '</b> / ' + ev.answerable + ' réponse(s)</span>' +
                (ev.scorePct != null ? '<span class="rf-score ' + (ev.scorePct >= 60 ? "hi" : ev.scorePct >= 30 ? "mid" : "lo") + '"><i class="bi bi-graph-up-arrow"></i> Score du lead <b>' + ev.scorePct + ' %</b></span>' : '') +
                (ev.suggestedStatus ? '<span class="rf-suggest s-' + ev.suggestedStatus + '"><i class="bi bi-lightbulb-fill"></i> Issue suggérée : <b>' + esc(STATUSES[ev.suggestedStatus]) + '</b>' + (ev.suggestedLabel ? ' — ' + esc(ev.suggestedLabel) : '') + '</span>' : '') + '</div>';
        }

        function refreshMeta() {
            var meta = root.querySelector(".rf-head");
            if (meta) meta.innerHTML = metaHtml();
            Object.keys(touched).forEach(function (id) {
                var box = root.querySelector('[data-err="' + cssEsc(id) + '"]'), err = ev.errors[id];
                if (!box) return;
                var show = err && err !== "Obligatoire.";
                box.innerHTML = show ? '<i class="bi bi-exclamation-circle"></i> ' + esc(err) : "";
                box.parentElement.classList.toggle("err", !!show);
            });
        }

        function cssEsc(s) { return String(s).replace(/["\\]/g, "\\$&"); }

        // Redessiner retire le champ actif ; son « blur » déclenche « change » qui redemanderait un rendu pendant
        // celui en cours : ce second rendu est reporté juste après.
        var drawing = false;
        function draw() {
            if (drawing) { setTimeout(draw, 0); return; }
            drawing = true;
            try { drawNow(); } finally { drawing = false; }
        }

        function drawNow() {
            var secs = sections();
            if (step >= secs.length) step = Math.max(0, secs.length - 1);
            var body = paged && secs.length ? sectionHtml(secs[step]) : secs.map(sectionHtml).join("");
            root.innerHTML = '<div class="rcc-form">' +
                '<div class="rf-head">' + metaHtml() + '</div>' +
                (paged && secs.length > 1 ? '<nav class="rf-steps">' + secs.map(function (s, i) {
                    var errs = sectionErrors(s).length && showAllErrors;
                    return '<button type="button" class="' + (i === step ? "on" : "") + (errs ? " bad" : "") + '" data-step="' + i + '"><span>' + (i + 1) + '</span>' + esc(s.title) + '</button>';
                }).join("") + '</nav>' : '') +
                '<div class="rf-body">' + body + '</div>' +
                (paged && secs.length > 1 ? '<div class="rf-nav"><button type="button" class="btn btn-light" data-nav="-1"' + (step === 0 ? " disabled" : "") + '><i class="bi bi-arrow-left"></i> Précédent</button>' +
                    '<span class="small text-muted">Section ' + (step + 1) + ' / ' + secs.length + '</span>' +
                    (step < secs.length - 1 ? '<button type="button" class="btn btn-primary" data-nav="1">Suivant <i class="bi bi-arrow-right"></i></button>'
                        : '<span class="rf-done ' + (ev.valid ? "ok" : "") + '"><i class="bi ' + (ev.valid ? "bi-check-circle-fill" : "bi-hourglass-split") + '"></i> ' + (ev.valid ? "Formulaire complet" : "Questions obligatoires à compléter") + '</span>') + '</div>' : '') +
                '</div>';
        }

        function focusFirstError() {
            var secs = sections();
            for (var i = 0; i < secs.length; i++) {
                if (sectionErrors(secs[i]).length) { step = i; break; }
            }
        }

        root.addEventListener("click", onClick);
        root.addEventListener("input", onInput);
        root.addEventListener("change", onChange);
        root.addEventListener("keydown", onKey);

        function onClick(e) {
            var b = e.target.closest("button");
            if (!b || !root.contains(b)) return;
            var d = b.dataset;
            if (d.step != null) { step = Number(d.step); draw(); return; }
            if (d.nav) {
                var secs = sections();
                if (Number(d.nav) > 0) {
                    var errs = sectionErrors(secs[step]);
                    if (errs.length) { errs.forEach(function (q) { touched[q.id] = true; }); showAllErrors = true; draw(); return; }
                }
                step = Math.max(0, Math.min(secs.length - 1, step + Number(d.nav)));
                draw();
                root.scrollIntoView({ block: "nearest" });
                return;
            }
            if (d.single) { var q1 = qOf(d.single); changed(d.single, findOption(q1, answers[d.single]) && answers[d.single].toLowerCase() === d.v.toLowerCase() && !q1.required ? null : d.v, true); return; }
            if (d.multi) {
                var cur = list(answers[d.multi]) || [], idx = cur.map(function (x) { return x.toLowerCase(); }).indexOf(d.v.toLowerCase());
                if (idx === -1) cur.push(d.v); else cur.splice(idx, 1);
                changed(d.multi, cur.length ? JSON.stringify(cur) : null, true);
                return;
            }
            if (d.scale) { changed(d.scale, answers[d.scale] === d.v ? null : d.v, true); return; }
            if (d.matrix) { var mm = map(answers[d.matrix]) || {}; mm[d.row] = d.v; changed(d.matrix, JSON.stringify(mm), true); return; }
            if (d.rank) {
                var q2 = qOf(d.rank), order = list(answers[d.rank]) || (q2.options || []).map(function (o) { return o.label; });
                var i = Number(d.i), j = i + Number(d.dir), tmp = order[i];
                order[i] = order[j]; order[j] = tmp;
                changed(d.rank, JSON.stringify(order), true);
                return;
            }
            if (d.rankOk) { var q3 = qOf(d.rankOk); changed(d.rankOk, JSON.stringify((q3.options || []).map(function (o) { return o.label; })), true); return; }
            if (d.consent) { changed(d.consent, String(answers[d.consent]) === "true" ? null : "true", true); return; }
        }

        function onInput(e) {
            var el = e.target, d = el.dataset;
            if (d.text) {
                changed(d.text, el.value.trim() === "" ? null : el.value, false);
                var c = root.querySelector('[data-count="' + cssEsc(d.text) + '"]');
                if (c) c.textContent = el.value.length;
            } else if (d.amount) {
                var raw = el.value.replace(/[^\d]/g, "");
                changed(d.amount, raw || null, false);
            } else if (d.other) {
                changed(d.other, el.value.trim() ? "Autre:" + el.value.trim() : null, false);
            } else if (d.otherMulti) {
                var cur = (list(answers[d.otherMulti]) || []).filter(function (x) { return x.indexOf("Autre:") !== 0; });
                if (el.value.trim()) cur.push("Autre:" + el.value.trim());
                changed(d.otherMulti, cur.length ? JSON.stringify(cur) : null, false);
            } else if (d.filter) {
                var sel = root.querySelector('[data-select="' + cssEsc(d.filter) + '"]'), f = el.value.toLowerCase();
                Array.prototype.forEach.call(sel.options, function (o, i) { if (i) o.hidden = f && o.textContent.toLowerCase().indexOf(f) === -1; });
            }
        }

        function onChange(e) {
            var el = e.target, d = el.dataset;
            if (d.select) { changed(d.select, el.value || null, true); return; }
            if (d.amount) { el.value = el.value ? fmtNum(el.value.replace(/[^\d]/g, "")) : ""; }
            if (d.text || d.amount || d.other || d.otherMulti) { touched[d.text || d.amount || d.other || d.otherMulti] = true; draw(); }
        }

        /** Raccourcis : chiffres 1–9 pour choisir une réponse dans la question qui a le focus. */
        function onKey(e) {
            if (!/^[1-9]$/.test(e.key) || /INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) return;
            var qBox = e.target.closest && e.target.closest(".rf-q");
            if (!qBox) return;
            var opts = qBox.querySelectorAll("[data-single],[data-multi]");
            var b = opts[Number(e.key) - 1];
            if (b) { e.preventDefault(); b.click(); var again = root.querySelector('[data-q="' + cssEsc(qBox.getAttribute("data-q")) + '"] .rf-opt'); if (again) again.focus(); }
        }

        function qOf(id) { return questions(form).filter(function (q) { return q.id === id; })[0] || {}; }

        draw();
        if (opts.onChange) opts.onChange(ev);

        return {
            answers: function () { return Object.assign({}, ev.answers); },
            rawAnswers: function () { return Object.assign({}, answers); },
            evaluation: function () { return ev; },
            /** Affiche toutes les erreurs et place la section concernée ; true si le formulaire est complet. */
            validate: function () { showAllErrors = true; focusFirstError(); draw(); return ev.valid; },
            setAnswers: function (a) { answers = Object.assign({}, a || {}); ev = evaluate(form, answers); draw(); },
            clearDraft: function () { if (draftKey && store) { try { store.removeItem(draftKey); } catch (e) { /* rien */ } } },
            destroy: function () {
                root.removeEventListener("click", onClick); root.removeEventListener("input", onInput);
                root.removeEventListener("change", onChange); root.removeEventListener("keydown", onKey); root.innerHTML = "";
            }
        };
    }

    return { TYPES: TYPES, STATUSES: STATUSES, evaluate: evaluate, matches: matches, render: render, pipe: pipe, questions: questions,
        readable: readable, esc: esc, list: list, map: map, num: num, fmtNum: fmtNum, findOption: findOption };
})();

/**
 * Résultats d'une campagne (GET /api/campaigns/{id}/results) — indicateurs, entonnoir, activité quotidienne,
 * score des leads, synthèse question par question, agents et leads prioritaires.
 * Une seule teinte pour les grandeurs ; le NPS en divergent (détracteurs / passifs / promoteurs) avec libellés.
 */
window.RccFormResults = (function () {
    var esc = RccForm.esc;
    var BLUE = "#2A78D6", NEG = "#D64545", MID = "#B9C0CC", POS = "#1F9D55";

    function pct(n, d) { return d ? Math.round(n * 100 / d) : 0; }
    function fmt(n) { return n == null ? "—" : Number(n).toLocaleString("fr-FR"); }

    function bars(items, total, opts) {
        opts = opts || {};
        var max = opts.max || Math.max.apply(null, items.map(function (i) { return i.count; }).concat([1]));
        return items.map(function (i) {
            var w = Math.max(0.5, i.count * 100 / max);
            var share = total ? " (" + pct(i.count, total) + " %)" : "";
            return '<div class="cr-bar" title="' + esc(i.label + " : " + fmt(i.count) + share) + '"><span class="cr-l">' + esc(i.label) + '</span>' +
                '<span class="cr-track"><span class="cr-t" style="display:block;width:' + w + '%;background:' + (opts.color || BLUE) + '"></span></span>' +
                '<span class="cr-v">' + fmt(i.count) + (total ? " · " + pct(i.count, total) + " %" : "") + '</span></div>';
        }).join("");
    }

    function cols(items, color) {
        var max = Math.max.apply(null, items.map(function (i) { return i.count; }).concat([1]));
        return '<div class="cr-cols">' + items.map(function (i) {
            return '<div title="' + esc(i.title || (i.label + " : " + fmt(i.count))) + '"><span style="height:' + (i.count ? Math.max(2, i.count * 100 / max) : 0) + '%;background:' + (color || BLUE) + '"></span></div>';
        }).join("") + '</div><div class="cr-cols-x">' + items.map(function (i, k) {
            // Au-delà de 12 colonnes, une étiquette sur cinq (et la dernière) : pas de chevauchement.
            var show = items.length <= 12 || k % 5 === 0 || k === items.length - 1;
            return '<span>' + (show ? esc(i.short != null ? i.short : i.label) : "") + '</span>';
        }).join("") + '</div>';
    }

    function funnelHtml(f) {
        var top = f.length ? f[0].count : 0;
        return f.map(function (s, i) {
            var conv = i > 0 && f[i - 1].count ? '<div class="cr-conv">' + pct(s.count, f[i - 1].count) + ' % de l\'étape précédente</div>' : "";
            return conv + bars([s], top, { max: top || 1 });
        }).join("");
    }

    function questionCard(q) {
        var head = '<h6>' + esc(q.label) + '</h6><div class="cr-sub">' + esc((RccForm.TYPES[q.type] || [q.type])[0]) + ' · ' + fmt(q.answered) + ' réponse(s)</div>';
        if (!q.answered) return '<div class="cr-card">' + head + '<div class="cr-empty">Pas encore de réponse.</div></div>';
        var body = "";
        if (q.options) body = bars(q.options.map(function (o) { return { label: o.label, count: o.count }; }), q.type === "MULTIPLE" ? 0 : q.answered) +
            (q.type === "MULTIPLE" ? '<div class="cr-sub mt-1">Plusieurs réponses possibles : pourcentages non cumulables.</div>' : "");
        if (q.nps) {
            var n = q.nps, tot = n.promoters + n.passives + n.detractors;
            body += '<div class="d-flex align-items-end gap-3"><div><div class="cr-big">' + (n.score > 0 ? "+" : "") + n.score + '</div><div class="cr-sub">Score NPS (−100 à +100)</div></div></div>' +
                '<div class="cr-stack" role="img" aria-label="Détracteurs ' + n.detractors + ', passifs ' + n.passives + ', promoteurs ' + n.promoters + '">' +
                [[n.detractors, NEG, "Détracteurs (0–6)"], [n.passives, MID, "Passifs (7–8)"], [n.promoters, POS, "Promoteurs (9–10)"]].map(function (p) {
                    return p[0] ? '<span title="' + esc(p[2] + " : " + p[0] + " (" + pct(p[0], tot) + " %)") + '" style="flex:' + p[0] + ';background:' + p[1] + '"></span>' : "";
                }).join("") + '</div><div class="cr-legend">' +
                '<span><i style="background:' + NEG + '"></i>Détracteurs ' + pct(n.detractors, tot) + ' %</span><span><i style="background:' + MID + '"></i>Passifs ' + pct(n.passives, tot) + ' %</span>' +
                '<span><i style="background:' + POS + '"></i>Promoteurs ' + pct(n.promoters, tot) + ' %</span></div>';
        }
        if (q.distribution) {
            body += (q.nps ? '<div class="mt-2"></div>' : '<div class="cr-big mb-1">' + fmt(q.average) + '<small class="cr-sub"> moyenne</small></div>') +
                cols(q.distribution.map(function (d) { return { label: String(d.value), count: d.count, title: "Note " + d.value + " : " + d.count + " réponse(s)" }; }));
        } else if (q.average != null) {
            body += '<div class="cr-stats"><span>Moyenne <b>' + fmt(q.average) + '</b></span><span>Médiane <b>' + fmt(q.median) + '</b></span><span>Min <b>' + fmt(q.min) + '</b></span><span>Max <b>' + fmt(q.max) + '</b></span>' +
                (q.total != null ? '<span>Total <b>' + fmt(q.total) + ' FCFA</b></span>' : '') + '</div>';
        }
        if (q.matrix) {
            var colsLbl = q.matrix.length ? Object.keys(q.matrix[0].counts) : [];
            var maxCell = 1;
            q.matrix.forEach(function (r) { colsLbl.forEach(function (c) { maxCell = Math.max(maxCell, r.counts[c]); }); });
            body += '<div class="table-responsive"><table class="cr-heat"><thead><tr><th></th>' + colsLbl.map(function (c) { return '<th>' + esc(c) + '</th>'; }).join("") + '</tr></thead><tbody>' +
                q.matrix.map(function (r) {
                    return '<tr><th class="text-start">' + esc(r.row) + '</th>' + colsLbl.map(function (c) {
                        var v = r.counts[c], a = v / maxCell;
                        return '<td title="' + esc(r.row + " · " + c + " : " + v) + '" style="background:rgba(42,120,214,' + (0.08 + a * 0.6).toFixed(2) + ');color:' + (a > 0.6 ? "#fff" : "#122240") + '">' + v + '</td>';
                    }).join("") + '</tr>';
                }).join("") + '</tbody></table></div>';
        }
        if (q.ranking) {
            body += '<ol class="mb-0 small">' + q.ranking.map(function (r) { return '<li><b>' + esc(r.label) + '</b> <span class="text-muted">— rang moyen ' + r.avgRank + '</span></li>'; }).join("") + '</ol>';
        }
        if (q.keywords && q.keywords.length) body += '<div class="cr-chips mb-2">' + q.keywords.map(function (k) { return '<span title="' + k.count + ' fois">' + esc(k.word) + ' · ' + k.count + '</span>'; }).join("") + '</div>';
        if (q.latest) body += q.latest.slice(0, 6).map(function (v) { return '<div class="cr-verb">' + esc(v) + '</div>'; }).join("");
        return '<div class="cr-card">' + head + body + '</div>';
    }

    function render(root, d, opts) {
        opts = opts || {};
        var f = d.funnel || [], total = f.length ? f[0].count : 0, called = f[2] ? f[2].count : 0, reached = f[3] ? f[3].count : 0;
        var st = d.statuses || {}, sc = d.scoring || {};
        var kpis = [["Contacts", fmt(total)], ["Appelés · " + pct(called, total) + " % des contacts", fmt(called)],
            ["Joints · " + pct(reached, called) + " % des appelés", fmt(reached)], ["RDV pris", fmt(st.YELLOW || 0)],
            ["Qualifiés", fmt(f[5] ? f[5].count : 0)], ["Score moyen", sc.average == null ? "—" : sc.average + " %"]];
        var daily = d.daily || [];
        var dailyItems = daily.map(function (x) {
            var n = (x.GREEN || 0) + (x.RED || 0) + (x.YELLOW || 0);
            var day = String(x.date).slice(8, 10) + "/" + String(x.date).slice(5, 7);
            return { label: day, short: day, count: n,
                title: day + " : " + n + " appel(s) — interaction " + (x.GREEN || 0) + ", RDV " + (x.YELLOW || 0) + ", pas de réponse " + (x.RED || 0) };
        });
        root.innerHTML = '<div class="cr">' +
            '<div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2"><div class="cr-sub mb-0">' + fmt(d.responses) + ' formulaire(s) renseigné(s)</div>' +
            '<div class="d-flex gap-2"><button type="button" class="btn btn-sm btn-outline-secondary" data-cr-refresh><i class="bi bi-arrow-clockwise"></i> Actualiser</button>' +
            '<a class="btn btn-sm btn-success" href="/api/campaigns/' + d.campaignId + '/results.csv"><i class="bi bi-filetype-csv"></i> Exporter toutes les réponses</a></div></div>' +
            '<div class="cr-kpis">' + kpis.map(function (k) { return '<div class="cr-kpi"><b>' + k[1] + '</b><span>' + k[0] + '</span></div>'; }).join("") + '</div>' +
            '<div class="cr-grid mb-3">' +
            '<div class="cr-card"><h6>Entonnoir de la campagne</h6><div class="cr-sub">Du fichier de contacts aux leads qualifiés</div><div class="cr-funnel">' + funnelHtml(f) + '</div></div>' +
            '<div class="cr-card"><h6>Issues d\'appel</h6><div class="cr-sub">Statut actuel des contacts</div>' +
                bars([{ label: "Interaction", count: st.GREEN || 0 }, { label: "RDV pris", count: st.YELLOW || 0 }, { label: "Pas de réponse", count: st.RED || 0 }, { label: "À appeler", count: st.PENDING || 0 }], total) + '</div>' +
            '<div class="cr-card"><h6>Appels par jour</h6><div class="cr-sub">45 derniers jours · survolez une colonne pour le détail</div>' +
                (dailyItems.length ? cols(dailyItems) : '<div class="cr-empty">Aucun appel enregistré sur la période.</div>') + '</div>' +
            '<div class="cr-card"><h6>Score des leads</h6><div class="cr-sub">' + (sc.count ? fmt(sc.count) + ' lead(s) noté(s)' + (sc.threshold ? ' · qualifié à partir de ' + sc.threshold + ' %' : '') : 'Aucune question notée ou pas encore de réponse') + '</div>' +
                (sc.count ? cols((sc.buckets || []).map(function (b) { return { label: b.label, count: b.count, title: b.label + " % : " + b.count + " lead(s)" }; })) : '') + '</div>' +
            '</div>' +
            '<h6 class="fw-bold mt-2">Réponses question par question</h6><div class="cr-grid mb-3">' + (d.questions || []).map(questionCard).join("") + '</div>' +
            '<div class="cr-grid">' +
            '<div class="cr-card" style="grid-column:1/-1"><h6>Performance par agent</h6><div class="table-responsive"><table class="table table-sm cr-table mb-0"><thead><tr><th>Agent</th><th class="text-end">Assignés</th><th class="text-end">Appelés</th><th>Avancement</th><th class="text-end">Joints</th><th class="text-end">Joignabilité</th><th class="text-end">RDV</th><th class="text-end">Qualifiés</th><th class="text-end">Score moyen</th></tr></thead><tbody>' +
                ((d.agents || []).length ? d.agents.map(function (a) {
                    return '<tr><td>' + esc(a.name) + '</td><td class="num">' + fmt(a.assigned) + '</td><td class="num">' + fmt(a.called) + '</td><td><div class="d-flex align-items-center gap-2"><div class="cr-mini flex-grow-1"><div style="width:' + a.progressPct + '%"></div></div><small>' + a.progressPct + ' %</small></div></td>' +
                        '<td class="num">' + fmt(a.reached) + '</td><td class="num">' + (a.reachPct == null ? "—" : a.reachPct + " %") + '</td><td class="num">' + fmt(a.appointments) + '</td><td class="num">' + fmt(a.qualified) + '</td><td class="num">' + (a.avgScore == null ? "—" : a.avgScore + " %") + '</td></tr>';
                }).join("") : '<tr><td colspan="9" class="cr-empty">Aucun contact assigné.</td></tr>') + '</tbody></table></div></div>' +
            '<div class="cr-card" style="grid-column:1/-1"><h6>Leads à traiter en priorité</h6><div class="cr-sub">Meilleurs scores de la campagne</div>' +
                ((d.topLeads || []).length ? '<table class="table table-sm cr-table mb-0"><thead><tr><th>Client</th><th class="text-end">Score</th><th>Statut</th><th>Issue suggérée</th></tr></thead><tbody>' + d.topLeads.map(function (l) {
                    return '<tr><td>' + esc(l.client) + '</td><td class="num"><b>' + l.score + ' %</b></td><td>' + esc(RccForm.STATUSES[l.status] || l.status) + '</td><td>' + esc(l.suggested) + '</td></tr>';
                }).join("") + '</tbody></table>' : '<div class="cr-empty">Pas encore de lead noté.</div>') + '</div>' +
            '</div></div>';
        var r = root.querySelector("[data-cr-refresh]");
        if (r && opts.onRefresh) r.addEventListener("click", opts.onRefresh);
    }

    return { render: render };
})();
