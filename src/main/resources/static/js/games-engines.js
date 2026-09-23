"use strict";

window.RccGameEngines = (function () {
    var G = window.RccGames;

    var REAL_CATEGORIES = ["COMPTE", "TRANSFERT", "CARTE ATM", "MMH", "CASHXPRESS", "DEMANDE DE PRET",
        "CHEQUE", "CONNEXION PRODUITS DIGITAUX", "ASSURANCE", "ATTESTATION BANCAIRE", "AUTRE"];

    function progressBar(current, total) {
        var pct = Math.round((current / total) * 100);
        return '<div class="gm-progress-track"><div class="gm-progress-fill" style="width:' + pct + '%"></div></div>';
    }

    function renderMcqOptions(options, onPick) {
        return options.map(function (opt, i) {
            return '<button type="button" class="gm-option-btn" data-idx="' + i + '">' + G.escapeHtml(opt) + '</button>';
        }).join("");
    }

    function wireMcqOptions(container, onPick) {
        Array.prototype.forEach.call(container.querySelectorAll(".gm-option-btn"), function (btn) {
            btn.addEventListener("click", function () { onPick(parseInt(btn.getAttribute("data-idx"), 10), btn); });
        });
    }

    function lockOptions(container) {
        Array.prototype.forEach.call(container.querySelectorAll(".gm-option-btn"), function (b) { b.disabled = true; });
    }

    // ===================== 1) QUIZ ÉCLAIR — MCQ_STANDARD =====================

    function mcqStandard(def, config) {
        var count = config.questionCount || 8;
        G.stageBody().innerHTML = '<p class="text-center text-muted">Préparation du quiz…</p>';
        G.drawQuestions(config.category, config.difficulty, "MCQ", count).then(function (questions) {
            if (!questions.length) { G.stageBody().innerHTML = '<p class="text-danger text-center">Pas assez de questions disponibles.</p>'; return; }
            runMcqSequence(def.gameKey, questions, { pointsPerCorrect: null, showProgress: true, timePerQuestion: null });
        });
    }

    /** Cœur réutilisé par Quiz Éclair, Duel Chrono et Chrono Challenge. */
    function runMcqSequence(gameKey, questions, opts) {
        var index = 0, score = 0, correct = 0;
        var answers = [];
        var body = G.stageBody();
        G.setStageScore(0);

        function renderQuestion() {
            if (index >= questions.length) { return finish(); }
            var q = questions[index];
            body.innerHTML =
                (opts.showProgress ? progressBar(index, questions.length) : "") +
                '<div class="d-flex justify-content-between align-items-center mb-2">' +
                    '<span class="badge bg-primary">Question ' + (index + 1) + ' / ' + questions.length + '</span>' +
                    '<span class="badge bg-secondary">' + (q.points || 10) + ' pts</span>' +
                '</div>' +
                '<h5 class="mb-4">' + G.escapeHtml(q.questionText) + '</h5>' +
                '<div id="gmMcqOptions">' + renderMcqOptions(q.options, null) + '</div>';
            wireMcqOptions(body, function (idx, btn) { onPick(q, idx, btn); });
        }

        function onPick(q, idx, btn) {
            lockOptions(body);
            G.submitAnswer(q.questionId, idx).then(function (result) {
                answers.push({ questionText: q.questionText, options: q.options, selectedIndex: idx, correctIndex: result.correctOptionIndex, correct: result.correct });
                if (result.correct) { correct++; score += (opts.pointsPerCorrect || result.pointsEarned || 10); G.playCorrect(); }
                else { G.playWrong(); }
                G.setStageScore(score);
                setTimeout(function () { index++; renderQuestion(); }, 500);
            });
        }

        function finish() {
            G.resultScreen(gameKey, score, correct, questions.length, undefined, answers);
        }

        renderQuestion();
    }

    // ===================== 2) QUI VEUT GAGNER DES MILLIONS ECOBANK — MCQ_LADDER =====================

    var LADDER_POINTS = [500, 1000, 2000, 5000, 10000, 20000, 40000, 80000, 150000, 300000];
    var LADDER_MILESTONES = [4, 9]; // index (0-based) — après ces questions, gains sécurisés

    function mcqLadder(def, config) {
        var count = LADDER_POINTS.length;
        G.stageBody().innerHTML = '<p class="text-center text-muted">Préparation de l\'échelle…</p>';

        var bands = ["EASY", "EASY", "MEDIUM", "MEDIUM", "MEDIUM", "HARD", "HARD", "HARD", "EXPERT", "EXPERT"];
        var promises = bands.map(function (diff) { return G.drawQuestions(null, diff, "MCQ", 1); });
        Promise.all(promises).then(function (results) {
            var questions = [];
            results.forEach(function (r) { if (r && r.length) questions.push(r[0]); });
            if (questions.length < 3) {
                G.drawQuestions(null, null, "MCQ", count).then(function (fallback) { startLadder(def.gameKey, fallback); });
                return;
            }
            startLadder(def.gameKey, questions);
        });
    }

    function startLadder(gameKey, questions) {
        var index = 0, secured = 0, fiftyUsed = false, skipUsed = false;
        var answers = [];
        var body = G.stageBody();
        G.setStageScore(0);

        function renderQuestion() {
            if (index >= questions.length) { return G.resultScreen(gameKey, LADDER_POINTS[questions.length - 1], questions.length, questions.length, "Vous avez décroché le jackpot ! 🎉", answers); }
            var q = questions[index];
            var canStop = LADDER_MILESTONES.indexOf(index) !== -1 || index > 0;
            body.innerHTML =
                '<div class="text-center mb-3"><span class="badge bg-warning text-dark fs-6">Palier ' + (index + 1) + ' / ' + questions.length + ' — ' + LADDER_POINTS[index].toLocaleString("fr-FR") + ' pts</span></div>' +
                '<h5 class="mb-4 text-center">' + G.escapeHtml(q.questionText) + '</h5>' +
                '<div id="gmMcqOptions">' + renderMcqOptions(q.options, null) + '</div>' +
                '<div class="d-flex justify-content-center gap-2 mt-4">' +
                    '<button class="btn btn-sm btn-outline-primary" id="gmLifeline5050" ' + (fiftyUsed ? "disabled" : "") + '><i class="bi bi-scissors"></i> 50/50</button>' +
                    '<button class="btn btn-sm btn-outline-secondary" id="gmLifelineSkip" ' + (skipUsed ? "disabled" : "") + '><i class="bi bi-skip-forward"></i> Passer la question</button>' +
                    (index > 0 ? '<button class="btn btn-sm btn-outline-success" id="gmLadderStop"><i class="bi bi-piggy-bank"></i> Sécuriser ' + secured.toLocaleString("fr-FR") + ' pts</button>' : "") +
                '</div>';
            wireMcqOptions(body, function (idx, btn) { onPick(q, idx, btn); });

            $id("gmLifeline5050").addEventListener("click", function () {
                fiftyUsed = true;
                this.disabled = true;
                G.getJson("/api/quiz-questions/" + q.questionId + "/eliminate").then(function (indexes) {
                    indexes.forEach(function (i) {
                        var btn = body.querySelectorAll(".gm-option-btn")[i];
                        if (btn) { btn.disabled = true; btn.style.opacity = "0.25"; }
                    });
                });
            });
            $id("gmLifelineSkip").addEventListener("click", function () {
                skipUsed = true;
                G.drawQuestions(null, null, "MCQ", 1).then(function (r) {
                    if (r && r.length) questions[index] = r[0];
                    renderQuestion();
                });
            });
            var stopBtn = $id("gmLadderStop");
            if (stopBtn) stopBtn.addEventListener("click", function () {
                G.resultScreen(gameKey, secured, index, questions.length, "Gains sécurisés — bien joué !", answers);
            });
        }

        function $id(id) { return body.querySelector("#" + id); }

        function onPick(q, idx, btn) {
            lockOptions(body);
            G.submitAnswer(q.questionId, idx).then(function (result) {
                answers.push({ questionText: q.questionText, options: q.options, selectedIndex: idx, correctIndex: result.correctOptionIndex, correct: result.correct });
                if (result.correct) {
                    secured = LADDER_POINTS[index];
                    G.setStageScore(secured);
                    G.playCorrect();
                } else {
                    G.playWrong();
                }
                setTimeout(function () {
                    if (!result.correct) { G.resultScreen(gameKey, secured, index, questions.length, "Vous repartez avec vos gains sécurisés.", answers); return; }
                    index++; renderQuestion();
                }, 500);
            });
        }

        renderQuestion();
    }

    // ===================== 3) VRAI OU FAUX CHRONO — TRUE_FALSE_RAPID =====================

    function trueFalseRapid(def, config) {
        var timeLimit = config.timeLimitSeconds || 30;
        G.stageBody().innerHTML = '<p class="text-center text-muted">Préparation…</p>';
        G.drawQuestions(null, null, "TRUE_FALSE", 30).then(function (questions) {
            if (!questions.length) { G.stageBody().innerHTML = '<p class="text-danger text-center">Pas de question Vrai/Faux disponible pour l\'instant.</p>'; return; }
            startTrueFalse(def.gameKey, questions, timeLimit);
        });
    }

    function startTrueFalse(gameKey, questions, timeLimit) {
        var body = G.stageBody();
        var qi = 0, score = 0, correct = 0, combo = 0, remaining = timeLimit;
        var answers = [];
        G.setStageScore(0);

        var timer = setInterval(function () {
            remaining--;
            var timerEl = body.querySelector("#gmTfTimer");
            if (timerEl) timerEl.textContent = remaining + "s";
            if (remaining <= 5 && remaining > 0) G.playTick();
            if (remaining <= 0) { clearInterval(timer); finish(); }
        }, 1000);

        function nextQuestion() {
            var q = questions[qi % questions.length];
            qi++;
            body.innerHTML =
                '<div class="d-flex justify-content-between mb-3">' +
                    '<span class="badge bg-danger fs-6" id="gmTfTimer">' + remaining + 's</span>' +
                    '<span class="badge bg-warning text-dark fs-6">Combo x' + comboMultiplier().toFixed(1) + '</span>' +
                '</div>' +
                '<h4 class="text-center mb-4">' + G.escapeHtml(q.questionText) + '</h4>' +
                '<div class="d-flex gap-3 justify-content-center">' +
                    '<button class="btn btn-success btn-lg px-5" id="gmTfTrue">VRAI</button>' +
                    '<button class="btn btn-danger btn-lg px-5" id="gmTfFalse">FAUX</button>' +
                '</div>';
            body.querySelector("#gmTfTrue").addEventListener("click", function () { answer(q, 0); });
            body.querySelector("#gmTfFalse").addEventListener("click", function () { answer(q, 1); });
        }

        function comboMultiplier() { return 1 + Math.min(combo, 5) * 0.2; }

        function answer(q, idx) {
            G.submitAnswer(q.questionId, idx).then(function (result) {
                answers.push({ questionText: q.questionText, options: ["Vrai", "Faux"], selectedIndex: idx, correctIndex: result.correctOptionIndex, correct: result.correct });
                if (result.correct) {
                    combo++; correct++;
                    score += Math.round((result.pointsEarned || 10) * comboMultiplier());
                    G.playCorrect();
                } else {
                    combo = 0;
                    G.playWrong();
                }
                G.setStageScore(score);
                if (remaining > 0) nextQuestion();
            });
        }

        function finish() { G.resultScreen(gameKey, score, correct, qi, undefined, answers); }

        nextQuestion();
    }

    // ===================== 4) LA ROUE DE LA FORTUNE ECOBANK — WHEEL =====================

    var WHEEL_SEGMENTS = [
        { cat: "COMPTE", mult: 1 }, { cat: "TRANSFERT", mult: 2 }, { cat: "CARTE ATM", mult: 1 },
        { cat: "CASHXPRESS", mult: 3 }, { cat: "MMH", mult: 1 }, { cat: "CONNEXION PRODUITS DIGITAUX", mult: 2 },
        { cat: "CHEQUE", mult: 1 }, { cat: "AUTRE", mult: 2 }
    ];
    var WHEEL_COLORS = ["#0057B8", "#00A651", "#F5A623", "#F72585", "#7B2FF7", "#0057B8", "#00A651", "#F5A623"];

    function buildWheelSvg() {
        var n = WHEEL_SEGMENTS.length, r = 140, cx = 140, cy = 140;
        var paths = "";
        for (var i = 0; i < n; i++) {
            var a0 = (i / n) * 2 * Math.PI - Math.PI / 2, a1 = ((i + 1) / n) * 2 * Math.PI - Math.PI / 2;
            var x0 = cx + r * Math.cos(a0), y0 = cy + r * Math.sin(a0);
            var x1 = cx + r * Math.cos(a1), y1 = cy + r * Math.sin(a1);
            paths += '<path d="M' + cx + ',' + cy + ' L' + x0 + ',' + y0 + ' A' + r + ',' + r + ' 0 0,1 ' + x1 + ',' + y1 + ' Z" fill="' + WHEEL_COLORS[i] + '"></path>';
            var mid = (a0 + a1) / 2, tx = cx + (r * 0.62) * Math.cos(mid), ty = cy + (r * 0.62) * Math.sin(mid);
            paths += '<text x="' + tx + '" y="' + ty + '" fill="#fff" font-size="10" font-weight="700" text-anchor="middle" transform="rotate(' + (mid * 180 / Math.PI + 90) + ' ' + tx + ' ' + ty + ')">' + WHEEL_SEGMENTS[i].cat.slice(0, 12) + '</text>';
        }
        return '<svg viewBox="0 0 280 280" width="280" height="280">' + paths + '<circle cx="140" cy="140" r="16" fill="#fff"></circle></svg>';
    }

    function wheel(def, config) {
        var body = G.stageBody();
        var round = 0, maxRounds = 5, score = 0, correct = 0;
        var answers = [];
        G.setStageScore(0);

        function renderWheel() {
            if (round >= maxRounds) return G.resultScreen(def.gameKey, score, correct, maxRounds, undefined, answers);
            body.innerHTML =
                '<div class="gm-wheel-wrap">' +
                    '<div class="text-muted">Tour ' + (round + 1) + ' / ' + maxRounds + '</div>' +
                    '<div class="gm-wheel-pointer">▼</div>' +
                    '<div class="gm-wheel" id="gmWheelSvg">' + buildWheelSvg() + '</div>' +
                    '<button class="btn btn-primary btn-lg" id="gmSpinBtn"><i class="bi bi-arrow-repeat"></i> Lancer la roue</button>' +
                '</div>';
            body.querySelector("#gmSpinBtn").addEventListener("click", spin);
        }

        function spin() {
            body.querySelector("#gmSpinBtn").disabled = true;
            var segIndex = Math.floor(Math.random() * WHEEL_SEGMENTS.length);
            var segAngle = 360 / WHEEL_SEGMENTS.length;
            var targetAngle = 360 * 5 + (360 - (segIndex * segAngle + segAngle / 2));
            var wheelEl = body.querySelector("#gmWheelSvg");
            wheelEl.style.transform = "rotate(" + targetAngle + "deg)";
            G.playTick();
            setTimeout(function () { askQuestion(WHEEL_SEGMENTS[segIndex]); }, 3600);
        }

        function askQuestion(segment) {
            G.drawQuestions(segment.cat, null, "MCQ", 1).then(function (qs) {
                if (!qs.length) return G.drawQuestions(null, null, "MCQ", 1).then(function (fb) { showQuestion(fb[0], segment); });
                showQuestion(qs[0], segment);
            });
        }

        function showQuestion(q, segment) {
            body.innerHTML =
                '<div class="text-center mb-3"><span class="badge" style="background:' + WHEEL_COLORS[WHEEL_SEGMENTS.indexOf(segment)] + '">' + G.escapeHtml(segment.cat) + ' — Multiplicateur x' + segment.mult + '</span></div>' +
                '<h5 class="mb-4 text-center">' + G.escapeHtml(q.questionText) + '</h5>' +
                '<div id="gmMcqOptions">' + renderMcqOptions(q.options) + '</div>';
            wireMcqOptions(body, function (idx, btn) {
                lockOptions(body);
                G.submitAnswer(q.questionId, idx).then(function (result) {
                    answers.push({ questionText: q.questionText, options: q.options, selectedIndex: idx, correctIndex: result.correctOptionIndex, correct: result.correct });
                    if (result.correct) { correct++; score += (result.pointsEarned || 10) * segment.mult; G.playCorrect(); }
                    else G.playWrong();
                    G.setStageScore(score);
                    round++;
                    setTimeout(renderWheel, 500);
                });
            });
        }

        renderWheel();
    }

    // ===================== 5) JOLIGO ECOBANK — WORD_GUESS (façon Motus) =====================

    function wordGuess(def, config) {
        var maxAttempts = config.maxAttempts || 6;
        G.drawTerms(1).then(function (terms) {
            if (!terms.length) { G.stageBody().innerHTML = '<p class="text-danger text-center">Aucun terme disponible.</p>'; return; }
            startWordGuess(def.gameKey, terms[0], maxAttempts);
        });
    }

    function startWordGuess(gameKey, termObj, maxAttempts) {
        var body = G.stageBody();
        var word = termObj.term.toUpperCase().replace(/\s/g, "");
        var attempts = 0;
        var revealed = new Array(word.length).fill(false);
        revealed[0] = true;
        G.setStageScore(0);

        function render() {
            body.innerHTML =
                '<p class="text-center text-muted">Définition : <strong>' + G.escapeHtml(termObj.definition) + '</strong></p>' +
                '<div class="gm-joligo-letters">' + word.split("").map(function (c, i) {
                    return '<div class="gm-joligo-letter' + (revealed[i] ? "" : " gm-empty") + '">' + (revealed[i] ? c : "?") + '</div>';
                }).join("") + '</div>' +
                '<p class="text-center text-muted">Essai ' + (attempts + 1) + ' / ' + maxAttempts + ' — ' + word.length + ' lettres</p>' +
                '<form id="gmJoligoForm" class="d-flex gap-2 justify-content-center">' +
                    '<input type="text" class="form-control" id="gmJoligoInput" maxlength="' + word.length + '" style="max-width:260px;text-transform:uppercase;" autocomplete="off">' +
                    '<button class="btn btn-primary" type="submit">Valider</button>' +
                '</form>' +
                '<div id="gmJoligoHistory" class="mt-3 d-flex flex-column gap-2 align-items-center"></div>';
            body.querySelector("#gmJoligoForm").addEventListener("submit", function (e) {
                e.preventDefault();
                var guess = body.querySelector("#gmJoligoInput").value.trim().toUpperCase();
                if (guess.length !== word.length) { alert("Le mot doit contenir " + word.length + " lettres."); return; }
                checkGuess(guess);
            });
        }

        function checkGuess(guess) {
            attempts++;
            var history = body.querySelector("#gmJoligoHistory");
            var rowHtml = '<div class="gm-joligo-letters" style="margin:4px 0;">' + guess.split("").map(function (c, i) {
                var cls = c === word[i] ? "gm-correct" : (word.indexOf(c) !== -1 ? "" : "");
                var bg = c === word[i] ? "background:#00A651;color:#fff;border-color:#00A651;" : (word.indexOf(c) !== -1 ? "background:#F5A623;color:#fff;border-color:#F5A623;" : "background:#eee;color:#999;");
                return '<div class="gm-joligo-letter" style="width:32px;height:38px;font-size:1rem;' + bg + '">' + c + '</div>';
            }).join("") + '</div>';
            history.insertAdjacentHTML("afterbegin", rowHtml);

            if (guess === word) {
                for (var i = 0; i < revealed.length; i++) revealed[i] = true;
                render(); history.outerHTML && (body.querySelector("#gmJoligoHistory") ? null : null);
                var score = Math.max(10, (maxAttempts - attempts + 1) * 15);
                setTimeout(function () { G.resultScreen(gameKey, score, 1, 1, "Trouvé en " + attempts + " essai(s) — le mot était " + word + "."); }, 900);
                return;
            }
            guess.split("").forEach(function (c, i) { if (c === word[i]) revealed[i] = true; });
            if (attempts >= maxAttempts) {
                setTimeout(function () { G.resultScreen(gameKey, 0, 0, 1, "Dommage — le mot était " + word + "."); }, 900);
                return;
            }
            render();
        }

        render();
    }

    // ===================== 6) DUEL CHRONO — MCQ_DUEL =====================

    function mcqDuel(def, config) {
        var timeLimit = config.timeLimitSeconds || 60;
        var par = 8; // objectif de bonnes réponses à battre
        G.stageBody().innerHTML = '<p class="text-center text-muted">Préparation du duel…</p>';
        G.drawQuestions(null, null, "MCQ", 20).then(function (questions) {
            if (!questions.length) { G.stageBody().innerHTML = '<p class="text-danger text-center">Pas assez de questions.</p>'; return; }
            startDuel(def.gameKey, questions, timeLimit, par);
        });
    }

    function startDuel(gameKey, questions, timeLimit, par) {
        var body = G.stageBody();
        var qi = 0, score = 0, correct = 0, remaining = timeLimit;
        var answers = [];
        G.setStageScore(0);

        var timer = setInterval(function () {
            remaining--;
            var el = body.querySelector("#gmDuelTimer");
            if (el) el.textContent = remaining + "s";
            if (remaining <= 0) { clearInterval(timer); finish(); }
        }, 1000);

        function nextQuestion() {
            if (qi >= questions.length) { clearInterval(timer); return finish(); }
            var q = questions[qi];
            body.innerHTML =
                '<div class="d-flex justify-content-between mb-3">' +
                    '<span class="badge bg-danger fs-6" id="gmDuelTimer">' + remaining + 's</span>' +
                    '<span class="badge bg-info text-dark fs-6">Objectif à battre : ' + par + ' bonnes réponses</span>' +
                '</div>' +
                '<h5 class="mb-4">' + G.escapeHtml(q.questionText) + '</h5>' +
                '<div id="gmMcqOptions">' + renderMcqOptions(q.options) + '</div>';
            wireMcqOptions(body, function (idx, btn) {
                lockOptions(body);
                G.submitAnswer(q.questionId, idx).then(function (result) {
                    answers.push({ questionText: q.questionText, options: q.options, selectedIndex: idx, correctIndex: result.correctOptionIndex, correct: result.correct });
                    if (result.correct) { correct++; score += (result.pointsEarned || 10); G.playCorrect(); } else G.playWrong();
                    G.setStageScore(score);
                    qi++;
                    setTimeout(nextQuestion, 300);
                });
            });
        }

        function finish() {
            var won = correct >= par;
            G.resultScreen(gameKey, score, correct, qi, won ? "Objectif battu — bravo ! 🎉" : "Objectif non atteint (" + correct + "/" + par + ") — retentez votre chance !", answers);
        }

        nextQuestion();
    }

    // ===================== 7) MÉMOIRE ECOBANK — MEMORY =====================

    function memory(def, config) {
        var pairCount = config.pairCount || 6;
        G.drawTerms(pairCount).then(function (terms) {
            if (terms.length < 2) { G.stageBody().innerHTML = '<p class="text-danger text-center">Pas assez de termes disponibles.</p>'; return; }
            startMemory(def.gameKey, terms);
        });
    }

    function startMemory(gameKey, terms) {
        var body = G.stageBody();
        var cards = [];
        terms.forEach(function (t) {
            cards.push({ pairId: t.termId, label: t.term, type: "term" });
            cards.push({ pairId: t.termId, label: t.definition.length > 60 ? t.definition.slice(0, 57) + "…" : t.definition, type: "def" });
        });
        for (var i = cards.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var tmp = cards[i]; cards[i] = cards[j]; cards[j] = tmp; }

        var flipped = [], matchedCount = 0, moves = 0;
        G.setStageScore(0);

        function render() {
            body.innerHTML =
                '<div class="d-flex justify-content-between mb-3"><span class="text-muted">Coups : ' + moves + '</span><span class="text-muted">Paires trouvées : ' + matchedCount + ' / ' + terms.length + '</span></div>' +
                '<div class="gm-memory-grid">' + cards.map(function (c, i) {
                    var stateClass = c.matched ? "gm-matched" : (flipped.indexOf(i) !== -1 ? "gm-flipped" : "");
                    var content = (c.matched || flipped.indexOf(i) !== -1) ? c.label : "?";
                    return '<div class="gm-memory-card ' + stateClass + '" data-idx="' + i + '">' + G.escapeHtml(content) + '</div>';
                }).join("") + '</div>';
            Array.prototype.forEach.call(body.querySelectorAll(".gm-memory-card"), function (el) {
                el.addEventListener("click", function () { onFlip(parseInt(el.getAttribute("data-idx"), 10)); });
            });
        }

        function onFlip(idx) {
            if (cards[idx].matched || flipped.indexOf(idx) !== -1 || flipped.length >= 2) return;
            flipped.push(idx);
            G.playClick();
            render();
            if (flipped.length === 2) {
                moves++;
                var a = cards[flipped[0]], b = cards[flipped[1]];
                if (a.pairId === b.pairId) {
                    a.matched = true; b.matched = true; matchedCount++;
                    G.playCorrect();
                    flipped = [];
                    render();
                    if (matchedCount === terms.length) {
                        var score = Math.max(20, 200 - moves * 8);
                        setTimeout(function () { G.resultScreen(gameKey, score, matchedCount, terms.length, moves + " coup(s) joués."); }, 600);
                    }
                } else {
                    setTimeout(function () { flipped = []; G.playWrong(); render(); }, 900);
                }
            }
        }

        render();
    }

    // ===================== 8) LE PENDU BANCAIRE — HANGMAN =====================

    var HANGMAN_STAGES = ["🙂", "😐", "😟", "😨", "😰", "😵", "💀"];

    function hangman(def, config) {
        var maxWrong = config.maxWrong || 6;
        G.drawTerms(1).then(function (terms) {
            if (!terms.length) { G.stageBody().innerHTML = '<p class="text-danger text-center">Aucun terme disponible.</p>'; return; }
            startHangman(def.gameKey, terms[0], maxWrong);
        });
    }

    function startHangman(gameKey, termObj, maxWrong) {
        var body = G.stageBody();
        var word = termObj.term.toUpperCase();
        var guessed = [];
        var wrongCount = 0;
        G.setStageScore(0);

        function render() {
            var display = word.split("").map(function (c) {
                if (c === " ") return " ";
                return guessed.indexOf(c) !== -1 ? c : "_";
            }).join(" ");
            body.innerHTML =
                '<div class="text-center" style="font-size:3rem;">' + HANGMAN_STAGES[Math.min(wrongCount, HANGMAN_STAGES.length - 1)] + '</div>' +
                '<p class="text-center text-muted">Définition : <strong>' + G.escapeHtml(termObj.definition) + '</strong></p>' +
                '<div class="gm-hangman-word">' + display + '</div>' +
                '<p class="text-center text-muted">Erreurs : ' + wrongCount + ' / ' + maxWrong + '</p>' +
                '<div class="gm-hangman-keyboard">' + "AZERTYUIOPQSDFGHJKLMWXCVBN".split("").map(function (letter) {
                    var used = guessed.indexOf(letter) !== -1;
                    var cls = used ? (word.indexOf(letter) !== -1 ? "gm-key-correct" : "gm-key-wrong") : "";
                    return '<button type="button" class="' + cls + '" data-letter="' + letter + '" ' + (used ? "disabled" : "") + '>' + letter + '</button>';
                }).join("") + '</div>';
            Array.prototype.forEach.call(body.querySelectorAll("[data-letter]"), function (btn) {
                btn.addEventListener("click", function () { guess(btn.getAttribute("data-letter")); });
            });
        }

        function guess(letter) {
            if (guessed.indexOf(letter) !== -1) return;
            guessed.push(letter);
            if (word.indexOf(letter) === -1) { wrongCount++; G.playWrong(); } else { G.playCorrect(); }

            var won = word.split("").every(function (c) { return c === " " || guessed.indexOf(c) !== -1; });
            if (won) {
                var score = Math.max(20, (maxWrong - wrongCount + 1) * 20);
                render();
                setTimeout(function () { G.resultScreen(gameKey, score, 1, 1, "Trouvé avec " + wrongCount + " erreur(s) !"); }, 700);
                return;
            }
            if (wrongCount >= maxWrong) {
                render();
                setTimeout(function () { G.resultScreen(gameKey, 0, 0, 1, "Perdu — le mot était " + word + "."); }, 700);
                return;
            }
            render();
        }

        render();
    }

    // ===================== 9) CHRONO CHALLENGE 60S — MCQ_SURVIVAL =====================

    function mcqSurvival(def, config) {
        var timeLimit = config.timeLimitSeconds || 60;
        G.stageBody().innerHTML = '<p class="text-center text-muted">Préparation…</p>';
        G.drawQuestions(null, null, "MCQ", 30).then(function (questions) {
            if (!questions.length) { G.stageBody().innerHTML = '<p class="text-danger text-center">Pas assez de questions.</p>'; return; }
            startSurvival(def.gameKey, questions, timeLimit);
        });
    }

    function startSurvival(gameKey, questions, timeLimit) {
        var body = G.stageBody();
        var qi = 0, score = 0, correct = 0, remaining = timeLimit;
        var answers = [];
        G.setStageScore(0);

        var timer = setInterval(function () {
            remaining--;
            var el = body.querySelector("#gmSurvivalTimer");
            if (el) el.textContent = remaining + "s";
            if (remaining <= 10 && remaining > 0) G.playTick();
            if (remaining <= 0) { clearInterval(timer); G.resultScreen(gameKey, score, correct, qi, "Survie terminée — " + correct + " bonnes réponses enchaînées !", answers); }
        }, 1000);

        function nextQuestion() {
            if (remaining <= 0) return;
            var q = questions[qi % questions.length];
            body.innerHTML =
                '<div class="d-flex justify-content-between mb-3">' +
                    '<span class="badge bg-danger fs-6" id="gmSurvivalTimer">' + remaining + 's</span>' +
                    '<span class="badge bg-success fs-6">' + correct + ' bonnes réponses</span>' +
                '</div>' +
                '<h5 class="mb-4">' + G.escapeHtml(q.questionText) + '</h5>' +
                '<div id="gmMcqOptions">' + renderMcqOptions(q.options) + '</div>';
            wireMcqOptions(body, function (idx, btn) {
                lockOptions(body);
                G.submitAnswer(q.questionId, idx).then(function (result) {
                    answers.push({ questionText: q.questionText, options: q.options, selectedIndex: idx, correctIndex: result.correctOptionIndex, correct: result.correct });
                    if (result.correct) { correct++; score += (result.pointsEarned || 10); G.playCorrect(); } else G.playWrong();
                    G.setStageScore(score);
                    qi++;
                    setTimeout(nextQuestion, 350);
                });
            });
        }

        nextQuestion();
    }

    // ===================== 10) PUZZLE DU PARCOURS CLIENT — PROCESS_ORDER =====================

    function processOrder(def, config) {
        G.stageBody().innerHTML = '<p class="text-center text-muted">Chargement d\'un vrai parcours de traitement…</p>';
        G.getJson("/api/games/process-puzzle/random").then(function (puzzle) {
            startPuzzle(def.gameKey, puzzle);
        }).catch(function (e) {
            G.stageBody().innerHTML = '<p class="text-danger text-center">' + G.escapeHtml(e.message) + '</p>';
        });
    }

    function startPuzzle(gameKey, puzzle) {
        var body = G.stageBody();
        var order = puzzle.steps.slice();
        var dragSrcIndex = null;
        G.setStageScore(0);

        function render() {
            body.innerHTML =
                '<h5 class="mb-1">' + G.escapeHtml(puzzle.procedureTitle) + '</h5>' +
                '<p class="text-muted mb-3">Remettez les étapes dans le bon ordre (glissez-déposez, ou utilisez les flèches).</p>' +
                '<div class="gm-puzzle-list" id="gmPuzzleList">' + order.map(function (s, i) {
                    return '<div class="gm-puzzle-step" draggable="true" data-idx="' + i + '">' +
                        '<span class="gm-puzzle-num">' + (i + 1) + '</span>' +
                        '<span class="flex-grow-1">' + G.escapeHtml(s.text) + '</span>' +
                        '<div class="d-flex gap-1">' +
                            '<button class="btn btn-sm btn-outline-secondary gm-puzzle-up" ' + (i === 0 ? "disabled" : "") + '><i class="bi bi-arrow-up"></i></button>' +
                            '<button class="btn btn-sm btn-outline-secondary gm-puzzle-down" ' + (i === order.length - 1 ? "disabled" : "") + '><i class="bi bi-arrow-down"></i></button>' +
                        '</div>' +
                    '</div>';
                }).join("") + '</div>' +
                '<button class="btn btn-primary w-100 mt-4" id="gmPuzzleSubmit"><i class="bi bi-check2-circle"></i> Valider l\'ordre</button>';

            var list = body.querySelector("#gmPuzzleList");
            Array.prototype.forEach.call(list.querySelectorAll(".gm-puzzle-step"), function (el) {
                el.addEventListener("dragstart", function () { dragSrcIndex = parseInt(el.getAttribute("data-idx"), 10); el.classList.add("gm-dragging"); });
                el.addEventListener("dragend", function () { el.classList.remove("gm-dragging"); });
                el.addEventListener("dragover", function (e) { e.preventDefault(); el.classList.add("gm-drag-over"); });
                el.addEventListener("dragleave", function () { el.classList.remove("gm-drag-over"); });
                el.addEventListener("drop", function (e) {
                    e.preventDefault(); el.classList.remove("gm-drag-over");
                    var targetIdx = parseInt(el.getAttribute("data-idx"), 10);
                    if (dragSrcIndex === null || dragSrcIndex === targetIdx) return;
                    var moved = order.splice(dragSrcIndex, 1)[0];
                    order.splice(targetIdx, 0, moved);
                    render();
                });
            });
            Array.prototype.forEach.call(list.querySelectorAll(".gm-puzzle-up"), function (btn) {
                btn.addEventListener("click", function () {
                    var i = parseInt(btn.closest(".gm-puzzle-step").getAttribute("data-idx"), 10);
                    if (i > 0) { var tmp = order[i - 1]; order[i - 1] = order[i]; order[i] = tmp; G.playClick(); render(); }
                });
            });
            Array.prototype.forEach.call(list.querySelectorAll(".gm-puzzle-down"), function (btn) {
                btn.addEventListener("click", function () {
                    var i = parseInt(btn.closest(".gm-puzzle-step").getAttribute("data-idx"), 10);
                    if (i < order.length - 1) { var tmp = order[i + 1]; order[i + 1] = order[i]; order[i] = tmp; G.playClick(); render(); }
                });
            });
            body.querySelector("#gmPuzzleSubmit").addEventListener("click", submit);
        }

        function submit() {
            var orderedStepIds = order.map(function (s) { return s.stepId; });
            G.sendJson("/api/games/process-puzzle/validate", "POST", { procedureTitle: puzzle.procedureTitle, orderedStepIds: orderedStepIds })
                .then(function (result) {
                    var score = Math.round((result.correctPositions / result.totalSteps) * 100);
                    if (result.correct) G.playWin(); else G.playWrong();
                    G.resultScreen(gameKey, score, result.correctPositions, result.totalSteps,
                        result.correct ? "Ordre parfait !" : (result.correctPositions + " étape(s) bien placée(s) sur " + result.totalSteps + "."));
                });
        }

        render();
    }

    return {
        mcqStandard: mcqStandard, mcqLadder: mcqLadder, trueFalseRapid: trueFalseRapid, wheel: wheel,
        wordGuess: wordGuess, mcqDuel: mcqDuel, memory: memory, hangman: hangman,
        mcqSurvival: mcqSurvival, processOrder: processOrder
    };
})();
