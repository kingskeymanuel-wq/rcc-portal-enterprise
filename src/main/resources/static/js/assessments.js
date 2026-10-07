"use strict";

/**
 * Évaluations programmées (onglet « Évaluations programmées » du Centre d'Évaluation, portail Team Leader, Ma Performance).
 *  - QA : on charge le fichier de cours → les questions se rédigent seules → relecture → équipe + période → « Terminer ».
 *  - Agent : liste de ses évaluations, passage, score immédiat (connu de son Team Leader et de la QA).
 *  - Team Leader / QA : résultats par évaluation (qui l'a passée, qui ne l'a pas encore fait).
 */
window.RccAssessments = (function () {
    var esc = function (s) { return window.RccApi ? RccApi.escapeHtml(s) : String(s == null ? "" : s); };
    var teams = null;
    var draft = null;

    function fmtDate(d) {
        if (!d) return "—";
        var p = String(d).slice(0, 10).split("-");
        return p.length === 3 ? p[2] + "/" + p[1] + "/" + p[0] : d;
    }

    function fmtDateTime(d) {
        if (!d) return "—";
        var x = new Date(d);
        return isNaN(x.getTime()) ? d : x.toLocaleString("fr-FR", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" });
    }

    function today(plus) {
        var d = new Date();
        d.setDate(d.getDate() + (plus || 0));
        return d.toISOString().slice(0, 10);
    }

    function postForm(url, fd) {
        return fetch(url, { method: "POST", credentials: "same-origin", body: fd }).then(function (res) {
            return res.text().then(function (t) {
                var data = null;
                try { data = t ? JSON.parse(t) : null; } catch (e) { /* réponse non JSON */ }
                if (!res.ok) throw new Error((data && (data.message || (data.error && data.error.message))) || ("Erreur " + res.status));
                return data;
            });
        });
    }

    function loadTeams() {
        if (teams) return Promise.resolve(teams);
        return RccApi.getJson("/api/assessments/teams").then(function (t) { teams = t; return t; });
    }

    function statusBadge(a) {
        if (a.status === "DRAFT") return '<span class="badge bg-secondary">Brouillon</span>';
        var t = today(0);
        if (a.endsOn < t) return '<span class="badge bg-dark">Terminée</span>';
        if (a.startsOn > t) return '<span class="badge bg-info text-dark">À venir</span>';
        return '<span class="badge bg-success">En cours</span>';
    }

    // ───────────── Agent : mes évaluations ─────────────

    function renderMine(root) {
        var box = root.querySelector("[data-as-mine]");
        if (!box) return Promise.resolve();
        box.innerHTML = '<p class="text-muted small mb-0">Chargement…</p>';
        return RccApi.getJson("/api/assessments/me").then(function (list) {
            var count = document.getElementById("evAsCount");
            var todo = list.filter(function (m) { return m.open && !m.done; }).length;
            if (count) { count.textContent = todo; count.style.display = todo ? "" : "none"; }
            if (!list.length) {
                box.innerHTML = '<p class="text-muted small mb-0">Aucune évaluation programmée pour votre équipe pour le moment. ' +
                    "Vous serez prévenu(e) par notification dès que la QA en ouvrira une.</p>";
                return;
            }
            box.innerHTML = '<div class="table-responsive"><table class="table align-middle mb-0"><thead><tr><th>Évaluation</th><th>Période</th>' +
                "<th>Questions</th><th>Réussite</th><th>Mon résultat</th><th></th></tr></thead><tbody>" +
                list.map(function (m) {
                    var a = m.assessment;
                    var result = m.done
                        ? '<span class="badge ' + (m.passed ? "bg-success" : "bg-danger") + '">' + m.score + " %</span> " +
                          '<span class="small text-muted">' + (m.passed ? "réussie" : "non réussie") + "</span>"
                        : '<span class="text-muted small">—</span>';
                    var action = m.done ? '<span class="small text-muted">Passée le ' + fmtDateTime(m.submittedAt) + "</span>"
                        : m.open ? '<button type="button" class="btn btn-sm btn-primary" data-as-take="' + a.id + '"><i class="bi bi-play-fill"></i> Passer l\'évaluation</button>'
                        : a.startsOn > today(0) ? '<span class="small text-muted">Ouvre le ' + fmtDate(a.startsOn) + "</span>"
                        : '<span class="small text-muted">Période terminée</span>';
                    return "<tr><td><strong>" + esc(a.title) + "</strong>" + (a.sourceName ? '<div class="small text-muted">' + esc(a.sourceName) + "</div>" : "") +
                        "</td><td class=\"small\">" + fmtDate(a.startsOn) + " → " + fmtDate(a.endsOn) + "</td><td>" + a.questionCount +
                        "</td><td>" + a.passScore + " %</td><td>" + result + "</td><td class=\"text-end\">" + action + "</td></tr>";
                }).join("") + "</tbody></table></div>";
            Array.prototype.forEach.call(box.querySelectorAll("[data-as-take]"), function (b) {
                b.addEventListener("click", function () { take(root, +b.getAttribute("data-as-take")); });
            });
        }).catch(function (e) {
            box.innerHTML = '<p class="text-danger small mb-0">' + esc(e.message) + "</p>";
        });
    }

    function take(root, id) {
        var stage = root.querySelector("[data-as-stage]");
        stage.style.display = "";
        stage.innerHTML = '<p class="text-muted">Chargement de l\'évaluation…</p>';
        stage.scrollIntoView({ behavior: "smooth", block: "start" });
        RccApi.getJson("/api/assessments/" + id + "/take").then(function (a) {
            stage.innerHTML = '<div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-3"><h5 class="mb-0"><i class="bi bi-ui-checks"></i> ' +
                esc(a.title) + '</h5><span class="small text-muted">' + a.questions.length + " question(s) · réussite à " + a.passScore +
                " % · une seule tentative</span></div>" +
                a.questions.map(function (q, i) {
                    return '<div class="border rounded p-3 mb-2" data-as-q="' + q.id + '"><div class="fw-semibold mb-2">' + (i + 1) + ". " + esc(q.text) + "</div>" +
                        q.options.map(function (o, k) {
                            return '<div class="form-check"><input class="form-check-input" type="radio" name="asq' + q.id + '" id="asq' + q.id + "_" + k +
                                '" value="' + k + '"><label class="form-check-label" for="asq' + q.id + "_" + k + '">' + esc(o) + "</label></div>";
                        }).join("") + "</div>";
                }).join("") +
                '<div class="d-flex gap-2 mt-3"><button type="button" class="btn btn-success" data-as-submit><i class="bi bi-send-check"></i> Valider mes réponses</button>' +
                '<button type="button" class="btn btn-outline-secondary" data-as-cancel>Plus tard</button><span class="small align-self-center" data-as-msg></span></div>';
            stage.querySelector("[data-as-cancel]").addEventListener("click", function () { stage.style.display = "none"; stage.innerHTML = ""; });
            stage.querySelector("[data-as-submit]").addEventListener("click", function () {
                var answers = {}, missing = 0;
                a.questions.forEach(function (q) {
                    var c = stage.querySelector('input[name="asq' + q.id + '"]:checked');
                    if (c) answers[q.id] = +c.value; else missing++;
                });
                var msg = stage.querySelector("[data-as-msg]");
                if (missing && !window.confirm(missing + " question(s) sans réponse. Valider quand même ?")) return;
                this.disabled = true;
                RccApi.sendJson("/api/assessments/" + id + "/submit", "POST", answers).then(function (r) {
                    showReview(stage, a, r);
                    renderMine(root);
                }).catch(function (e) { msg.className = "small align-self-center text-danger"; msg.textContent = e.message; });
            });
        }).catch(function (e) {
            stage.innerHTML = '<p class="text-danger mb-0">' + esc(e.message) + "</p>";
        });
    }

    function showReview(stage, a, r) {
        var byId = {};
        r.review.forEach(function (x) { byId[x.questionId] = x; });
        stage.innerHTML = '<div class="alert ' + (r.passed ? "alert-success" : "alert-warning") + '"><h5 class="mb-1">' +
            (r.passed ? "🎉 Évaluation réussie" : "Évaluation non réussie") + " — " + r.score + " %</h5>" +
            r.correct + " bonne(s) réponse(s) sur " + r.total + " (réussite à " + r.passScore + " %). Votre Team Leader et la QA voient ce résultat.</div>" +
            a.questions.map(function (q, i) {
                var x = byId[q.id] || {};
                return '<div class="border rounded p-2 mb-2 ' + (x.ok ? "border-success" : "border-danger") + '"><div class="fw-semibold">' + (x.ok ? "✅ " : "❌ ") + (i + 1) + ". " + esc(q.text) + "</div>" +
                    '<div class="small">Bonne réponse : <strong>' + esc(q.options[x.correct]) + "</strong>" +
                    (!x.ok && x.given != null ? " · votre réponse : " + esc(q.options[x.given]) : "") + "</div>" +
                    (x.explanation ? '<div class="small text-muted">' + esc(x.explanation) + "</div>" : "") + "</div>";
            }).join("");
    }

    // ───────────── QA : création ─────────────

    function renderDraft(root) {
        var box = root.querySelector("[data-as-draft]");
        if (!draft) { box.style.display = "none"; box.innerHTML = ""; return; }
        box.style.display = "";
        loadTeams().then(function (t) {
            var teamOpts = Object.keys(t).map(function (k) { return '<option value="' + k + '">' + esc(t[k]) + "</option>"; }).join("");
            box.innerHTML = '<div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2"><h6 class="mb-0"><i class="bi bi-2-circle"></i> Relisez les ' +
                draft.questions.length + ' question(s) rédigée(s)' + (draft.generatedBy ? ' <span class="badge bg-light text-dark">' + esc(genLabel(draft.generatedBy)) + "</span>" : "") +
                '</h6><span class="small text-muted">Supprimez celles qui ne conviennent pas ; cliquez une réponse pour la marquer comme bonne.</span></div>' +
                draft.questions.map(function (q, i) {
                    return '<div class="border rounded p-2 mb-2"><div class="d-flex gap-2"><textarea class="form-control form-control-sm" rows="1" data-as-qtext="' + q.id + '">' + esc(q.text) +
                        '</textarea><button type="button" class="btn btn-sm btn-outline-danger" title="Supprimer" data-as-qdel="' + q.id + '"><i class="bi bi-trash"></i></button></div>' +
                        '<div class="d-flex flex-wrap gap-1 mt-2">' + q.options.map(function (o, k) {
                            return '<button type="button" class="btn btn-sm ' + (k === q.correct ? "btn-success" : "btn-outline-secondary") + '" data-as-qok="' + q.id + ":" + k + '">' +
                                (k === q.correct ? '<i class="bi bi-check2"></i> ' : "") + esc(o) + "</button>";
                        }).join("") + '</div><div class="small text-muted mt-1">Question ' + (i + 1) + "</div></div>";
                }).join("") +
                '<h6 class="mt-3"><i class="bi bi-3-circle"></i> Équipe, période et réussite</h6><div class="row g-2 align-items-end">' +
                '<div class="col-md-4"><label class="form-label small mb-1">Titre</label><input class="form-control form-control-sm" data-as-title value="' + esc(draft.title) + '"></div>' +
                '<div class="col-md-3"><label class="form-label small mb-1">Équipe concernée</label><select class="form-select form-select-sm" data-as-team>' + teamOpts + "</select></div>" +
                '<div class="col-md-2"><label class="form-label small mb-1">Du</label><input type="date" class="form-control form-control-sm" data-as-from value="' + (draft.startsOn || today(0)) + '"></div>' +
                '<div class="col-md-2"><label class="form-label small mb-1">Au</label><input type="date" class="form-control form-control-sm" data-as-to value="' + (draft.endsOn || today(14)) + '"></div>' +
                '<div class="col-md-1"><label class="form-label small mb-1">Réussite %</label><input type="number" min="1" max="100" class="form-control form-control-sm" data-as-pass value="' + (draft.passScore || 70) + '"></div>' +
                '</div><div class="d-flex gap-2 mt-3"><button type="button" class="btn btn-success" data-as-publish><i class="bi bi-check2-circle"></i> Terminer</button>' +
                '<button type="button" class="btn btn-outline-danger" data-as-discard>Abandonner le brouillon</button><span class="small align-self-center" data-as-pubmsg></span></div>' +
                '<p class="small text-muted mt-2 mb-0">À « Terminer », les agents de l\'équipe et leur Team Leader reçoivent une notification.</p>';
            if (draft.teamCode && t[draft.teamCode]) box.querySelector("[data-as-team]").value = draft.teamCode;
            wireDraft(root, box);
        });
    }

    function genLabel(g) {
        return g === "ia" ? "Rédigées par l'IA" : g === "ia-locale" ? "Rédigées par l'IA locale" : "Générateur intégré";
    }

    function wireDraft(root, box) {
        var base = "/api/assessments/" + draft.id + "/questions/";
        Array.prototype.forEach.call(box.querySelectorAll("[data-as-qdel]"), function (b) {
            b.addEventListener("click", function () {
                RccApi.sendJson(base + b.getAttribute("data-as-qdel"), "DELETE").then(function (a) { draft = a; renderDraft(root); }).catch(alertErr);
            });
        });
        Array.prototype.forEach.call(box.querySelectorAll("[data-as-qok]"), function (b) {
            b.addEventListener("click", function () {
                var p = b.getAttribute("data-as-qok").split(":");
                RccApi.sendJson(base + p[0], "PUT", { correct: +p[1] }).then(function (a) { draft = a; renderDraft(root); }).catch(alertErr);
            });
        });
        Array.prototype.forEach.call(box.querySelectorAll("[data-as-qtext]"), function (t) {
            t.addEventListener("change", function () {
                RccApi.sendJson(base + t.getAttribute("data-as-qtext"), "PUT", { text: t.value }).then(function (a) { draft = a; }).catch(alertErr);
            });
        });
        box.querySelector("[data-as-discard]").addEventListener("click", function () {
            if (!window.confirm("Supprimer ce brouillon et ses questions ?")) return;
            RccApi.sendJson("/api/assessments/" + draft.id, "DELETE").then(function () { draft = null; renderDraft(root); renderManaged(root); }).catch(alertErr);
        });
        box.querySelector("[data-as-publish]").addEventListener("click", function () {
            var msg = box.querySelector("[data-as-pubmsg]"), btn = this;
            btn.disabled = true;
            RccApi.sendJson("/api/assessments/" + draft.id + "/publish", "POST", {
                title: box.querySelector("[data-as-title]").value,
                teamCode: box.querySelector("[data-as-team]").value,
                startsOn: box.querySelector("[data-as-from]").value,
                endsOn: box.querySelector("[data-as-to]").value,
                passScore: +box.querySelector("[data-as-pass]").value
            }).then(function (a) {
                draft = null;
                renderDraft(root);
                var f = root.querySelector("[data-as-create-msg]");
                if (f) f.innerHTML = '<span class="text-success"><i class="bi bi-check-circle"></i> « ' + esc(a.title) + " » programmée pour " + esc(a.teamLabel) +
                    " du " + fmtDate(a.startsOn) + " au " + fmtDate(a.endsOn) + ". Agents et Team Leaders prévenus.</span>";
                renderManaged(root);
                renderMine(root);
            }).catch(function (e) { msg.className = "small align-self-center text-danger"; msg.textContent = e.message; btn.disabled = false; });
        });
    }

    function alertErr(e) { window.alert(e.message); }

    function wireCreate(root) {
        var btn = root.querySelector("[data-as-analyse]");
        if (!btn) return;
        btn.addEventListener("click", function () {
            var file = root.querySelector("[data-as-file]").files[0];
            var msg = root.querySelector("[data-as-create-msg]");
            if (!file) { msg.innerHTML = '<span class="text-warning">Choisissez d\'abord le fichier du cours (PDF, Word, PowerPoint, vidéo…).</span>'; return; }
            var fd = new FormData();
            fd.append("file", file);
            fd.append("count", root.querySelector("[data-as-count]").value);
            var title = root.querySelector("[data-as-newtitle]").value.trim();
            if (title) fd.append("title", title);
            btn.disabled = true;
            msg.innerHTML = '<span class="text-muted"><span class="spinner-border spinner-border-sm"></span> Analyse du fichier et rédaction des questions… ' +
                (/^(video|audio)\//.test(file.type) ? "(transcription de la vidéo : cela peut prendre quelques minutes)" : "") + "</span>";
            postForm("/api/assessments/draft", fd).then(function (a) {
                draft = a;
                msg.innerHTML = '<span class="text-success"><i class="bi bi-check-circle"></i> ' + a.questions.length + " question(s) créée(s) à partir de « " + esc(file.name) + " ».</span>";
                renderDraft(root);
                renderManaged(root);
            }).catch(function (e) { msg.innerHTML = '<span class="text-danger">' + esc(e.message) + "</span>"; })
              .then(function () { btn.disabled = false; });
        });
    }

    // ───────────── QA / Team Leader : suivi ─────────────

    function renderManaged(root) {
        var box = root.querySelector("[data-as-managed]");
        if (!box) return Promise.resolve();
        return RccApi.getJson("/api/assessments").then(function (list) {
            if (!list.length) { box.innerHTML = '<p class="text-muted small mb-0">Aucune évaluation programmée.</p>'; return; }
            box.innerHTML = '<div class="table-responsive"><table class="table table-sm align-middle mb-0"><thead><tr><th>Évaluation</th><th>Équipe</th><th>Période</th>' +
                "<th>Statut</th><th>Passée par</th><th>Réussite</th><th>Moyenne</th><th></th></tr></thead><tbody>" +
                list.map(function (a) {
                    return "<tr><td><strong>" + esc(a.title) + "</strong><div class=\"small text-muted\">" + a.questionCount + " question(s)" +
                        (a.sourceName ? " · " + esc(a.sourceName) : "") + "</div></td><td>" + esc(a.teamLabel) + "</td><td class=\"small\">" +
                        fmtDate(a.startsOn) + " → " + fmtDate(a.endsOn) + "</td><td>" + statusBadge(a) + "</td><td>" + a.attempts + "</td><td>" +
                        (a.attempts ? a.passed + "/" + a.attempts : "—") + "</td><td>" + (a.averageScore != null ? a.averageScore + " %" : "—") + '</td><td class="text-end text-nowrap">' +
                        (a.status === "DRAFT" && root.querySelector("[data-as-draft]") ? '<button type="button" class="btn btn-sm btn-outline-primary" data-as-resume="' + a.id + '">Reprendre</button> ' : "") +
                        (a.status !== "DRAFT" ? '<button type="button" class="btn btn-sm btn-outline-primary" data-as-results="' + a.id + '"><i class="bi bi-people"></i> Résultats</button> ' : "") +
                        (root.querySelector("[data-as-draft]") ? '<button type="button" class="btn btn-sm btn-outline-danger" title="Supprimer" data-as-del="' + a.id + '"><i class="bi bi-trash"></i></button>' : "") +
                        "</td></tr>";
                }).join("") + "</tbody></table></div>";
            Array.prototype.forEach.call(box.querySelectorAll("[data-as-results]"), function (b) {
                b.addEventListener("click", function () { showResults(root, +b.getAttribute("data-as-results")); });
            });
            Array.prototype.forEach.call(box.querySelectorAll("[data-as-resume]"), function (b) {
                b.addEventListener("click", function () {
                    RccApi.getJson("/api/assessments/" + b.getAttribute("data-as-resume")).then(function (a) { draft = a; renderDraft(root); }).catch(alertErr);
                });
            });
            Array.prototype.forEach.call(box.querySelectorAll("[data-as-del]"), function (b) {
                b.addEventListener("click", function () {
                    if (!window.confirm("Supprimer cette évaluation, ses questions et ses résultats ?")) return;
                    var id = +b.getAttribute("data-as-del");
                    RccApi.sendJson("/api/assessments/" + id, "DELETE").then(function () {
                        if (draft && draft.id === id) { draft = null; renderDraft(root); }
                        renderManaged(root);
                    }).catch(alertErr);
                });
            });
        }).catch(function (e) { box.innerHTML = '<p class="text-danger small mb-0">' + esc(e.message) + "</p>"; });
    }

    function showResults(root, id) {
        var box = root.querySelector("[data-as-results-box]");
        box.style.display = "";
        box.innerHTML = '<p class="text-muted small">Chargement des résultats…</p>';
        RccApi.getJson("/api/assessments/" + id + "/results").then(function (r) {
            var a = r.assessment;
            box.innerHTML = '<div class="d-flex justify-content-between align-items-center mb-2"><h6 class="mb-0"><i class="bi bi-people"></i> ' + esc(a.title) +
                ' — ' + r.results.length + "/" + r.expected + ' agent(s) l\'ont passée</h6><button type="button" class="btn-close" data-as-close></button></div>' +
                (r.results.length ? '<div class="table-responsive"><table class="table table-sm mb-2"><thead><tr><th>Agent</th><th>Équipe</th><th>Score</th><th>Bonnes réponses</th><th>Passée le</th></tr></thead><tbody>' +
                    r.results.map(function (x) {
                        return "<tr><td>" + esc(x.name || x.username) + "</td><td class=\"small\">" + esc(x.team || "—") + '</td><td><span class="badge ' + (x.passed ? "bg-success" : "bg-danger") + '">' +
                            x.score + " %</span></td><td>" + x.correct + "/" + x.total + "</td><td class=\"small\">" + fmtDateTime(x.submittedAt) + "</td></tr>";
                    }).join("") + "</tbody></table></div>" : '<p class="small text-muted">Personne ne l\'a encore passée.</p>') +
                (r.notYet.length ? '<div class="small"><strong>Pas encore passée (' + r.notYet.length + ') :</strong> ' + r.notYet.map(esc).join(", ") + "</div>" : "");
            box.querySelector("[data-as-close]").addEventListener("click", function () { box.style.display = "none"; });
            box.scrollIntoView({ behavior: "smooth", block: "nearest" });
        }).catch(function (e) { box.innerHTML = '<p class="text-danger small">' + esc(e.message) + "</p>"; });
    }

    // ───────────── Montages ─────────────

    var CREATE_HTML =
        '<div class="ef-card mb-4" data-as-create><div class="ef-card-title"><i class="bi bi-magic"></i> Programmer une évaluation (QA)</div>' +
        '<h6><i class="bi bi-1-circle"></i> Chargez le fichier du cours</h6><div class="row g-2 align-items-end">' +
        '<div class="col-md-5"><input type="file" class="form-control form-control-sm" data-as-file accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.rtf,.odt,video/*,audio/*"></div>' +
        '<div class="col-md-3"><input class="form-control form-control-sm" data-as-newtitle placeholder="Titre (facultatif)"></div>' +
        '<div class="col-md-2"><select class="form-select form-select-sm" data-as-count><option value="5">QCM — 5 questions</option><option value="10" selected>QCM — 10 questions</option>' +
        '<option value="15">QCM — 15 questions</option><option value="20">QCM — 20 questions</option></select></div>' +
        '<div class="col-md-2"><button type="button" class="btn btn-sm btn-primary w-100" data-as-analyse><i class="bi bi-stars"></i> Créer les questions</button></div></div>' +
        '<div class="small mt-2" data-as-create-msg></div><div class="mt-3" data-as-draft style="display:none;"></div></div>';

    var MANAGED_HTML =
        '<div class="ef-card mb-4"><div class="ef-card-title"><i class="bi bi-list-check"></i> Évaluations programmées — suivi</div>' +
        '<div data-as-managed><p class="text-muted small mb-0">Chargement…</p></div><div class="border-top mt-3 pt-3" data-as-results-box style="display:none;"></div></div>';

    var MINE_HTML =
        '<div class="ef-card mb-4"><div class="ef-card-title"><i class="bi bi-calendar-check"></i> Mes évaluations programmées</div><div data-as-mine></div>' +
        '<div class="border-top mt-3 pt-3" data-as-stage style="display:none;"></div></div>';

    /** Onglet du Centre d'Évaluation : l'agent passe ses évaluations ; la QA en programme et suit les résultats. */
    function mountTab(root, profile) {
        var qa = ["QA", "QA_SUPERVISOR", "FORMATEUR", "ADMIN"].indexOf(profile) !== -1;
        var manager = qa || profile === "TEAM_LEADER" || profile === "SUPERVISOR" || profile === "RH";
        root.innerHTML = (qa ? CREATE_HTML : "") + (manager ? MANAGED_HTML : "") + (profile === "AGENT" || !manager ? MINE_HTML : "");
        wireCreate(root);
        renderMine(root);
        if (manager) renderManaged(root);
        // Arrivée depuis une formation (?assessment=ID) : on ouvre directement l'évaluation correspondante.
        var m = /[?&]assessment=(\d+)/.exec(window.location.search);
        if (m && root.querySelector("[data-as-stage]")) take(root, +m[1]);
    }

    /** Portail Team Leader : suivi des résultats de son équipe. */
    function mountManager(root) {
        root.innerHTML = MANAGED_HTML;
        renderManaged(root);
    }

    /** Ma Performance : résultats de l'agent. */
    function mountMyResults(root) {
        RccApi.getJson("/api/assessments/me/results").then(function (list) {
            if (!list.length) { root.innerHTML = '<p class="text-muted small mb-0">Aucune évaluation passée pour le moment.</p>'; return; }
            var avg = Math.round(list.reduce(function (s, m) { return s + (m.score || 0); }, 0) / list.length);
            var ok = list.filter(function (m) { return m.passed; }).length;
            root.innerHTML = '<div class="d-flex gap-3 mb-2 small"><span>Moyenne : <strong>' + avg + ' %</strong></span><span>Réussies : <strong>' + ok + "/" + list.length + "</strong></span></div>" +
                '<ul class="list-group list-group-flush">' + list.map(function (m) {
                    return '<li class="list-group-item d-flex justify-content-between align-items-center px-0"><span>' + esc(m.assessment.title) +
                        '<span class="small text-muted d-block">' + fmtDateTime(m.submittedAt) + '</span></span><span class="badge ' + (m.passed ? "bg-success" : "bg-danger") + '">' +
                        m.score + " %</span></li>";
                }).join("") + "</ul>";
        }).catch(function (e) { root.innerHTML = '<p class="text-muted small mb-0">' + esc(e.message) + "</p>"; });
    }

    return { mountTab: mountTab, mountManager: mountManager, mountMyResults: mountMyResults };
})();
