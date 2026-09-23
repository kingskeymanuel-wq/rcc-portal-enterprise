"use strict";

/**
 * Formations programmées (jour/semaine/mois) — distinct du système "Cours"
 * existant (/api/courses). Utilise /api/training/* (TrainingApiController).
 * Voir training-lesson.js pour le lecteur (scroll tracké + vidéo plafonnée 1.5x).
 */
(function () {
    var $ = function (id) { return document.getElementById(id); };
    if (!$("scheduleAgendaList")) return; // page sans ce bloc

    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var formationsCache = [];
    var scheduleView = "day";
    var scheduleAnchor = new Date();
    var currentProfile = null;

    var MONTHS_FR = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"];
    var DAYS_FR = ["dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi"];

    function fmtDate(d) {
        var y = d.getFullYear(), m = String(d.getMonth() + 1).padStart(2, "0"), day = String(d.getDate()).padStart(2, "0");
        return y + "-" + m + "-" + day;
    }

    function parseDate(str) {
        var parts = str.split("-");
        return new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10));
    }

    function startOfWeek(d) {
        var copy = new Date(d);
        var day = copy.getDay();
        var diff = (day === 0 ? -6 : 1) - day; // lundi comme premier jour
        copy.setDate(copy.getDate() + diff);
        return copy;
    }

    // ===================== CHARGEMENT =====================

    function loadFormations() {
        return getJson("/api/training/formations").then(function (list) {
            formationsCache = list || [];
            renderAgenda();
            populateFormationSelects();
        }).catch(function () {
            $("scheduleAgendaList").innerHTML = '<p class="text-danger text-center mb-0">Impossible de charger les formations programmées.</p>';
        });
    }

    // ===================== RENDU CALENDRIER =====================

    function renderAgenda() {
        var label = $("scheduleRangeLabel");
        var list = $("scheduleAgendaList");
        var inRange;

        if (scheduleView === "day") {
            var dayStr = fmtDate(scheduleAnchor);
            inRange = formationsCache.filter(function (f) { return f.scheduledDate === dayStr; });
            label.textContent = DAYS_FR[scheduleAnchor.getDay()] + " " + scheduleAnchor.getDate() + " " + MONTHS_FR[scheduleAnchor.getMonth()] + " " + scheduleAnchor.getFullYear();
        } else if (scheduleView === "week") {
            var weekStart = startOfWeek(scheduleAnchor);
            var weekEnd = new Date(weekStart); weekEnd.setDate(weekEnd.getDate() + 6);
            inRange = formationsCache.filter(function (f) {
                var d = parseDate(f.scheduledDate);
                return d >= weekStart && d <= weekEnd;
            });
            label.textContent = "Semaine du " + weekStart.getDate() + " " + MONTHS_FR[weekStart.getMonth()] + " au " + weekEnd.getDate() + " " + MONTHS_FR[weekEnd.getMonth()];
        } else {
            var y = scheduleAnchor.getFullYear(), m = scheduleAnchor.getMonth();
            inRange = formationsCache.filter(function (f) {
                var d = parseDate(f.scheduledDate);
                return d.getFullYear() === y && d.getMonth() === m;
            });
            label.textContent = MONTHS_FR[m].charAt(0).toUpperCase() + MONTHS_FR[m].slice(1) + " " + y;
        }

        inRange.sort(function (a, b) { return (a.scheduledDate + (a.scheduledTime || "")).localeCompare(b.scheduledDate + (b.scheduledTime || "")); });

        if (inRange.length === 0) {
            list.innerHTML = '<p class="text-muted text-center mb-0">Aucune formation programmée sur cette période.</p>';
            return;
        }

        list.innerHTML = inRange.map(function (f) {
            var pct = f.myProgressPercent == null ? 0 : f.myProgressPercent;
            var badgeClass = pct >= 100 ? "bg-success" : (pct > 0 ? "bg-warning text-dark" : "bg-secondary");
            var mandatoryBadge = f.mandatory ? '<span class="badge bg-danger ms-1">Obligatoire</span>' : "";
            var recurrenceLabel = f.recurrenceType === "WEEKLY" ? "Chaque semaine" : (f.recurrenceType === "MONTHLY" ? "Chaque mois" : "");
            var recBadge = recurrenceLabel ? '<span class="badge bg-info text-dark ms-1">' + recurrenceLabel + "</span>" : "";
            var dateLabel = f.scheduledDate + (f.scheduledTime ? " à " + f.scheduledTime.substring(0, 5) : "");
            return (
                '<div class="d-flex justify-content-between align-items-center border rounded p-3 flex-wrap gap-2">' +
                    '<div>' +
                        '<div class="fw-semibold">' + escapeHtml(f.title) + mandatoryBadge + recBadge + "</div>" +
                        '<div class="text-muted small">' + escapeHtml(dateLabel) + (f.category ? " · " + escapeHtml(f.category) : "") + " · " + (f.lessonCount || 0) + " leçon(s)</div>" +
                    "</div>" +
                    '<div class="d-flex align-items-center gap-2">' +
                        '<span class="badge ' + badgeClass + '">' + pct + "%</span>" +
                        '<button class="btn btn-sm btn-primary" data-open-formation="' + f.formationId + '">' + (pct > 0 ? "Continuer" : "Commencer") + "</button>" +
                    "</div>" +
                "</div>"
            );
        }).join("");

        Array.prototype.forEach.call(list.querySelectorAll("[data-open-formation]"), function (btn) {
            btn.addEventListener("click", function () { openFirstLesson(parseInt(btn.getAttribute("data-open-formation"), 10)); });
        });
    }

    function openFirstLesson(formationId) {
        getJson("/api/training/formations/" + formationId + "/lessons").then(function (lessons) {
            if (!lessons || lessons.length === 0) {
                alert("Cette formation n'a pas encore de leçon.");
                return;
            }
            var firstIncomplete = lessons.find(function (l) { return !l.completed; }) || lessons[0];
            window.location.href = "/training/lesson/" + firstIncomplete.lessonId;
        });
    }

    // ===================== NAVIGATION CALENDRIER =====================

    Array.prototype.forEach.call(document.querySelectorAll("#scheduleViewToggle button"), function (btn) {
        btn.addEventListener("click", function () {
            Array.prototype.forEach.call(document.querySelectorAll("#scheduleViewToggle button"), function (b) { b.classList.remove("active"); });
            btn.classList.add("active");
            scheduleView = btn.getAttribute("data-view");
            renderAgenda();
        });
    });

    $("scheduleNavPrev").addEventListener("click", function () { navigate(-1); });
    $("scheduleNavNext").addEventListener("click", function () { navigate(1); });

    function navigate(delta) {
        if (scheduleView === "day") scheduleAnchor.setDate(scheduleAnchor.getDate() + delta);
        else if (scheduleView === "week") scheduleAnchor.setDate(scheduleAnchor.getDate() + delta * 7);
        else scheduleAnchor.setMonth(scheduleAnchor.getMonth() + delta);
        renderAgenda();
    }

    // ===================== QA : PROGRAMMATION =====================

    function populateFormationSelects() {
        var options = formationsCache.map(function (f) {
            return '<option value="' + f.formationId + '">' + escapeHtml(f.title) + " (" + f.scheduledDate + ")</option>";
        }).join("");
        var lessonsSelect = $("schedFormationSelect");
        var statsSelect = $("schedStatsFormationSelect");
        if (lessonsSelect) {
            var prevLessonsVal = lessonsSelect.value;
            lessonsSelect.innerHTML = '<option value="">— Choisir une formation —</option>' + options;
            if (prevLessonsVal) lessonsSelect.value = prevLessonsVal;
        }
        if (statsSelect) {
            var prevStatsVal = statsSelect.value;
            statsSelect.innerHTML = '<option value="">— Choisir une formation —</option>' + options;
            if (prevStatsVal) statsSelect.value = prevStatsVal;
        }
    }

    if ($("createScheduleBtn")) {
        $("createScheduleBtn").addEventListener("click", function () {
            var title = $("schedTitle").value.trim();
            var date = $("schedDate").value;
            if (!title || !date) {
                alert("Le titre et la date sont obligatoires.");
                return;
            }
            sendJson("/api/training/formations", "POST", {
                title: title,
                description: $("schedDescription").value.trim(),
                category: $("schedCategory").value.trim(),
                scheduledDate: date,
                scheduledTime: $("schedTime").value || null,
                recurrenceType: $("schedRecurrence").value,
                videoMaxPlaybackRate: 1.5,
                completionThresholdPercent: 95,
                status: "PLANNED",
                targetTeam: $("schedTeam").value || null,
                mandatory: $("schedMandatory").checked
            }).then(function () {
                $("schedTitle").value = "";
                $("schedDescription").value = "";
                $("schedCategory").value = "";
                $("schedDate").value = "";
                $("schedTime").value = "";
                loadFormations();
            }).catch(function (err) { alert("Erreur : " + err.message); });
        });
    }

    if ($("schedFormationSelect")) {
        $("schedFormationSelect").addEventListener("change", loadScheduleLessons);
    }

    function loadScheduleLessons() {
        var formationId = $("schedFormationSelect").value;
        var container = $("schedLessonsList");
        if (!formationId) {
            container.innerHTML = '<p class="text-muted small mb-0">Sélectionnez une formation ci-dessus.</p>';
            return;
        }
        getJson("/api/training/formations/" + formationId + "/lessons").then(function (lessons) {
            if (!lessons || lessons.length === 0) {
                container.innerHTML = '<p class="text-muted small mb-0">Aucune leçon pour l\'instant.</p>';
                return;
            }
            container.innerHTML = lessons.map(function (l) {
                return (
                    '<div class="d-flex justify-content-between align-items-center border rounded p-2">' +
                        '<span>' + (l.orderIndex + 1) + ". " + escapeHtml(l.title) + (l.videoUrl ? ' <i class="bi bi-camera-video text-muted"></i>' : "") + "</span>" +
                        '<button class="btn btn-sm btn-outline-danger" data-del-lesson="' + l.lessonId + '"><i class="bi bi-trash"></i></button>' +
                    "</div>"
                );
            }).join("");
            Array.prototype.forEach.call(container.querySelectorAll("[data-del-lesson]"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Supprimer cette leçon ?")) return;
                    sendJson("/api/training/lessons/" + btn.getAttribute("data-del-lesson"), "DELETE").then(loadScheduleLessons).then(loadFormations);
                });
            });
        });
    }

    if ($("addScheduleLessonBtn")) {
        $("addScheduleLessonBtn").addEventListener("click", function () {
            var formationId = $("schedFormationSelect").value;
            var title = $("schedLessonTitle").value.trim();
            if (!formationId) { alert("Choisissez d'abord une formation."); return; }
            if (!title) { alert("Le titre de la leçon est obligatoire."); return; }
            sendJson("/api/training/formations/" + formationId + "/lessons", "POST", {
                title: title,
                contentHtml: $("schedLessonContent").value,
                videoUrl: $("schedLessonVideoUrl").value.trim() || null
            }).then(function () {
                $("schedLessonTitle").value = "";
                $("schedLessonContent").value = "";
                $("schedLessonVideoUrl").value = "";
                loadScheduleLessons();
                loadFormations();
            }).catch(function (err) { alert("Erreur : " + err.message); });
        });
    }

    // ===================== QA : SUIVI DU PARCOURS =====================

    if ($("schedStatsFormationSelect")) {
        $("schedStatsFormationSelect").addEventListener("change", loadStats);
    }

    function loadStats() {
        var formationId = $("schedStatsFormationSelect").value;
        var teamBody = $("schedTeamStatsTable");
        var agentBody = $("schedAgentStatsTable");
        if (!formationId) {
            teamBody.innerHTML = '<tr><td colspan="5" class="text-muted text-center">Choisissez une formation programmée.</td></tr>';
            agentBody.innerHTML = '<tr><td colspan="5" class="text-muted text-center">—</td></tr>';
            return;
        }
        Promise.all([
            getJson("/api/training/formations/" + formationId + "/stats/teams"),
            getJson("/api/training/formations/" + formationId + "/stats/agents")
        ]).then(function (results) {
            var teams = results[0], agents = results[1];
            teamBody.innerHTML = teams.length ? teams.map(function (t) {
                return "<tr><td>" + escapeHtml(t.team) + "</td><td>" + t.agentCount + "</td><td>" + t.agentsCompletedCount +
                    "</td><td>" + t.agentsNotStartedCount + "</td><td><strong>" + t.averagePercent + "%</strong></td></tr>";
            }).join("") : '<tr><td colspan="5" class="text-muted text-center">Aucune donnée pour l\'instant.</td></tr>';

            agentBody.innerHTML = agents.length ? agents.map(function (a) {
                var statusBadge = a.status === "Terminé" ? "bg-success" : (a.status === "En cours" ? "bg-warning text-dark" : "bg-secondary");
                return "<tr><td>" + escapeHtml(a.name || a.username) + "</td><td>" + escapeHtml(a.team || "—") + "</td><td>" +
                    a.lessonsCompleted + "/" + a.totalLessons + "</td><td>" + a.overallPercent + "%</td><td><span class=\"badge " + statusBadge + "\">" + escapeHtml(a.status) + "</span></td></tr>";
            }).join("") : '<tr><td colspan="5" class="text-muted text-center">Aucun agent n\'a encore ouvert cette formation.</td></tr>';
        }).catch(function () {
            teamBody.innerHTML = '<tr><td colspan="5" class="text-danger text-center">Erreur de chargement.</td></tr>';
        });
    }

    // ===================== ÉQUIPES POUR LE SÉLECTEUR "Équipe cible" =====================

    function loadTeamOptions() {
        getJson("/api/teams").then(function (teams) {
            var select = $("schedTeam");
            if (!select || !teams) return;
            teams.forEach(function (t) {
                var opt = document.createElement("option");
                opt.value = t.name || t.code || t.label;
                opt.textContent = t.label || t.name || t.code;
                select.appendChild(opt);
            });
        }).catch(function () { /* endpoint optionnel selon config */ });
    }

    // ===================== INIT =====================

    if (window.RccSession) {
        window.RccSession.init().then(function (session) {
            if (session) currentProfile = session.profile;
            loadFormations();
        });
    } else {
        loadFormations();
    }
    loadTeamOptions();
})();
