"use strict";

/**
 * Espace Formation — modèle EduFun (tableau de bord XP/niveau/badges, parcours, quiz & défis,
 * formations programmées, certificats) + Espace QA synchronisé (publication des cours,
 * validation des certificats, activité en direct, classement).
 *
 * training.js garde le catalogue, le lecteur de cours et la création de contenu ;
 * training-schedule.js garde l'agenda et la programmation. Ce fichier orchestre l'ensemble et
 * s'appuie sur /api/training/journey (TrainingJourneyController).
 */
(function () {
    var $ = function (id) { return document.getElementById(id); };
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var esc = RccApi.escapeHtml;

    var profile = null;
    var summary = null;
    var coursesCache = [];
    var attemptsCache = [];
    var quizFilter = "all";
    var pubFilter = "";
    var lastSyncAt = null;
    var qaTimer = null;
    var TAB_KEY = "rcc.formation.tab";
    var PASS = 70;

    function isQa() { return profile === "QA" || profile === "ADMIN" || profile === "FORMATEUR"; }

    function toast(message) {
        var t = $("efToast");
        if (!t) return;
        t.textContent = message;
        t.classList.add("show");
        clearTimeout(toast.timer);
        toast.timer = setTimeout(function () { t.classList.remove("show"); }, 2800);
    }

    function formatDate(iso, withTime) {
        if (!iso) return "—";
        var d = new Date(iso);
        if (isNaN(d)) return "—";
        return d.toLocaleDateString("fr-FR") + (withTime ? " " + d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" }) : "");
    }

    function timeAgo(iso) {
        var d = new Date(iso);
        if (isNaN(d)) return "";
        var s = Math.max(0, Math.round((Date.now() - d.getTime()) / 1000));
        if (s < 60) return "à l'instant";
        if (s < 3600) return "il y a " + Math.round(s / 60) + " min";
        if (s < 86400) return "il y a " + Math.round(s / 3600) + " h";
        return formatDate(iso);
    }

    function initials(name) {
        return String(name || "?").split(/\s+/).filter(Boolean).slice(0, 2).map(function (p) { return p[0].toUpperCase(); }).join("") || "?";
    }

    // ───────────── Navigation entre espaces ─────────────

    function showTab(name, remember) {
        var btn = document.querySelector('#efTabs [data-ef-tab="' + name + '"]');
        if (!btn || btn.style.display === "none") name = "dashboard";
        Array.prototype.forEach.call(document.querySelectorAll("#efTabs [data-ef-tab]"), function (b) {
            b.classList.toggle("active", b.getAttribute("data-ef-tab") === name);
        });
        Array.prototype.forEach.call(document.querySelectorAll("[data-ef-pane]"), function (p) {
            p.classList.toggle("active", p.getAttribute("data-ef-pane") === name);
        });
        if (remember !== false) { try { localStorage.setItem(TAB_KEY, name); } catch (ignore) {} }
        if (name === "qa") loadQa();
        if (name === "certificats") loadCertificates();
    }

    function wireTabs() {
        Array.prototype.forEach.call(document.querySelectorAll("[data-ef-tab]"), function (b) {
            b.addEventListener("click", function () { showTab(b.getAttribute("data-ef-tab")); });
        });
        Array.prototype.forEach.call(document.querySelectorAll("[data-ef-go]"), function (b) {
            b.addEventListener("click", function () {
                showTab(b.getAttribute("data-ef-go"));
                window.scrollTo({ top: $("efTabs").offsetTop - 10, behavior: "smooth" });
            });
        });
    }

    // ───────────── Tableau de bord personnel ─────────────

    function loadSummary() {
        return getJson("/api/training/journey/me").then(function (s) {
            summary = s;
            renderSummary();
            renderEligible();
        }).catch(function (e) {
            $("efBadges").innerHTML = '<p class="ef-muted small mb-0">Progression indisponible : ' + esc(e.message) + "</p>";
        });
    }

    function renderSummary() {
        var s = summary;
        $("efName").textContent = s.name || "Mon espace";
        $("efAvatar").textContent = initials(s.name);
        $("efLevel").textContent = "Niveau " + s.level + (s.teamLabel ? " • " + s.teamLabel : "");
        $("efXpPill").innerHTML = '<i class="bi bi-lightning-charge-fill"></i> ' + s.xp + " XP";
        $("efLevelName").textContent = "Niveau " + s.level;
        if (s.nextLevelXp) {
            var span = s.nextLevelXp - s.levelFloorXp;
            var pct = span > 0 ? Math.round(((s.xp - s.levelFloorXp) / span) * 100) : 100;
            $("efLevelNext").textContent = (s.nextLevelXp - s.xp) + " XP avant « " + s.nextLevel + " »";
            $("efLevelBar").style.width = Math.max(3, Math.min(100, pct)) + "%";
        } else {
            $("efLevelNext").textContent = "Niveau maximum atteint 🎉";
            $("efLevelBar").style.width = "100%";
        }
        countUp($("efKpiLessons"), s.lessonsCompleted);
        countUp($("efKpiXp"), s.xp);
        $("efKpiQuiz").textContent = s.quizzesPassed + " / " + s.quizzesTaken;
        var earned = s.badges.filter(function (b) { return b.earned; }).length;
        $("efKpiBadges").textContent = earned + " / " + s.badges.length;
        $("efStreak").textContent = s.streakDays;
        // Centre d'Évaluation (même XP, même niveau) — voir evaluation.js.
        if ($("efEvalPlays")) {
            $("efEvalPlays").textContent = s.gamesPlayed || 0;
            $("efEvalPassed").textContent = (s.evaluationsPassed || 0) + " / " + (s.evaluationsTaken || 0);
        }

        $("efBadges").innerHTML = s.badges.map(function (b) {
            return '<div class="ef-badge' + (b.earned ? " earned" : "") + '" title="' + esc(b.description) + '">' +
                '<i class="bi ' + esc(b.icon) + '"></i><b>' + esc(b.label) + "</b>" +
                "<small>" + (b.earned ? "Obtenu" : b.progress + " / " + b.target) + "</small></div>";
        }).join("");

        $("efTeamLabel").textContent = s.teamLabel ? "— " + s.teamLabel : "";
        $("efLeaderboard").innerHTML = leaderboardHtml(s.teamLeaderboard, "Aucun XP dans l'équipe pour l'instant — soyez le premier !");
    }

    function leaderboardHtml(list, emptyText) {
        if (!list || !list.length) return '<li class="ef-muted small">' + esc(emptyText) + "</li>";
        return list.map(function (e) {
            return '<li class="' + (e.me ? "me" : "") + '"><span class="ef-rank' + (e.rank <= 3 ? " r" + e.rank : "") + '">' + e.rank + "</span>" +
                '<span class="ef-lb-name">' + esc(e.name) + '<small class="d-block ef-muted">' + esc(e.level) + (e.teamLabel ? " · " + esc(e.teamLabel) : "") + "</small></span>" +
                '<span class="ef-lb-xp">' + e.xp + " XP</span></li>";
        }).join("");
    }

    function countUp(el, target) {
        if (!el) return;
        var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        if (reduce || !target) { el.textContent = target; return; }
        var start = null;
        function step(ts) {
            if (start === null) start = ts;
            var t = Math.min(1, (ts - start) / 700);
            el.textContent = Math.round(target * (1 - Math.pow(1 - t, 3)));
            if (t < 1) requestAnimationFrame(step);
        }
        requestAnimationFrame(step);
    }

    // ───────────── Quiz & défis ─────────────

    function quizState(course) {
        var a = attemptsCache.filter(function (x) { return x.courseId === course.courseId; })[0];
        if (!a || a.status !== "DONE") return { key: "todo", attempt: a };
        if (a.score !== null && a.score !== undefined && a.score >= PASS) return { key: "passed", attempt: a };
        return { key: a.finalized ? "failed" : "retry", attempt: a };
    }

    function renderQuizzes() {
        var grid = $("efQuizGrid");
        var quizzes = coursesCache.filter(function (c) { return c.type === "STANDARD" && (!c.publicationStatus || c.publicationStatus === "PUBLISHED"); });
        var items = quizzes.map(function (c) { return { course: c, state: quizState(c) }; })
            .filter(function (it) { return quizFilter === "all" || it.state.key === quizFilter || (quizFilter === "retry" && it.state.key === "failed"); });
        if (!quizzes.length) {
            grid.innerHTML = '<div class="col-12 ef-empty"><i class="bi bi-controller fs-1 d-block mb-2"></i>Aucun quiz publié pour votre équipe pour le moment.</div>';
            return;
        }
        if (!items.length) {
            grid.innerHTML = '<div class="col-12 ef-empty">Aucun quiz dans ce filtre.</div>';
            return;
        }
        var LABELS = {
            todo: '<span class="ef-chip ef-chip-todo">À faire</span>',
            passed: '<span class="ef-chip ef-chip-success"><i class="bi bi-check2"></i> Réussi</span>',
            retry: '<span class="ef-chip ef-chip-warn">2e tentative disponible</span>',
            failed: '<span class="ef-chip ef-chip-danger">Non validé</span>'
        };
        var ACTIONS = { todo: "Relever le défi", passed: "Revoir mon résultat", retry: "Repasser le quiz", failed: "Voir le résultat" };
        grid.innerHTML = items.map(function (it, i) {
            var c = it.course, st = it.state, score = st.attempt && st.attempt.score;
            var ring = st.key === "todo" ? '<span class="ef-chip">+' + 50 + " XP</span>"
                : '<div class="ef-score-ring" style="--p:' + (score || 0) + ";--ring:" + (st.key === "passed" ? "#00A651" : st.key === "retry" ? "#f59f00" : "#e45d6a") + '"><span>' + (score || 0) + "%</span></div>";
            return '<div class="col-md-6 col-xl-4"><div class="ef-quiz-card ' + st.key + '" style="animation-delay:' + Math.min(i * 40, 320) + 'ms">' +
                '<div class="d-flex justify-content-between align-items-start gap-2"><span class="ef-pill ef-pill-sm">' + esc(c.category || "Général") + "</span>" + LABELS[st.key] + "</div>" +
                "<h5>" + esc(c.title) + "</h5>" +
                '<p class="ef-course-desc mb-0">' + esc(c.description || "Évaluation à choix multiples.") + "</p>" +
                '<div class="ef-course-meta mb-0"><span>🧠 ' + (c.questionCount || 0) + " question(s)</span>" + (c.mandatory ? "<span>⭐ Obligatoire</span>" : "") + (c.videoUrl ? "<span>🎬 Vidéo à voir</span>" : "") + "</div>" +
                '<div class="ef-quiz-foot">' + ring + '<button type="button" class="ef-btn ' + (st.key === "todo" || st.key === "retry" ? "ef-btn-primary" : "ef-btn-soft") + ' ef-btn-sm" data-quiz="' + c.courseId + '">' + ACTIONS[st.key] + ' <i class="bi bi-arrow-right"></i></button></div>' +
                "</div></div>";
        }).join("");
        Array.prototype.forEach.call(grid.querySelectorAll("[data-quiz]"), function (b) {
            b.addEventListener("click", function () {
                if (window.RccTraining) window.RccTraining.openCoursePlayer(Number(b.getAttribute("data-quiz")));
            });
        });
    }

    function wireQuizFilter() {
        Array.prototype.forEach.call(document.querySelectorAll("#efQuizFilter button"), function (b) {
            b.addEventListener("click", function () {
                Array.prototype.forEach.call(document.querySelectorAll("#efQuizFilter button"), function (x) { x.classList.toggle("active", x === b); });
                quizFilter = b.getAttribute("data-filter");
                renderQuizzes();
            });
        });
    }

    // ───────────── Certificats (agent) ─────────────

    var myCertificates = [];

    function loadCertificates() {
        return getJson("/api/training/journey/certificates/me").then(function (list) {
            myCertificates = list;
            renderCertificates();
        }).catch(function (e) {
            $("efCertList").innerHTML = '<div class="col-12 ef-empty">Certificats indisponibles : ' + esc(e.message) + "</div>";
        });
    }

    var CERT_STATUS = {
        PENDING: '<span class="ef-chip ef-chip-warn"><i class="bi bi-hourglass-split"></i> En attente de validation QA</span>',
        ISSUED: '<span class="ef-chip ef-chip-success"><i class="bi bi-patch-check-fill"></i> Validé</span>',
        REJECTED: '<span class="ef-chip ef-chip-danger"><i class="bi bi-x-circle"></i> Refusé</span>',
        REVOKED: '<span class="ef-chip ef-chip-draft"><i class="bi bi-slash-circle"></i> Révoqué</span>'
    };

    function renderCertificates() {
        var box = $("efCertList");
        if (!myCertificates.length) {
            box.innerHTML = '<div class="col-12 ef-empty"><i class="bi bi-mortarboard fs-1 d-block mb-2"></i>' +
                "Pas encore de certificat. Terminez un parcours programmé ou réussissez une évaluation, puis demandez votre certificat.</div>";
            return;
        }
        box.innerHTML = myCertificates.map(function (c) {
            return '<div class="col-md-6"><div class="ef-cert-card ' + c.status.toLowerCase() + '">' +
                '<div class="d-flex justify-content-between gap-2 flex-wrap">' + CERT_STATUS[c.status] +
                '<span class="ef-chip">' + (c.sourceType === "FORMATION" ? "Parcours" : "Évaluation") + "</span></div>" +
                "<h5>" + esc(c.title) + "</h5>" +
                (c.certificateNumber ? '<div class="ef-cert-number">N° ' + esc(c.certificateNumber) + "</div>" : "") +
                '<div class="small ef-muted">Demandé le ' + formatDate(c.requestedAt) + (c.decidedAt ? " · décidé le " + formatDate(c.decidedAt) + (c.decidedBy ? " par " + esc(c.decidedBy) : "") : "") + "</div>" +
                (c.decisionNote ? '<div class="small"><i class="bi bi-chat-left-text"></i> ' + esc(c.decisionNote) + "</div>" : "") +
                (c.status === "ISSUED" ? '<button type="button" class="ef-btn ef-btn-primary ef-btn-sm mt-auto align-self-start" data-cert="' + c.id + '"><i class="bi bi-eye"></i> Voir / imprimer</button>' : "") +
                "</div></div>";
        }).join("");
        Array.prototype.forEach.call(box.querySelectorAll("[data-cert]"), function (b) {
            b.addEventListener("click", function () {
                var id = Number(b.getAttribute("data-cert"));
                openCertificate(myCertificates.filter(function (c) { return c.id === id; })[0]);
            });
        });
    }

    function renderEligible() {
        var box = $("efEligible");
        var list = summary ? summary.eligibleCertificates : [];
        if (!list.length) {
            box.innerHTML = '<p class="ef-muted small mb-0">Rien à demander pour l\'instant : terminez un parcours programmé à 100 % ou réussissez une évaluation (≥ ' + PASS + " %).</p>";
            return;
        }
        box.innerHTML = list.map(function (e, i) {
            return '<div class="ef-eligible"><i class="bi ' + (e.sourceType === "FORMATION" ? "bi-signpost-2" : "bi-trophy") + ' text-primary"></i>' +
                '<span class="ef-eligible-title">' + esc(e.title) + (e.score !== null && e.score !== undefined ? ' <small class="ef-muted">(' + e.score + " %)</small>" : "") + "</span>" +
                '<button type="button" class="ef-btn ef-btn-success ef-btn-sm" data-eligible="' + i + '">Demander</button></div>';
        }).join("");
        Array.prototype.forEach.call(box.querySelectorAll("[data-eligible]"), function (b) {
            b.addEventListener("click", function () {
                var e = list[Number(b.getAttribute("data-eligible"))];
                b.disabled = true;
                sendJson("/api/training/journey/certificates", "POST", { sourceType: e.sourceType, sourceId: e.sourceId })
                    .then(function () {
                        toast("Demande envoyée à la Quality Assurance ✔");
                        return Promise.all([loadSummary(), loadCertificates()]);
                    })
                    .catch(function (err) { b.disabled = false; alert(err.message); });
            });
        });
    }

    function openCertificate(c) {
        if (!c) return;
        $("efCertModalBody").innerHTML =
            '<div class="ef-certificate">' +
            '<img src="/images/ecobank-logo.png" alt="Ecobank" class="ef-cert-logo">' +
            '<div class="ef-cert-kicker">RELATION CLIENT CENTER · ACADÉMIE</div>' +
            "<h2>Certificat de réussite</h2>" +
            "<div>Ce certificat est décerné à</div>" +
            '<div class="ef-cert-holder">' + esc(c.holderName || (summary && summary.name) || "") + "</div>" +
            "<div>pour avoir " + (c.sourceType === "FORMATION" ? "suivi et terminé le parcours" : "réussi l'évaluation") + "</div>" +
            '<div class="ef-cert-title">« ' + esc(c.title) + " »" + (c.score !== null && c.score !== undefined ? " — score " + c.score + " %" : "") + "</div>" +
            '<div class="ef-cert-foot">' +
            "<div><b>N° " + esc(c.certificateNumber || "") + "</b><br>Délivré le " + formatDate(c.decidedAt) + "<br>Vérifiable dans Formation › Certificats</div>" +
            '<div class="ef-cert-seal">VALIDÉ<br>QA</div>' +
            '<div class="text-end">Validé par<br><b>' + esc(c.decidedBy || "Quality Assurance") + "</b><br>Quality Assurance</div>" +
            "</div></div>";
        bootstrap.Modal.getOrCreateInstance($("efCertModal")).show();
    }

    function wireVerify() {
        $("efVerifyForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            var number = $("efVerifyInput").value.trim();
            if (!number) return;
            var out = $("efVerifyResult");
            out.innerHTML = '<span class="ef-muted">Vérification…</span>';
            getJson("/api/training/journey/certificates/verify/" + encodeURIComponent(number)).then(function (v) {
                if (v.valid) {
                    out.innerHTML = '<div class="alert alert-success py-2 px-3 mb-0"><i class="bi bi-patch-check-fill"></i> Certificat <b>authentique</b> — ' +
                        esc(v.holderName || "") + " · « " + esc(v.title) + " » · délivré le " + formatDate(v.issuedAt) + "</div>";
                } else if (v.status === "REVOKED") {
                    out.innerHTML = '<div class="alert alert-warning py-2 px-3 mb-0"><i class="bi bi-slash-circle"></i> Ce certificat a été <b>révoqué</b> par la QA.</div>';
                } else {
                    out.innerHTML = '<div class="alert alert-danger py-2 px-3 mb-0"><i class="bi bi-x-circle"></i> Aucun certificat valide avec ce numéro.</div>';
                }
            }).catch(function (e) { out.innerHTML = '<span class="text-danger">' + esc(e.message) + "</span>"; });
        });
        $("efCertPrint").addEventListener("click", function () { window.print(); });
    }

    // ───────────── Espace QA (synchronisé) ─────────────

    function loadQa() {
        if (!isQa()) return;
        loadQaOverview();
        loadQaCertificates();
        renderPublication();
        startQaTimer();
    }

    function loadQaOverview() {
        var team = $("efQaTeam").value;
        return getJson("/api/training/journey/qa/overview" + (team ? "?team=" + encodeURIComponent(team) : "")).then(function (o) {
            lastSyncAt = Date.now();
            updateSyncLabel();
            $("efQaPending").textContent = o.pendingCertificates;
            $("efQaDraft").textContent = o.draftCourses;
            $("efQaActive").textContent = o.activeLearnersToday;
            $("efQaWeek").textContent = o.lessonsCompletedWeek + o.quizzesPassedWeek + o.quizzesFailedWeek;
            $("efQaWeekDetail").textContent = o.lessonsCompletedWeek + " leçon(s) · " + o.quizzesPassedWeek + " quiz réussi(s) · " + o.quizzesFailedWeek + " échec(s)";
            var badge = $("efQaPendingCount");
            badge.textContent = o.pendingCertificates;
            badge.style.display = o.pendingCertificates ? "" : "none";

            var ICONS = {
                LESSON: ['<i class="bi bi-journal-check"></i>', "#eaf2ff", "#0057B8", "a terminé la leçon"],
                QUIZ: ['<i class="bi bi-trophy"></i>', "#e6f7ec", "#0a8a3e", "a passé l'évaluation"],
                SELF_ASSESSMENT: ['<i class="bi bi-person-check"></i>', "#f3eaff", "#7b2ff7", "a réalisé l'auto-diagnostic"],
                GAME: ['<i class="bi bi-controller"></i>', "#fff4e0", "#c77700", "a passé l'évaluation du jeu"]
            };
            $("efQaFeed").innerHTML = o.recentActivity.length ? o.recentActivity.map(function (a) {
                var ic = ICONS[a.type] || ICONS.LESSON;
                var bg = a.type === "QUIZ" && a.passed === false ? "#ffe9ec" : ic[1];
                var fg = a.type === "QUIZ" && a.passed === false ? "#b33e4c" : ic[2];
                var score = a.score !== null && a.score !== undefined ? ' <span class="ef-chip ' + (a.passed ? "ef-chip-success" : "ef-chip-danger") + '">' + a.score + " %</span>" : "";
                return '<li><span class="ef-feed-icon" style="background:' + bg + ";color:" + fg + '">' + ic[0] + "</span>" +
                    "<div><b>" + esc(a.userName) + "</b> " + ic[3] + " <i>" + esc(a.title) + "</i>" + score +
                    (a.teamLabel ? '<div class="ef-muted">' + esc(a.teamLabel) + "</div>" : "") + "</div>" +
                    '<span class="ef-feed-time">' + timeAgo(a.at) + "</span></li>";
            }).join("") : '<li class="ef-muted small">Aucune activité enregistrée pour l\'instant.</li>';
            $("efQaLeaderboard").innerHTML = leaderboardHtml(o.leaderboard, "Aucun apprenant n'a encore gagné d'XP.");
        }).catch(function (e) {
            $("efSyncLabel").textContent = "synchronisation interrompue (" + e.message + ")";
            document.querySelector(".ef-sync-dot").classList.add("stale");
        });
    }

    function updateSyncLabel() {
        if (!lastSyncAt) return;
        var s = Math.round((Date.now() - lastSyncAt) / 1000);
        $("efSyncLabel").textContent = s < 5 ? "à jour" : "mis à jour il y a " + (s < 60 ? s + " s" : Math.round(s / 60) + " min");
        document.querySelector(".ef-sync-dot").classList.toggle("stale", s > 90);
    }

    function startQaTimer() {
        if (qaTimer) return;
        qaTimer = setInterval(function () {
            updateSyncLabel();
            var qaPane = document.querySelector('[data-ef-pane="qa"]');
            if (document.hidden || !qaPane || !qaPane.classList.contains("active")) return;
            if (lastSyncAt && Date.now() - lastSyncAt >= 30000) {
                loadQaOverview();
                loadQaCertificates();
            }
        }, 5000);
    }

    function loadQaCertificates() {
        var status = $("efCertStatusFilter").value;
        return getJson("/api/training/journey/certificates" + (status ? "?status=" + status : "")).then(function (list) {
            var box = $("efQaCertificates");
            if (!list.length) {
                box.innerHTML = '<p class="ef-muted small mb-0">' + (status === "PENDING" ? "Aucune demande en attente 🎉" : "Aucun certificat dans cette liste.") + "</p>";
                return;
            }
            box.innerHTML = list.map(function (c) {
                var actions = c.status === "PENDING"
                    ? '<button type="button" class="ef-btn ef-btn-success ef-btn-sm" data-decide="ISSUE" data-id="' + c.id + '"><i class="bi bi-check2"></i> Valider</button>' +
                      '<button type="button" class="ef-btn ef-btn-danger ef-btn-sm" data-decide="REJECT" data-id="' + c.id + '"><i class="bi bi-x"></i> Refuser</button>'
                    : c.status === "ISSUED"
                        ? '<button type="button" class="ef-btn ef-btn-soft ef-btn-sm" data-view="' + c.id + '"><i class="bi bi-eye"></i></button>' +
                          '<button type="button" class="ef-btn ef-btn-danger ef-btn-sm" data-decide="REVOKE" data-id="' + c.id + '">Révoquer</button>'
                        : "";
                return '<div class="ef-cert-row"><div class="ef-avatar" style="width:34px;height:34px;font-size:.75rem;">' + esc(initials(c.holderName)) + "</div>" +
                    '<div class="ef-cert-who"><b>' + esc(c.holderName || "—") + "</b>" + (c.teamLabel ? ' <small class="ef-muted">· ' + esc(c.teamLabel) + "</small>" : "") +
                    '<div class="small">' + (c.sourceType === "FORMATION" ? "Parcours" : "Évaluation") + " « " + esc(c.title) + " »" +
                    (c.score !== null && c.score !== undefined ? " — " + c.score + " %" : "") + "</div>" +
                    '<div class="small ef-muted">Demandé ' + timeAgo(c.requestedAt) + (c.certificateNumber ? " · N° " + esc(c.certificateNumber) : "") +
                    (c.decisionNote ? " · " + esc(c.decisionNote) : "") + "</div></div>" +
                    CERT_STATUS[c.status] + '<div class="d-flex gap-1">' + actions + "</div></div>";
            }).join("");
            Array.prototype.forEach.call(box.querySelectorAll("[data-decide]"), function (b) {
                b.addEventListener("click", function () {
                    var action = b.getAttribute("data-decide");
                    var note = null;
                    if (action !== "ISSUE") {
                        note = prompt(action === "REJECT" ? "Motif du refus (visible par l'agent) :" : "Motif de la révocation :");
                        if (!note || !note.trim()) return;
                    }
                    b.disabled = true;
                    sendJson("/api/training/journey/certificates/" + b.getAttribute("data-id") + "/decision", "POST", { action: action, note: note })
                        .then(function (c) {
                            toast(action === "ISSUE" ? "Certificat validé — N° " + c.certificateNumber : action === "REJECT" ? "Demande refusée" : "Certificat révoqué");
                            loadQaCertificates();
                            loadQaOverview();
                        })
                        .catch(function (e) { b.disabled = false; alert(e.message); });
                });
            });
            Array.prototype.forEach.call(box.querySelectorAll("[data-view]"), function (b) {
                b.addEventListener("click", function () {
                    var id = Number(b.getAttribute("data-view"));
                    openCertificate(list.filter(function (c) { return c.id === id; })[0]);
                });
            });
        }).catch(function (e) {
            $("efQaCertificates").innerHTML = '<p class="text-danger small mb-0">' + esc(e.message) + "</p>";
        });
    }

    var PUB_LABELS = {
        DRAFT: '<span class="ef-chip ef-chip-draft"><i class="bi bi-pencil"></i> Brouillon</span>',
        PUBLISHED: '<span class="ef-chip ef-chip-success"><i class="bi bi-broadcast"></i> Publié</span>',
        ARCHIVED: '<span class="ef-chip ef-chip-warn"><i class="bi bi-archive"></i> Archivé</span>'
    };

    function renderPublication() {
        if (!isQa()) return;
        var body = $("efPubTable");
        var list = coursesCache.filter(function (c) { return !pubFilter || (c.publicationStatus || "PUBLISHED") === pubFilter; });
        if (!list.length) {
            body.innerHTML = '<tr><td colspan="6" class="text-muted text-center">Aucun cours dans cette liste.</td></tr>';
            return;
        }
        body.innerHTML = list.map(function (c) {
            var st = c.publicationStatus || "PUBLISHED";
            var actions = [];
            if (st !== "PUBLISHED") actions.push('<button type="button" class="ef-btn ef-btn-success ef-btn-sm" data-pub="PUBLISHED" data-id="' + c.courseId + '"><i class="bi bi-broadcast"></i> Publier</button>');
            if (st === "PUBLISHED") actions.push('<button type="button" class="ef-btn ef-btn-soft ef-btn-sm" data-pub="DRAFT" data-id="' + c.courseId + '">Dépublier</button>');
            if (st !== "ARCHIVED") actions.push('<button type="button" class="ef-btn ef-btn-soft ef-btn-sm" data-pub="ARCHIVED" data-id="' + c.courseId + '" title="Archiver"><i class="bi bi-archive"></i></button>');
            var warn = c.type === "STANDARD" && !c.questionCount ? ' <i class="bi bi-exclamation-triangle-fill text-warning" title="Aucune question : ajoutez-en avant de publier"></i>' : "";
            return "<tr><td><b>" + esc(c.title) + "</b><div class=\"small ef-muted\">" + esc(c.category || "Général") +
                (c.publishedAt && st === "PUBLISHED" ? " · publié le " + formatDate(c.publishedAt) + (c.publishedBy ? " par " + esc(c.publishedBy) : "") : "") + "</div></td>" +
                "<td>" + (c.type === "STANDARD" ? "Évaluation" : "Auto-diagnostic") + "</td>" +
                "<td>" + esc(c.teamLabel || "Toutes") + "</td>" +
                "<td>" + (c.type === "STANDARD" ? (c.questionCount || 0) + warn : "—") + "</td>" +
                "<td>" + PUB_LABELS[st] + '</td><td class="text-end"><div class="d-inline-flex gap-1">' + actions.join("") + "</div></td></tr>";
        }).join("");
        Array.prototype.forEach.call(body.querySelectorAll("[data-pub]"), function (b) {
            b.addEventListener("click", function () {
                var status = b.getAttribute("data-pub");
                b.disabled = true;
                fetch("/api/courses/" + b.getAttribute("data-id") + "/publication?status=" + status, { method: "PATCH", credentials: "same-origin" })
                    .then(function (res) {
                        if (res.ok) return res.json();
                        return res.text().then(function (t) {
                            var msg = t;
                            try { msg = JSON.parse(t).error.message; } catch (ignore) {}
                            throw new Error(msg || "HTTP " + res.status);
                        });
                    })
                    .then(function (c) {
                        toast(status === "PUBLISHED" ? "« " + c.title + " » est maintenant visible des agents" : status === "DRAFT" ? "Cours repassé en brouillon" : "Cours archivé");
                        if (window.RccTraining) window.RccTraining.reload();
                        loadQaOverview();
                    })
                    .catch(function (e) { b.disabled = false; alert(e.message); });
            });
        });
    }

    function wireQa() {
        $("efQaRefresh").addEventListener("click", function () { loadQaOverview(); loadQaCertificates(); if (window.RccTraining) window.RccTraining.reload(); });
        $("efQaTeam").addEventListener("change", loadQaOverview);
        $("efCertStatusFilter").addEventListener("change", loadQaCertificates);
        Array.prototype.forEach.call(document.querySelectorAll("#efPubFilter button"), function (b) {
            b.addEventListener("click", function () {
                Array.prototype.forEach.call(document.querySelectorAll("#efPubFilter button"), function (x) { x.classList.toggle("active", x === b); });
                pubFilter = b.getAttribute("data-filter");
                renderPublication();
            });
        });
        getJson("/api/teams").then(function (teams) {
            var select = $("efQaTeam");
            (teams || []).forEach(function (t) {
                var o = document.createElement("option");
                o.value = t.code;
                o.textContent = t.label || t.code;
                select.appendChild(o);
            });
        }).catch(function () {});
    }

    // ───────────── Synchronisation avec training.js ─────────────

    var summaryReloadTimer = null;
    document.addEventListener("rcc:training-updated", function (evt) {
        coursesCache = evt.detail.courses || [];
        attemptsCache = evt.detail.attempts || [];
        renderQuizzes();
        renderPublication();
        // Une tentative vient peut-être d'être soumise : XP, badges et certificats suivent.
        clearTimeout(summaryReloadTimer);
        summaryReloadTimer = setTimeout(loadSummary, 400);
    });

    // ───────────── Init ─────────────

    wireTabs();
    wireQuizFilter();
    wireVerify();
    wireQa();

    window.RccSession.init().then(function (session) {
        profile = session ? session.profile : null;
        Array.prototype.forEach.call(document.querySelectorAll(".qa-only"), function (el) { el.style.display = isQa() ? "" : "none"; });
        var fromHash = (window.location.hash || "").replace("#", "");
        var saved = null;
        try { saved = localStorage.getItem(TAB_KEY); } catch (ignore) {}
        showTab(fromHash || saved || "dashboard", false);
        if (isQa()) loadQaOverview();
    });
    loadSummary();
    loadCertificates();
})();
