"use strict";

/**
 * Banque de questions enrichie — /api/quiz-questions (QuizQuestionController).
 * Distincte du système de questions par cours existant (/api/courses/.../questions),
 * conservé tel quel pour la compatibilité. Cette banque alimentera aussi les jeux.
 */
(function () {
    var $ = function (id) { return document.getElementById(id); };
    if (!$("qbTable")) return;

    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var questionsCache = [];
    var qbModal = null;
    var optionRowCount = 0;

    var DIFFICULTY_LABELS = { EASY: "Facile", MEDIUM: "Moyen", HARD: "Difficile", EXPERT: "Expert" };
    var DIFFICULTY_BADGES = { EASY: "bg-success", MEDIUM: "bg-info text-dark", HARD: "bg-warning text-dark", EXPERT: "bg-danger" };
    var TYPE_LABELS = { MCQ: "Choix unique", TRUE_FALSE: "Vrai / Faux", MULTI_SELECT: "Choix multiples" };

    document.addEventListener("DOMContentLoaded", function () {
        qbModal = new bootstrap.Modal($("qbModal"));
    });

    // ===================== CHARGEMENT / RENDU LISTE =====================

    function loadQuestions() {
        var params = new URLSearchParams();
        var cat = $("qbFilterCategory").value, diff = $("qbFilterDifficulty").value,
            type = $("qbFilterType").value, search = $("qbSearch").value;
        if (cat) params.set("category", cat);
        if (diff) params.set("difficulty", diff);
        if (type) params.set("type", type);
        if (search) params.set("search", search);
        getJson("/api/quiz-questions?" + params.toString()).then(function (list) {
            questionsCache = list || [];
            renderTable();
        }).catch(function () {
            $("qbTable").innerHTML = '<tr><td colspan="8" class="text-danger text-center">Impossible de charger la banque de questions.</td></tr>';
        });
    }

    function loadCategories() {
        getJson("/api/quiz-questions/categories").then(function (cats) {
            var filterSelect = $("qbFilterCategory");
            var datalist = $("qbCategoryList");
            var options = (cats || []).map(function (c) { return '<option value="' + escapeHtml(c) + '"></option>'; }).join("");
            datalist.innerHTML = options;
            var filterOptions = (cats || []).map(function (c) { return '<option value="' + escapeHtml(c) + '">' + escapeHtml(c) + "</option>"; }).join("");
            filterSelect.innerHTML = '<option value="">Tous les thèmes</option>' + filterOptions;
        });
    }

    function renderTable() {
        var tbody = $("qbTable");
        if (questionsCache.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" class="text-muted text-center">Aucune question. Cliquez sur "Nouvelle question" pour commencer.</td></tr>';
            return;
        }
        tbody.innerHTML = questionsCache.map(function (q) {
            var successRate = q.usageCount > 0 ? Math.round((q.correctAnswerCount / q.usageCount) * 100) + "%" : "—";
            var diffBadge = DIFFICULTY_BADGES[q.difficulty] || "bg-secondary";
            var activeMark = q.active ? "" : ' <span class="badge bg-secondary">Inactive</span>';
            var videoMark = q.videoUrl ? ' <i class="bi bi-camera-video-fill text-primary" title="Vidéo associée"></i>' : "";
            var imageMark = q.imageUrl ? ' <i class="bi bi-image-fill text-success" title="Image associée"></i>' : "";
            return (
                "<tr>" +
                    "<td>" + escapeHtml(q.questionText.length > 80 ? q.questionText.substring(0, 80) + "…" : q.questionText) + activeMark + videoMark + imageMark + "</td>" +
                    "<td>" + (q.category ? escapeHtml(q.category) : '<span class="text-muted">—</span>') + "</td>" +
                    '<td><span class="badge ' + diffBadge + '">' + (DIFFICULTY_LABELS[q.difficulty] || q.difficulty) + "</span></td>" +
                    "<td>" + (TYPE_LABELS[q.type] || q.type) + "</td>" +
                    "<td>" + q.points + "</td>" +
                    "<td>" + q.usageCount + "</td>" +
                    "<td>" + successRate + "</td>" +
                    '<td class="text-end">' +
                        '<button class="btn btn-sm btn-outline-secondary" data-edit="' + q.questionId + '"><i class="bi bi-pencil"></i></button> ' +
                        '<button class="btn btn-sm btn-outline-secondary" data-dup="' + q.questionId + '"><i class="bi bi-files"></i></button> ' +
                        '<button class="btn btn-sm btn-outline-danger" data-del="' + q.questionId + '"><i class="bi bi-trash"></i></button>' +
                    "</td>" +
                "</tr>"
            );
        }).join("");

        Array.prototype.forEach.call(tbody.querySelectorAll("[data-edit]"), function (btn) {
            btn.addEventListener("click", function () { openEditModal(parseInt(btn.getAttribute("data-edit"), 10)); });
        });
        Array.prototype.forEach.call(tbody.querySelectorAll("[data-dup]"), function (btn) {
            btn.addEventListener("click", function () {
                sendJson("/api/quiz-questions/" + btn.getAttribute("data-dup") + "/duplicate", "POST").then(loadQuestions);
            });
        });
        Array.prototype.forEach.call(tbody.querySelectorAll("[data-del]"), function (btn) {
            btn.addEventListener("click", function () {
                if (!confirm("Supprimer définitivement cette question ?")) return;
                sendJson("/api/quiz-questions/" + btn.getAttribute("data-del"), "DELETE").then(loadQuestions);
            });
        });
    }

    ["qbFilterCategory", "qbFilterDifficulty", "qbFilterType"].forEach(function (id) {
        $(id).addEventListener("change", loadQuestions);
    });
    var searchTimer;
    $("qbSearch").addEventListener("input", function () {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(loadQuestions, 350);
    });

    // ===================== OPTIONS DYNAMIQUES (selon le type) =====================

    function renderOptionRows(options, correctOptionIndex, correctIndexes, type) {
        var container = $("qbOptionsContainer");
        container.innerHTML = "";
        optionRowCount = 0;
        var opts = options && options.length ? options.slice() : (type === "TRUE_FALSE" ? ["Vrai", "Faux"] : ["", ""]);
        opts.forEach(function (label, idx) {
            addOptionRow(label, type === "MULTI_SELECT" ? (correctIndexes || []).indexOf(idx) >= 0 : correctOptionIndex === idx);
        });
    }

    function addOptionRow(value, isCorrect) {
        var type = $("qbType").value;
        var idx = optionRowCount++;
        var row = document.createElement("div");
        row.className = "d-flex align-items-center gap-2 qb-option-row";
        row.setAttribute("data-idx", idx);
        var inputType = type === "MULTI_SELECT" ? "checkbox" : "radio";
        row.innerHTML =
            '<input type="' + inputType + '" name="qbCorrectAnswer" class="form-check-input mt-0 qb-correct-marker" ' + (isCorrect ? "checked" : "") + '>' +
            '<input type="text" class="form-control form-control-sm qb-option-text" value="' + escapeHtml(value || "") + '" placeholder="Option de réponse">' +
            '<button type="button" class="btn btn-sm btn-outline-danger qb-remove-option"><i class="bi bi-x"></i></button>';
        row.querySelector(".qb-remove-option").addEventListener("click", function () { row.remove(); });
        $("qbOptionsContainer").appendChild(row);
    }

    $("qbAddOptionBtn").addEventListener("click", function () { addOptionRow("", false); });

    $("qbType").addEventListener("change", function () {
        var type = $("qbType").value;
        var markers = $("qbOptionsContainer").querySelectorAll(".qb-correct-marker");
        Array.prototype.forEach.call(markers, function (m) {
            m.type = type === "MULTI_SELECT" ? "checkbox" : "radio";
            m.checked = false;
        });
        if (type === "TRUE_FALSE") {
            renderOptionRows(["Vrai", "Faux"], 0, [], type);
        }
    });

    $("qbImageFile").addEventListener("change", function () {
        var file = this.files[0];
        if (!file) return;
        $("qbImageStatus").innerHTML = '<i class="bi bi-arrow-repeat"></i> Envoi en cours...';
        var formData = new FormData();
        formData.append("file", file);
        fetch("/api/mon-rcc/media/upload", { method: "POST", credentials: "same-origin", body: formData })
            .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
            .then(function (result) {
                $("qbImageUrl").value = result.url;
                $("qbImageStatus").innerHTML = '<i class="bi bi-check-circle text-success"></i> Image importée';
            })
            .catch(function (e) {
                $("qbImageStatus").innerHTML = '<i class="bi bi-x-circle text-danger"></i> Échec de l\'import';
                alert("Erreur d'import image : " + e.message);
            });
    });

    $("qbVideoFile").addEventListener("change", function () {
        var file = this.files[0];
        if (!file) return;
        $("qbVideoStatus").innerHTML = '<i class="bi bi-arrow-repeat"></i> Envoi en cours...';
        var formData = new FormData();
        formData.append("file", file);
        fetch("/api/mon-rcc/media/upload", { method: "POST", credentials: "same-origin", body: formData })
            .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
            .then(function (result) {
                $("qbVideoUrl").value = result.url;
                $("qbVideoStatus").innerHTML = '<i class="bi bi-check-circle text-success"></i> Vidéo importée';
            })
            .catch(function (e) {
                $("qbVideoStatus").innerHTML = '<i class="bi bi-x-circle text-danger"></i> Échec de l\'import';
                alert("Erreur d'import vidéo : " + e.message);
            });
    });

    // ===================== CRÉATION / ÉDITION =====================

    $("qbNewQuestionBtn").addEventListener("click", function () {
        $("qbModalTitle").textContent = "Nouvelle question";
        $("qbEditId").value = "";
        $("qbQuestionText").value = "";
        $("qbType").value = "MCQ";
        $("qbDifficulty").value = "MEDIUM";
        $("qbCategory").value = "";
        $("qbPoints").value = "10";
        $("qbExplanation").value = "";
        $("qbImageUrl").value = "";
        $("qbImageFile").value = "";
        $("qbImageStatus").textContent = "";
        $("qbVideoUrl").value = "";
        $("qbVideoFile").value = "";
        $("qbVideoStatus").textContent = "";
        $("qbTags").value = "";
        $("qbTimeLimit").value = "";
        $("qbActive").checked = true;
        renderOptionRows(["", ""], 0, [], "MCQ");
        qbModal.show();
    });

    function openEditModal(id) {
        var q = questionsCache.find(function (x) { return x.questionId === id; });
        if (!q) return;
        $("qbModalTitle").textContent = "Modifier la question";
        $("qbEditId").value = q.questionId;
        $("qbQuestionText").value = q.questionText;
        $("qbType").value = q.type;
        $("qbDifficulty").value = q.difficulty;
        $("qbCategory").value = q.category || "";
        $("qbPoints").value = q.points;
        $("qbExplanation").value = q.explanation || "";
        $("qbImageUrl").value = q.imageUrl || "";
        $("qbImageFile").value = "";
        $("qbImageStatus").innerHTML = q.imageUrl ? '<i class="bi bi-check-circle text-success"></i> Image déjà associée' : "";
        $("qbVideoUrl").value = q.videoUrl || "";
        $("qbVideoFile").value = "";
        $("qbVideoStatus").innerHTML = q.videoUrl ? '<i class="bi bi-check-circle text-success"></i> Vidéo déjà associée' : "";
        $("qbTags").value = q.tags || "";
        $("qbTimeLimit").value = q.timeLimitSeconds || "";
        $("qbActive").checked = q.active;
        renderOptionRows(q.options, q.correctOptionIndex, q.correctIndexes, q.type);
        qbModal.show();
    }

    $("qbSaveBtn").addEventListener("click", function () {
        var text = $("qbQuestionText").value.trim();
        if (!text) { alert("Le texte de la question est obligatoire."); return; }
        var type = $("qbType").value;
        var rows = Array.prototype.slice.call($("qbOptionsContainer").querySelectorAll(".qb-option-row"));
        var options = rows.map(function (r) { return r.querySelector(".qb-option-text").value.trim(); }).filter(function (v) { return v !== ""; });
        if (options.length < 2) { alert("Au moins 2 options de réponse sont requises."); return; }

        var correctOptionIndex = null, correctIndexes = null;
        if (type === "MULTI_SELECT") {
            correctIndexes = [];
            rows.forEach(function (r, i) { if (r.querySelector(".qb-correct-marker").checked) correctIndexes.push(i); });
            if (correctIndexes.length === 0) { alert("Cochez au moins une bonne réponse."); return; }
        } else {
            var checkedIdx = -1;
            rows.forEach(function (r, i) { if (r.querySelector(".qb-correct-marker").checked) checkedIdx = i; });
            if (checkedIdx === -1) { alert("Sélectionnez la bonne réponse."); return; }
            correctOptionIndex = checkedIdx;
        }

        var payload = {
            questionText: text,
            type: type,
            difficulty: $("qbDifficulty").value,
            category: $("qbCategory").value.trim() || null,
            options: options,
            correctOptionIndex: correctOptionIndex,
            correctIndexes: correctIndexes,
            explanation: $("qbExplanation").value.trim() || null,
            imageUrl: $("qbImageUrl").value.trim() || null,
            videoUrl: $("qbVideoUrl").value.trim() || null,
            tags: $("qbTags").value.trim() || null,
            points: parseInt($("qbPoints").value, 10) || 10,
            timeLimitSeconds: $("qbTimeLimit").value ? parseInt($("qbTimeLimit").value, 10) : null,
            active: $("qbActive").checked
        };

        var editId = $("qbEditId").value;
        var request = editId
            ? sendJson("/api/quiz-questions/" + editId, "PUT", payload)
            : sendJson("/api/quiz-questions", "POST", payload);

        request.then(function () {
            qbModal.hide();
            loadQuestions();
            loadCategories();
        }).catch(function (err) { alert("Erreur : " + err.message); });
    });

    // ===================== INIT =====================

    function isQaOrAdmin(profile) { return profile === "QA" || profile === "ADMIN"; }

    if (window.RccSession) {
        window.RccSession.init().then(function (session) {
            if (session && isQaOrAdmin(session.profile)) {
                loadCategories();
                loadQuestions();
            }
        });
    } else {
        loadCategories();
        loadQuestions();
    }
})();
