"use strict";

/**
 * Centre de Jeux Ecobank — 10 jeux, un seul moteur de sons/animations partagé.
 * Chaque jeu pioche dans /api/quiz-questions (banque enrichie), /api/word-terms
 * (vocabulaire) ou /api/games/process-puzzle (vrais parcours interactifs QA).
 */
window.RccGames = (function () {
    var $ = function (id) { return document.getElementById(id); };
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var gamesCache = [];
    var currentGameDef = null;
    var currentProfile = null;
    var audioCtx = null;

    // ===================== SONS (Web Audio API — aucun fichier nécessaire) =====================

    function ctx() {
        if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        return audioCtx;
    }

    function tone(freq, startTime, duration, type, gainPeak) {
        var c = ctx();
        var osc = c.createOscillator();
        var gain = c.createGain();
        osc.type = type || "sine";
        osc.frequency.setValueAtTime(freq, c.currentTime + startTime);
        gain.gain.setValueAtTime(0, c.currentTime + startTime);
        gain.gain.linearRampToValueAtTime(gainPeak || 0.15, c.currentTime + startTime + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.001, c.currentTime + startTime + duration);
        osc.connect(gain).connect(c.destination);
        osc.start(c.currentTime + startTime);
        osc.stop(c.currentTime + startTime + duration + 0.05);
    }

    function playCorrect() { tone(523.25, 0, 0.12, "sine"); tone(783.99, 0.1, 0.18, "sine"); }
    function playWrong() { tone(196, 0, 0.28, "sawtooth", 0.1); }
    function playClick() { tone(440, 0, 0.06, "square", 0.06); }
    function playWin() {
        [523.25, 659.25, 783.99, 1046.5].forEach(function (f, i) { tone(f, i * 0.11, 0.2, "sine"); });
    }
    function playTick() { tone(880, 0, 0.05, "square", 0.04); }

    // ===================== CONFETTIS (canvas maison) =====================

    function confettiBurst() {
        var canvas = document.getElementById("gmConfettiCanvas");
        if (!canvas) {
            canvas = document.createElement("canvas");
            canvas.id = "gmConfettiCanvas";
            document.body.appendChild(canvas);
        }
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;
        var ctx2d = canvas.getContext("2d");
        var colors = ["#0057B8", "#00A651", "#F5A623", "#F72585", "#7B2FF7"];
        var particles = [];
        for (var i = 0; i < 120; i++) {
            particles.push({
                x: Math.random() * canvas.width,
                y: -20 - Math.random() * canvas.height * 0.3,
                vx: (Math.random() - 0.5) * 4,
                vy: 2 + Math.random() * 4,
                size: 4 + Math.random() * 6,
                color: colors[Math.floor(Math.random() * colors.length)],
                rotation: Math.random() * 360,
                vr: (Math.random() - 0.5) * 10
            });
        }
        var frames = 0;
        function animate() {
            ctx2d.clearRect(0, 0, canvas.width, canvas.height);
            particles.forEach(function (p) {
                p.x += p.vx; p.y += p.vy; p.rotation += p.vr;
                ctx2d.save();
                ctx2d.translate(p.x, p.y);
                ctx2d.rotate(p.rotation * Math.PI / 180);
                ctx2d.fillStyle = p.color;
                ctx2d.fillRect(-p.size / 2, -p.size / 2, p.size, p.size);
                ctx2d.restore();
            });
            frames++;
            if (frames < 110) requestAnimationFrame(animate);
            else ctx2d.clearRect(0, 0, canvas.width, canvas.height);
        }
        animate();
    }

    // ===================== API HELPERS =====================

    function drawQuestions(category, difficulty, type, count) {
        var params = new URLSearchParams();
        if (category) params.set("category", category);
        if (difficulty) params.set("difficulty", difficulty);
        if (type) params.set("type", type);
        params.set("count", count);
        return getJson("/api/quiz-questions/draw?" + params.toString());
    }

    function submitAnswer(questionId, selectedOptionIndex, selectedIndexes) {
        return sendJson("/api/quiz-questions/" + questionId + "/answer", "POST", {
            selectedOptionIndex: selectedOptionIndex, selectedIndexes: selectedIndexes || null
        });
    }

    function drawTerms(count) {
        return getJson("/api/word-terms/draw?count=" + count);
    }

    function submitGameScore(gameKey, score, correctCount, totalCount) {
        return sendJson("/api/games/" + gameKey + "/score", "POST", { score: score, correctCount: correctCount, totalCount: totalCount })
            .catch(function () {});
    }

    /** gameKey -> nombre de tentatives déjà jouées dans le cycle d'évaluation en cours
     *  (remis à 0 après la 2e tentative, pour que rejouer reparte sur un cycle neuf). */
    var evaluationAttempts = {};

    var activeCompetitionId = null; // suivi de la compétition en cours, si "Commencer" a été cliqué depuis une carte compétition

    function submitEvaluationResult(gameKey, attemptNumber, score, correctCount, totalCount, answers) {
        return sendJson("/api/games/" + gameKey + "/evaluation-result", "POST", {
            attemptNumber: attemptNumber, score: score, correctCount: correctCount,
            totalCount: totalCount, answers: answers || []
        }).catch(function () {}).then(function () {
            if (activeCompetitionId && attemptNumber >= 2) {
                var compId = activeCompetitionId;
                activeCompetitionId = null;
                sendJson("/api/competitions/" + compId + "/complete", "POST", { score: score })
                    .then(loadMyCompetitions).catch(function () {});
            }
        });
    }

    // ===================== GALERIE =====================

    function loadGallery() {
        getJson("/api/games").then(function (games) {
            gamesCache = games || [];
            renderGallery();

            // Lien direct depuis une rubrique Formation ("Lancer l'évaluation") — lance
            // automatiquement Quiz Éclair (seul mécanisme respectant le filtre catégorie
            // aujourd'hui), sans que l'agent ait à le retrouver dans la galerie.
            var params = new URLSearchParams(window.location.search);
            if (params.get("category") && params.get("autoplay") === "quiz-eclair") {
                var quizEclair = gamesCache.filter(function (g) { return g.gameKey === "quiz-eclair"; })[0];
                if (quizEclair) launchGame(quizEclair);
            }
        }).catch(function () {
            $("gmGallery").innerHTML = '<p class="text-danger text-center">Impossible de charger les jeux.</p>';
        });
    }

    function renderGallery() {
        var container = $("gmGallery");
        if (!gamesCache.length) { container.innerHTML = '<p class="text-muted text-center">Aucun jeu disponible pour l\'instant.</p>'; return; }
        container.innerHTML = gamesCache.map(function (g) {
            var bestBadge = g.bestScore != null ? '<div class="gm-best"><i class="bi bi-trophy-fill"></i> ' + g.bestScore + '</div>' : "";
            var inactiveClass = g.active ? "" : " gm-inactive";
            return (
                '<div class="gm-card' + inactiveClass + '" style="background:linear-gradient(135deg,' + g.colorFrom + ',' + g.colorTo + ');" data-game-key="' + g.gameKey + '">' +
                    bestBadge +
                    '<div class="gm-icon"><i class="bi ' + (g.icon || "bi-controller") + '"></i></div>' +
                    '<div><h5>' + escapeHtml(g.title) + '</h5><p>' + escapeHtml(g.description || "") + '</p></div>' +
                    '<span class="gm-play-btn"><i class="bi bi-play-fill"></i> ' + (g.active ? "Jouer" : "Indisponible") + '</span>' +
                '</div>'
            );
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".gm-card"), function (card) {
            card.addEventListener("click", function () {
                var key = card.getAttribute("data-game-key");
                var def = gamesCache.find(function (g) { return g.gameKey === key; });
                if (!def || !def.active) return;
                playClick();
                launchGame(def);
            });
        });
    }

    // ===================== SCÈNE DE JEU =====================

    function setRalphFabVisible(visible) {
        var fab = document.getElementById("ralphFab");
        if (fab) fab.classList.toggle("d-none", !visible);
        var panel = document.getElementById("ralphPanel");
        if (panel && !visible) panel.classList.add("d-none"); // ferme aussi le panneau s'il était ouvert
    }

    function openStage(def) {
        currentGameDef = def;
        $("gmStageIcon").innerHTML = '<i class="bi ' + (def.icon || "bi-controller") + '"></i>';
        $("gmStageTitle").textContent = def.title;
        $("gmStageScore").style.display = "none";
        $("gmStage").style.display = "flex";
        document.body.style.overflow = "hidden";
        setRalphFabVisible(false); // l'agent est dans une évaluation — RAF reste caché jusqu'à la sortie complète
    }

    function closeStage() {
        $("gmStage").style.display = "none";
        $("gmStageBody").innerHTML = "";
        document.body.style.overflow = "";
        setRalphFabVisible(true); // sortie complète des évaluations — RAF réapparaît
        loadGallery();
    }

    function setStageScore(score) {
        var el = $("gmStageScore");
        el.style.display = "";
        el.textContent = score + " pts";
    }

    function stageBody() { return $("gmStageBody"); }

    function parseConfig(def) {
        var config;
        try { config = JSON.parse(def.configJson || "{}"); } catch (e) { config = {}; }
        // Lien direct depuis une rubrique Formation ("Lancer l'évaluation") — la partie ne
        // pioche alors que dans les questions de cette rubrique, sans jamais affecter le
        // Centre d'Évaluation général quand ce paramètre est absent de l'URL.
        var categoryFromUrl = new URLSearchParams(window.location.search).get("category");
        if (categoryFromUrl) config.category = categoryFromUrl;
        return config;
    }

    /**
     * @param answers optionnel — uniquement fourni par les jeux à choix (QCM). Quand présent,
     *   active le mode "évaluation" : 1re tentative → score seul, aucune correction affichée ;
     *   2e tentative → détail complet (vert/rouge). Absent pour les jeux non-QCM (mémoire,
     *   pendu, JoliGo, puzzle), qui gardent l'écran de résultat classique inchangé.
     *
     *   En mode évaluation, le score transmis par le moteur de jeu (points bruts, très
     *   variables selon le mécanisme — l'échelle des millions grimpe jusqu'à 300 000, la
     *   roue applique des multiplicateurs...) est ici RECALCULÉ sur 100, uniquement à partir
     *   du taux de bonnes réponses : le score d'une évaluation a le même sens partout, peu
     *   importe le jeu utilisé pour la faire passer.
     */
    function resultScreen(gameKey, score, correctCount, totalCount, extraNote, answers) {
        if (answers && answers.length) {
            score = totalCount ? Math.round((correctCount / totalCount) * 100) : 0;
        }

        submitGameScore(gameKey, score, correctCount, totalCount);

        if (answers && answers.length) {
            var attempt = (evaluationAttempts[gameKey] || 0) + 1;
            evaluationAttempts[gameKey] = attempt;
            submitEvaluationResult(gameKey, attempt, score, correctCount, totalCount, answers);

            if (attempt === 1) {
                renderFirstAttemptResult(gameKey, score, correctCount, totalCount, extraNote);
            } else {
                evaluationAttempts[gameKey] = 0;
                renderFinalEvaluationResult(gameKey, score, correctCount, totalCount, extraNote, answers);
            }
            return;
        }

        renderPlainResult(gameKey, score, correctCount, totalCount, extraNote);
    }

    function renderPlainResult(gameKey, score, correctCount, totalCount, extraNote) {
        var isGood = totalCount ? (correctCount / totalCount) >= 0.6 : score > 0;
        if (isGood) { playWin(); confettiBurst(); }
        stageBody().innerHTML =
            '<div class="gm-result-screen">' +
                '<div style="font-size:3.4rem;">' + (isGood ? "🏆" : "💪") + '</div>' +
                '<div class="gm-result-score">' + score + ' pts</div>' +
                (totalCount ? '<p class="text-muted mt-2">' + correctCount + ' / ' + totalCount + ' bonnes réponses</p>' : '') +
                (extraNote ? '<p class="text-muted">' + extraNote + '</p>' : '') +
                '<div class="d-flex gap-2 justify-content-center mt-4">' +
                    '<button class="btn btn-primary" id="gmReplayBtn"><i class="bi bi-arrow-repeat"></i> Rejouer</button>' +
                    '<button class="btn btn-outline-secondary" id="gmLeaderboardBtn"><i class="bi bi-list-ol"></i> Classement</button>' +
                '</div>' +
                '<div id="gmLeaderboardBox" class="mt-4 text-start mx-auto" style="max-width:420px; display:none;"></div>' +
            '</div>';
        $("gmReplayBtn").addEventListener("click", function () { launchGame(currentGameDef); });
        $("gmLeaderboardBtn").addEventListener("click", function () { showLeaderboard(gameKey); });
    }

    /** 1re tentative d'une évaluation QCM — score seul, aucune correction affichée : le
     *  conseiller enchaîne les questions jusqu'au bout sans savoir lesquelles étaient
     *  justes, puis a droit à UNE reprise avant que le détail ne soit dévoilé. */
    function renderFirstAttemptResult(gameKey, score, correctCount, totalCount, extraNote) {
        stageBody().innerHTML =
            '<div class="gm-result-screen">' +
                '<div style="font-size:3.4rem;">📝</div>' +
                '<div class="gm-result-score">' + score + ' / 100</div>' +
                '<p class="text-muted mt-2">' + correctCount + ' / ' + totalCount + ' bonnes réponses</p>' +
                (extraNote ? '<p class="text-muted">' + extraNote + '</p>' : '') +
                '<div class="alert alert-primary mt-3 mx-auto" style="max-width:460px;">' +
                    '<i class="bi bi-info-circle"></i> Vous avez droit à une seule reprise. ' +
                    'Les bonnes réponses ne seront révélées qu\'après votre 2<sup>e</sup> tentative.' +
                '</div>' +
                '<div class="d-flex gap-2 justify-content-center mt-4">' +
                    '<button class="btn btn-primary" id="gmRetryBtn"><i class="bi bi-arrow-repeat"></i> Retenter (dernière chance)</button>' +
                    '<button class="btn btn-outline-secondary" id="gmFinishBtn">Terminer sans reprise</button>' +
                '</div>' +
            '</div>';
        $("gmRetryBtn").addEventListener("click", function () { launchGame(currentGameDef); });
        $("gmFinishBtn").addEventListener("click", closeStage);
    }

    /** 2e tentative (ou dernière autorisée) — score + détail complet : bonnes réponses en
     *  vert, mauvaises en rouge avec la bonne réponse affichée à côté. */
    function renderFinalEvaluationResult(gameKey, score, correctCount, totalCount, extraNote, answers, skipCelebration) {
        var isGood = totalCount ? (correctCount / totalCount) >= 0.6 : score > 0;
        if (isGood && !skipCelebration) { playWin(); confettiBurst(); }
        var rows = (answers || []).map(function (a, i) {
            var yourAnswer = a.selectedIndex != null && a.options && a.options[a.selectedIndex] != null
                ? a.options[a.selectedIndex] : "(pas de réponse)";
            var correctAnswer = a.correctIndex != null && a.options ? a.options[a.correctIndex] : null;
            return '<div class="gm-eval-row ' + (a.correct ? "gm-eval-ok" : "gm-eval-ko") + '">' +
                '<div class="gm-eval-q">' + (i + 1) + '. ' + escapeHtml(a.questionText) + '</div>' +
                '<div class="gm-eval-a">' +
                    (a.correct
                        ? '<span class="text-success"><i class="bi bi-check-circle-fill"></i> ' + escapeHtml(yourAnswer) + '</span>'
                        : '<span class="text-danger"><i class="bi bi-x-circle-fill"></i> ' + escapeHtml(yourAnswer) + '</span>' +
                          (correctAnswer != null ? ' <span class="text-success ms-2"><i class="bi bi-arrow-right-circle-fill"></i> ' + escapeHtml(correctAnswer) + '</span>' : "")) +
                '</div></div>';
        }).join("");

        stageBody().innerHTML =
            '<div class="gm-result-screen">' +
                '<div style="font-size:3.4rem;">' + (isGood ? "🏆" : "💪") + '</div>' +
                '<div class="gm-result-score">' + score + ' / 100</div>' +
                '<p class="text-muted mt-2">' + correctCount + ' / ' + totalCount + ' bonnes réponses</p>' +
                (extraNote ? '<p class="text-muted">' + extraNote + '</p>' : '') +
                '<div class="gm-eval-detail text-start mx-auto mt-3" style="max-width:640px;">' + rows + '</div>' +
                '<div class="d-flex gap-2 justify-content-center mt-4">' +
                    '<button class="btn btn-outline-secondary" id="gmFinishBtn">Fermer</button>' +
                '</div>' +
            '</div>';
        $("gmFinishBtn").addEventListener("click", closeStage);
    }

    function showLeaderboard(gameKey) {
        var box = $("gmLeaderboardBox");
        box.style.display = "";
        box.innerHTML = '<p class="text-muted text-center">Chargement…</p>';
        getJson("/api/games/" + gameKey + "/leaderboard").then(function (rows) {
            if (!rows.length) { box.innerHTML = '<p class="text-muted text-center">Aucun score enregistré pour l\'instant.</p>'; return; }
            box.innerHTML = '<h6 class="mb-2"><i class="bi bi-list-ol"></i> Meilleurs scores</h6>' +
                rows.map(function (r, i) {
                    return '<div class="d-flex justify-content-between border-bottom py-1"><span>' + (i + 1) + '. ' + escapeHtml(r.playerName) + '</span><strong>' + r.score + '</strong></div>';
                }).join("");
        });
    }

    // ===================== ROUTAGE VERS LE MOTEUR =====================

    /** Mécaniques "évaluation" (QCM) — soumises à la règle des 2 tentatives, voir resultScreen(). */
    var EVALUATION_MECHANICS = ["MCQ_STANDARD", "MCQ_LADDER", "TRUE_FALSE_RAPID", "WHEEL", "MCQ_DUEL", "MCQ_SURVIVAL"];

    function launchGame(def) {
        if (EVALUATION_MECHANICS.indexOf(def.mechanic) !== -1) {
            openStage(def);
            stageBody().innerHTML = '<p class="text-center text-muted">Chargement…</p>';
            getJson("/api/games/" + def.gameKey + "/evaluation-status").then(function (status) {
                if (status.completed) {
                    renderFinalEvaluationResult(def.gameKey, status.finalScore, status.finalCorrectCount, status.finalTotalCount,
                        "Vous avez déjà terminé cette évaluation. Contactez votre formateur si une nouvelle session doit être ouverte.",
                        status.finalAnswers, true);
                } else {
                    startEngine(def);
                }
            }).catch(function () { startEngine(def); });
            return;
        }
        startEngine(def);
    }

    function startEngine(def) {
        openStage(def);
        var config = parseConfig(def);
        switch (def.mechanic) {
            case "MCQ_STANDARD": window.RccGameEngines.mcqStandard(def, config); break;
            case "MCQ_LADDER": window.RccGameEngines.mcqLadder(def, config); break;
            case "TRUE_FALSE_RAPID": window.RccGameEngines.trueFalseRapid(def, config); break;
            case "WHEEL": window.RccGameEngines.wheel(def, config); break;
            case "WORD_GUESS": window.RccGameEngines.wordGuess(def, config); break;
            case "MCQ_DUEL": window.RccGameEngines.mcqDuel(def, config); break;
            case "MEMORY": window.RccGameEngines.memory(def, config); break;
            case "HANGMAN": window.RccGameEngines.hangman(def, config); break;
            case "MCQ_SURVIVAL": window.RccGameEngines.mcqSurvival(def, config); break;
            case "PROCESS_ORDER": window.RccGameEngines.processOrder(def, config); break;
            default: stageBody().innerHTML = '<p class="text-danger">Moteur de jeu inconnu.</p>';
        }
    }

    $("gmStageCloseBtn").addEventListener("click", closeStage);

    // ===================== ADMINISTRATION (QA/ADMIN) =====================

    var ADMIN_TABS = ["Games", "Terms", "Questions", "Results", "Competitions"];

    function switchAdminTab(tab) {
        ADMIN_TABS.forEach(function (t) {
            $("gmAdminTab" + t + "Btn").classList.toggle("active", t === tab);
            $("gmAdmin" + t + "Pane").style.display = t === tab ? "" : "none";
        });
        if (tab === "Results") loadResultsGameSelect();
        if (tab === "Competitions") loadCompetitionsAdmin();
    }

    function wireAdmin() {
        var toggleBtn = $("gmAdminToggleBtn");
        var panel = $("gmAdminPanel");
        toggleBtn.addEventListener("click", function () {
            var showing = panel.style.display !== "none";
            panel.style.display = showing ? "none" : "";
            if (!showing) { loadAdminGames(); loadAdminTerms(); }
        });

        ADMIN_TABS.forEach(function (tab) {
            $("gmAdminTab" + tab + "Btn").addEventListener("click", function () { switchAdminTab(tab); });
        });

        $("gmResultsGameSelect").addEventListener("change", loadEvaluationResults);

        $("gmTermAddBtn").addEventListener("click", function () {
            var term = $("gmTermInput").value.trim();
            var def = $("gmTermDefInput").value.trim();
            if (!term || !def) { alert("Le terme et la définition sont obligatoires."); return; }
            sendJson("/api/word-terms", "POST", { term: term, definition: def, category: $("gmTermCatInput").value.trim() || null, active: true })
                .then(function () {
                    $("gmTermInput").value = ""; $("gmTermDefInput").value = ""; $("gmTermCatInput").value = "";
                    loadAdminTerms();
                }).catch(function (e) { alert("Erreur : " + e.message); });
        });
    }

    var GAME_TEAM_OPTIONS = [
        { code: "", label: "Toutes équipes" },
        { code: "INBOUND_VOICE", label: "Inbound Voix" },
        { code: "INBOUND_MAIL", label: "Inbound Mail / Rafiki" },
        { code: "CIB", label: "CIB" },
        { code: "OUTBOUND", label: "Outbound" }
    ];

    function loadAdminGames() {
        getJson("/api/games/admin").then(function (games) {
            var body = $("gmAdminGamesBody");
            body.innerHTML = games.map(function (g) {
                var isEvaluation = EVALUATION_MECHANICS.indexOf(g.mechanic) !== -1;
                var roundBtn = isEvaluation
                    ? '<button class="btn btn-sm btn-outline-warning gm-admin-new-round" title="Rouvre l\'évaluation à tout le monde, y compris ceux qui l\'ont déjà terminée"><i class="bi bi-arrow-clockwise"></i> Nouvelle session</button>'
                    : '<span class="text-muted small">—</span>';
                var teamSelect = '<select class="form-select form-select-sm gm-admin-team">' +
                    GAME_TEAM_OPTIONS.map(function (t) {
                        return '<option value="' + t.code + '"' + (g.targetTeam === t.code || (!g.targetTeam && !t.code) ? " selected" : "") + '>' + t.label + '</option>';
                    }).join("") + '</select>';
                return '<tr data-game-id="' + g.gameId + '">' +
                    '<td><i class="bi ' + g.icon + '"></i></td>' +
                    '<td><input type="text" class="form-control form-control-sm gm-admin-title" value="' + escapeHtml(g.title) + '"></td>' +
                    '<td><input type="text" class="form-control form-control-sm gm-admin-desc" value="' + escapeHtml(g.description || "") + '"></td>' +
                    '<td>' + teamSelect + '</td>' +
                    '<td><div class="form-check form-switch"><input class="form-check-input gm-admin-active" type="checkbox" ' + (g.active ? "checked" : "") + '></div></td>' +
                    '<td>' + roundBtn + '</td>' +
                    '<td><button class="btn btn-sm btn-outline-primary gm-admin-save">Enregistrer</button></td>' +
                    '</tr>';
            }).join("");
            Array.prototype.forEach.call(body.querySelectorAll(".gm-admin-new-round"), function (btn) {
                btn.addEventListener("click", function () {
                    var gameId = btn.closest("tr").getAttribute("data-game-id");
                    if (!confirm("Ouvrir une nouvelle session d'évaluation ? Tout le monde pourra la repasser, y compris ceux qui l'ont déjà terminée.")) return;
                    sendJson("/api/games/admin/" + gameId + "/new-evaluation-round", "POST", {})
                        .then(function () { alert("Nouvelle session ouverte."); })
                        .catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll(".gm-admin-save"), function (btn) {
                btn.addEventListener("click", function () {
                    var row = btn.closest("tr");
                    var gameId = row.getAttribute("data-game-id");
                    sendJson("/api/games/admin/" + gameId, "PUT", {
                        title: row.querySelector(".gm-admin-title").value.trim(),
                        description: row.querySelector(".gm-admin-desc").value.trim(),
                        active: row.querySelector(".gm-admin-active").checked,
                        targetTeam: row.querySelector(".gm-admin-team").value
                    }).then(function () { loadAdminGames(); loadGallery(); }).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        });
    }

    /** Peuple le sélecteur avec les jeux d'évaluation QCM uniquement — les 4 autres jeux
     *  (mémoire, pendu, JoliGo, puzzle) n'ont pas de notion de "terminé/débloqué". */
    function loadResultsGameSelect() {
        var select = $("gmResultsGameSelect");
        var evaluationGames = gamesCache.filter(function (g) { return EVALUATION_MECHANICS.indexOf(g.mechanic) !== -1; });
        var previousValue = select.value;
        select.innerHTML = evaluationGames.map(function (g) {
            return '<option value="' + g.gameKey + '">' + escapeHtml(g.title) + '</option>';
        }).join("");
        if (previousValue && evaluationGames.some(function (g) { return g.gameKey === previousValue; })) {
            select.value = previousValue;
        }
        loadEvaluationResults();
    }

    var evaluationResultsCache = [];
    var currentResultsTeamFilter = "";
    var TEAM_LABELS_GM = { INBOUND_VOICE: "Inbound Voix", INBOUND_MAIL: "Inbound Mail / Rafiki", CIB: "CIB", OUTBOUND: "Outbound", OTHER: "Non classée" };

    function loadEvaluationResults() {
        var gameKey = $("gmResultsGameSelect").value;
        var body = $("gmResultsBody");
        if (!gameKey) { body.innerHTML = '<tr><td colspan="7" class="text-center text-muted">Choisissez un jeu ci-dessus.</td></tr>'; $("gmResultsTeamTabs").innerHTML = ""; return; }
        body.innerHTML = '<tr><td colspan="7" class="text-center text-muted">Chargement…</td></tr>';

        getJson("/api/games/" + gameKey + "/evaluation-results").then(function (rows) {
            evaluationResultsCache = rows || [];
            currentResultsTeamFilter = "";
            renderResultsTeamTabs();
            renderEvaluationResults(gameKey);
        }).catch(function (e) {
            body.innerHTML = '<tr><td colspan="7" class="text-center text-danger">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
        });
    }

    function renderResultsTeamTabs() {
        var teamsPresent = {};
        evaluationResultsCache.forEach(function (r) { if (r.team) teamsPresent[r.team] = true; });
        var teams = Object.keys(TEAM_LABELS_GM).filter(function (t) { return t !== "OTHER" && teamsPresent[t]; });
        if (teamsPresent.OTHER) teams.push("OTHER");

        var buttons = [{ code: "", label: "Toutes" }].concat(teams.map(function (t) { return { code: t, label: TEAM_LABELS_GM[t] }; }))
            .map(function (t) {
                var active = currentResultsTeamFilter === t.code ? "btn-primary" : "btn-outline-secondary";
                return '<button type="button" class="btn btn-sm ' + active + ' gm-results-team-btn" data-team="' + t.code + '">' + t.label + '</button>';
            }).join(" ");
        $("gmResultsTeamTabs").innerHTML = '<span class="text-muted small"><i class="bi bi-people-fill"></i> Équipe :</span> ' + buttons;
        Array.prototype.forEach.call($("gmResultsTeamTabs").querySelectorAll(".gm-results-team-btn"), function (btn) {
            btn.addEventListener("click", function () {
                currentResultsTeamFilter = btn.getAttribute("data-team");
                renderResultsTeamTabs();
                renderEvaluationResults($("gmResultsGameSelect").value);
            });
        });
    }

    function renderEvaluationResults(gameKey) {
        var rows = currentResultsTeamFilter
            ? evaluationResultsCache.filter(function (r) { return r.team === currentResultsTeamFilter; })
            : evaluationResultsCache;
        var body = $("gmResultsBody");
        if (!rows.length) { body.innerHTML = '<tr><td colspan="7" class="text-center text-muted">' + (currentResultsTeamFilter ? "Personne dans cette équipe pour l'instant." : "Personne n'a encore commencé cette évaluation.") + '</td></tr>'; return; }
        body.innerHTML = rows.map(function (r) {
            var statusBadge = r.completed
                ? '<span class="badge bg-success">Terminé</span>'
                : '<span class="badge bg-warning text-dark">' + (r.attemptNumber === 1 ? "1re tentative en cours" : "Débloqué — pas encore rejoué") + '</span>';
            var when = r.lastAttemptAt ? new Date(r.lastAttemptAt).toLocaleString("fr-FR") : "—";
            var unlockBtn = r.completed
                ? '<button class="btn btn-sm btn-outline-warning" data-unlock-user="' + r.userId + '"><i class="bi bi-unlock"></i> Débloquer une reprise</button>'
                : "";
            return "<tr><td>" + escapeHtml(r.fullName) + "</td>" +
                "<td>" + escapeHtml(TEAM_LABELS_GM[r.team] || r.team || "—") + "</td>" +
                "<td>" + statusBadge + "</td>" +
                "<td>" + (r.score != null ? r.score + " / 100" : "—") + "</td>" +
                "<td>" + (r.correctCount != null ? r.correctCount + " / " + r.totalCount : "—") + "</td>" +
                "<td>" + when + "</td>" +
                "<td>" + unlockBtn + "</td></tr>";
        }).join("");

        Array.prototype.forEach.call(body.querySelectorAll("[data-unlock-user]"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Débloquer une nouvelle évaluation pour cet agent ? Il pourra repasser un cycle complet (2 tentatives) sans que cela affecte les autres agents.")) return;
                sendJson("/api/games/" + gameKey + "/unlock-user/" + btn.getAttribute("data-unlock-user"), "POST", {})
                    .then(loadEvaluationResults)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    function loadAdminTerms() {
        getJson("/api/word-terms").then(function (terms) {
            var body = $("gmTermsBody");
            if (!terms.length) { body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Aucun terme.</td></tr>'; return; }
            body.innerHTML = terms.map(function (t) {
                var statusBadge = t.active ? '<span class="badge bg-success">Actif</span>' : '<span class="badge bg-secondary">Inactif</span>';
                return '<tr><td>' + escapeHtml(t.term) + '</td><td>' + escapeHtml(t.definition) + '</td><td>' + escapeHtml(t.category || "—") +
                    '</td><td>' + statusBadge + '</td><td><button class="btn btn-sm btn-outline-danger" data-del-term="' + t.termId + '"><i class="bi bi-trash"></i></button></td></tr>';
            }).join("");
            Array.prototype.forEach.call(body.querySelectorAll("[data-del-term]"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Supprimer ce terme ?")) return;
                    fetch("/api/word-terms/" + btn.getAttribute("data-del-term"), { method: "DELETE", credentials: "same-origin" })
                        .then(function () { loadAdminTerms(); });
                });
            });
        });
    }

    // ===================== COMPÉTITIONS =====================

    var compParticipantsModal = null;
    var compCurrentId = null;
    var compParticipantsModeIsTrainee = false;

    function populateCompGameSelect() {
        var select = $("compGameSelect");
        var evaluationGames = gamesCache.filter(function (g) { return EVALUATION_MECHANICS.indexOf(g.mechanic) !== -1; });
        select.innerHTML = evaluationGames.map(function (g) {
            return '<option value="' + g.gameKey + '">' + escapeHtml(g.title) + '</option>';
        }).join("");
    }

    function wireCompetitionsAdmin() {
        $("compTraineeOnly").addEventListener("change", function () {
            $("compTeamsPicker").style.display = this.checked ? "none" : "";
        });
        $("compCreateBtn").addEventListener("click", function () {
            var teams = Array.prototype.filter.call(document.querySelectorAll(".comp-team-check"), function (c) { return c.checked; })
                .map(function (c) { return c.value; });
            var isTrainee = $("compTraineeOnly").checked;
            if (!$("compTitle").value.trim()) { alert("Le titre est obligatoire."); return; }
            if (!$("compScheduledAt").value) { alert("La date programmée est obligatoire."); return; }
            if (!isTrainee && teams.length < 2) { alert("Choisissez au moins deux équipes, ou cochez « stagiaires uniquement »."); return; }
            sendJson("/api/competitions", "POST", {
                gameKey: $("compGameSelect").value,
                title: $("compTitle").value.trim(),
                scheduledAt: $("compScheduledAt").value,
                isTraineeOnly: isTrainee,
                teams: teams
            }).then(function () {
                $("compTitle").value = ""; $("compScheduledAt").value = ""; $("compTraineeOnly").checked = false;
                $("compTeamsPicker").style.display = "";
                Array.prototype.forEach.call(document.querySelectorAll(".comp-team-check"), function (c) { c.checked = false; });
                loadCompetitionsAdmin();
            }).catch(function (e) { alert("Erreur : " + e.message); });
        });
        $("compValidateParticipantsBtn").addEventListener("click", submitParticipantsSelection);
    }

    function competitionStatusBadge(status) {
        return {
            DRAFT: '<span class="badge bg-secondary">Brouillon</span>',
            PENDING_VALIDATION: '<span class="badge bg-warning text-dark">En attente de validation</span>',
            SCHEDULED: '<span class="badge bg-info text-dark">Programmée</span>',
            ACTIVE: '<span class="badge bg-primary">En cours</span>',
            COMPLETED: '<span class="badge bg-success">Terminée</span>'
        }[status] || status;
    }

    function loadCompetitionsAdmin() {
        populateCompGameSelect();
        getJson("/api/competitions").then(function (comps) {
            var container = $("compList");
            if (!comps.length) { container.innerHTML = '<p class="text-muted text-center">Aucune compétition pour l\'instant.</p>'; return; }
            container.innerHTML = comps.map(function (c) {
                var teamsHtml = c.teams.map(function (t) {
                    var participantsHtml = t.participants.map(function (p) {
                        return '<li>' + escapeHtml(p.fullName) + (p.completed ? ' — <strong>' + p.score + ' / 100</strong>' : ' <span class="text-muted">(pas encore joué)</span>') + '</li>';
                    }).join("");
                    var validatedBadge = t.validated ? '<span class="badge bg-success ms-1">Validée</span>' : '<span class="badge bg-warning text-dark ms-1">En attente</span>';
                    var traineeAddBtn = (c.isTraineeOnly && t.team === "STAGIAIRES")
                        ? '<button class="btn btn-sm btn-outline-primary ms-2 comp-add-trainees" data-comp-id="' + c.competitionId + '">+ Ajouter des stagiaires</button>' : "";
                    return '<div class="mb-2"><strong>' + escapeHtml(t.teamLabel) + '</strong>' + validatedBadge + traineeAddBtn +
                        '<ul class="small mb-0">' + (participantsHtml || '<li class="text-muted">Aucun participant retenu.</li>') + '</ul></div>';
                }).join("");
                return '<div class="border rounded p-3 mb-3">' +
                    '<div class="d-flex justify-content-between align-items-start">' +
                        '<div><strong>' + escapeHtml(c.title) + '</strong> ' + competitionStatusBadge(c.status) +
                            '<div class="small text-muted">' + escapeHtml(c.gameTitle) + ' — programmée le ' + new Date(c.scheduledAt).toLocaleString("fr-FR") + '</div></div>' +
                    '</div><hr class="my-2">' + teamsHtml + '</div>';
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll(".comp-add-trainees"), function (btn) {
                btn.addEventListener("click", function () { openParticipantsPicker(Number(btn.getAttribute("data-comp-id")), true); });
            });
        }).catch(function (e) {
            $("compList").innerHTML = '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    /** Réutilisée par le formateur (stagiaires) et par team-leader.js (équipe) — voir teamLeaderStyle param. */
    function openParticipantsPicker(competitionId, isTraineeMode) {
        compCurrentId = competitionId;
        compParticipantsModeIsTrainee = isTraineeMode;
        $("compParticipantsTitle").innerHTML = '<i class="bi bi-people-fill"></i> ' + (isTraineeMode ? "Choisir les stagiaires" : "Choisir les membres de mon équipe");
        $("compParticipantsList").innerHTML = '<p class="text-muted small">Chargement…</p>';

        var directoryUrl = isTraineeMode ? "/api/users/directory" : "/api/users/directory";
        getJson(directoryUrl).then(function (users) {
            $("compParticipantsList").innerHTML = users.map(function (u) {
                return '<div class="form-check"><input class="form-check-input comp-participant-check" type="checkbox" value="' + u.id + '" id="compUser' + u.id + '">' +
                    '<label class="form-check-label" for="compUser' + u.id + '">' + escapeHtml(u.fullName || u.username) + ' <span class="text-muted small">(' + escapeHtml(u.activity || "—") + ')</span></label></div>';
            }).join("");
        }).catch(function () {
            $("compParticipantsList").innerHTML = '<p class="text-danger small">Erreur de chargement des agents.</p>';
        });
        compParticipantsModal.show();
    }

    function submitParticipantsSelection() {
        var userIds = Array.prototype.filter.call(document.querySelectorAll(".comp-participant-check"), function (c) { return c.checked; })
            .map(function (c) { return Number(c.value); });
        if (!userIds.length) { alert("Choisissez au moins un participant."); return; }
        var endpoint = compParticipantsModeIsTrainee
            ? "/api/competitions/" + compCurrentId + "/trainee-participants"
            : "/api/competitions/" + compCurrentId + "/validate-members";
        sendJson(endpoint, "POST", { userIds: userIds }).then(function () {
            compParticipantsModal.hide();
            if (typeof loadCompetitionsAdmin === "function" && $("gmAdminCompetitionsPane").style.display !== "none") loadCompetitionsAdmin();
            if (typeof window.reloadTeamLeaderCompetitions === "function") window.reloadTeamLeaderCompetitions();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    // ===================== MES COMPÉTITIONS (agent) =====================

    function loadMyCompetitions() {
        getJson("/api/competitions/mine").then(function (comps) {
            var container = $("gmMyCompetitions");
            if (!comps.length) { container.style.display = "none"; return; }
            container.style.display = "";
            container.innerHTML = comps.map(function (c) {
                var myScore = null;
                c.teams.forEach(function (t) { t.participants.forEach(function (p) { /* score visible via classement ci-dessous */ }); });
                var leaderboard = c.teams.map(function (t) {
                    return '<div class="mb-1"><strong>' + escapeHtml(t.teamLabel) + '</strong> : ' +
                        t.participants.map(function (p) { return escapeHtml(p.fullName) + (p.completed ? ' (' + p.score + ')' : ''); }).join(", ") + '</div>';
                }).join("");
                return '<div class="border rounded p-3 mb-2" style="border-left:5px solid #F5A623 !important;">' +
                    '<div class="d-flex justify-content-between align-items-center flex-wrap gap-2">' +
                        '<div><strong><i class="bi bi-trophy-fill text-warning"></i> ' + escapeHtml(c.title) + '</strong>' +
                            '<div class="small text-muted">' + escapeHtml(c.gameTitle) + '</div></div>' +
                        '<button class="btn btn-sm btn-warning comp-start-btn" data-game-key="' + c.gameKey + '" data-comp-id="' + c.competitionId + '"><i class="bi bi-play-fill"></i> Commencer</button>' +
                    '</div>' +
                    '<div class="small mt-2">' + leaderboard + '</div>' +
                '</div>';
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll(".comp-start-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    var def = gamesCache.find(function (g) { return g.gameKey === btn.getAttribute("data-game-key"); });
                    if (!def) { alert("Ce jeu n'est plus disponible."); return; }
                    activeCompetitionId = Number(btn.getAttribute("data-comp-id"));
                    launchGame(def);
                });
            });
        }).catch(function () {});
    }

    // ===================== INIT =====================

    function init() {
        compParticipantsModal = new bootstrap.Modal($("compParticipantsModal"));
        wireCompetitionsAdmin();
        loadGallery();
        loadMyCompetitions();
        wireAdmin();
        if (window.RccSession) {
            window.RccSession.init().then(function (session) {
                if (session) {
                    currentProfile = session.profile;
                    if (currentProfile === "QA" || currentProfile === "ADMIN") {
                        $("gmAdminToggleBtn").style.display = "";
                    }
                }
            });
        }
    }

    document.addEventListener("DOMContentLoaded", init);

    // Exposé pour les moteurs de jeu (games-engines.js)
    return {
        $: $, escapeHtml: escapeHtml, drawQuestions: drawQuestions, submitAnswer: submitAnswer,
        drawTerms: drawTerms, playCorrect: playCorrect, playWrong: playWrong, playClick: playClick,
        playWin: playWin, playTick: playTick, confettiBurst: confettiBurst, stageBody: stageBody,
        setStageScore: setStageScore, resultScreen: resultScreen, closeStage: closeStage,
        getJson: getJson, sendJson: sendJson
    };
})();
