"use strict";

/**
 * Centre d'Évaluation au format de l'espace Formation, SYNCHRONISÉ avec lui : même barre
 * personnelle (XP, niveau), mêmes badges, même classement d'équipe — le tout calculé par
 * /api/training/journey/me qui additionne Formation (leçons, cours, quiz, certificats) et
 * Centre d'Évaluation (parties, évaluations notées). games.js garde les jeux et l'administration.
 */
(function () {
    var $ = function (id) { return document.getElementById(id); };
    var esc = RccApi.escapeHtml;
    var summary = null;
    var games = [];
    var filter = "all";
    var TAB_KEY = "rcc.evaluation.tab";

    function initials(name) {
        return String(name || "?").split(/\s+/).filter(Boolean).slice(0, 2).map(function (p) { return p[0].toUpperCase(); }).join("") || "?";
    }

    function fmtDate(iso) {
        if (!iso) return "—";
        var d = new Date(iso);
        return isNaN(d) ? "—" : d.toLocaleDateString("fr-FR") + " " + d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
    }

    function resultOf(key) {
        return summary && summary.gameResults ? summary.gameResults.filter(function (r) { return r.gameKey === key; })[0] : null;
    }

    function isEval(g) { return window.RccGames && RccGames.isEvaluation(g); }

    function evalState(g) {
        var r = resultOf(g.gameKey);
        if (!isEval(g)) return "train";
        if (!r || !r.evaluationAttempts) return "todo";
        if (r.evaluationPassed) return "passed";
        return r.evaluationAttempts >= 2 ? "failed" : "retry";
    }

    // ───────────── Onglets ─────────────

    function showTab(name, remember) {
        var btn = document.querySelector('#evTabs [data-ev-tab="' + name + '"]');
        if (!btn || btn.style.display === "none") name = "jeux";
        Array.prototype.forEach.call(document.querySelectorAll("#evTabs [data-ev-tab]"), function (b) {
            b.classList.toggle("active", b.getAttribute("data-ev-tab") === name);
        });
        Array.prototype.forEach.call(document.querySelectorAll("[data-ev-pane]"), function (p) {
            var on = p.getAttribute("data-ev-pane") === name;
            p.classList.toggle("active", on);
            if (p.id === "gmAdminPanel") p.style.display = on ? "" : "none";
        });
        if (remember !== false) { try { localStorage.setItem(TAB_KEY, name); } catch (ignore) {} }
    }

    function wireTabs() {
        Array.prototype.forEach.call(document.querySelectorAll("#evTabs [data-ev-tab]"), function (b) {
            // Enregistré après games.js : l'onglet QA ouvre toujours l'administration (games.js
            // bascule l'affichage et charge les données, on fixe ensuite l'état voulu).
            b.addEventListener("click", function () { showTab(b.getAttribute("data-ev-tab")); });
        });
        Array.prototype.forEach.call(document.querySelectorAll("[data-ev-go]"), function (b) {
            b.addEventListener("click", function () { showTab(b.getAttribute("data-ev-go")); });
        });
        Array.prototype.forEach.call(document.querySelectorAll("#evGameFilter button"), function (b) {
            b.addEventListener("click", function () {
                filter = b.getAttribute("data-filter");
                Array.prototype.forEach.call(document.querySelectorAll("#evGameFilter button"), function (x) { x.classList.toggle("active", x === b); });
                decorateGallery();
            });
        });
        $("evPlayNextBtn").addEventListener("click", function () {
            var next = games.filter(function (g) { return g.active && (evalState(g) === "todo" || evalState(g) === "retry"); })[0]
                || games.filter(function (g) { return g.active; })[0];
            if (next && window.RccGames) RccGames.launchByKey(next.gameKey);
        });
        $("evLbGame").addEventListener("change", loadGameLeaderboard);
    }

    // ───────────── Progression partagée avec la Formation ─────────────

    function loadSummary() {
        return RccApi.getJson("/api/training/journey/me").then(function (s) {
            summary = s;
            renderSummary();
            decorateGallery();
            renderResults();
        }).catch(function () { /* progression indisponible : les jeux restent jouables */ });
    }

    function renderSummary() {
        var s = summary;
        $("evName").textContent = s.name || "Mon espace";
        $("evAvatar").textContent = initials(s.name);
        $("evLevel").textContent = "Niveau " + s.level + (s.teamLabel ? " • " + s.teamLabel : "");
        $("evXpPill").innerHTML = '<i class="bi bi-lightning-charge-fill"></i> ' + s.xp + " XP";
        $("evLevelName").textContent = "Niveau " + s.level;
        if (s.nextLevelXp) {
            var span = s.nextLevelXp - s.levelFloorXp;
            $("evLevelNext").textContent = (s.nextLevelXp - s.xp) + " XP avant « " + s.nextLevel + " »";
            $("evLevelBar").style.width = Math.max(3, Math.min(100, span > 0 ? Math.round((s.xp - s.levelFloorXp) * 100 / span) : 100)) + "%";
        } else {
            $("evLevelNext").textContent = "Niveau maximum atteint 🎉";
            $("evLevelBar").style.width = "100%";
        }
        $("evKpiPlays").textContent = s.gamesPlayed;
        $("evKpiPassed").textContent = s.evaluationsPassed + " / " + s.evaluationsTaken;
        $("evKpiXp").textContent = s.xp;
        updateTodoCount();

        $("evBadges").innerHTML = s.badges.map(function (b) {
            return '<div class="ef-badge' + (b.earned ? " earned" : "") + '" title="' + esc(b.description) + '"><i class="bi ' + esc(b.icon) + '"></i><b>' +
                esc(b.label) + "</b><small>" + (b.earned ? "Obtenu" : b.progress + " / " + b.target) + "</small></div>";
        }).join("");
        $("evTeamLabel").textContent = s.teamLabel ? "— " + s.teamLabel : "";
        $("evLeaderboard").innerHTML = s.teamLeaderboard && s.teamLeaderboard.length ? s.teamLeaderboard.map(function (e) {
            return '<li class="' + (e.me ? "me" : "") + '"><span class="ef-rank' + (e.rank <= 3 ? " r" + e.rank : "") + '">' + e.rank + "</span>" +
                '<span class="ef-lb-name">' + esc(e.name) + '<small class="d-block ef-muted">' + esc(e.level) + "</small></span>" +
                '<span class="ef-lb-xp">' + e.xp + " XP</span></li>";
        }).join("") : '<li class="ef-muted small">Aucun XP dans l\'équipe pour l\'instant — soyez le premier !</li>';
    }

    function updateTodoCount() {
        if (!games.length) return;
        $("evKpiTodo").textContent = games.filter(function (g) { return g.active && (evalState(g) === "todo" || evalState(g) === "retry"); }).length;
    }

    // ───────────── Galerie (cartes rendues par games.js) ─────────────

    var STATUS = {
        todo: '<span class="ef-chip ef-chip-todo"><i class="bi bi-hourglass"></i> Évaluation à passer</span>',
        retry: '<span class="ef-chip ef-chip-warn"><i class="bi bi-arrow-repeat"></i> 2e tentative disponible</span>',
        passed: '<span class="ef-chip ef-chip-success"><i class="bi bi-check2"></i> Réussie</span>',
        failed: '<span class="ef-chip ef-chip-danger"><i class="bi bi-x"></i> Non validée</span>',
        train: ""
    };

    function decorateGallery() {
        games.forEach(function (g) {
            var card = document.querySelector('.ev-card[data-game-key="' + g.gameKey + '"]');
            if (!card) return;
            var st = evalState(g), r = resultOf(g.gameKey);
            var html = STATUS[st] || "";
            if (r && r.evaluationScore != null && isEval(g)) html += '<span class="ef-chip">Score ' + r.evaluationScore + '/100</span>';
            if (r && r.plays) html += '<span class="ef-chip"><i class="bi bi-controller"></i> ' + r.plays + " partie(s)</span>";
            var slot = card.querySelector("[data-ev-status]");
            if (slot) slot.innerHTML = html;
            var show = filter === "all" || (filter === "eval" && isEval(g)) || (filter === "train" && !isEval(g))
                || (filter === "todo" && (st === "todo" || st === "retry"));
            card.classList.toggle("ev-hidden", !show);
        });
        updateTodoCount();
    }

    function renderResults() {
        var body = $("evResultsBody");
        if (!games.length) return;
        body.innerHTML = games.map(function (g) {
            var r = resultOf(g.gameKey), st = evalState(g);
            var evalCell = !isEval(g) ? '<span class="ef-muted small">Entraînement</span>'
                : (STATUS[st] + (r && r.evaluationScore != null ? ' <b>' + r.evaluationScore + "/100</b>" : ""));
            return "<tr><td><i class=\"bi " + esc(g.icon || "bi-controller") + '" style="color:' + esc(g.colorFrom || "#0057B8") + '"></i> <b>' + esc(g.title) + "</b></td>" +
                "<td>" + (r ? r.plays : 0) + "</td><td>" + (r && r.bestPlayScore != null ? r.bestPlayScore : "—") + "</td>" +
                "<td>" + evalCell + "</td><td>" + (r ? fmtDate(r.lastPlayedAt) : "—") + "</td>" +
                '<td class="text-end">' + (g.active ? '<button type="button" class="ef-btn ef-btn-soft ef-btn-sm" data-play="' + esc(g.gameKey) + '"><i class="bi bi-play-fill"></i> Jouer</button>' : "") + "</td></tr>";
        }).join("");
        Array.prototype.forEach.call(body.querySelectorAll("[data-play]"), function (b) {
            b.addEventListener("click", function () { RccGames.launchByKey(b.getAttribute("data-play")); });
        });
    }

    function loadGameLeaderboard() {
        var key = $("evLbGame").value;
        var box = $("evGameLeaderboard");
        if (!key) return;
        box.innerHTML = '<li class="ef-muted small">Chargement…</li>';
        RccApi.getJson("/api/games/" + encodeURIComponent(key) + "/leaderboard").then(function (rows) {
            box.innerHTML = rows.length ? rows.map(function (r, i) {
                return '<li><span class="ef-rank' + (i < 3 ? " r" + (i + 1) : "") + '">' + (i + 1) + '</span><span class="ef-lb-name">' + esc(r.playerName) +
                    '</span><span class="ef-lb-xp">' + r.score + " pts</span></li>";
            }).join("") : '<li class="ef-muted small">Aucun score enregistré pour ce jeu.</li>';
        }).catch(function () { box.innerHTML = '<li class="text-danger small">Classement indisponible.</li>'; });
    }

    // ───────────── Synchronisation ─────────────

    document.addEventListener("rcc:games-rendered", function (evt) {
        games = evt.detail.games || [];
        var select = $("evLbGame");
        if (!select.options.length) {
            select.innerHTML = games.map(function (g) { return '<option value="' + esc(g.gameKey) + '">' + esc(g.title) + "</option>"; }).join("");
            loadGameLeaderboard();
        }
        // games.js recharge la galerie à la sortie d'une partie : la progression suit aussitôt.
        loadSummary();
    });

    document.addEventListener("rcc:competitions-loaded", function (evt) {
        var n = evt.detail.count || 0;
        $("evCompCount").textContent = n;
        $("evCompCount").style.display = n ? "" : "none";
        $("evNoCompetition").style.display = n ? "none" : "";
    });

    document.addEventListener("DOMContentLoaded", function () {
        wireTabs();
        var saved = null;
        try { saved = localStorage.getItem(TAB_KEY); } catch (ignore) {}
        var fromHash = (window.location.hash || "").replace("#", "");
        // L'onglet QA n'est visible qu'après la session (games.js) : on attend un instant.
        setTimeout(function () {
            var target = fromHash || saved || "jeux";
            var qaBtn = $("gmAdminToggleBtn");
            // Espace QA : passer par le bouton pour que games.js charge l'administration.
            if (target === "qa" && qaBtn && qaBtn.style.display !== "none") qaBtn.click();
            else showTab(target, false);
        }, 500);
    });
})();
