"use strict";

/**
 * Studio de création de cours (Formation, QA / Formateur) — modale en 5 étapes :
 * 1. L'essentiel (titre, description, thématique, vignette)  2. Contenu (éditeur riche + plans types)
 * 3. Médias (vidéo en lien ou téléversée, document joint)       4. Paramètres (type, équipe, obligatoire, publication)
 * 5. Évaluation (questions de la rubrique, banque commune avec le Centre d'Évaluation).
 * Aperçu en direct de la carte telle que l'agent la verra + contrôle qualité « prêt à publier ».
 * Sert aussi à MODIFIER un cours existant (remplace les anciennes boîtes prompt()).
 *
 *   RccCourseStudio.open({ category: "Compte" })     // nouveau cours dans une rubrique
 *   RccCourseStudio.open({ course: courseResponse })  // modification
 *   événement document "rcc:course-saved" { detail: course } après enregistrement.
 */
(function () {
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var esc = RccApi.escapeHtml;

    var STEPS = [
        { key: "basics", icon: "bi-card-heading", title: "L'essentiel", hint: "Titre, thématique, vignette" },
        { key: "content", icon: "bi-journal-richtext", title: "Contenu", hint: "Le cours lui-même" },
        { key: "media", icon: "bi-play-btn", title: "Médias", hint: "Vidéo et document" },
        { key: "settings", icon: "bi-sliders", title: "Paramètres", hint: "Type, équipe, publication" },
        { key: "quiz", icon: "bi-patch-question", title: "Évaluation", hint: "Questions notées" }
    ];
    var COVERS = ["pexels-18804128.jpg", "pexels-5053847.jpg", "pexels-669610.jpg", "pexels-7681091.jpg",
        "pexels-12903122.jpg", "pexels-5239804.jpg", "pexels-3760067.jpg", "pexels-8152734.jpg"];
    var TEMPLATES = {
        plan: '<h3>🎯 Objectifs</h3><ul><li>À la fin de ce module, le conseiller saura…</li></ul>' +
            '<h3>📚 Points clés</h3><ol><li>…</li><li>…</li></ol><h3>✅ À retenir</h3><p>…</p>',
        procedure: '<h3>📋 Procédure pas à pas</h3><ol><li>Vérifier l\'identité du client</li><li>…</li><li>…</li></ol>' +
            '<h3>⏱ Délai / SLA</h3><p>…</p><h3>⚠️ Points de vigilance</h3><ul><li>…</li></ul>',
        script: '<h3>📞 Script d\'appel</h3><p><b>Accueil :</b> « Ecobank bonjour, je suis …, que puis-je faire pour vous ? »</p>' +
            '<p><b>Découverte :</b> …</p><p><b>Solution :</b> …</p><p><b>Conclusion :</b> « Y a-t-il autre chose que je puisse faire pour vous ? »</p>',
        faq: '<h3>❓ Questions fréquentes</h3><p><b>Q :</b> …<br><b>R :</b> …</p><p><b>Q :</b> …<br><b>R :</b> …</p>'
    };

    var modal, bsModal, editor, teamsLoaded = false, categories = [];
    var state = null;

    function blank() {
        return {
            courseId: null, step: 0, title: "", description: "", category: "", categoryLocked: false,
            content: "", videoMode: "link", videoUrl: "", videoFile: null, docFile: null, existingFileName: null,
            coverFile: null, coverPreset: null, coverUrl: null,
            type: "SELF_ASSESSMENT", teamCode: "", mandatory: false, publish: "DRAFT", initialStatus: "DRAFT",
            questions: [], saving: false
        };
    }

    // ── Markup ───────────────────────────────────────────────────────────────────────────
    function build() {
        if (modal) return;
        var wrap = document.createElement("div");
        wrap.innerHTML =
            '<div class="modal fade cs-modal" id="csModal" tabindex="-1" data-bs-backdrop="static">' +
            '<div class="modal-dialog modal-xl modal-dialog-centered modal-dialog-scrollable modal-fullscreen-lg-down"><div class="modal-content">' +
            '<div class="cs-head"><div class="cs-head-art" aria-hidden="true"><span></span><span></span><span></span></div>' +
            '<div class="cs-head-icon"><i class="bi bi-mortarboard"></i></div>' +
            '<div class="flex-grow-1 min-w-0"><div class="cs-eyebrow" id="csEyebrow">Studio de création</div><h5 id="csTitle">Nouveau cours</h5></div>' +
            '<button type="button" class="btn-close btn-close-white" data-bs-dismiss="modal" aria-label="Fermer"></button></div>' +
            '<div class="modal-body p-0"><div class="cs-layout">' +
            '<nav class="cs-steps" id="csSteps"></nav>' +
            '<div class="cs-main" id="csMain">' + panes() + '</div>' +
            '<aside class="cs-side"><div class="cs-side-label">Aperçu agent</div><div id="csPreview"></div>' +
            '<div class="cs-side-label mt-3">Prêt à publier ?</div><ul class="cs-checks" id="csChecks"></ul></aside>' +
            '</div></div>' +
            '<div class="cs-foot"><div class="cs-progress"><div class="cs-progress-bar" id="csProgressBar"></div></div>' +
            '<span class="cs-foot-step" id="csFootStep"></span>' +
            '<div class="ms-auto d-flex gap-2 flex-wrap">' +
            '<button type="button" class="ef-btn ef-btn-soft" id="csPrev"><i class="bi bi-arrow-left"></i> Précédent</button>' +
            '<button type="button" class="ef-btn ef-btn-soft" id="csNext">Suivant <i class="bi bi-arrow-right"></i></button>' +
            '<button type="button" class="ef-btn ef-btn-primary" id="csSave"><i class="bi bi-check2-circle"></i> <span>Enregistrer le cours</span></button>' +
            '</div></div>' +
            '<div class="cs-saving" id="csSaving" hidden><div class="cs-saving-card"><div class="cs-spinner"></div>' +
            '<b id="csSavingLabel">Enregistrement…</b><div class="cs-upbar"><span id="csSavingBar"></span></div><small id="csSavingDetail"></small></div></div>' +
            '</div></div></div>';
        modal = wrap.firstChild;
        document.body.appendChild(modal);
        bsModal = new bootstrap.Modal(modal);
        editor = window.RccRichText.create(modal.querySelector("#csEditor"), "");
        wire();
    }

    function panes() {
        return '' +
            // 1 — L'essentiel
            '<section class="cs-pane" data-pane="basics">' +
            '<h6 class="cs-pane-title">L\'essentiel</h6><p class="cs-pane-sub">Ce que l\'agent voit en premier dans sa grille de cours.</p>' +
            '<label class="cs-label" for="csFTitle">Titre du cours <span class="cs-req">*</span></label>' +
            '<div class="cs-counter-wrap"><input type="text" class="form-control cs-input" id="csFTitle" maxlength="120" placeholder="Ex. Traiter une réclamation de retrait GAB non servi">' +
            '<small class="cs-counter" data-for="csFTitle">0/120</small></div>' +
            '<label class="cs-label mt-3" for="csFDesc">Description courte</label>' +
            '<div class="cs-counter-wrap"><textarea class="form-control cs-input" id="csFDesc" rows="2" maxlength="280" placeholder="En une ou deux phrases : ce que l\'agent va apprendre."></textarea>' +
            '<small class="cs-counter" data-for="csFDesc">0/280</small></div>' +
            '<label class="cs-label mt-3" for="csFCat">Thématique</label>' +
            '<div id="csCatLocked" class="cs-locked" hidden><i class="bi bi-folder2-open"></i> Rubrique <b id="csCatLockedName"></b>' +
            '<button type="button" class="btn btn-link btn-sm p-0 ms-auto" id="csCatUnlock">Changer</button></div>' +
            '<input type="text" class="form-control cs-input" id="csFCat" list="csCatList" placeholder="Ex. Compte, Carte, Transfert… (vide = Général)"><datalist id="csCatList"></datalist>' +
            '<label class="cs-label mt-3">Vignette</label>' +
            '<div class="cs-covers" id="csCovers"></div>' +
            '<label class="cs-drop cs-drop-sm mt-2" id="csCoverDrop"><input type="file" accept="image/*" hidden id="csCoverFile">' +
            '<i class="bi bi-image"></i><span><b>Importer une image</b> ou glisser-déposer · JPG, PNG, WEBP · 20 Mo max</span></label>' +
            '</section>' +
            // 2 — Contenu
            '<section class="cs-pane" data-pane="content" hidden>' +
            '<h6 class="cs-pane-title">Contenu du cours</h6><p class="cs-pane-sub">Rédigez directement ou partez d\'un plan type.</p>' +
            '<div class="cs-templates">' +
            '<button type="button" data-tpl="plan"><i class="bi bi-list-check"></i> Plan pédagogique</button>' +
            '<button type="button" data-tpl="procedure"><i class="bi bi-diagram-3"></i> Procédure</button>' +
            '<button type="button" data-tpl="script"><i class="bi bi-headset"></i> Script d\'appel</button>' +
            '<button type="button" data-tpl="faq"><i class="bi bi-question-circle"></i> FAQ</button></div>' +
            '<div id="csEditor" class="cs-editor"></div>' +
            '<small class="cs-muted" id="csWords">0 mot · ~0 min de lecture</small>' +
            '</section>' +
            // 3 — Médias
            '<section class="cs-pane" data-pane="media" hidden>' +
            '<h6 class="cs-pane-title">Vidéo et document</h6><p class="cs-pane-sub">Facultatif — la vidéo doit être regardée jusqu\'au bout par l\'agent.</p>' +
            '<div class="cs-seg" id="csVideoMode"><button type="button" data-mode="link" class="active"><i class="bi bi-link-45deg"></i> Lien vidéo</button>' +
            '<button type="button" data-mode="upload"><i class="bi bi-cloud-arrow-up"></i> Téléverser</button>' +
            '<button type="button" data-mode="none"><i class="bi bi-slash-circle"></i> Aucune</button></div>' +
            '<div data-video="link"><input type="url" class="form-control cs-input" id="csFVideo" placeholder="https://www.youtube.com/watch?v=… , Vimeo ou lien .mp4"></div>' +
            '<div data-video="upload" hidden><label class="cs-drop" id="csVideoDrop"><input type="file" accept="video/mp4,video/webm,video/quicktime,.m4v,.mov" hidden id="csVideoFile">' +
            '<i class="bi bi-film"></i><span><b>Choisir une vidéo</b> ou glisser-déposer<br><small>MP4, MOV, M4V, WEBM · 500 Mo max</small></span></label></div>' +
            '<div class="cs-video-preview" id="csVideoPreview"></div>' +
            '<label class="cs-label mt-3">Document de support</label>' +
            '<label class="cs-drop" id="csDocDrop"><input type="file" hidden id="csDocFile" accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.png,.jpg,.jpeg">' +
            '<i class="bi bi-file-earmark-arrow-up"></i><span><b>Joindre un fichier</b> ou glisser-déposer<br><small>PDF, Word, Excel, PowerPoint… · 20 Mo max</small></span></label>' +
            '<div id="csDocInfo"></div>' +
            '</section>' +
            // 4 — Paramètres
            '<section class="cs-pane" data-pane="settings" hidden>' +
            '<h6 class="cs-pane-title">Paramètres</h6><p class="cs-pane-sub">Qui suit ce cours et comment il est évalué.</p>' +
            '<label class="cs-label">Type de cours</label><div class="cs-choice-grid" id="csType">' +
            '<button type="button" class="cs-choice" data-type="SELF_ASSESSMENT"><span class="cs-choice-ic" style="background:#eaf2ff;color:#0057B8"><i class="bi bi-clipboard-heart"></i></span>' +
            '<b>Auto-diagnostic</b><small>L\'agent s\'évalue sur une échelle de 1 à 5. Non noté · +30 XP</small></button>' +
            '<button type="button" class="cs-choice" data-type="STANDARD"><span class="cs-choice-ic" style="background:#e4f7ef;color:#0b7a4b"><i class="bi bi-trophy"></i></span>' +
            '<b>Évaluation notée</b><small>QCM avec score, réussite à 70 % · +50 XP et certificat</small></button></div>' +
            '<div class="row g-3 mt-1"><div class="col-md-7"><label class="cs-label" for="csFTeam">Équipe concernée</label>' +
            '<select class="form-select cs-input" id="csFTeam"><option value="">Toutes les équipes (cours générique)</option></select></div>' +
            '<div class="col-md-5"><label class="cs-label">Caractère</label>' +
            '<label class="cs-switch"><input type="checkbox" id="csFMandatory"><span class="cs-switch-ui"></span><span><b>Obligatoire</b><small>Mis en avant chez l\'agent</small></span></label></div></div>' +
            '<label class="cs-label mt-3">Publication</label><div class="cs-choice-grid" id="csPublish">' +
            '<button type="button" class="cs-choice" data-pub="DRAFT"><span class="cs-choice-ic" style="background:#f2f4f7;color:#6b7684"><i class="bi bi-pencil-square"></i></span>' +
            '<b>Brouillon</b><small>Invisible des agents, à publier plus tard</small></button>' +
            '<button type="button" class="cs-choice" data-pub="PUBLISHED"><span class="cs-choice-ic" style="background:#fff4e0;color:#c77700"><i class="bi bi-broadcast"></i></span>' +
            '<b>Publier maintenant</b><small>Visible immédiatement par les équipes ciblées</small></button></div>' +
            '</section>' +
            // 5 — Évaluation
            '<section class="cs-pane" data-pane="quiz" hidden>' +
            '<h6 class="cs-pane-title">Questions de l\'évaluation</h6>' +
            '<p class="cs-pane-sub">Banque commune à la rubrique <b id="csQuizCat"></b> — les mêmes questions alimentent le Centre d\'Évaluation. 3 questions minimum recommandées.</p>' +
            '<div id="csQuizOff" class="cs-note" hidden><i class="bi bi-info-circle"></i> Ce cours est un auto-diagnostic : pas de questions notées. ' +
            '<button type="button" class="btn btn-link btn-sm p-0" id="csToStandard">Passer en évaluation notée</button></div>' +
            '<div id="csQuizOn"><div id="csQList" class="cs-qlist"></div>' +
            '<div class="cs-qform"><label class="cs-label" for="csQText">Nouvelle question</label>' +
            '<input type="text" class="form-control cs-input mb-2" id="csQText" placeholder="Ex. Quel est le délai de traitement d\'une réclamation GAB ?">' +
            '<div class="cs-qopts" id="csQOpts">' + [0, 1, 2, 3].map(function (i) {
                return '<label class="cs-qopt"><input type="radio" name="csQCorrect" value="' + i + '"' + (i === 0 ? " checked" : "") + '>' +
                    '<span class="cs-qletter">' + "ABCD"[i] + '</span><input type="text" class="form-control form-control-sm" placeholder="Réponse ' + "ABCD"[i] + (i < 2 ? "" : " (facultative)") + '"></label>';
            }).join("") + '</div><small class="cs-muted d-block mb-2"><i class="bi bi-check2-circle"></i> Cochez la bonne réponse.</small>' +
            '<button type="button" class="ef-btn ef-btn-soft w-100" id="csQAdd"><i class="bi bi-plus-lg"></i> Ajouter la question</button></div></div>' +
            '</section>';
    }

    // ── Comportements ────────────────────────────────────────────────────────────────────
    function q(sel) { return modal.querySelector(sel); }
    function qa(sel) { return Array.prototype.slice.call(modal.querySelectorAll(sel)); }

    function wire() {
        q("#csPrev").addEventListener("click", function () { go(state.step - 1); });
        q("#csNext").addEventListener("click", function () { if (validateStep()) go(state.step + 1); });
        q("#csSave").addEventListener("click", save);

        ["csFTitle", "csFDesc"].forEach(function (id) {
            q("#" + id).addEventListener("input", function () {
                state[id === "csFTitle" ? "title" : "description"] = this.value;
                this.classList.remove("is-invalid");
                refresh();
            });
        });
        q("#csFCat").addEventListener("input", function () { state.category = this.value; refresh(); });
        q("#csCatUnlock").addEventListener("click", function () {
            state.categoryLocked = false; syncCategory(); q("#csFCat").focus();
        });

        // Vignettes proposées + import
        q("#csCovers").innerHTML = COVERS.map(function (c) {
            return '<button type="button" class="cs-cover" data-cover="' + c + '" style="background-image:url(/images/formation/' + c + ')"><i class="bi bi-check-lg"></i></button>';
        }).join("");
        q("#csCovers").addEventListener("click", function (e) {
            var b = e.target.closest("[data-cover]");
            if (!b) return;
            state.coverPreset = b.getAttribute("data-cover");
            state.coverFile = null;
            refresh();
        });
        dropzone(q("#csCoverDrop"), q("#csCoverFile"), function (file) {
            if (!/^image\//.test(file.type)) return toast("Choisissez une image (JPG, PNG, WEBP).", true);
            if (file.size > 20 * 1024 * 1024) return toast("Image trop lourde (20 Mo max).", true);
            state.coverFile = file; state.coverPreset = null; refresh();
        });

        // Contenu
        qa("[data-tpl]").forEach(function (b) {
            b.addEventListener("click", function () {
                var current = editor.getHtml().replace(/<[^>]+>/g, "").trim();
                if (current && !confirm("Ajouter ce plan type à la suite du contenu existant ?")) return;
                editor.setHtml((current ? editor.getHtml() : "") + TEMPLATES[b.getAttribute("data-tpl")]);
                onContent();
            });
        });
        q("#csEditor").addEventListener("input", onContent);

        // Médias
        qa("#csVideoMode [data-mode]").forEach(function (b) {
            b.addEventListener("click", function () { state.videoMode = b.getAttribute("data-mode"); syncVideo(); refresh(); });
        });
        q("#csFVideo").addEventListener("input", function () { state.videoUrl = this.value.trim(); syncVideo(); refresh(); });
        dropzone(q("#csVideoDrop"), q("#csVideoFile"), function (file) {
            if (!/^video\//.test(file.type) && !/\.(mp4|m4v|mov|webm)$/i.test(file.name)) return toast("Formats vidéo acceptés : MP4, MOV, M4V, WEBM.", true);
            if (file.size > 500 * 1024 * 1024) return toast("Vidéo trop lourde (500 Mo max).", true);
            state.videoFile = file; syncVideo(); refresh();
        });
        dropzone(q("#csDocDrop"), q("#csDocFile"), function (file) {
            if (file.size > 20 * 1024 * 1024) return toast("Document trop lourd (20 Mo max).", true);
            state.docFile = file; syncDoc(); refresh();
        });
        q("#csDocInfo").addEventListener("click", function (e) {
            if (e.target.closest("[data-doc-remove]")) { state.docFile = null; q("#csDocFile").value = ""; syncDoc(); refresh(); }
        });
        q("#csVideoPreview").addEventListener("click", function (e) {
            if (e.target.closest("[data-video-remove]")) { state.videoFile = null; q("#csVideoFile").value = ""; syncVideo(); refresh(); }
        });

        // Paramètres
        qa("#csType [data-type]").forEach(function (b) {
            b.addEventListener("click", function () { state.type = b.getAttribute("data-type"); syncChoices(); renderSteps(); refresh(); });
        });
        qa("#csPublish [data-pub]").forEach(function (b) {
            b.addEventListener("click", function () { state.publish = b.getAttribute("data-pub"); syncChoices(); refresh(); });
        });
        q("#csFTeam").addEventListener("change", function () { state.teamCode = this.value; refresh(); });
        q("#csFMandatory").addEventListener("change", function () { state.mandatory = this.checked; refresh(); });
        q("#csToStandard").addEventListener("click", function () { state.type = "STANDARD"; syncChoices(); renderSteps(); go(4); });

        // Questions
        q("#csQAdd").addEventListener("click", addQuestion);
        q("#csQList").addEventListener("click", function (e) {
            var del = e.target.closest("[data-qdel]");
            if (!del || !confirm("Supprimer cette question de la banque de la rubrique ?")) return;
            sendJson("/api/quiz-questions/" + del.getAttribute("data-qdel"), "DELETE")
                .then(loadQuestions).catch(function (err) { toast("Suppression impossible : " + err.message, true); });
        });

        modal.addEventListener("hide.bs.modal", function (e) {
            if (state && state.saving) { e.preventDefault(); return; }
            if (state && isDirty() && !state.saved && !confirm("Fermer le studio ? Les modifications non enregistrées seront perdues.")) e.preventDefault();
        });
    }

    function dropzone(zone, input, onFile) {
        input.addEventListener("change", function () { if (input.files[0]) onFile(input.files[0]); });
        ["dragenter", "dragover"].forEach(function (ev) {
            zone.addEventListener(ev, function (e) { e.preventDefault(); zone.classList.add("drag"); });
        });
        ["dragleave", "drop"].forEach(function (ev) {
            zone.addEventListener(ev, function (e) { e.preventDefault(); zone.classList.remove("drag"); });
        });
        zone.addEventListener("drop", function (e) { if (e.dataTransfer.files[0]) onFile(e.dataTransfer.files[0]); });
    }

    function onContent() {
        state.content = editor.getHtml();
        var words = (q("#csEditor").innerText || "").trim().split(/\s+/).filter(Boolean).length;
        q("#csWords").textContent = words + " mot" + (words > 1 ? "s" : "") + " · ~" + Math.max(1, Math.round(words / 200)) + " min de lecture";
        refresh();
    }

    function isDirty() {
        return !!(state.title.trim() || state.description.trim() || textOf(state.content) || state.videoUrl || state.videoFile || state.docFile || state.coverFile);
    }

    function textOf(html) { return (html || "").replace(/<[^>]+>/g, "").replace(/&nbsp;/g, " ").trim(); }

    // ── Navigation ───────────────────────────────────────────────────────────────────────
    function renderSteps() {
        q("#csSteps").innerHTML = STEPS.map(function (s, i) {
            var cls = i === state.step ? "active" : (stepDone(i) ? "done" : "");
            var off = s.key === "quiz" && state.type !== "STANDARD";
            return '<button type="button" class="cs-step ' + cls + (off ? " off" : "") + '" data-step="' + i + '">' +
                '<span class="cs-step-dot"><i class="bi ' + (cls === "done" && i !== state.step ? "bi-check-lg" : s.icon) + '"></i></span>' +
                '<span class="cs-step-txt"><b>' + s.title + '</b><small>' + (off ? "Non requis (auto-diagnostic)" : s.hint) + '</small></span></button>';
        }).join("");
        qa("#csSteps [data-step]").forEach(function (b) {
            b.addEventListener("click", function () {
                var target = Number(b.getAttribute("data-step"));
                if (target > 0 && !state.title.trim()) { validateStep(); return; }
                go(target);
            });
        });
    }

    function stepDone(i) {
        switch (STEPS[i].key) {
            case "basics": return !!state.title.trim();
            case "content": return !!textOf(state.content);
            case "media": return !!(state.videoUrl || state.videoFile || state.docFile || state.existingFileName);
            case "settings": return true;
            case "quiz": return state.type === "STANDARD" && state.questions.length >= 3;
        }
        return false;
    }

    function go(step) {
        step = Math.max(0, Math.min(STEPS.length - 1, step));
        state.step = step;
        qa(".cs-pane").forEach(function (p) { p.hidden = p.getAttribute("data-pane") !== STEPS[step].key; });
        var pane = q('.cs-pane[data-pane="' + STEPS[step].key + '"]');
        pane.classList.remove("cs-enter"); void pane.offsetWidth; pane.classList.add("cs-enter");
        q("#csPrev").style.display = step === 0 ? "none" : "";
        q("#csNext").style.display = step === STEPS.length - 1 ? "none" : "";
        q("#csFootStep").textContent = "Étape " + (step + 1) + " sur " + STEPS.length + " · " + STEPS[step].title;
        q("#csProgressBar").style.width = ((step + 1) / STEPS.length * 100) + "%";
        if (STEPS[step].key === "quiz") syncQuiz();
        renderSteps();
        q("#csMain").scrollTop = 0;
    }

    function validateStep() {
        if (state.step === 0 && !state.title.trim()) {
            var t = q("#csFTitle");
            t.classList.add("is-invalid");
            t.focus();
            toast("Donnez un titre au cours pour continuer.", true);
            if (state.step !== 0) go(0);
            return false;
        }
        return true;
    }

    // ── Synchronisation des champs ───────────────────────────────────────────────────────
    function syncCategory() {
        q("#csCatLocked").hidden = !state.categoryLocked;
        q("#csFCat").hidden = state.categoryLocked;
        q("#csCatLockedName").textContent = state.category || "Général";
        q("#csFCat").value = state.category;
        q("#csCatList").innerHTML = categories.map(function (c) { return '<option value="' + esc(c) + '">'; }).join("");
    }

    function syncVideo() {
        qa("#csVideoMode [data-mode]").forEach(function (b) { b.classList.toggle("active", b.getAttribute("data-mode") === state.videoMode); });
        q('[data-video="link"]').hidden = state.videoMode !== "link";
        q('[data-video="upload"]').hidden = state.videoMode !== "upload";
        var box = q("#csVideoPreview"), html = "";
        if (state.videoMode === "upload" && state.videoFile) {
            html = '<div class="cs-file"><i class="bi bi-film"></i><div class="min-w-0"><b>' + esc(state.videoFile.name) + '</b><small>' + size(state.videoFile.size) +
                ' · sera téléversée à l\'enregistrement</small></div><button type="button" class="btn btn-sm btn-light ms-auto" data-video-remove><i class="bi bi-x-lg"></i></button></div>';
        } else if (state.videoMode === "link" && state.videoUrl) {
            var embed = toEmbed(state.videoUrl);
            if (embed) html = '<div class="ratio ratio-16x9 cs-embed"><iframe src="' + esc(embed) + '" allowfullscreen loading="lazy"></iframe></div>';
            else if (/\.(mp4|webm|m4v|mov|ogg)(\?.*)?$/i.test(state.videoUrl) || /^\/uploaded-photos\//.test(state.videoUrl))
                html = '<video class="cs-embed" controls preload="metadata" src="' + esc(state.videoUrl) + '"></video>';
            else html = '<div class="cs-note warn"><i class="bi bi-exclamation-triangle"></i> Lien non reconnu : utilisez YouTube, Vimeo ou un lien direct .mp4.</div>';
        }
        box.innerHTML = html;
    }

    function syncDoc() {
        var f = state.docFile;
        q("#csDocInfo").innerHTML = f
            ? '<div class="cs-file mt-2"><i class="bi bi-file-earmark-text"></i><div class="min-w-0"><b>' + esc(f.name) + '</b><small>' + size(f.size) +
              ' · sera joint à l\'enregistrement</small></div><button type="button" class="btn btn-sm btn-light ms-auto" data-doc-remove><i class="bi bi-x-lg"></i></button></div>'
            : state.existingFileName
                ? '<div class="cs-file mt-2"><i class="bi bi-paperclip"></i><div class="min-w-0"><b>' + esc(state.existingFileName) + '</b><small>Document actuel — importez-en un autre pour le remplacer</small></div></div>'
                : "";
    }

    function syncChoices() {
        qa("#csType [data-type]").forEach(function (b) { b.classList.toggle("active", b.getAttribute("data-type") === state.type); });
        qa("#csPublish [data-pub]").forEach(function (b) { b.classList.toggle("active", b.getAttribute("data-pub") === state.publish); });
        q("#csFTeam").value = state.teamCode;
        q("#csFMandatory").checked = state.mandatory;
    }

    function syncQuiz() {
        var on = state.type === "STANDARD";
        q("#csQuizOff").hidden = on;
        q("#csQuizOn").hidden = !on;
        q("#csQuizCat").textContent = categoryName();
        if (on) loadQuestions();
    }

    function categoryName() { return (state.category || "").trim() || "Général"; }

    function loadQuestions() {
        var cat = categoryName();
        q("#csQList").innerHTML = '<div class="cs-muted small">Chargement des questions…</div>';
        return getJson("/api/quiz-questions?category=" + encodeURIComponent(cat)).then(function (list) {
            state.questions = list || [];
            renderQuestions();
            refresh();
        }).catch(function () { q("#csQList").innerHTML = '<div class="cs-note warn">Questions indisponibles pour le moment.</div>'; });
    }

    function renderQuestions() {
        var list = state.questions;
        q("#csQList").innerHTML = list.length ? list.map(function (qq, i) {
            return '<div class="cs-q" style="animation-delay:' + (i * 40) + 'ms"><div class="cs-q-num">' + (i + 1) + '</div><div class="min-w-0 flex-grow-1">' +
                '<b>' + esc(qq.questionText) + '</b><div class="cs-q-opts">' + (qq.options || []).map(function (o, k) {
                    return '<span class="' + (k === qq.correctOptionIndex ? "ok" : "") + '">' + "ABCD"[k] + ". " + esc(o) + '</span>';
                }).join("") + '</div></div><button type="button" class="btn btn-sm btn-light" title="Supprimer" data-qdel="' + qq.questionId + '"><i class="bi bi-trash"></i></button></div>';
        }).join("") : '<div class="cs-empty-q"><i class="bi bi-patch-question"></i><b>Aucune question pour cette rubrique</b><small>Ajoutez-en au moins 3 pour une évaluation fiable.</small></div>';
    }

    function addQuestion() {
        var text = q("#csQText").value.trim();
        var inputs = qa("#csQOpts input[type=text]");
        var raw = inputs.map(function (i) { return i.value.trim(); });
        var checked = Number((q('input[name="csQCorrect"]:checked') || { value: 0 }).value);
        if (!text) { q("#csQText").classList.add("is-invalid"); return toast("Saisissez l'intitulé de la question.", true); }
        if (!raw[checked]) return toast("La réponse cochée comme correcte est vide.", true);
        var options = [], correct = 0;
        raw.forEach(function (v, i) { if (v) { if (i === checked) correct = options.length; options.push(v); } });
        if (options.length < 2) return toast("Il faut au moins 2 réponses.", true);
        q("#csQAdd").disabled = true;
        sendJson("/api/quiz-questions", "POST", {
            questionText: text, type: "MCQ", difficulty: "MEDIUM", category: categoryName(),
            options: options, correctOptionIndex: correct, points: 10, active: true
        }).then(function () {
            q("#csQText").value = ""; q("#csQText").classList.remove("is-invalid");
            inputs.forEach(function (i) { i.value = ""; });
            q('input[name="csQCorrect"][value="0"]').checked = true;
            toast("Question ajoutée ✓");
            return loadQuestions();
        }).catch(function (e) { toast("Erreur : " + e.message, true); })
          .then(function () { q("#csQAdd").disabled = false; q("#csQText").focus(); });
    }

    // ── Aperçu + contrôle qualité ────────────────────────────────────────────────────────
    var coverObjectUrl = null;
    function coverSrc() {
        if (state.coverFile) {
            if (!coverObjectUrl || coverObjectUrl.file !== state.coverFile) {
                if (coverObjectUrl) URL.revokeObjectURL(coverObjectUrl.url);
                coverObjectUrl = { file: state.coverFile, url: URL.createObjectURL(state.coverFile) };
            }
            return coverObjectUrl.url;
        }
        if (state.coverPreset) return "/images/formation/" + state.coverPreset;
        return state.coverUrl || "/images/formation/" + COVERS[(state.title.length || 3) % COVERS.length];
    }

    function refresh() {
        if (!state) return;
        qa(".cs-counter").forEach(function (c) {
            var el = q("#" + c.getAttribute("data-for"));
            c.textContent = el.value.length + "/" + el.getAttribute("maxlength");
        });
        qa("#csCovers [data-cover]").forEach(function (b) { b.classList.toggle("active", b.getAttribute("data-cover") === state.coverPreset); });
        q("#csCoverDrop span").innerHTML = state.coverFile
            ? '<b>' + esc(state.coverFile.name) + '</b> · ' + size(state.coverFile.size) + ' — cliquer pour changer'
            : '<b>Importer une image</b> ou glisser-déposer · JPG, PNG, WEBP · 20 Mo max';

        var hasVideo = (state.videoMode === "upload" && state.videoFile) || (state.videoMode === "link" && state.videoUrl);
        var team = q("#csFTeam");
        var teamLabel = state.teamCode && team.selectedOptions[0] ? team.selectedOptions[0].textContent : "Toutes les équipes";
        q("#csPreview").innerHTML =
            '<div class="cs-card"><div class="cs-card-img" style="background-image:url(\'' + esc(coverSrc()) + '\')">' +
            '<span class="cs-card-xp">' + (state.type === "STANDARD" ? "+50 XP" : "+30 XP") + '</span>' +
            (state.publish === "DRAFT" ? '<span class="cs-card-draft">Brouillon</span>' : '<span class="cs-card-live"><i class="bi bi-broadcast"></i> Publié</span>') + '</div>' +
            '<div class="cs-card-body"><span class="ef-pill ef-pill-sm">' + esc(categoryName()) + '</span>' +
            '<h4>' + (esc(state.title.trim()) || '<span class="cs-ph">Titre du cours</span>') + '</h4>' +
            '<p>' + (esc(state.description.trim()) || '<span class="cs-ph">La description apparaîtra ici.</span>') + '</p>' +
            '<div class="cs-card-meta"><span>' + (state.type === "STANDARD" ? "🧠 Évaluation" : "📝 Auto-diagnostic") + '</span>' +
            (state.mandatory ? "<span>⭐ Obligatoire</span>" : "") + (hasVideo ? "<span>🎬 Vidéo</span>" : "") +
            (state.docFile || state.existingFileName ? "<span>📎 Document</span>" : "") + '</div>' +
            '<small class="cs-card-team"><i class="bi bi-people"></i> ' + esc(teamLabel) + '</small></div></div>';

        var checks = [
            { ok: !!state.title.trim(), label: "Titre renseigné", req: true },
            { ok: state.description.trim().length >= 20, label: "Description claire (20 caractères min.)" },
            { ok: !!(textOf(state.content) || hasVideo || state.docFile || state.existingFileName), label: "Contenu, vidéo ou document" },
            { ok: !!(state.coverFile || state.coverPreset || state.coverUrl), label: "Vignette choisie" }
        ];
        if (state.type === "STANDARD") checks.push({ ok: state.questions.length >= 3, label: "3 questions ou plus (" + state.questions.length + ")" });
        var score = checks.filter(function (c) { return c.ok; }).length;
        q("#csChecks").innerHTML = checks.map(function (c) {
            return '<li class="' + (c.ok ? "ok" : (c.req ? "req" : "")) + '"><i class="bi ' + (c.ok ? "bi-check-circle-fill" : "bi-circle") + '"></i>' + esc(c.label) + '</li>';
        }).join("") + '<li class="cs-score"><div class="cs-score-bar"><span style="width:' + Math.round(score / checks.length * 100) + '%"></span></div>' +
            score + '/' + checks.length + '</li>';

        q("#csSave span").textContent = state.courseId
            ? "Enregistrer les modifications"
            : (state.publish === "PUBLISHED" ? "Créer et publier" : "Créer le cours");
        renderStepsLight();
    }

    function renderStepsLight() {
        qa("#csSteps [data-step]").forEach(function (b) {
            var i = Number(b.getAttribute("data-step"));
            b.classList.toggle("done", i !== state.step && stepDone(i));
        });
    }

    // ── Enregistrement ───────────────────────────────────────────────────────────────────
    function save() {
        if (!state.title.trim()) { go(0); validateStep(); return; }
        if (state.videoMode === "link" && state.videoUrl && !/^(https?:\/\/|\/)/i.test(state.videoUrl)) {
            go(2); return toast("Le lien vidéo doit commencer par https://", true);
        }
        state.saving = true;
        var payload = {
            title: state.title.trim(),
            description: state.description.trim() || (state.courseId ? "" : null),
            content: textOf(state.content) ? state.content : (state.courseId ? "" : null),
            videoUrl: state.videoMode === "link" ? (state.videoUrl || "") : (state.videoMode === "none" ? "" : null),
            type: state.type,
            teamCode: state.teamCode || "",
            category: state.category.trim() || "",
            mandatory: state.mandatory
        };
        if (!state.courseId && payload.videoUrl === "") payload.videoUrl = null;
        var tasks = [];
        if (state.coverFile) tasks.push({ label: "Vignette", url: "image", file: state.coverFile });
        if (state.videoMode === "upload" && state.videoFile) tasks.push({ label: "Vidéo", url: "video", file: state.videoFile });
        if (state.docFile) tasks.push({ label: "Document", url: "file", file: state.docFile });

        overlay(true, state.courseId ? "Enregistrement des modifications…" : "Création du cours…", 0, "");
        var request = state.courseId
            ? sendJson("/api/courses/" + state.courseId, "PUT", payload)
            : sendJson("/api/courses", "POST", payload);
        var course;
        request.then(function (saved) {
            course = saved;
            state.courseId = saved.courseId;
            var chain = Promise.resolve();
            if (state.coverPreset && !state.coverFile) {
                chain = chain.then(function () {
                    return fetch("/images/formation/" + state.coverPreset).then(function (r) { return r.blob(); }).then(function (blob) {
                        tasks.unshift({ label: "Vignette", url: "image", file: new File([blob], state.coverPreset, { type: blob.type || "image/jpeg" }) });
                    }).catch(function () {});
                });
            }
            return chain.then(function () {
                return tasks.reduce(function (p, t, i) {
                    return p.then(function () {
                        return upload("/api/courses/" + course.courseId + "/" + t.url, t.file, function (pct) {
                            overlay(true, "Envoi : " + t.label.toLowerCase() + "…", pct, (i + 1) + "/" + tasks.length + " · " + t.file.name);
                        }).then(function (updated) { course = updated || course; })
                          .catch(function (e) { toast(t.label + " non envoyé(e) : " + e.message, true); });
                    });
                }, Promise.resolve());
            });
        }).then(function () {
            if (state.publish !== (course.publicationStatus || "DRAFT")) {
                overlay(true, state.publish === "PUBLISHED" ? "Publication…" : "Mise en brouillon…", 100, "");
                return fetch("/api/courses/" + course.courseId + "/publication?status=" + state.publish, { method: "PATCH", credentials: "same-origin" })
                    .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
                    .then(function (c) { course = c; })
                    .catch(function (e) { toast("Cours enregistré, mais publication impossible : " + e.message, true); });
            }
        }).then(function () {
            state.saving = false;
            state.saved = true;
            overlay(false);
            bsModal.hide();
            toast(course.publicationStatus === "PUBLISHED" ? "Cours publié — visible par les agents ✓" : "Cours enregistré en brouillon ✓");
            document.dispatchEvent(new CustomEvent("rcc:course-saved", { detail: course }));
        }).catch(function (e) {
            state.saving = false;
            overlay(false);
            toast("Enregistrement impossible : " + e.message, true);
        });
    }

    function upload(url, file, onProgress) {
        return new Promise(function (resolve, reject) {
            var xhr = new XMLHttpRequest();
            var fd = new FormData();
            fd.append("file", file);
            xhr.open("POST", url);
            xhr.withCredentials = true;
            xhr.upload.onprogress = function (e) { if (e.lengthComputable) onProgress(Math.round(e.loaded / e.total * 100)); };
            xhr.onload = function () {
                if (xhr.status >= 200 && xhr.status < 300) {
                    try { resolve(JSON.parse(xhr.responseText)); } catch (err) { resolve(null); }
                } else {
                    var msg = "HTTP " + xhr.status;
                    try { msg = JSON.parse(xhr.responseText).message || msg; } catch (err) { /* texte brut */ }
                    reject(new Error(msg));
                }
            };
            xhr.onerror = function () { reject(new Error("connexion interrompue")); };
            xhr.send(fd);
        });
    }

    function overlay(show, label, pct, detail) {
        var o = q("#csSaving");
        o.hidden = !show;
        if (!show) return;
        q("#csSavingLabel").textContent = label;
        q("#csSavingBar").style.width = (pct || 0) + "%";
        q("#csSavingDetail").textContent = detail || "";
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────────────────
    function toEmbed(url) {
        var yt = url.match(/(?:youtube\.com\/watch\?v=|youtu\.be\/|youtube\.com\/shorts\/)([\w-]{6,})/);
        if (yt) return "https://www.youtube.com/embed/" + yt[1];
        var vimeo = url.match(/vimeo\.com\/(\d+)/);
        if (vimeo) return "https://player.vimeo.com/video/" + vimeo[1];
        return null;
    }

    function size(bytes) {
        return bytes > 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(1).replace(".", ",") + " Mo" : Math.max(1, Math.round(bytes / 1024)) + " Ko";
    }

    function toast(message, error) {
        var t = document.getElementById("efToast");
        if (!t) { if (error) alert(message); return; }
        t.textContent = message;
        t.classList.toggle("ef-toast-error", !!error);
        t.classList.add("show");
        clearTimeout(toast.timer);
        toast.timer = setTimeout(function () { t.classList.remove("show"); }, 3200);
    }

    function loadRefs() {
        var jobs = [];
        if (!teamsLoaded) {
            jobs.push(getJson("/api/teams").then(function (teams) {
                var sel = q("#csFTeam");
                (teams || []).forEach(function (t) {
                    var o = document.createElement("option");
                    o.value = t.code; o.textContent = t.label;
                    sel.appendChild(o);
                });
                teamsLoaded = true;
            }).catch(function () {}));
        }
        jobs.push(getJson("/api/courses/categories").then(function (cats) {
            categories = (cats || []).map(function (c) { return c.title; }).filter(Boolean);
        }).catch(function () {}));
        return Promise.all(jobs);
    }

    // ── API publique ─────────────────────────────────────────────────────────────────────
    function open(options) {
        options = options || {};
        build();
        state = blank();
        var c = options.course;
        if (c) {
            state.courseId = c.courseId;
            state.title = c.title || "";
            state.description = c.description || "";
            state.category = c.category || "";
            state.content = c.content || "";
            state.videoUrl = c.videoUrl || "";
            state.videoMode = c.videoUrl ? "link" : "none";
            state.existingFileName = c.fileName || null;
            state.coverUrl = c.imageUrl || null;
            state.type = c.type || "SELF_ASSESSMENT";
            state.teamCode = c.teamCode || "";
            state.mandatory = !!c.mandatory;
            state.publish = state.initialStatus = c.publicationStatus || "DRAFT";
        } else if (options.category != null) {
            state.category = options.category === "Général" ? "" : options.category;
            state.categoryLocked = true;
        }
        q("#csEyebrow").textContent = c ? "Studio de création · modification" : "Studio de création";
        q("#csTitle").textContent = c ? c.title : (state.categoryLocked ? "Nouveau cours · " + categoryName() : "Nouveau cours");
        q("#csFTitle").value = state.title;
        q("#csFTitle").classList.remove("is-invalid");
        q("#csFDesc").value = state.description;
        q("#csFVideo").value = state.videoUrl;
        q("#csCoverFile").value = ""; q("#csVideoFile").value = ""; q("#csDocFile").value = "";
        editor.setHtml(state.content);
        loadRefs().then(function () { syncCategory(); syncChoices(); refresh(); });
        syncCategory(); syncVideo(); syncDoc(); syncChoices();
        overlay(false);
        go(0);
        onContent();
        bsModal.show();
        setTimeout(function () { q("#csFTitle").focus(); }, 350);
    }

    window.RccCourseStudio = { open: open };
})();
