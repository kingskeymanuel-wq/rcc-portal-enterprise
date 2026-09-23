"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };
    var criteriaCache = [];
    var evaluationsCache = [];
    var directoryCache = [];
    var currentPeriod = "all";
    var scoreEvolutionChart, statusChart, criteriaChart, teamChart;

    var getJson = RccApi.getJson;

    var sendJson = RccApi.sendJson;

    var escapeHtml = RccApi.escapeHtml;

    // ===== Upload audio =====

    function uploadAudioIfNeeded() {
        var fileInput = $("formAudioFile");
        if (!fileInput.files.length) return Promise.resolve(null);

        var formData = new FormData();
        formData.append("file", fileInput.files[0]);

        return fetch("/api/quality/recordings", { method: "POST", credentials: "same-origin", body: formData })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t)); });
                return res.json();
            })
            .then(function (data) { return data.recordingRef; });
    }

    // ===== KPI =====

    function renderKpis(evaluations) {
        var count = evaluations.length;
        var avgScore = count ? (evaluations.reduce(function (s, e) { return s + e.scorePercentage; }, 0) / count) : 0;
        var passCount = evaluations.filter(function (e) { return e.passed; }).length;
        var failCount = evaluations.filter(function (e) { return e.knockedOut; }).length;

        $("kpiCount").textContent = count;
        $("kpiAvgScore").textContent = count ? Math.round(avgScore) + "%" : "—";
        $("kpiPassRate").textContent = count ? Math.round((passCount / count) * 100) + "%" : "—";
        $("kpiFailCount").textContent = failCount;
    }

    // ===== Graphiques =====

    function renderScoreEvolutionChart(evaluations) {
        var sorted = evaluations.slice().sort(function (a, b) { return a.evaluationDate.localeCompare(b.evaluationDate); });
        var labels = sorted.map(function (e) { return e.evaluationDate; });
        var data = sorted.map(function (e) { return e.scorePercentage; });

        if (scoreEvolutionChart) scoreEvolutionChart.destroy();
        scoreEvolutionChart = new Chart($("scoreEvolutionChart"), {
            type: "line",
            data: { labels: labels, datasets: [{ label: "Score (%)", data: data, borderWidth: 2, tension: 0.3 }] },
            options: { responsive: true, maintainAspectRatio: false, scales: { y: { min: 0, max: 100 } } }
        });
    }

    function renderStatusChart(evaluations) {
        var ko = evaluations.filter(function (e) { return e.knockedOut; }).length;
        var conforme = evaluations.filter(function (e) { return e.passed; }).length;
        var aAmeliorer = evaluations.length - ko - conforme;

        if (statusChart) statusChart.destroy();
        statusChart = new Chart($("statusChart"), {
            type: "doughnut",
            data: {
                labels: ["Conforme", "À améliorer", "Échec (KO)"],
                datasets: [{ data: [conforme, aAmeliorer, ko], backgroundColor: ["#198754", "#ffc107", "#dc3545"] }]
            },
            options: { responsive: true, maintainAspectRatio: false }
        });
    }

    function renderCriteriaChart(evaluations) {
        var sums = {}, counts = {};
        evaluations.forEach(function (ev) {
            (ev.scores || []).forEach(function (s) {
                if (s.isNotApplicable || s.scoreValue == null) return;
                sums[s.criterionId] = (sums[s.criterionId] || 0) + s.scoreValue;
                counts[s.criterionId] = (counts[s.criterionId] || 0) + 1;
            });
        });

        var labels = criteriaCache.map(function (c) { return c.code; });
        var data = criteriaCache.map(function (c) {
            var count = counts[c.id] || 0;
            var avg = count ? (sums[c.id] / count) / 2 * 100 : 0; // score max = 2
            return Math.round(avg);
        });

        if (criteriaChart) criteriaChart.destroy();
        criteriaChart = new Chart($("criteriaChart"), {
            type: "bar",
            data: { labels: labels, datasets: [{ label: "% du maximum", data: data, backgroundColor: "#0d6efd" }] },
            options: { responsive: true, maintainAspectRatio: false, scales: { y: { min: 0, max: 100 } } }
        });
    }

    function renderTeamChart(evaluations) {
        var directoryByUsername = {};
        directoryCache.forEach(function (u) { directoryByUsername[u.username] = u; });

        var sums = {}, counts = {};
        evaluations.forEach(function (ev) {
            var user = directoryByUsername[ev.agentMatricule];
            var team = (user && user.service) ? user.service : "Sans équipe";
            sums[team] = (sums[team] || 0) + ev.scorePercentage;
            counts[team] = (counts[team] || 0) + 1;
        });

        var labels = Object.keys(sums).sort();
        var data = labels.map(function (team) { return Math.round(sums[team] / counts[team]); });

        if (teamChart) teamChart.destroy();
        teamChart = new Chart($("teamChart"), {
            type: "bar",
            data: { labels: labels, datasets: [{ label: "Score moyen (%)", data: data, backgroundColor: "#0d6efd" }] },
            options: { responsive: true, maintainAspectRatio: false, scales: { y: { min: 0, max: 100 } } }
        });
    }

    // ===== Filtre de période =====

    function filterByPeriod(evaluations) {
        if (currentPeriod === "all") return evaluations;
        var days = Number(currentPeriod);
        var cutoff = new Date();
        cutoff.setDate(cutoff.getDate() - days);
        var cutoffIso = cutoff.toISOString().slice(0, 10);
        return evaluations.filter(function (e) { return e.evaluationDate >= cutoffIso; });
    }

    function wirePeriodFilter() {
        var buttons = document.querySelectorAll("#periodFilter button");
        Array.prototype.forEach.call(buttons, function (btn) {
            btn.addEventListener("click", function () {
                Array.prototype.forEach.call(buttons, function (b) {
                    b.classList.remove("active", "btn-secondary");
                    b.classList.add("btn-outline-secondary");
                });
                btn.classList.remove("btn-outline-secondary");
                btn.classList.add("active", "btn-secondary");
                currentPeriod = btn.getAttribute("data-period");
                renderAll();
            });
        });
    }

    function renderAll() {
        var filtered = filterByPeriod(evaluationsCache);
        renderKpis(filtered);
        renderScoreEvolutionChart(filtered);
        renderStatusChart(filtered);
        renderTeamChart(filtered);
        renderCriteriaChart(filtered);
        renderEvaluationsTable(filtered);
    }

    function renderEvaluationsTable(evaluations) {
        var table = $("evaluationsTable");
        if (!evaluations.length) {
            table.innerHTML = '<tr><td colspan="8" class="text-muted text-center">Aucune évaluation pour l\'instant.</td></tr>';
            return;
        }
        var sorted = evaluations.slice().sort(function (a, b) { return b.evaluationDate.localeCompare(a.evaluationDate); });
        table.innerHTML = sorted.map(function (e) {
            var statusBadge = e.knockedOut
                ? '<span class="badge text-bg-danger">Échec (KO)</span>'
                : (e.passed ? '<span class="badge text-bg-success">Conforme</span>' : '<span class="badge text-bg-warning">À améliorer</span>');
            var player = e.recordingRef
                ? '<audio controls style="height:32px;max-width:200px;"><source src="' + e.recordingRef + '"></audio>'
                : '<span class="text-muted">—</span>';
            var aiBtn = e.recordingRef
                ? '<button class="btn btn-sm btn-outline-secondary ai-btn" data-id="' + e.id + '"><i class="bi bi-robot"></i></button>'
                : '<span class="text-muted">—</span>';
            return "<tr>" +
                "<td>" + escapeHtml(e.evaluationDate) + "</td>" +
                '<td><a href="#" class="agent-name-link" data-id="' + e.id + '">' + escapeHtml(e.agentName || e.agentMatricule) + '</a></td>' +
                "<td>" + Math.round(e.scorePercentage) + "%</td>" +
                "<td>" + statusBadge + "</td>" +
                "<td>" + escapeHtml(e.motifLabel || "—") + "</td>" +
                "<td>" + player + "</td>" +
                "<td>" + aiBtn + "</td>" +
                '<td class="text-end text-nowrap">' +
                '<button class="btn btn-sm btn-outline-secondary edit-eval-btn" data-id="' + e.id + '"><i class="bi bi-pencil"></i></button> ' +
                '<button class="btn btn-sm btn-outline-danger delete-eval-btn" data-id="' + e.id + '"><i class="bi bi-trash"></i></button>' +
                "</td>" +
                "</tr>";
        }).join("");

        Array.prototype.forEach.call(table.querySelectorAll(".ai-btn"), function (btn) {
            btn.addEventListener("click", function () { openAiModal(Number(btn.dataset.id)); });
        });
        Array.prototype.forEach.call(table.querySelectorAll(".agent-name-link"), function (link) {
            link.addEventListener("click", function (evt) {
                evt.preventDefault();
                openEditEvaluation(Number(link.dataset.id));
            });
        });
        Array.prototype.forEach.call(table.querySelectorAll(".edit-eval-btn"), function (btn) {
            btn.addEventListener("click", function () { openEditEvaluation(Number(btn.dataset.id)); });
        });
        Array.prototype.forEach.call(table.querySelectorAll(".delete-eval-btn"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer cette évaluation ? Cette action est irréversible.")) return;
                sendJson("/api/quality/evaluations/" + btn.dataset.id, "DELETE")
                    .then(loadAll)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    // ===== Modifier une évaluation existante =====

    var currentEditEvaluationId = null;

    function openEditEvaluation(evaluationId) {
        getJson("/api/quality/evaluations/" + evaluationId).then(function (ev) {
            currentEditEvaluationId = evaluationId;

            $("evaluationFormCard").style.display = "";
            $("evaluationFormCard").scrollIntoView({ behavior: "smooth" });

            $("formAgentMatricule").value = ev.agentMatricule;
            $("formAgentMatricule").dispatchEvent(new Event("input"));
            $("formCallDate").value = ev.callDate;
            $("formMotif").value = ev.motifId || "";
            $("formDuration").value = ev.durationMinutes || "";
            $("formStrengths").value = ev.strengths || "";
            $("formImprovements").value = ev.improvements || "";
            $("formComment").value = ev.comment || "";

            renderCriteriaForm(criteriaCache);
            (ev.scores || []).forEach(function (s) {
                var value = s.isNotApplicable ? "na" : s.scoreValue;
                currentScores[s.criterionId] = value;
                var group = document.querySelector('.qa-score-group[data-criterion-id="' + s.criterionId + '"]');
                if (!group) return;
                Array.prototype.forEach.call(group.querySelectorAll(".qa-score-btn"), function (b) { b.className = "qa-score-btn"; });
                var btn = group.querySelector('[data-value="' + value + '"]');
                if (btn) btn.classList.add("active-" + value);
            });
            updateLiveScore();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    // ===== Modale IA (transcription / analyse — 100% embarqué, rien n'est stocké) =====

    var currentAiEvaluationId = null;
    var currentTranscript = null;

    function setAiStatus(message, isError) {
        var box = $("aiStatus");
        if (!message) { box.style.display = "none"; return; }
        box.textContent = message;
        box.className = "alert " + (isError ? "alert-danger" : "alert-info");
        box.style.display = "";
    }

    function renderAnalysis(result) {
        $("aiAnalysis").innerHTML = '<div style="white-space:pre-wrap;">' + escapeHtml(result.analysis) + '</div>';
    }

    function openAiModal(evaluationId) {
        currentAiEvaluationId = evaluationId;
        currentTranscript = null;
        setAiStatus(null);
        $("aiTranscript").textContent = "Cliquez sur « Transcrire l'appel » pour commencer.";
        $("aiAnalysis").textContent = "—";
        new bootstrap.Modal($("aiModal")).show();
    }

    function wireAiButtons() {
        $("transcribeBtn").addEventListener("click", function () {
            if (!currentAiEvaluationId) return;
            setAiStatus("Transcription en cours (traitement local, peut prendre un moment selon la durée de l'appel)…", false);
            sendJson("/api/quality/evaluations/" + currentAiEvaluationId + "/ai/transcribe", "POST")
                .then(function (result) {
                    setAiStatus(null);
                    currentTranscript = result.transcript;
                    $("aiTranscript").textContent = result.transcript || "(transcription vide)";
                })
                .catch(function (e) { setAiStatus("Erreur : " + e.message, true); });
        });

        $("analyzeBtn").addEventListener("click", function () {
            if (!currentAiEvaluationId) return;
            if (!currentTranscript) { setAiStatus("Transcrivez d'abord l'appel.", true); return; }
            setAiStatus("Analyse en cours…", false);
            sendJson("/api/quality/evaluations/" + currentAiEvaluationId + "/ai/analyze", "POST", { transcript: currentTranscript })
                .then(function (result) { setAiStatus(null); renderAnalysis(result); })
                .catch(function (e) { setAiStatus("Erreur : " + e.message, true); });
        });
    }

    // ===== Chargement =====

    function loadAll() {
        Promise.all([
            getJson("/api/quality/criteria"),
            getJson("/api/quality/motifs"),
            getJson("/api/quality/evaluations"),
            getJson("/api/users/directory")
        ]).then(function (results) {
            criteriaCache = results[0];
            evaluationsCache = results[2];
            directoryCache = results[3];

            renderAll();

            $("formMotif").innerHTML = '<option value="">— Aucun motif —</option>' +
                results[1].map(function (m) { return '<option value="' + m.id + '">' + escapeHtml(m.label) + '</option>'; }).join("");

            renderCriteriaForm(criteriaCache);
        }).catch(function (e) { console.error(e); });
    }

    // ===== Formulaire de création (QA/admin uniquement) =====

    var currentScores = {}; // criterionId -> 0 | 1 | 2 | "na"

    function renderCriteriaForm(criteria) {
        currentScores = {};
        var container = $("criteriaFormList");
        var sorted = criteria.slice().sort(function (a, b) { return a.sortOrder - b.sortOrder; });

        var html = "";
        var lastSection = null;
        sorted.forEach(function (c) {
            if (c.section !== lastSection) {
                html += '<div class="qa-section-title">' + escapeHtml(c.section) + '</div>';
                lastSection = c.section;
            }
            var koTag = c.isKnockOut ? '<span class="ko-tag">KO</span>' : "";
            html += '<div class="qa-criterion" data-criterion-id="' + c.id + '">' +
                '<div>' +
                '<div class="qa-criterion-code">' + escapeHtml(c.code) + koTag + '</div>' +
                '<div class="qa-criterion-name">' + escapeHtml(c.name) + '</div>' +
                '<div class="qa-criterion-desc">' + escapeHtml(c.description || "") + ' · ' + c.weight + '%</div>' +
                '</div>' +
                '<div class="qa-score-group" data-criterion-id="' + c.id + '">' +
                [2, 1, 0].map(function (v) {
                    return '<button type="button" class="qa-score-btn" data-value="' + v + '">' + v + '</button>';
                }).join("") +
                '<button type="button" class="qa-score-btn" data-value="na">N/A</button>' +
                '</div>' +
                '</div>';
        });
        container.innerHTML = html;

        Array.prototype.forEach.call(container.querySelectorAll(".qa-score-group"), function (group) {
            var criterionId = Number(group.getAttribute("data-criterion-id"));
            Array.prototype.forEach.call(group.querySelectorAll(".qa-score-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    currentScores[criterionId] = btn.dataset.value === "na" ? "na" : Number(btn.dataset.value);
                    Array.prototype.forEach.call(group.querySelectorAll(".qa-score-btn"), function (b) {
                        b.className = "qa-score-btn";
                    });
                    btn.classList.add("active-" + btn.dataset.value);
                    updateLiveScore();
                });
            });
        });

        updateLiveScore();
    }

    /** Réplique QualityScoreCalculator côté client, pour un retour instantané avant enregistrement. */
    function updateLiveScore() {
        var criteria = criteriaCache;
        var scoredIds = Object.keys(currentScores);
        var counted = 0, weightedObtained = 0, weightedMax = 0, knockedOut = false;
        var tally = { conforme: 0, partiel: 0, nonConforme: 0, na: 0 };

        criteria.forEach(function (c) {
            var value = currentScores[c.id];
            if (value === undefined) return;

            if (value === "na") { tally.na++; return; }

            counted++;
            if (value === 2) tally.conforme++;
            else if (value === 1) tally.partiel++;
            else tally.nonConforme++;

            if (c.isKnockOut && value === 0) knockedOut = true;

            weightedObtained += value * c.weight;
            weightedMax += 2 * c.weight;
        });

        var percentage = weightedMax > 0 ? Math.round((weightedObtained / weightedMax) * 100) : null;
        var allScored = scoredIds.length >= criteria.length && criteria.length > 0;

        $("tallySummary").textContent =
            tally.conforme + " conforme · " + tally.partiel + " partiel · " + tally.nonConforme + " non conforme · " + tally.na + " N/A";
        $("qaCriteriaCounted").textContent = scoredIds.length + " / " + criteria.length;
        $("qaKoWarning").style.display = knockedOut ? "" : "none";

        var gauge = $("qaGauge");
        var valueEl = $("qaGaugeValue");
        var badge = $("qaStatusBadge");

        if (percentage === null || scoredIds.length === 0) {
            gauge.style.background = "conic-gradient(#e9ecef 0deg 360deg)";
            valueEl.textContent = "—";
            badge.textContent = "● À évaluer";
            badge.className = "qa-status-badge qa-status-eval";
            return;
        }

        var passed = !knockedOut && percentage >= 80;
        var color = knockedOut ? "#dc3545" : (passed ? "#0f9d6c" : (percentage >= 50 ? "#fd7e14" : "#dc3545"));
        var degrees = Math.min(100, Math.max(0, percentage)) * 3.6;
        gauge.style.background = "conic-gradient(" + color + " 0deg " + degrees + "deg, #e9ecef " + degrees + "deg 360deg)";
        valueEl.textContent = percentage + "%";

        if (!allScored) {
            badge.textContent = "● En cours";
            badge.className = "qa-status-badge qa-status-eval";
        } else if (passed) {
            badge.textContent = "● Conforme";
            badge.className = "qa-status-badge qa-status-conforme";
        } else {
            badge.textContent = "● Non conforme";
            badge.className = "qa-status-badge qa-status-non-conforme";
        }
    }

    // ===== Recherche automatique du nom d'agent =====

    function loadAgentTeamInfo(username) {
        var box = $("formAgentTeamInfo");
        box.innerHTML = '<span class="text-muted"><i class="bi bi-hourglass-split"></i> Chargement de l\'équipe…</span>';
        getJson("/api/users/" + encodeURIComponent(username) + "/team-info").then(function (info) {
            var teamLeaderLine = info.teamLeaderName
                ? "Team Leader : <strong>" + escapeHtml(info.teamLeaderName) + "</strong>"
                : '<span class="text-warning">Aucun Team Leader désigné pour cette équipe.</span>';
            box.innerHTML = '<i class="bi bi-people-fill"></i> Équipe : <strong>' + escapeHtml(info.teamLabel) + "</strong> — " + teamLeaderLine;
        }).catch(function () {
            box.innerHTML = '<span class="text-muted">Équipe non déterminée.</span>';
        });
    }

    function wireAgentLookup() {
        var input = $("formAgentMatricule");
        var preview = $("formAgentNamePreview");

        input.addEventListener("input", function () {
            var term = input.value.trim().toLowerCase();
            if (!term) { preview.textContent = ""; preview.className = "form-text"; return; }

            var match = directoryCache.filter(function (u) { return (u.username || "").toLowerCase() === term; })[0];
            if (match) {
                preview.textContent = "✓ " + match.fullName + (match.service ? " — " + match.service : "");
                preview.className = "form-text text-success";
                loadAgentTeamInfo(match.username);
                return;
            }
            $("formAgentTeamInfo").innerHTML = "";

            var partial = directoryCache.filter(function (u) { return (u.username || "").toLowerCase().indexOf(term) !== -1; }).slice(0, 5);
            if (partial.length) {
                preview.innerHTML = partial.map(function (u) {
                    return '<a href="#" class="agent-suggestion me-2" data-username="' + escapeHtml(u.username) + '">' +
                        escapeHtml(u.fullName) + ' (' + escapeHtml(u.username) + ')</a>';
                }).join("");
                preview.className = "form-text";
                Array.prototype.forEach.call(preview.querySelectorAll(".agent-suggestion"), function (link) {
                    link.addEventListener("click", function (evt) {
                        evt.preventDefault();
                        input.value = link.getAttribute("data-username");
                        input.dispatchEvent(new Event("input"));
                    });
                });
            } else {
                preview.textContent = "Aucun agent trouvé pour ce matricule.";
                preview.className = "form-text text-danger";
            }
        });
    }

    // ===== Aperçu audio local (avant envoi, pour écouter en notant) =====

    function wireAudioPreview() {
        var fileInput = $("formAudioFile");
        var player = $("formAudioPreview");

        fileInput.addEventListener("change", function () {
            var file = fileInput.files[0];
            if (!file) { player.style.display = "none"; player.src = ""; return; }
            player.src = URL.createObjectURL(file);
            player.style.display = "";
        });
    }

    $("newEvaluationBtn").addEventListener("click", function () {
        currentEditEvaluationId = null;
        $("formAgentMatricule").value = "";
        $("formCallDate").value = "";
        $("formDuration").value = "";
        $("formStrengths").value = "";
        $("formImprovements").value = "";
        $("formComment").value = "";
        $("formAgentNamePreview").textContent = ""; $("formAgentTeamInfo").innerHTML = "";
        renderCriteriaForm(criteriaCache);
        $("evaluationFormCard").style.display = "";
        $("evaluationFormCard").scrollIntoView({ behavior: "smooth" });
    });

    $("cancelEvaluationBtn").addEventListener("click", function () {
        currentEditEvaluationId = null;
        $("evaluationFormCard").style.display = "none";
    });

    $("resetEvaluationBtn").addEventListener("click", function () {
        renderCriteriaForm(criteriaCache);
        $("formStrengths").value = "";
        $("formImprovements").value = "";
        $("formComment").value = "";
    });

    $("submitEvaluationBtn").addEventListener("click", function () {
        var agentMatricule = $("formAgentMatricule").value.trim();
        var callDate = $("formCallDate").value;
        var motifId = $("formMotif").value ? Number($("formMotif").value) : null;

        if (!agentMatricule || !callDate) { alert("Matricule agent et date de l'appel sont obligatoires."); return; }

        var scores = criteriaCache.map(function (c) {
            var value = currentScores[c.id];
            if (value === undefined) return null;
            var isNa = value === "na";
            return { criterionId: c.id, scoreValue: isNa ? null : value, isNotApplicable: isNa };
        }).filter(function (s) { return s !== null; });

        if (scores.length < criteriaCache.length) { alert("Veuillez noter tous les critères."); return; }

        var today = new Date().toISOString().slice(0, 10);
        var duration = $("formDuration").value ? Number($("formDuration").value) : null;

        uploadAudioIfNeeded().then(function (recordingRef) {
            var payload = {
                agentMatricule: agentMatricule,
                evaluationDate: today,
                callDate: callDate,
                motifId: motifId,
                recordingRef: recordingRef,
                durationMinutes: duration,
                strengths: $("formStrengths").value.trim() || null,
                improvements: $("formImprovements").value.trim() || null,
                comment: $("formComment").value.trim() || null,
                scores: scores
            };
            return currentEditEvaluationId
                ? sendJson("/api/quality/evaluations/" + currentEditEvaluationId, "PUT", payload)
                : sendJson("/api/quality/evaluations", "POST", payload);
        }).then(function () {
            currentEditEvaluationId = null;
            $("evaluationFormCard").style.display = "none";
            $("formAgentMatricule").value = "";
            $("formCallDate").value = "";
            $("formAudioFile").value = "";
            $("formAgentNamePreview").textContent = ""; $("formAgentTeamInfo").innerHTML = "";
            $("formAudioPreview").style.display = "none";
            $("formAudioPreview").src = "";
            $("formDuration").value = "";
            $("formStrengths").value = "";
            $("formImprovements").value = "";
            $("formComment").value = "";
            renderCriteriaForm(criteriaCache);
            loadAll();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===== Coaching =====

    function loadCoaching() {
        getJson("/api/coaching-plans").then(function (plans) {
            $("coachingList").innerHTML = plans.map(function (p) {
                var statusBadge = p.status === "done" ? '<span class="badge bg-success">Fait</span>' :
                    p.status === "in_progress" ? '<span class="badge bg-warning">En cours</span>' :
                    '<span class="badge bg-secondary">À faire</span>';
                return '<div class="border rounded p-2 small d-flex justify-content-between align-items-start">' +
                    '<div><strong>' + escapeHtml(p.agentName || p.agentMatricule) + '</strong> — ' + escapeHtml(p.axis) +
                    '<div class="text-muted">Échéance : ' + p.dueDate + (p.note ? ' · ' + escapeHtml(p.note) : '') + '</div></div>' +
                    '<div>' + statusBadge + ' <button class="btn btn-sm btn-outline-danger delete-coaching-btn" data-id="' + p.id + '"><i class="bi bi-trash"></i></button></div>' +
                    '</div>';
            }).join("") || '<p class="text-muted small">Aucun plan de coaching.</p>';

            Array.prototype.forEach.call($("coachingList").querySelectorAll(".delete-coaching-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Supprimer ce plan ?")) return;
                    sendJson("/api/coaching-plans/" + btn.dataset.id, "DELETE").then(loadCoaching).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function (e) { $("coachingList").innerHTML = '<p class="text-danger small">Erreur : ' + e.message + '</p>'; });
    }

    $("newCoachingBtn").addEventListener("click", function () {
        $("coachingForm").classList.toggle("d-none");
    });

    $("saveCoachingBtn").addEventListener("click", function () {
        var agentMatricule = $("coachingAgent").value.trim();
        var axis = $("coachingAxis").value.trim();
        var dueDate = $("coachingDueDate").value;
        if (!agentMatricule || !axis || !dueDate) { alert("Agent, axe et échéance sont obligatoires."); return; }
        sendJson("/api/coaching-plans", "POST", {
            agentMatricule: agentMatricule, axis: axis, dueDate: dueDate,
            status: "todo", note: $("coachingNote").value.trim() || null
        }).then(function () {
            $("coachingAgent").value = ""; $("coachingAxis").value = ""; $("coachingDueDate").value = ""; $("coachingNote").value = "";
            $("coachingForm").classList.add("d-none");
            loadCoaching();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===== Dossiers agents =====

    function loadDossiers() {
        getJson("/api/agent-dossiers").then(function (dossiers) {
            $("dossierList").innerHTML = dossiers.map(function (d) {
                return '<div class="border rounded p-2 small d-flex justify-content-between align-items-start">' +
                    '<div><strong>' + escapeHtml(d.linkedUserName || d.linkedUserMatricule || "Sans agent lié") + '</strong>' +
                    ' <span class="badge bg-light text-dark border">' + escapeHtml(d.source) + '</span>' +
                    '<div class="text-muted">' + escapeHtml((d.payload || "").slice(0, 120)) + '</div></div>' +
                    '<button class="btn btn-sm btn-outline-danger delete-dossier-btn" data-id="' + d.id + '"><i class="bi bi-trash"></i></button>' +
                    '</div>';
            }).join("") || '<p class="text-muted small">Aucun dossier.</p>';

            Array.prototype.forEach.call($("dossierList").querySelectorAll(".delete-dossier-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    if (!confirm("Supprimer ce dossier ?")) return;
                    sendJson("/api/agent-dossiers/" + btn.dataset.id, "DELETE").then(loadDossiers).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }).catch(function (e) { $("dossierList").innerHTML = '<p class="text-danger small">Erreur : ' + e.message + '</p>'; });
    }

    $("saveDossierBtn").addEventListener("click", function () {
        var source = $("dossierSource").value.trim();
        var payload = $("dossierPayload").value.trim();
        if (!source || !payload) { alert("Source et contenu sont obligatoires."); return; }
        sendJson("/api/agent-dossiers", "POST", {
            linkedUserMatricule: $("dossierAgent").value.trim() || null,
            source: source, payload: payload
        }).then(function () {
            $("dossierAgent").value = ""; $("dossierSource").value = ""; $("dossierPayload").value = "";
            loadDossiers();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===== Performance (par service / par équipe) — réutilise /api/reporting/team =====

    var qaPerfCache = [];
    var qaPerfLoaded = false;

    function qaPerfMonthValue() {
        var now = new Date();
        return now.getFullYear() + "-" + String(now.getMonth() + 1).padStart(2, "0");
    }

    function fmtPct(v) { return v != null ? v + " %" : "—"; }

    function renderPerfByService(rows) {
        var groups = {};
        rows.forEach(function (r) {
            var service = r.serviceName || "Sans service";
            groups[service] = groups[service] || [];
            groups[service].push(r);
        });

        var body = $("qaPerfByServiceBody");
        var keys = Object.keys(groups).sort();
        if (!keys.length) { body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Aucune donnée.</td></tr>'; return; }

        body.innerHTML = keys.map(function (service) {
            var agents = groups[service];
            var avg = function (field) {
                var values = agents.map(function (a) { return a[field]; }).filter(function (v) { return v != null; });
                if (!values.length) return null;
                return Math.round((values.reduce(function (s, v) { return s + v; }, 0) / values.length) * 100) / 100;
            };
            return "<tr><td>" + escapeHtml(service) + "</td>" +
                "<td>" + agents.length + "</td>" +
                "<td>" + fmtPct(avg("presenceRate")) + "</td>" +
                "<td>" + fmtPct(avg("avgQualityScore")) + "</td>" +
                "<td>" + fmtPct(avg("performanceGlobale")) + "</td></tr>";
        }).join("");
    }

    function populateTeamSelect(rows) {
        var teams = Array.from(new Set(rows.map(function (r) { return r.activity || "Sans équipe"; }))).sort();
        $("qaPerfTeamSelect").innerHTML = '<option value="">— Choisir une équipe —</option>' +
            teams.map(function (t) { return '<option value="' + escapeHtml(t) + '">' + escapeHtml(t) + "</option>"; }).join("");
    }

    function renderPerfByTeam(rows, team) {
        var body = $("qaPerfByTeamBody");
        if (!team) { body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Choisissez une équipe.</td></tr>'; return; }
        var filtered = rows.filter(function (r) { return (r.activity || "Sans équipe") === team; });
        if (!filtered.length) { body.innerHTML = '<tr><td colspan="5" class="text-center text-muted">Aucun agent dans cette équipe.</td></tr>'; return; }
        body.innerHTML = filtered.map(function (r) {
            return "<tr><td>" + escapeHtml(r.userFullName || r.username) + "</td>" +
                "<td>" + fmtPct(r.presenceRate) + "</td>" +
                "<td>" + fmtPct(r.avgQualityScore) + "</td>" +
                "<td>" + r.evaluationCount + "</td>" +
                "<td>" + fmtPct(r.performanceGlobale) + "</td></tr>";
        }).join("");
    }

    function loadQaPerformance() {
        getJson("/api/reporting/team?month=" + encodeURIComponent($("qaPerfMonth").value))
            .then(function (rows) {
                qaPerfCache = rows;
                renderPerfByService(rows);
                populateTeamSelect(rows);
                renderPerfByTeam(rows, $("qaPerfTeamSelect").value);
            })
            .catch(function (e) {
                $("qaPerfByServiceBody").innerHTML = '<tr><td colspan="5" class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</td></tr>';
            });
    }

    function wireQaPerformanceTab() {
        $("qaPerfMonth").value = qaPerfMonthValue();

        $("qaOverviewTabBtn").addEventListener("click", function () {
            $("qaOverviewTabBtn").classList.add("active");
            $("qaPerformanceTabBtn").classList.remove("active");
            $("qaOverviewPane").style.display = "";
            $("qaPerformancePane").style.display = "none";
        });
        $("qaPerformanceTabBtn").addEventListener("click", function () {
            $("qaPerformanceTabBtn").classList.add("active");
            $("qaOverviewTabBtn").classList.remove("active");
            $("qaOverviewPane").style.display = "none";
            $("qaPerformancePane").style.display = "";
            if (!qaPerfLoaded) { qaPerfLoaded = true; loadQaPerformance(); }
        });

        $("qaPerfByServiceBtn").addEventListener("click", function () {
            $("qaPerfByServiceBtn").classList.add("active", "btn-secondary");
            $("qaPerfByServiceBtn").classList.remove("btn-outline-secondary");
            $("qaPerfByTeamBtn").classList.remove("active", "btn-secondary");
            $("qaPerfByTeamBtn").classList.add("btn-outline-secondary");
            $("qaPerfByServiceView").style.display = "";
            $("qaPerfByTeamView").style.display = "none";
        });
        $("qaPerfByTeamBtn").addEventListener("click", function () {
            $("qaPerfByTeamBtn").classList.add("active", "btn-secondary");
            $("qaPerfByTeamBtn").classList.remove("btn-outline-secondary");
            $("qaPerfByServiceBtn").classList.remove("active", "btn-secondary");
            $("qaPerfByServiceBtn").classList.add("btn-outline-secondary");
            $("qaPerfByTeamView").style.display = "";
            $("qaPerfByServiceView").style.display = "none";
        });

        $("qaPerfMonth").addEventListener("change", loadQaPerformance);
        $("qaPerfTeamSelect").addEventListener("change", function () {
            renderPerfByTeam(qaPerfCache, this.value);
        });
    }

    // ===== Init =====

    window.RccSession.init().then(function (session) {
        var isQaOrAdmin = session && (session.profile === "QA" || session.profile === "ADMIN");
        $("newEvaluationBtn").style.display = isQaOrAdmin ? "" : "none";
        if (session && session.user) {
            $("formEvaluatorDisplay").value = session.user.name || session.user.username;
        }
    });

    wirePeriodFilter();
    wireAgentLookup();
    wireAudioPreview();
    wireAiButtons();
    wireQaPerformanceTab();
    loadAll();
    loadCoaching();
    loadDossiers();
})();