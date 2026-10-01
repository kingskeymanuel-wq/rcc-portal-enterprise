"use strict";

/**
 * Concepteur de formulaires de campagne (Team Leader Outbound) — RccFormBuilder.mount(root, { form, campaignName, onChange })
 * → { getForm(), setForm(form), issues() }.
 *
 * Colonne gauche : sections et questions (sélection, ordre, duplication, suppression), modèles prêts et génération par
 * l'IA. Centre : l'élément choisi — intitulé, aide, type (19 types), choix avec points et issue d'appel, grille,
 * échelle, contrôles, pré-remplissage, conditions d'affichage, script d'appel avec variables. Droite : aperçu agent
 * en direct (même rendu que pendant l'appel), score et issue suggérée. Onglet « Issues & score » : seuil de
 * qualification et règles d'issue d'appel.
 */
window.RccFormBuilder = (function () {
    var esc = RccForm.esc, TYPES = RccForm.TYPES, STATUSES = RccForm.STATUSES;
    var CHOICE_TYPES = { SINGLE: 1, MULTIPLE: 1, DROPDOWN: 1, RANKING: 1, YES_NO: 1, MATRIX: 1 };
    var OPS = [["eq", "est"], ["neq", "n'est pas"], ["in", "est l'un de"], ["nin", "n'est aucun de"], ["contains", "contient"], ["ncontains", "ne contient pas"],
        ["gt", ">"], ["gte", "≥"], ["lt", "<"], ["lte", "≤"], ["notempty", "est renseigné"], ["empty", "est vide"]];
    var VARS = [["{{client.prenom}}", "Prénom client"], ["{{client.nom}}", "Nom client"], ["{{agent.prenom}}", "Mon prénom"], ["{{campagne}}", "Campagne"]];
    var uid = 0;
    function newId(p) { uid++; return p + Date.now().toString(36).slice(-4) + uid; }
    function clone(o) { return JSON.parse(JSON.stringify(o)); }

    function emptyForm() {
        return { version: 2, settings: { showProgress: true, qualifiedThreshold: 0 },
            sections: [{ id: "s1", title: "Prise de contact", script: "Bonjour {{client.prenom}}, je suis {{agent.prenom}} d'Ecobank…", questions: [] }], outcomes: [] };
    }

    function newQuestion(type) {
        var q = { id: newId("q"), type: type, label: "", required: false };
        if (type === "SINGLE" || type === "MULTIPLE" || type === "DROPDOWN" || type === "RANKING") q.options = [{ label: "Option 1" }, { label: "Option 2" }];
        if (type === "YES_NO") q.options = [{ label: "Oui" }, { label: "Non" }];
        if (type === "MATRIX") { q.options = [{ label: "Insatisfait" }, { label: "Satisfait" }, { label: "Très satisfait" }]; q.rows = [{ label: "Critère 1" }, { label: "Critère 2" }]; }
        if (type === "SCALE") q.scale = { min: 1, max: 5, minLabel: "", maxLabel: "" };
        if (type === "RATING") q.scale = { min: 1, max: 5 };
        if (type === "STATEMENT") q.label = "Information pour l'agent";
        return q;
    }

    function mount(root, opts) {
        opts = opts || {};
        var form = opts.form ? clone(opts.form) : emptyForm();
        var sel = { s: 0, q: -1 }, tab = "build", preview = null, previewAll = false, showTypes = false;

        function sec() { return form.sections[sel.s]; }
        function cur() { return sel.q >= 0 ? sec().questions[sel.q] : null; }
        function allQuestions() { return RccForm.questions(form); }

        /** Questions placées avant l'élément (les seules qu'une condition peut utiliser). */
        function earlier(sIdx, qIdx) {
            var out = [];
            form.sections.forEach(function (s, i) {
                (s.questions || []).forEach(function (q, j) {
                    if (q.type === "STATEMENT") return;
                    if (i < sIdx || (i === sIdx && qIdx != null && j < qIdx)) out.push(q);
                });
            });
            return out;
        }

        function issues() {
            var out = [], ids = {};
            allQuestions().forEach(function (q) { ids[q.id] = true; });
            form.sections.forEach(function (s, i) {
                if (!(s.questions || []).length) out.push("La section « " + (s.title || i + 1) + " » est vide.");
                (s.questions || []).forEach(function (q) {
                    var name = q.label ? "« " + q.label + " »" : "Une question sans intitulé (section « " + s.title + " »)";
                    if (!q.label) out.push(name + " : intitulé à saisir.");
                    if (CHOICE_TYPES[q.type] && !(q.options || []).filter(function (o) { return o.label; }).length) out.push(name + " : aucun choix.");
                    if (q.type === "MATRIX" && !(q.rows || []).length) out.push(name + " : aucune ligne.");
                    ((q.visibleIf || {}).rules || []).forEach(function (r) { if (!ids[r.q]) out.push(name + " : condition sur une question supprimée."); });
                });
            });
            (form.outcomes || []).forEach(function (o, i) { if (!((o.when || {}).rules || []).length) out.push("Règle d'issue n°" + (i + 1) + " : condition à définir."); });
            return out;
        }

        // ───────────── Structure (colonne gauche) ─────────────

        function structHtml() {
            var bad = {};
            form.sections.forEach(function (s) { (s.questions || []).forEach(function (q) { if (!q.label || (CHOICE_TYPES[q.type] && !(q.options || []).length)) bad[q.id] = true; }); });
            return '<div class="fb-tools"><button type="button" class="btn btn-outline-primary" data-act="templates"><i class="bi bi-collection"></i> Modèles</button>' +
                '<button type="button" class="btn btn-primary" data-act="ai"><i class="bi bi-stars"></i> Générer (IA)</button></div>' +
                form.sections.map(function (s, i) {
                    return '<div class="fb-sec"><div class="fb-sec-h' + (sel.s === i && sel.q < 0 ? " on" : "") + '" data-sel-s="' + i + '"><i class="bi bi-layout-text-sidebar-reverse"></i> ' +
                        esc(s.title || "Section " + (i + 1)) + (s.visibleIf && (s.visibleIf.rules || []).length ? ' <i class="bi bi-signpost-split" title="Affichée sous condition"></i>' : '') +
                        '<span class="fb-n">' + (s.questions || []).length + '</span></div>' +
                        '<ul class="fb-qs">' + (s.questions || []).map(function (q, j) {
                            var t = TYPES[q.type] || ["", "bi-question"];
                            return '<li class="fb-qi' + (sel.s === i && sel.q === j ? " on" : "") + '" data-sel-s="' + i + '" data-sel-q="' + j + '"><i class="bi ' + t[1] + '"></i><span class="text-truncate">' +
                                esc(q.label || "(sans intitulé)") + '</span>' + (bad[q.id] ? '<i class="bi bi-exclamation-circle-fill fb-bad" title="À compléter"></i>'
                                : q.visibleIf && (q.visibleIf.rules || []).length ? '<i class="bi bi-signpost-split fb-flag" title="Affichée sous condition"></i>' : (q.required ? '<span class="fb-flag">*</span>' : '')) + '</li>';
                        }).join("") + '</ul>' +
                        '<button type="button" class="fb-add" data-act="add-q" data-s="' + i + '"><i class="bi bi-plus-lg"></i> Question</button></div>';
                }).join("") +
                '<button type="button" class="fb-add mt-2" data-act="add-s"><i class="bi bi-plus-square"></i> Nouvelle section</button>';
        }

        // ───────────── Éditeur (colonne centrale) ─────────────

        function field(label, html, cls) { return '<div class="mb-2 ' + (cls || "") + '"><label class="form-label">' + label + '</label>' + html + '</div>'; }
        function input(path, value, ph, type) { return '<input class="form-control form-control-sm" data-f="' + path + '" value="' + esc(value == null ? "" : value) + '"' + (ph ? ' placeholder="' + esc(ph) + '"' : '') + (type ? ' type="' + type + '"' : '') + '>'; }
        function varsHtml(target) { return '<div class="fb-vars">' + VARS.map(function (v) { return '<button type="button" data-var="' + esc(v[0]) + '" data-target="' + target + '">' + esc(v[1]) + '</button>'; }).join("") + '</div>'; }

        function condHtml(cond, candidates, path, title) {
            cond = cond || { logic: "all", rules: [] };
            var rules = cond.rules || [];
            return '<div class="fb-block"><b><i class="bi bi-signpost-split"></i> ' + title + '</b>' +
                (rules.length > 1 ? '<div class="mb-2 small">Afficher si <select class="form-select form-select-sm d-inline-block w-auto" data-cond-logic="' + path + '"><option value="all"' + (cond.logic !== "any" ? " selected" : "") + '>toutes</option><option value="any"' + (cond.logic === "any" ? " selected" : "") + '>au moins une</option></select> des conditions sont vraies</div>' : '') +
                rules.map(function (r, i) {
                    var q = candidates.filter(function (c) { return c.id === r.q; })[0];
                    var noValue = r.op === "empty" || r.op === "notempty";
                    var multi = r.op === "in" || r.op === "nin";
                    var valHtml;
                    if (noValue) valHtml = '<span></span>';
                    else if (q && (q.options || []).length && !multi && q.type !== "MATRIX") {
                        valHtml = '<select class="form-select form-select-sm" data-rule="' + path + '" data-i="' + i + '" data-k="value"><option value="">— valeur —</option>' +
                            q.options.map(function (o) { return '<option' + (String(r.value) === o.label ? " selected" : "") + '>' + esc(o.label) + '</option>'; }).join("") + '</select>';
                    } else if (q && (q.options || []).length && multi) {
                        var vals = Array.isArray(r.value) ? r.value : r.value ? [r.value] : [];
                        valHtml = '<div class="small">' + q.options.map(function (o) {
                            return '<label class="me-2"><input type="checkbox" class="form-check-input me-1" data-rule-multi="' + path + '" data-i="' + i + '" value="' + esc(o.label) + '"' + (vals.indexOf(o.label) !== -1 ? " checked" : "") + '>' + esc(o.label) + '</label>';
                        }).join("") + '</div>';
                    } else {
                        valHtml = '<input class="form-control form-control-sm" data-rule="' + path + '" data-i="' + i + '" data-k="value" value="' + esc(Array.isArray(r.value) ? r.value.join(" | ") : r.value == null ? "" : r.value) + '" placeholder="valeur">';
                    }
                    return '<div class="fb-rule"><select class="form-select form-select-sm" data-rule="' + path + '" data-i="' + i + '" data-k="q"><option value="">— question —</option>' +
                        candidates.map(function (c) { return '<option value="' + esc(c.id) + '"' + (c.id === r.q ? " selected" : "") + '>' + esc(c.label || c.id) + '</option>'; }).join("") + '</select>' +
                        '<select class="form-select form-select-sm" data-rule="' + path + '" data-i="' + i + '" data-k="op">' + OPS.map(function (o) { return '<option value="' + o[0] + '"' + ((r.op || "eq") === o[0] ? " selected" : "") + '>' + o[1] + '</option>'; }).join("") + '</select>' +
                        valHtml + '<button type="button" class="btn btn-sm btn-light" data-rule-del="' + path + '" data-i="' + i + '" title="Retirer"><i class="bi bi-x-lg"></i></button></div>';
                }).join("") +
                (candidates.length ? '<button type="button" class="btn btn-sm btn-outline-secondary" data-rule-add="' + path + '"><i class="bi bi-plus-lg"></i> Condition</button>'
                    : '<div class="small text-muted">Ajoutez d\'abord des questions avant celle-ci : une condition porte sur une réponse déjà donnée.</div>') +
                (rules.length ? '' : '<div class="small text-muted mt-1">Sans condition : toujours affichée.</div>') + '</div>';
        }

        function sectionEditor() {
            var s = sec();
            return '<h6><i class="bi bi-layout-text-sidebar-reverse"></i> Section ' + (sel.s + 1) + '</h6>' +
                field("Titre", input("s.title", s.title)) +
                field("Description (vue par l'agent)", '<textarea class="form-control form-control-sm" rows="2" data-f="s.description">' + esc(s.description || "") + '</textarea>') +
                field("Script d'appel — ce que l'agent dit au client", '<textarea class="form-control form-control-sm" rows="3" id="fbScript" data-f="s.script">' + esc(s.script || "") + '</textarea>' + varsHtml("fbScript")) +
                condHtml(s.visibleIf, earlier(sel.s, null), "s", "Afficher cette section seulement si…") +
                '<div class="d-flex gap-2 mt-3 flex-wrap"><button type="button" class="btn btn-sm btn-light" data-act="s-up"' + (sel.s === 0 ? " disabled" : "") + '><i class="bi bi-arrow-up"></i> Monter</button>' +
                '<button type="button" class="btn btn-sm btn-light" data-act="s-down"' + (sel.s === form.sections.length - 1 ? " disabled" : "") + '><i class="bi bi-arrow-down"></i> Descendre</button>' +
                '<button type="button" class="btn btn-sm btn-light" data-act="s-dup"><i class="bi bi-copy"></i> Dupliquer</button>' +
                '<button type="button" class="btn btn-sm btn-outline-danger ms-auto" data-act="s-del"' + (form.sections.length === 1 ? " disabled" : "") + '><i class="bi bi-trash"></i> Supprimer la section</button></div>' +
                typesHtml();
        }

        function typesHtml() {
            return '<div class="fb-block"><b><i class="bi bi-plus-circle"></i> Ajouter une question ' + (sel.q >= 0 ? "après celle-ci" : "à cette section") + '</b><div class="fb-types">' +
                Object.keys(TYPES).map(function (t) { return '<button type="button" data-add-type="' + t + '"><i class="bi ' + TYPES[t][1] + '"></i>' + esc(TYPES[t][0]) + '</button>'; }).join("") + '</div></div>';
        }

        function optionsHtml(q, key, title, withScore) {
            var list = q[key] || [];
            return '<div class="fb-block"><b>' + title + '</b>' + list.map(function (o, i) {
                return '<div class="fb-opt"><span class="fb-grip">' + (i + 1) + '</span>' +
                    '<input class="form-control form-control-sm" data-opt="' + key + '" data-i="' + i + '" data-k="label" value="' + esc(o.label) + '">' +
                    (withScore ? '<input type="number" class="form-control form-control-sm" data-opt="' + key + '" data-i="' + i + '" data-k="score" value="' + esc(o.score == null ? "" : o.score) + '" placeholder="points" title="Points ajoutés au score du lead">' +
                        '<select class="form-select form-select-sm" data-opt="' + key + '" data-i="' + i + '" data-k="outcome" title="Issue d\'appel suggérée si cette réponse est choisie"><option value="">— issue —</option>' +
                        Object.keys(STATUSES).map(function (k) { return '<option value="' + k + '"' + (o.outcome === k ? " selected" : "") + '>' + STATUSES[k] + '</option>'; }).join("") + '</select>' : '<span></span><span></span>') +
                    '<span class="d-flex gap-1"><button type="button" class="btn btn-sm btn-light" data-opt-move="' + key + '" data-i="' + i + '" data-dir="-1"' + (i === 0 ? " disabled" : "") + '><i class="bi bi-arrow-up"></i></button>' +
                    '<button type="button" class="btn btn-sm btn-light" data-opt-del="' + key + '" data-i="' + i + '"><i class="bi bi-x-lg"></i></button></span></div>';
            }).join("") +
                '<div class="d-flex gap-2 mt-1 flex-wrap"><button type="button" class="btn btn-sm btn-outline-secondary" data-opt-add="' + key + '"><i class="bi bi-plus-lg"></i> Ajouter</button>' +
                '<button type="button" class="btn btn-sm btn-outline-secondary" data-opt-paste="' + key + '"><i class="bi bi-clipboard"></i> Coller une liste</button></div></div>';
        }

        function validationHtml(q) {
            var v = q.validation || {}, t = q.type, rows = "";
            var num = function (k, lbl, ph) { return field(lbl, '<input type="number" class="form-control form-control-sm" data-val="' + k + '" value="' + esc(v[k] == null ? "" : v[k]) + '"' + (ph ? ' placeholder="' + ph + '"' : '') + '>'); };
            if (t === "SHORT_TEXT" || t === "LONG_TEXT") {
                rows = '<div class="fb-row">' + num("minLength", "Caractères minimum") + num("maxLength", "Caractères maximum") + '</div>' +
                    field("Format attendu", '<select class="form-select form-select-sm" data-val-preset><option value="">Libre</option>' +
                        [["\\d{11}", "Numéro de compte (11 chiffres)"], ["[A-Za-z]{2}\\d{6,10}", "N° de pièce d'identité"], ["\\d+", "Chiffres uniquement"], ["[^\\d]+", "Sans chiffres"]].map(function (p) {
                            return '<option value="' + esc(p[0]) + '"' + (v.pattern === p[0] ? " selected" : "") + '>' + esc(p[1]) + '</option>';
                        }).join("") + (v.pattern && ["\\d{11}", "[A-Za-z]{2}\\d{6,10}", "\\d+", "[^\\d]+"].indexOf(v.pattern) === -1 ? '<option selected value="' + esc(v.pattern) + '">Personnalisé</option>' : '') + '</select>');
            } else if (t === "NUMBER" || t === "AMOUNT") {
                rows = '<div class="fb-row">' + num("min", "Minimum") + num("max", "Maximum") + '</div>';
            } else if (t === "MULTIPLE") {
                rows = '<div class="fb-row">' + num("minSelect", "Réponses minimum") + num("maxSelect", "Réponses maximum") + '</div>';
            } else if (t === "DATE") {
                rows = '<div class="form-check mb-2"><input class="form-check-input" type="checkbox" id="fbFuture" data-val-future' + (v.minDate === "today" ? " checked" : "") + '><label class="form-check-label small" for="fbFuture">Date à venir uniquement (pas dans le passé)</label></div>';
            }
            if (!rows) return "";
            return '<div class="fb-block"><b><i class="bi bi-shield-check"></i> Contrôle de la réponse</b>' + rows +
                field("Message affiché si la réponse ne convient pas", '<input class="form-control form-control-sm" data-val="message" value="' + esc(v.message || "") + '" placeholder="Message par défaut">') + '</div>';
        }

        function questionEditor() {
            var q = cur(), t = q.type;
            var typeSel = '<select class="form-select form-select-sm" data-qtype>' + Object.keys(TYPES).map(function (k) { return '<option value="' + k + '"' + (k === t ? " selected" : "") + '>' + esc(TYPES[k][0]) + '</option>'; }).join("") + '</select>';
            var html = '<h6><i class="bi ' + (TYPES[t] || ["", "bi-question"])[1] + '"></i> Question</h6>' +
                field(t === "STATEMENT" ? "Texte affiché à l'agent" : "Intitulé de la question", '<textarea class="form-control form-control-sm" rows="2" id="fbLabel" data-f="q.label" placeholder="Ex. Quel montant souhaitez-vous emprunter ?">' + esc(q.label) + '</textarea>' + varsHtml("fbLabel")) +
                '<div class="fb-row">' + field("Type", typeSel) + field("Section", '<select class="form-select form-select-sm" data-qmove-sec>' + form.sections.map(function (s, i) { return '<option value="' + i + '"' + (i === sel.s ? " selected" : "") + '>' + esc(s.title) + '</option>'; }).join("") + '</select>') + '</div>' +
                field("Aide pour l'agent (facultatif)", input("q.help", q.help, "Précision, argument, rappel de conformité…"));
            if (t !== "STATEMENT") {
                html += '<div class="d-flex flex-wrap gap-3 mb-2"><div class="form-check form-switch"><input class="form-check-input" type="checkbox" id="fbReq" data-qbool="required"' + (q.required ? " checked" : "") + '><label class="form-check-label small" for="fbReq">Réponse obligatoire</label></div>' +
                    (t === "SINGLE" || t === "MULTIPLE" ? '<div class="form-check form-switch"><input class="form-check-input" type="checkbox" id="fbOther" data-qbool="allowOther"' + (q.allowOther ? " checked" : "") + '><label class="form-check-label small" for="fbOther">Réponse « Autre » libre</label></div>' : '') + '</div>';
            }
            if (t === "SHORT_TEXT" || t === "LONG_TEXT" || t === "NUMBER" || t === "PHONE" || t === "EMAIL") html += field("Texte indicatif dans le champ", input("q.placeholder", q.placeholder));
            if (t === "SINGLE" || t === "MULTIPLE" || t === "DROPDOWN" || t === "YES_NO") html += optionsHtml(q, "options", "Choix proposés · points pour le score du lead · issue d'appel suggérée", true);
            if (t === "RANKING") html += optionsHtml(q, "options", "Éléments à classer", false);
            if (t === "MATRIX") html += optionsHtml(q, "rows", "Lignes (critères)", false) + optionsHtml(q, "options", "Colonnes (réponses)", false);
            if (t === "SCALE" || t === "RATING") {
                var sc = q.scale || {};
                html += '<div class="fb-block"><b>Échelle</b><div class="fb-row">' +
                    (t === "SCALE" ? field("De", '<select class="form-select form-select-sm" data-scale="min">' + [0, 1].map(function (n) { return '<option' + ((sc.min == null ? 1 : sc.min) === n ? " selected" : "") + '>' + n + '</option>'; }).join("") + '</select>') : '') +
                    field("À", '<select class="form-select form-select-sm" data-scale="max">' + [3, 4, 5, 6, 7, 8, 9, 10].map(function (n) { return '<option' + ((sc.max || 5) === n ? " selected" : "") + '>' + n + '</option>'; }).join("") + '</select>') + '</div>' +
                    (t === "SCALE" ? '<div class="fb-row">' + field("Libellé du minimum", '<input class="form-control form-control-sm" data-scale="minLabel" value="' + esc(sc.minLabel || "") + '">') + field("Libellé du maximum", '<input class="form-control form-control-sm" data-scale="maxLabel" value="' + esc(sc.maxLabel || "") + '">') + '</div>' : '') + '</div>';
            }
            if (t === "SCALE" || t === "RATING" || t === "NPS" || t === "NUMBER") {
                html += '<div class="fb-block"><b><i class="bi bi-graph-up-arrow"></i> Compte dans le score du lead</b>' +
                    field("Poids (points par unité, 0 = non noté)", '<input type="number" step="0.5" min="0" class="form-control form-control-sm" data-f="q.weight" value="' + esc(q.weight || "") + '">') +
                    (t === "NUMBER" ? '<div class="small text-muted">Le maximum du contrôle ci-dessous sert de plafond au score.</div>' : '') + '</div>';
            }
            html += validationHtml(q);
            if (t === "PHONE" || t === "SHORT_TEXT" || t === "EMAIL") {
                var pf = q.prefill || "";
                html += '<div class="fb-block"><b><i class="bi bi-magic"></i> Pré-remplir</b><select class="form-select form-select-sm" data-prefill><option value="">Non</option>' +
                    '<option value="client.phone"' + (pf === "client.phone" ? " selected" : "") + '>Téléphone du contact</option><option value="client.name"' + (pf === "client.name" ? " selected" : "") + '>Nom du contact</option>' +
                    '<option value="extra"' + (pf.indexOf("extra:") === 0 ? " selected" : "") + '>Colonne du fichier importé…</option></select>' +
                    (pf.indexOf("extra:") === 0 ? '<input class="form-control form-control-sm mt-1" data-prefill-col value="' + esc(pf.slice(6)) + '" placeholder="Nom exact de la colonne (ex. Agence)">' : '') + '</div>';
            }
            html += condHtml(q.visibleIf, earlier(sel.s, sel.q), "q", "Afficher cette question seulement si…");
            html += '<div class="d-flex gap-2 mt-3 flex-wrap"><button type="button" class="btn btn-sm btn-light" data-act="q-up"' + (sel.q === 0 ? " disabled" : "") + '><i class="bi bi-arrow-up"></i></button>' +
                '<button type="button" class="btn btn-sm btn-light" data-act="q-down"' + (sel.q === sec().questions.length - 1 ? " disabled" : "") + '><i class="bi bi-arrow-down"></i></button>' +
                '<button type="button" class="btn btn-sm btn-light" data-act="q-dup"><i class="bi bi-copy"></i> Dupliquer</button>' +
                '<button type="button" class="btn btn-sm btn-outline-danger ms-auto" data-act="q-del"><i class="bi bi-trash"></i> Supprimer</button></div>' + typesHtml();
            return html;
        }

        // ───────────── Issues & score ─────────────

        function outcomesHtml() {
            var st = form.settings || (form.settings = {});
            var cands = allQuestions().filter(function (q) { return q.type !== "STATEMENT"; });
            return '<div class="fb-edit"><h6><i class="bi bi-bullseye"></i> Score du lead et issue d\'appel suggérée</h6>' +
                '<p class="small text-muted">Pendant l\'appel, le score se calcule en direct (points des réponses et questions pondérées) et l\'issue suggérée est mise en avant sur les boutons de l\'agent. Le serveur refait le calcul à l\'enregistrement.</p>' +
                '<div class="fb-row">' + field("Lead qualifié à partir de (%)", '<input type="number" min="0" max="100" class="form-control form-control-sm" data-setting="qualifiedThreshold" value="' + esc(st.qualifiedThreshold || 0) + '">') +
                field("Barre de progression", '<select class="form-select form-select-sm" data-setting="showProgress"><option value="true">Affichée</option><option value="false"' + (st.showProgress === false ? " selected" : "") + '>Masquée</option></select>') + '</div>' +
                '<div class="small text-muted mb-2">Ordre de priorité : 1) la première règle ci-dessous qui s\'applique, 2) l\'issue attachée à un choix de réponse, 3) « Interaction » si le score atteint le seuil.</div>' +
                (form.outcomes || []).map(function (o, i) {
                    return '<div class="fb-block"><div class="d-flex gap-2 align-items-center mb-2"><b class="mb-0 text-nowrap">Règle ' + (i + 1) + '</b>' +
                        '<select class="form-select form-select-sm w-auto" data-out="' + i + '" data-k="status">' + Object.keys(STATUSES).map(function (k) { return '<option value="' + k + '"' + (o.status === k ? " selected" : "") + '>' + STATUSES[k] + '</option>'; }).join("") + '</select>' +
                        '<input class="form-control form-control-sm" data-out="' + i + '" data-k="label" value="' + esc(o.label || "") + '" placeholder="Libellé (ex. Lead chaud)">' +
                        '<button type="button" class="btn btn-sm btn-outline-danger" data-out-del="' + i + '"><i class="bi bi-trash"></i></button></div>' +
                        condHtml(o.when, cands, "o" + i, "Quand…") + '</div>';
                }).join("") +
                '<button type="button" class="btn btn-sm btn-outline-primary" data-out-add><i class="bi bi-plus-lg"></i> Règle d\'issue</button></div>';
        }

        // ───────────── Rendu ─────────────

        function draw() {
            var probs = issues();
            root.innerHTML = '<div class="fb-tabs"><button type="button" data-tab="build" class="' + (tab === "build" ? "on" : "") + '"><i class="bi bi-ui-checks-grid"></i> Questions &amp; logique</button>' +
                '<button type="button" data-tab="outcomes" class="' + (tab === "outcomes" ? "on" : "") + '"><i class="bi bi-bullseye"></i> Score &amp; issues d\'appel</button>' +
                '<span class="small text-muted align-self-center ms-2">' + form.sections.length + ' section(s) · ' + allQuestions().length + ' question(s)</span></div>' +
                '<div class="fb">' +
                '<aside class="fb-col fb-struct">' + structHtml() + '</aside>' +
                '<div class="fb-col">' + (probs.length ? '<div class="fb-issues m-2"><b><i class="bi bi-exclamation-triangle"></i> À compléter avant d\'enregistrer</b><ul>' + probs.slice(0, 6).map(function (p) { return '<li>' + esc(p) + '</li>'; }).join("") + '</ul></div>' : '') +
                (tab === "outcomes" ? outcomesHtml() : '<div class="fb-edit">' + (sel.q >= 0 ? questionEditor() : sectionEditor()) + '</div>') + '</div>' +
                '<aside class="fb-col fb-preview"><div class="fb-preview-h"><span><i class="bi bi-phone"></i> Aperçu agent (en direct)</span>' +
                '<span><label class="small"><input type="checkbox" class="form-check-input me-1" data-preview-all' + (previewAll ? " checked" : "") + '>Tout sur une page</label> ' +
                '<button type="button" class="btn btn-sm btn-light" data-act="preview-reset" title="Effacer les réponses de test"><i class="bi bi-arrow-counterclockwise"></i></button></span></div>' +
                '<div class="fb-phone" id="fbPreview"></div></aside></div>';
            drawPreview();
        }

        var previewAnswers = {};
        function drawPreview() {
            var box = root.querySelector("#fbPreview");
            if (!box) return;
            if (preview) { previewAnswers = preview.rawAnswers(); preview.destroy(); }
            preview = RccForm.render(box, form, {
                answers: previewAnswers, paged: !previewAll,
                context: { client: { name: "KOFFI Jean Marc", phone: "+225 07 07 07 07 07" }, agent: { name: "Awa BAMBA" }, campaign: opts.campaignName ? opts.campaignName() : "" }
            });
        }

        var previewTimer = null;
        function soft() {
            // Saisie en cours : structure et aperçu mis à jour, l'éditeur garde le focus.
            var st = root.querySelector(".fb-struct");
            if (st) st.innerHTML = structHtml();
            clearTimeout(previewTimer);
            previewTimer = setTimeout(drawPreview, 250);
            if (opts.onChange) opts.onChange(form);
        }
        function hard() { draw(); if (opts.onChange) opts.onChange(form); }

        function condAt(path) {
            if (path === "s") return sec().visibleIf || (sec().visibleIf = { logic: "all", rules: [] });
            if (path === "q") return cur().visibleIf || (cur().visibleIf = { logic: "all", rules: [] });
            var o = form.outcomes[Number(path.slice(1))];
            return o.when || (o.when = { logic: "all", rules: [] });
        }

        function insertVar(targetId, v) {
            var el = root.querySelector("#" + targetId);
            if (!el) return;
            var a = el.selectionStart || el.value.length, b = el.selectionEnd || a;
            el.value = el.value.slice(0, a) + v + el.value.slice(b);
            el.focus();
            el.selectionStart = el.selectionEnd = a + v.length;
            el.dispatchEvent(new Event("input", { bubbles: true }));
        }

        // ───────────── Événements ─────────────

        root.addEventListener("click", function (e) {
            var b = e.target.closest("button,[data-sel-s]");
            if (!b || !root.contains(b)) return;
            var d = b.dataset;
            if (d.tab) { tab = d.tab; draw(); return; }
            if (d.selS != null && !d.act) { sel = { s: Number(d.selS), q: d.selQ != null ? Number(d.selQ) : -1 }; tab = "build"; draw(); return; }
            if (d.var) { insertVar(d.target, d.var); return; }
            if (d.addType) {
                var nq = newQuestion(d.addType), qs = sec().questions || (sec().questions = []);
                var at = sel.q >= 0 ? sel.q + 1 : qs.length;
                qs.splice(at, 0, nq);
                sel.q = at;
                hard();
                var lbl = root.querySelector("#fbLabel");
                if (lbl) lbl.focus();
                return;
            }
            if (d.optAdd) { var q1 = cur(); (q1[d.optAdd] = q1[d.optAdd] || []).push({ label: (d.optAdd === "rows" ? "Ligne " : "Option ") + ((q1[d.optAdd] || []).length + 1) }); hard(); return; }
            if (d.optDel) { cur()[d.optDel].splice(Number(d.i), 1); hard(); return; }
            if (d.optMove) { var arr = cur()[d.optMove], i = Number(d.i), j = i + Number(d.dir), tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp; hard(); return; }
            if (d.optPaste) {
                var txt = prompt("Collez la liste (un choix par ligne). Elle remplace les choix actuels.");
                if (txt == null) return;
                var items = txt.split(/\r?\n/).map(function (s) { return s.trim(); }).filter(Boolean);
                if (items.length) { cur()[d.optPaste] = items.map(function (l) { return { label: l }; }); hard(); }
                return;
            }
            if (d.ruleAdd) { condAt(d.ruleAdd).rules.push({ q: "", op: "eq", value: "" }); hard(); return; }
            if (d.ruleDel) { var c = condAt(d.ruleDel); c.rules.splice(Number(d.i), 1); hard(); return; }
            if (d.outAdd != null) { (form.outcomes = form.outcomes || []).push({ status: "GREEN", label: "", when: { logic: "all", rules: [] } }); hard(); return; }
            if (d.outDel != null) { form.outcomes.splice(Number(d.outDel), 1); hard(); return; }
            switch (d.act) {
                case "add-s":
                    form.sections.push({ id: newId("s"), title: "Nouvelle section", questions: [] });
                    sel = { s: form.sections.length - 1, q: -1 }; tab = "build"; hard(); return;
                case "add-q": sel = { s: Number(d.s), q: -1 }; tab = "build"; draw(); var tp = root.querySelector(".fb-types"); if (tp) tp.scrollIntoView({ block: "center" }); return;
                case "s-up": case "s-down":
                    var k = sel.s + (d.act === "s-up" ? -1 : 1), t2 = form.sections[sel.s];
                    form.sections[sel.s] = form.sections[k]; form.sections[k] = t2; sel.s = k; hard(); return;
                case "s-dup":
                    var copy = clone(sec()); copy.id = newId("s"); copy.title += " (copie)";
                    (copy.questions || []).forEach(function (q) { q.id = newId("q"); });
                    form.sections.splice(sel.s + 1, 0, copy); sel.s++; hard(); return;
                case "s-del":
                    if (!confirm("Supprimer la section « " + sec().title + " » et ses " + (sec().questions || []).length + " question(s) ?")) return;
                    form.sections.splice(sel.s, 1); sel = { s: Math.max(0, sel.s - 1), q: -1 }; hard(); return;
                case "q-up": case "q-down":
                    var qs2 = sec().questions, m = sel.q + (d.act === "q-up" ? -1 : 1), t3 = qs2[sel.q];
                    qs2[sel.q] = qs2[m]; qs2[m] = t3; sel.q = m; hard(); return;
                case "q-dup":
                    var qc = clone(cur()); qc.id = newId("q"); qc.label += " (copie)"; sec().questions.splice(sel.q + 1, 0, qc); sel.q++; hard(); return;
                case "q-del":
                    sec().questions.splice(sel.q, 1); sel.q = Math.min(sel.q, sec().questions.length - 1); hard(); return;
                case "preview-reset": previewAnswers = {}; if (preview) { preview.destroy(); preview = null; } drawPreview(); return;
                case "templates": openTemplates(); return;
                case "ai": openAi(); return;
            }
        });

        root.addEventListener("input", function (e) {
            var el = e.target, d = el.dataset;
            if (d.f) {
                var parts = d.f.split("."), target = parts[0] === "s" ? sec() : cur(), key = parts[1];
                var v = el.type === "number" ? (el.value === "" ? undefined : Number(el.value)) : el.value;
                if (v === undefined || v === "") delete target[key]; else target[key] = v;
                soft(); return;
            }
            if (d.opt && (d.k === "label" || d.k === "score")) {
                var o = cur()[d.opt][Number(d.i)];
                if (d.k === "score") { if (el.value === "") delete o.score; else o.score = Number(el.value); } else o.label = el.value;
                soft(); return;
            }
            if (d.val) {
                var q = cur(), val = q.validation || (q.validation = {});
                if (el.value === "") delete val[d.val]; else val[d.val] = el.type === "number" ? Number(el.value) : el.value;
                soft(); return;
            }
            if (d.scale === "minLabel" || d.scale === "maxLabel") { (cur().scale = cur().scale || {})[d.scale] = el.value; soft(); return; }
            if (d.rule && d.k === "value" && el.tagName === "INPUT") {
                var r = condAt(d.rule).rules[Number(d.i)];
                r.value = (r.op === "in" || r.op === "nin") ? el.value.split("|").map(function (s) { return s.trim(); }).filter(Boolean) : el.value;
                soft(); return;
            }
            if (d.out != null && d.k === "label") { form.outcomes[Number(d.out)].label = el.value; soft(); return; }
            if (d.setting === "qualifiedThreshold") { form.settings.qualifiedThreshold = Number(el.value) || 0; soft(); return; }
            if (el.hasAttribute("data-prefill-col")) { cur().prefill = "extra:" + el.value.trim(); soft(); }
        });

        root.addEventListener("change", function (e) {
            var el = e.target, d = el.dataset;
            if (el.hasAttribute("data-qtype")) {
                var q = cur(), fresh = newQuestion(el.value);
                q.type = el.value;
                if (fresh.options && !(q.options || []).length) q.options = fresh.options;
                if (fresh.rows && !(q.rows || []).length) q.rows = fresh.rows;
                if (fresh.scale && !q.scale) q.scale = fresh.scale;
                if (!fresh.options && el.value !== "MATRIX") delete q.options;
                if (el.value !== "MATRIX") delete q.rows;
                hard(); return;
            }
            if (el.hasAttribute("data-qmove-sec")) {
                var to = Number(el.value), moved = sec().questions.splice(sel.q, 1)[0];
                (form.sections[to].questions = form.sections[to].questions || []).push(moved);
                sel = { s: to, q: form.sections[to].questions.length - 1 }; hard(); return;
            }
            if (d.qbool) { cur()[d.qbool] = el.checked; hard(); return; }
            if (d.opt === "options" || d.opt === "rows") { if (d.k === "outcome") { var o = cur()[d.opt][Number(d.i)]; if (el.value) o.outcome = el.value; else delete o.outcome; soft(); } return; }
            if (d.scale === "min" || d.scale === "max") { (cur().scale = cur().scale || {})[d.scale] = Number(el.value); hard(); return; }
            if (el.hasAttribute("data-val-preset")) { var v = cur().validation || (cur().validation = {}); if (el.value) v.pattern = el.value; else delete v.pattern; soft(); return; }
            if (el.hasAttribute("data-val-future")) { var v2 = cur().validation || (cur().validation = {}); if (el.checked) v2.minDate = "today"; else delete v2.minDate; soft(); return; }
            if (el.hasAttribute("data-prefill")) { if (!el.value) delete cur().prefill; else cur().prefill = el.value === "extra" ? "extra:" : el.value; hard(); return; }
            if (d.condLogic) { condAt(d.condLogic).logic = el.value; soft(); return; }
            if (d.rule && el.tagName === "SELECT") {
                var r = condAt(d.rule).rules[Number(d.i)];
                if (d.k === "q") { r.q = el.value; r.value = ""; }
                else if (d.k === "op") { r.op = el.value; if (el.value === "in" || el.value === "nin") r.value = r.value ? [].concat(r.value) : []; else if (Array.isArray(r.value)) r.value = r.value[0] || ""; if (el.value === "empty" || el.value === "notempty") delete r.value; }
                else r.value = el.value;
                hard(); return;
            }
            if (d.ruleMulti) {
                var rr = condAt(d.ruleMulti).rules[Number(d.i)], vals = Array.isArray(rr.value) ? rr.value : [];
                if (el.checked) vals.push(el.value); else vals = vals.filter(function (x) { return x !== el.value; });
                rr.value = vals; soft(); return;
            }
            if (d.out != null && d.k === "status") { form.outcomes[Number(d.out)].status = el.value; soft(); return; }
            if (d.setting === "showProgress") { form.settings.showProgress = el.value === "true"; soft(); return; }
            if (el.hasAttribute("data-preview-all")) { previewAll = el.checked; if (preview) { preview.destroy(); preview = null; } drawPreview(); }
        });

        // ───────────── Modèles et IA ─────────────

        function modal(title, bodyHtml) {
            var wrap = document.createElement("div");
            wrap.className = "modal fade";
            wrap.tabIndex = -1;
            wrap.innerHTML = '<div class="modal-dialog modal-lg modal-dialog-scrollable"><div class="modal-content"><div class="modal-header"><h5 class="modal-title">' + title +
                '</h5><button type="button" class="btn-close" data-bs-dismiss="modal"></button></div><div class="modal-body">' + bodyHtml + '</div></div></div>';
            document.body.appendChild(wrap);
            var m = new bootstrap.Modal(wrap);
            wrap.addEventListener("hidden.bs.modal", function () { wrap.remove(); });
            m.show();
            return { el: wrap, close: function () { m.hide(); } };
        }

        function confirmReplace() {
            return !allQuestions().length || confirm("Remplacer le formulaire actuel (" + allQuestions().length + " question(s)) ?");
        }

        function openTemplates() {
            var md = modal('<i class="bi bi-collection"></i> Modèles de formulaires', '<div class="fb-tpl" id="fbTplList"><p class="text-muted">Chargement…</p></div>');
            RccApi.getJson("/api/campaigns/form/templates").then(function (list) {
                md.el.querySelector("#fbTplList").innerHTML = list.map(function (t, i) {
                    var n = RccForm.questions(t.form).length;
                    return '<button type="button" data-tpl="' + i + '"><i class="bi ' + esc(t.icon) + '"></i><b>' + esc(t.title) + '</b><small>' + esc(t.description) + '</small><div class="small text-muted mt-1">' +
                        t.form.sections.length + ' sections · ' + n + ' questions · ' + (t.form.outcomes || []).length + ' règle(s) d\'issue</div></button>';
                }).join("");
                md.el.querySelector("#fbTplList").addEventListener("click", function (e) {
                    var b = e.target.closest("[data-tpl]");
                    if (!b || !confirmReplace()) return;
                    form = clone(list[Number(b.getAttribute("data-tpl"))].form);
                    sel = { s: 0, q: -1 }; previewAnswers = {}; md.close(); hard();
                });
            }).catch(function (err) { md.el.querySelector("#fbTplList").innerHTML = '<p class="text-danger">' + esc(err.message) + '</p>'; });
        }

        function openAi() {
            var md = modal('<i class="bi bi-stars"></i> Générer le formulaire',
                '<p class="small text-muted">Décrivez la campagne : produit, clients visés, objectif de l\'appel, informations à recueillir. Le formulaire est construit avec ses sections, son script, ses questions notées, sa logique et ses issues d\'appel — tout reste modifiable ensuite.</p>' +
                '<textarea class="form-control mb-2" rows="5" id="fbAiPrompt" placeholder="Ex. Relancer les salariés domiciliés qui n\'ont pas de carte Visa : connaître leurs usages, proposer Visa Classic ou Gold, prendre RDV en agence pour le retrait."></textarea>' +
                '<div class="d-flex gap-2 align-items-center"><button type="button" class="btn btn-primary" id="fbAiGo"><i class="bi bi-stars"></i> Générer</button><span class="small text-muted" id="fbAiStatus"></span></div>');
            var go = md.el.querySelector("#fbAiGo"), status = md.el.querySelector("#fbAiStatus");
            go.addEventListener("click", function () {
                var prompt = md.el.querySelector("#fbAiPrompt").value.trim();
                if (!prompt) { status.textContent = "Décrivez d'abord la campagne."; return; }
                if (!confirmReplace()) return;
                go.disabled = true; status.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Conception du formulaire…';
                RccApi.sendJson("/api/campaigns/form/generate", "POST", { prompt: prompt }).then(function (res) {
                    form = res.form; sel = { s: 0, q: -1 }; previewAnswers = {};
                    md.close(); hard();
                    if (res.notice && opts.onNotice) opts.onNotice(res.notice);
                }).catch(function (err) { go.disabled = false; status.textContent = err.message; });
            });
        }

        draw();
        return {
            getForm: function () { return clone(form); },
            setForm: function (f) { form = f ? clone(f) : emptyForm(); sel = { s: 0, q: -1 }; previewAnswers = {}; draw(); },
            issues: issues
        };
    }

    return { mount: mount, emptyForm: emptyForm };
})();
