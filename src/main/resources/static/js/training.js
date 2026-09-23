"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };
    var coursesCache = [];
    var attemptsCache = [];
    var currentCourseId = null;
    var currentQuestions = [];
    var currentProfile = null;
    var newCourseContentEditor = null;
    /** true tant qu'un agent a un questionnaire ouvert et non soumis — voir renderCourseForm /
     * submitCourseBtn — pour ne pas rafraîchir "Mes cours" sous ses pieds pendant qu'il répond. */
    var attemptInProgress = false;

    var getJson = RccApi.getJson;

    var sendJson = RccApi.sendJson;

    var escapeHtml = RccApi.escapeHtml;

    function applyQaVisibility(profile) {
        var isQaOrAdmin = profile === "QA" || profile === "ADMIN" || profile === "FORMATEUR";
        var elements = document.querySelectorAll(".qa-only");
        Array.prototype.forEach.call(elements, function (el) {
            el.style.display = isQaOrAdmin ? "" : "none";
        });
    }

    var STATUS_LABELS = { TODO: "À faire", IN_PROGRESS: "En cours", DONE: "Terminé" };

    // Visuels de repli (modèle EduFun) quand QA n'a pas encore choisi d'image : toujours la même
    // image pour un même cours / une même thématique, pour que l'agent s'y retrouve.
    var FALLBACK_IMAGES = ["pexels-18804128.jpg", "pexels-5053847.jpg", "pexels-669610.jpg", "pexels-7681091.jpg",
        "pexels-12903122.jpg", "pexels-5239804.jpg", "pexels-3760067.jpg", "pexels-8152734.jpg", "pexels-53621.jpg",
        "pexels-60504.jpg", "pexels-1602726.jpg", "advisor-accent.jpg"];
    function fallbackImage(index) {
        var n = Math.abs(Number(index) || 0);
        return "/images/formation/" + FALLBACK_IMAGES[n % FALLBACK_IMAGES.length];
    }

    // ===== Espace agent : "Mes cours" =====

    var currentCourseCategory = null; // null = grille de rubriques affichée

    function loadMyCoursesTable() {
        Promise.all([getJson("/api/courses"), getJson("/api/courses/attempts/me")]).then(function (results) {
            coursesCache = results[0];
            attemptsCache = results[1];

            // Le cours actuellement ouvert (lecteur) vient d'être supprimé côté QA/admin
            // pendant que cette page était ouverte : on referme le lecteur proprement au
            // lieu de laisser l'agent face à un cours fantôme.
            if (currentCourseId !== null && !coursesCache.some(function (c) { return c.courseId === currentCourseId; })) {
                var card = $("coursePlayerCard");
                if (card) card.style.display = "none";
                attemptInProgress = false;
                currentCourseId = null;
            }

            renderDashboardStats();
            renderQuickAccessModules();
            renderCourseCategoryGrid();
            if (currentCourseCategory !== null) renderCourseCategoryDetail(currentCourseCategory);
            // Synchronise l'espace Formation (formation.js : XP, badges, quiz, publication QA).
            document.dispatchEvent(new CustomEvent("rcc:training-updated", { detail: { courses: coursesCache, attempts: attemptsCache } }));
        }).catch(function (e) { console.error(e); });
    }

    function courseCategoryLabel(c) {
        return (c.category && c.category.trim()) || "Général";
    }

    /** Grille de rubriques façon Knowledge Base — une vignette FIXE par rubrique (voir
     *  CourseCategories, seedée au démarrage comme KnowledgeCategories), jamais dérivée des
     *  seuls cours déjà créés : la rubrique reste visible et cliquable même à 0 cours, pour
     *  que QA/Formateur puisse y programmer du contenu dès le départ. */
    var courseCategoryImages = {}; // titre -> imageUrl, chargé une fois au démarrage
    var courseCategoryOrder = []; // ordre des rubriques (SortOrder), chargé une fois au démarrage

    function loadCourseCategoryImages() {
        return getJson("/api/courses/categories").then(function (categories) {
            courseCategoryImages = {};
            courseCategoryOrder = [];
            categories.forEach(function (c) { courseCategoryImages[c.title] = c.imageUrl; courseCategoryOrder.push(c.title); });
        }).catch(function () {});
    }

    function renderCourseCategoryGrid() {
        var grid = $("courseCategoryGrid");
        if (!courseCategoryOrder.length) {
            grid.innerHTML = '<div class="col-12 text-muted text-center">Rubriques indisponibles pour le moment.</div>';
            return;
        }
        var counts = {};
        coursesCache.forEach(function (c) {
            var label = courseCategoryLabel(c);
            counts[label] = (counts[label] || 0) + 1;
        });

        var isQa = currentProfile === "QA" || currentProfile === "ADMIN" || currentProfile === "FORMATEUR";

        grid.innerHTML = courseCategoryOrder.map(function (label) {
            var imageUrl = courseCategoryImages[label];
            var visual = '<div class="ef-cat-visual" style="background-image:url(\'' + escapeHtml(imageUrl || fallbackImage(courseCategoryOrder.indexOf(label) + 1)) + '\');"></div>';
            var qaImageBtn = isQa
                ? '<button type="button" class="btn btn-sm btn-outline-secondary category-image-btn" data-category="' + escapeHtml(label) + '" ' +
                  'style="position:absolute;top:6px;right:6px;" title="' + (imageUrl ? "Changer l'image" : "Ajouter une image") + '">' +
                  '<i class="bi bi-image"></i></button>' : "";
            var count = counts[label] || 0;
            return '<div class="col-xl-3 col-md-4 col-sm-6">' +
                '<div class="ef-cat-card course-category-card" data-category="' + escapeHtml(label) + '">' +
                    qaImageBtn + visual +
                    '<div class="ef-cat-body">' +
                        '<h6>' + escapeHtml(label) + '</h6>' +
                        '<span class="ef-chip">' + count + ' cours</span>' +
                        '<i class="bi bi-arrow-right-circle-fill ef-cat-go"></i>' +
                    '</div>' +
                '</div></div>';
        }).join("");

        Array.prototype.forEach.call(grid.querySelectorAll(".course-category-card"), function (card) {
            card.addEventListener("click", function (evt) {
                if (evt.target.closest(".category-image-btn")) return; // le bouton image gère son propre clic
                openCourseCategory(card.getAttribute("data-category"));
            });
        });
        Array.prototype.forEach.call(grid.querySelectorAll(".category-image-btn"), function (btn) {
            btn.addEventListener("click", function (evt) {
                evt.stopPropagation();
                pickCategoryImage(btn.getAttribute("data-category"));
            });
        });
    }

    /** Ouvre un sélecteur de fichier volant pour l'image de rubrique — évite de construire une
     *  modale dédiée juste pour ça, cohérent avec la légèreté attendue de cette action. */
    function pickCategoryImage(category) {
        var input = document.createElement("input");
        input.type = "file";
        input.accept = "image/*";
        input.addEventListener("change", function () {
            var file = input.files[0];
            if (!file) return;
            var formData = new FormData();
            formData.append("file", file);
            fetch("/api/courses/categories/image?title=" + encodeURIComponent(category), {
                method: "POST", credentials: "same-origin", body: formData
            }).then(function (res) {
                if (!res.ok) return Promise.reject(new Error("HTTP " + res.status));
                return loadCourseCategoryImages();
            }).then(function () { renderCourseCategoryGrid(); })
              .catch(function (e) { alert("Erreur : " + e.message); });
        });
        input.click();
    }

    function openCourseCategory(category) {
        currentCourseCategory = category;
        $("courseCategoriesCard").style.display = "none";
        $("courseCategoryDetailCard").style.display = "";
        $("courseCategoryDetailTitle").textContent = category;
        renderCourseCategoryDetail(category);

        var addBtn = $("addCourseToCategoryBtn");
        if (currentProfile === "QA" || currentProfile === "ADMIN" || currentProfile === "FORMATEUR") {
            addBtn.style.display = "";
            addBtn.onclick = function () { prefillCourseFormForCategory(category); };
        } else {
            addBtn.style.display = "none";
        }

        // Le bouton n'apparaît que si des questions existent réellement pour cette rubrique —
        // jamais un lien vers une évaluation vide qui frustrerait l'agent.
        var launchBtn = $("launchEvaluationBtn");
        launchBtn.style.display = "none";
        getJson("/api/quiz-questions/draw?category=" + encodeURIComponent(category) + "&count=1").then(function (questions) {
            if (questions.length) {
                launchBtn.style.display = "";
                launchBtn.href = "/games?category=" + encodeURIComponent(category) + "&autoplay=quiz-eclair";
            }
        }).catch(function () {});
    }

    /** Verrouille la thématique du formulaire "Créer un cours" sur la rubrique déjà ouverte —
     *  évite à QA de retaper le nom exact de la rubrique et risquer une faute de frappe qui
     *  créerait une nouvelle rubrique en double au lieu de rattacher le cours à la bonne. */
    function prefillCourseFormForCategory(category) {
        var categoryField = $("newCourseCategory");
        var hint = $("createCourseCategoryHint");
        categoryField.value = category === "Général" ? "" : category;
        categoryField.readOnly = true;
        hint.style.display = "";
        hint.innerHTML = '<i class="bi bi-info-circle"></i> Ce cours sera ajouté à la rubrique <strong>' + escapeHtml(category) + '</strong>. ' +
            '<a href="#" id="unlockCourseCategoryLink">Changer de rubrique</a>';
        $("unlockCourseCategoryLink").addEventListener("click", function (evt) {
            evt.preventDefault();
            categoryField.readOnly = false;
            hint.style.display = "none";
        });
        $("newCourseTitle").scrollIntoView({ behavior: "smooth", block: "center" });
        $("newCourseTitle").focus();
    }

    $("courseCategoryBackBtn").addEventListener("click", function () {
        currentCourseCategory = null;
        $("courseCategoryDetailCard").style.display = "none";
        $("courseCategoriesCard").style.display = "";
    });

    function renderCourseCategoryDetail(category) {
        var table = $("myCoursesTable");
        var coursesInCategory = coursesCache.filter(function (c) { return courseCategoryLabel(c) === category; });

        if (!coursesInCategory.length) {
            table.innerHTML = '<tr><td colspan="5" class="text-muted text-center">Aucun cours dans cette rubrique.</td></tr>';
            return;
        }

        table.innerHTML = coursesInCategory.map(function (c) {
            var attempt = attemptsCache.filter(function (a) { return a.courseId === c.courseId; })[0];
            var status = attempt ? attempt.status : "TODO";
            var typeLabel = c.type === "SELF_ASSESSMENT" ? "Auto-diagnostic" : "Évaluation métier";
            var mandatoryBadge = c.mandatory
                ? '<span class="badge text-bg-danger">Obligatoire</span>'
                : '<span class="badge text-bg-secondary">Facultatif</span>';
            var statusBadge = status === "DONE"
                ? '<span class="badge text-bg-success">' + STATUS_LABELS[status] + '</span>'
                : '<span class="badge text-bg-warning">' + STATUS_LABELS[status] + '</span>';
            var actionLabel = status === "DONE" ? "Revoir" : (status === "IN_PROGRESS" ? "Continuer" : "Commencer");

            var manageButtons = "";
            if (currentProfile === "QA" || currentProfile === "ADMIN" || currentProfile === "FORMATEUR") {
                manageButtons =
                    ' <button class="btn btn-sm btn-outline-secondary edit-course-btn" data-course-id="' + c.courseId + '"><i class="bi bi-pencil"></i></button>' +
                    ' <button class="btn btn-sm btn-outline-danger delete-course-btn" data-course-id="' + c.courseId + '"><i class="bi bi-trash"></i></button>';
            }

            return "<tr>" +
                "<td>" + escapeHtml(c.title) + "</td>" +
                "<td>" + typeLabel + "</td>" +
                "<td>" + mandatoryBadge + "</td>" +
                "<td>" + statusBadge + "</td>" +
                '<td><button class="btn btn-sm btn-outline-primary" data-course-id="' + c.courseId + '">' + actionLabel + '</button>' + manageButtons + '</td>' +
                "</tr>";
        }).join("");

        Array.prototype.forEach.call(table.querySelectorAll("td > button.btn-outline-primary"), function (btn) {
            btn.addEventListener("click", function () { openCoursePlayer(Number(btn.dataset.courseId)); });
        });
        Array.prototype.forEach.call(table.querySelectorAll(".edit-course-btn"), function (btn) {
            btn.addEventListener("click", function () { openEditCourse(Number(btn.dataset.courseId)); });
        });
        Array.prototype.forEach.call(table.querySelectorAll(".delete-course-btn"), function (btn) {
            btn.addEventListener("click", function () { deleteCourse(Number(btn.dataset.courseId)); });
        });
    }

    function renderDashboardStats() {
        var total = coursesCache.length;
        var done = attemptsCache.filter(function (a) { return a.status === "DONE"; });
        var scored = done.filter(function (a) { return a.score !== null && a.score !== undefined; });
        var avgScore = scored.length ? Math.round(scored.reduce(function (s, a) { return s + a.score; }, 0) / scored.length) : 0;
        var passed = scored.filter(function (a) { return a.score >= 70; }).length;

        $("statModulesCompleted").textContent = done.length + "/" + total;
        $("statAvgScore").textContent = avgScore + "%";
        $("statAttempts").textContent = attemptsCache.length;
        $("statBadges").textContent = passed;

        var progress = total ? Math.round((done.length / total) * 100) : 0;
        $("progressLabel").textContent = progress + "%";
        $("progressBar").style.width = progress + "%";
    }

    function attemptDone(c) {
        return attemptsCache.some(function (a) { return a.courseId === c.courseId && a.status === "DONE"; });
    }

    function renderQuickAccessModules() {
        var container = $("quickAccessModules");
        if (!coursesCache.length) {
            container.innerHTML = '<p class="text-muted">Aucun module disponible pour le moment.</p>';
            return;
        }

        // « À découvrir maintenant » (modèle EduFun) : d'abord ce qui reste à faire, obligatoires en tête.
        var ordered = coursesCache.slice().sort(function (a, b) {
            var da = attemptDone(a) ? 1 : 0, db = attemptDone(b) ? 1 : 0;
            if (da !== db) return da - db;
            return (b.mandatory ? 1 : 0) - (a.mandatory ? 1 : 0);
        }).slice(0, 6);
        container.innerHTML = ordered.map(function (c) {
            var attempt = attemptsCache.filter(function (a) { return a.courseId === c.courseId; })[0];
            var done = attempt && attempt.status === "DONE";
            var statusChip = done
                ? '<span class="ef-chip ef-chip-success"><i class="bi bi-check2"></i> Complété' + (attempt.score !== null && attempt.score !== undefined ? " · " + attempt.score + "%" : "") + '</span>'
                : '<span class="ef-chip ef-chip-todo">À faire</span>';
            var uploadBtn = currentProfile === "ADMIN"
                ? '<label class="btn btn-sm btn-light position-absolute top-0 end-0 m-2" style="cursor:pointer;" title="Changer la vignette (admin)">' +
                  '<i class="bi bi-camera"></i><input type="file" accept="image/*" class="d-none module-image-input" data-course-id="' + c.courseId + '"></label>'
                : "";
            var draft = c.publicationStatus && c.publicationStatus !== "PUBLISHED"
                ? '<span class="ef-chip ef-chip-draft">' + (c.publicationStatus === "DRAFT" ? "Brouillon" : "Archivé") + '</span>' : "";
            var xp = c.type === "STANDARD" ? "+50 XP" : "+30 XP";

            return '<div class="col-md-6 col-xxl-4">' +
                '<div class="ef-course-card open-course-btn" data-id="' + c.courseId + '">' +
                '<div class="position-relative"><img src="' + escapeHtml(c.imageUrl || fallbackImage(c.courseId * 7 + 3)) + '" alt="">' +
                '<span class="ef-course-xp">' + xp + '</span>' + uploadBtn + '</div>' +
                '<div class="ef-course-body">' +
                '<span class="ef-pill ef-pill-sm">' + escapeHtml(courseCategoryLabel(c)) + '</span>' +
                '<h4>' + escapeHtml(c.title) + '</h4>' +
                '<p class="ef-course-desc">' + escapeHtml(c.description || "") + '</p>' +
                '<div class="ef-course-meta">' +
                    '<span>' + (c.type === "STANDARD" ? "🧠 Évaluation" : "📝 Auto-diagnostic") + '</span>' +
                    (c.mandatory ? '<span>⭐ Obligatoire</span>' : "") +
                    (c.videoUrl ? '<span>🎬 Vidéo</span>' : "") + draft +
                '</div>' + statusChip +
                '</div></div></div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".open-course-btn"), function (card) {
            card.addEventListener("click", function (evt) {
                if (evt.target.closest("label")) return; // clic sur le bouton photo, pas la carte
                openCoursePlayer(Number(card.getAttribute("data-id")));
            });
        });

        Array.prototype.forEach.call(container.querySelectorAll(".module-image-input"), function (input) {
            input.addEventListener("change", function (evt) {
                evt.stopPropagation();
                var file = this.files[0];
                var courseId = this.getAttribute("data-course-id");
                if (!file) return;
                var formData = new FormData();
                formData.append("file", file);
                fetch("/api/courses/" + courseId + "/image", { method: "POST", credentials: "same-origin", body: formData })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); return res.json(); })
                    .then(function () { loadMyCoursesTable(); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });
    }

    // ===== Espace QA/admin : modifier / supprimer un cours =====

    function openEditCourse(courseId) {
        var course = coursesCache.filter(function (c) { return c.courseId === courseId; })[0];
        if (!course) return;

        var newTitle = prompt("Titre du cours :", course.title);
        if (newTitle === null) return;
        var newDescription = prompt("Description :", course.description || "");
        if (newDescription === null) return;
        var newContent = prompt("Contenu :", course.content || "");
        if (newContent === null) return;
        var newVideoUrl = prompt("Lien vidéo (optionnel) :", course.videoUrl || "");
        if (newVideoUrl === null) return;
        var newCategory = prompt("Thématique (laisser vide = Général) :", course.category || "");
        if (newCategory === null) return;
        var newMandatory = confirm("Ce cours doit-il être obligatoire ? (OK = oui, Annuler = non)");

        sendJson("/api/courses/" + courseId, "PUT", {
            title: newTitle.trim(),
            description: newDescription.trim() || null,
            content: newContent.trim() || null,
            videoUrl: newVideoUrl.trim() || null,
            category: newCategory.trim() || "",
            type: course.type,
            mandatory: newMandatory
        }).then(function () {
            currentCourseCategory = null;
            $("courseCategoryDetailCard").style.display = "none";
            $("courseCategoriesCard").style.display = "";
            loadMyCoursesTable();
            loadQaCourseSelects();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    function deleteCourse(courseId) {
        var course = coursesCache.filter(function (c) { return c.courseId === courseId; })[0];
        if (!course) return;
        if (!confirm('Supprimer le cours "' + course.title + '" ? Cette action est irréversible.')) return;

        sendJson("/api/courses/" + courseId, "DELETE").then(function () {
            loadMyCoursesTable();
            loadQaCourseSelects();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    // ===== Lecture vidéo intégrée (modale, pas d'ouverture externe) =====

    function toEmbedUrl(url) {
        var yt = url.match(/(?:youtube\.com\/watch\?v=|youtu\.be\/)([\w-]{6,})/);
        if (yt) return "https://www.youtube.com/embed/" + yt[1];
        var vimeo = url.match(/vimeo\.com\/(\d+)/);
        if (vimeo) return "https://player.vimeo.com/video/" + vimeo[1];
        return null;
    }

    var videoProgressCourseId = null;
    var videoProgressReportTimer = null;
    var videoMaxReachedSeconds = 0;

    function openVideoModal(title, url, courseId) {
        $("videoModalTitle").textContent = title || "Vidéo";
        var body = $("videoModalBody");
        var embedUrl = toEmbedUrl(url);
        var isDirectFile = /\.(mp4|webm|ogg)(\?.*)?$/i.test(url);

        videoProgressCourseId = courseId || null;
        videoMaxReachedSeconds = 0;
        clearInterval(videoProgressReportTimer);

        if (isDirectFile) {
            body.innerHTML = '<video controls autoplay style="width:100%;max-height:70vh;" id="strictVideoPlayer" src="' + escapeHtml(url) + '"></video>' +
                '<p class="small text-muted mt-2"><i class="bi bi-lock-fill"></i> Cette vidéo doit être regardée du début à la fin, sans avance rapide.</p>';
            wireStrictVideoPlayer($("strictVideoPlayer"));
        } else if (embedUrl) {
            body.innerHTML = '<div class="ratio ratio-16x9"><iframe src="' + escapeHtml(embedUrl) +
                '" allow="autoplay; encrypted-media" allowfullscreen frameborder="0"></iframe></div>' +
                '<p class="small text-muted mt-2"><i class="bi bi-info-circle"></i> Vidéo hébergée en externe — la lecture complète ne peut pas être vérifiée techniquement ici ; ' +
                'privilégiez un fichier vidéo importé directement pour un suivi strict.</p>';
            // Repli honnête : impossible de contrôler un lecteur externe (YouTube...) depuis
            // ici sans son API propre — on marque la vidéo comme regardée après un délai
            // raisonnable plutôt que de bloquer indéfiniment un cours dont la vidéo est externe.
            if (videoProgressCourseId) {
                setTimeout(function () { reportVideoProgress(videoProgressCourseId, 100, false); }, 20000);
            }
        } else {
            body.innerHTML = '<div class="ratio ratio-16x9"><iframe src="' + escapeHtml(url) +
                '" allowfullscreen frameborder="0"></iframe></div>';
        }

        new bootstrap.Modal($("videoModal")).show();
    }

    /** Empêche tout saut en avant au-delà du point déjà atteint (avance rapide OU glissement
     *  de la barre de progression) — seul un retour en arrière reste autorisé, jamais un saut
     *  vers l'avant. Rapporte la progression au backend toutes les 5 secondes. */
    function wireStrictVideoPlayer(video) {
        if (!video) return;
        video.addEventListener("timeupdate", function () {
            if (video.currentTime > videoMaxReachedSeconds + 0.5) {
                // Avance détectée au-delà de ce qui a été normalement lu — repositionne au
                // point maximum déjà atteint et journalise la tentative.
                video.currentTime = videoMaxReachedSeconds;
                if (videoProgressCourseId) reportVideoProgress(videoProgressCourseId, currentWatchedPercent(video), true);
            } else {
                videoMaxReachedSeconds = Math.max(videoMaxReachedSeconds, video.currentTime);
            }
        });
        video.addEventListener("ended", function () {
            if (videoProgressCourseId) reportVideoProgress(videoProgressCourseId, 100, false);
        });
        videoProgressReportTimer = setInterval(function () {
            if (videoProgressCourseId) reportVideoProgress(videoProgressCourseId, currentWatchedPercent(video), false);
        }, 5000);
    }

    function currentWatchedPercent(video) {
        if (!video.duration || isNaN(video.duration)) return 0;
        return Math.round((videoMaxReachedSeconds / video.duration) * 100);
    }

    function reportVideoProgress(courseId, watchedPercent, seekViolation) {
        fetch("/api/courses/" + courseId + "/video-progress?watchedPercent=" + Math.round(watchedPercent) +
            "&seekViolation=" + seekViolation, { method: "POST", credentials: "same-origin" }).catch(function () {});
    }

    document.getElementById("videoModal").addEventListener("hidden.bs.modal", function () {
        // Coupe la lecture à la fermeture plutôt que de laisser tourner en fond.
        document.getElementById("videoModalBody").innerHTML = "";
        clearInterval(videoProgressReportTimer);
    });

    // ===== Lecteur de cours (agent) =====

    function openCoursePlayer(courseId) {
        currentCourseId = courseId;
        var course = coursesCache.filter(function (c) { return c.courseId === courseId; })[0];
        if (!course) return;

        Promise.all([
            getJson("/api/courses/" + courseId + "/attempt"),
            getJson("/api/courses/" + courseId + "/questions")
        ]).then(function (results) {
            var attempt = results[0];
            currentQuestions = results[1];

            $("coursePlayerCard").style.display = "";
            $("coursePlayerTitle").textContent = course.title;

            if (attempt.status === "DONE") {
                attemptInProgress = false;
                renderCourseResult(course, attempt);
            } else {
                attemptInProgress = true;
                renderCourseForm(course);
            }

            $("coursePlayerCard").scrollIntoView({ behavior: "smooth" });
        }).catch(function (e) { alert("Erreur : " + e.message); });
    }

    function renderCourseForm(course) {
        var body = $("coursePlayerBody");
        var html = "";

        if (course.description) html += '<p class="text-muted">' + escapeHtml(course.description) + '</p>';
        // course.content est du HTML riche produit par richtext-editor.js (RccRichText.getHtml()),
        // saisi par un admin/QA de confiance côté formulaire de création — il ne doit pas être
        // ré-échappé ici, sinon le navigateur affiche le balisage brut au lieu de l'interpréter
        // (c'est exactement le bug observé : <h2>, <ul>, data-sourcepos... affichés tels quels).
        if (course.content) html += '<div class="rcc-course-content">' + course.content + '</div>';
        if (course.videoUrl) {
            html += '<div class="mb-3"><button type="button" class="btn btn-outline-secondary btn-sm" id="watchVideoBtn">' +
                '<i class="bi bi-play-circle"></i> Voir la vidéo</button></div>';
        }
        if (course.fileUrl) {
            html += '<div class="mb-3"><a href="' + escapeHtml(course.fileUrl) + '" target="_blank" rel="noopener" class="btn btn-outline-secondary btn-sm">' +
                '<i class="bi bi-file-earmark-arrow-down"></i> ' + escapeHtml(course.fileName || "Télécharger le fichier") + '</a></div>';
        }

        if (!currentQuestions.length) {
            html += '<p class="text-muted">Aucune question pour ce cours pour l\'instant.</p>';
        } else if (course.type === "SELF_ASSESSMENT") {
            html += '<h6 class="mt-3">Auto-diagnostic (1 = pas du tout, 5 = tout à fait)</h6>';
            currentQuestions.forEach(function (q) {
                html += '<div class="mb-3">' +
                    '<p class="mb-1">' + escapeHtml(q.questionText) + '</p>' +
                    '<div class="btn-group" role="group">' +
                    [1, 2, 3, 4, 5].map(function (v) {
                        return '<input type="radio" class="btn-check" name="q' + q.questionId + '" id="q' + q.questionId + '_' + v +
                            '" value="' + v + '" data-question-id="' + q.questionId + '">' +
                            '<label class="btn btn-outline-primary btn-sm" for="q' + q.questionId + '_' + v + '">' + v + '</label>';
                    }).join("") +
                    '</div></div>';
            });
        } else {
            html += '<h6 class="mt-3">Évaluation</h6>';
            currentQuestions.forEach(function (q) {
                html += '<div class="mb-3">' +
                    '<p class="mb-1">' + escapeHtml(q.questionText) + '</p>';
                (q.options || []).forEach(function (opt, idx) {
                    html += '<div class="form-check">' +
                        '<input class="form-check-input" type="radio" name="q' + q.questionId + '" id="q' + q.questionId + '_' + idx +
                        '" value="' + idx + '" data-question-id="' + q.questionId + '">' +
                        '<label class="form-check-label" for="q' + q.questionId + '_' + idx + '">' + escapeHtml(opt) + '</label>' +
                        '</div>';
                });
                html += '</div>';
            });
        }

        if (currentQuestions.length) {
            html += '<button class="btn btn-primary" id="submitCourseBtn">Valider</button>';
        }

        body.innerHTML = html;

        var watchBtn = $("watchVideoBtn");
        if (watchBtn) {
            watchBtn.addEventListener("click", function () { openVideoModal(course.title, course.videoUrl, course.courseId); });
        }

        var submitBtn = $("submitCourseBtn");
        if (submitBtn) {
            submitBtn.addEventListener("click", function () { submitCourseAttempt(course); });
        }
    }

    function submitCourseAttempt(course) {
        var answers = {};
        currentQuestions.forEach(function (q) {
            var checked = document.querySelector('input[name="q' + q.questionId + '"]:checked');
            if (checked) answers[String(q.questionId)] = Number(checked.value);
        });

        if (Object.keys(answers).length < currentQuestions.length) {
            alert("Veuillez répondre à toutes les questions.");
            return;
        }

        sendJson("/api/courses/" + currentCourseId + "/attempt/submit", "POST", { answers: answers })
            .then(function (attempt) {
                attemptInProgress = false;
                renderCourseResult(course, attempt);
                loadMyCoursesTable();
            })
            .catch(function (e) { alert("Erreur : " + e.message); });
    }

    function renderCourseResult(course, attempt) {
        var body = $("coursePlayerBody");
        if (course.type === "SELF_ASSESSMENT") {
            body.innerHTML = '<div class="alert alert-success">Auto-diagnostic complété. Merci pour vos réponses.</div>';
            return;
        }

        var passed = attempt.score >= 70;
        var html = '<div class="alert alert-' + (passed ? "success" : "warning") + '">' +
            'Évaluation terminée — score : <strong>' + attempt.score + '%</strong>' +
            ' (tentative ' + attempt.attemptNumber + '/2)</div>';

        if (attempt.finalized) {
            html += '<p class="text-muted small"><i class="bi bi-lock-fill"></i> Résultat définitif — ' +
                'aucune nouvelle tentative possible. Le résultat est visible par QA/admin.</p>';
        } else {
            html += '<p class="text-warning small"><i class="bi bi-exclamation-triangle"></i> Résultat insuffisant. ' +
                'Il vous reste une dernière tentative.</p>' +
                '<button class="btn btn-outline-primary btn-sm" id="retryCourseBtn">Retenter (dernière chance)</button>';
        }

        body.innerHTML = html;

        var retryBtn = $("retryCourseBtn");
        if (retryBtn) {
            retryBtn.addEventListener("click", function () { openCoursePlayer(course.courseId); });
        }
    }

    // ===== Espace QA/admin : création de cours =====

    function loadCourseServiceOptions() {
        // Liste alignée sur les équipes de l'onglet Shift (dbo.Teams) — pas sur les services
        // du référentiel Procédures — pour que l'assignation d'un cours corresponde à
        // l'équipe réelle de l'agent (User.activity).
        getJson("/api/teams").then(function (teams) {
            var select = $("newCourseService");
            teams.forEach(function (t) {
                var opt = document.createElement("option");
                opt.value = t.code;
                opt.textContent = t.label;
                select.appendChild(opt);
            });
        }).catch(function () {});
    }

    $("createCourseBtn").addEventListener("click", function () {
        var title = $("newCourseTitle").value.trim();
        if (!title) { alert("Le titre est obligatoire."); return; }
        var fileInput = $("newCourseFile");
        var pendingFile = fileInput.files[0];

        var chosenType = $("newCourseType").value;
        var chosenCategory = $("newCourseCategory").value.trim() || "Général";
        sendJson("/api/courses", "POST", {
            title: title,
            description: $("newCourseDescription").value.trim() || null,
            content: newCourseContentEditor ? (newCourseContentEditor.getHtml().trim() || null) : null,
            videoUrl: $("newCourseVideoUrl").value.trim() || null,
            type: chosenType,
            teamCode: $("newCourseService").value || null,
            category: $("newCourseCategory").value.trim() || null,
            mandatory: $("newCourseMandatory").checked
        }).then(function (created) {
            var afterCreate = function () {
                $("newCourseTitle").value = "";
                $("newCourseDescription").value = "";
                if (newCourseContentEditor) newCourseContentEditor.setHtml("");
                $("newCourseVideoUrl").value = "";
                $("newCourseFile").value = "";
                $("newCourseService").value = "";
                $("newCourseCategory").value = "";
                $("newCourseCategory").readOnly = false;
                $("createCourseCategoryHint").style.display = "none";
                $("newCourseMandatory").checked = false;
                loadQaCourseSelects();
                loadMyCoursesTable();
                // Le formateur voulait une évaluation notée — on lui ouvre directement la
                // gestion des questions pour cette rubrique, sans qu'il ait à la rechercher ailleurs.
                if (chosenType === "STANDARD") {
                    openCourseQuestionsEditor(chosenCategory, created.title);
                }
            };
            if (!pendingFile) { afterCreate(); return; }

            var formData = new FormData();
            formData.append("file", pendingFile);
            fetch("/api/courses/" + created.courseId + "/file", { method: "POST", credentials: "same-origin", body: formData })
                .then(afterCreate)
                .catch(function (e) { alert("Cours créé, mais l'import du fichier a échoué : " + e.message); afterCreate(); });
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    // ===== Questions de l'évaluation — mêmes questions que le Centre d'Évaluation (jeux),
    //       taguées avec la catégorie de la rubrique, pas un système séparé pour la Formation =====

    var courseQuestionsForCategory = null;

    function openCourseQuestionsEditor(category, title) {
        courseQuestionsForCategory = category;
        $("courseQuestionsForTitle").textContent = title;
        $("courseQuestionsCard").style.display = "";
        $("courseQuestionsCard").scrollIntoView({ behavior: "smooth", block: "center" });
        refreshCourseQuestionsList();
    }

    function refreshCourseQuestionsList() {
        if (!courseQuestionsForCategory) return;
        getJson("/api/quiz-questions?category=" + encodeURIComponent(courseQuestionsForCategory)).then(function (questions) {
            var list = $("courseQuestionsList");
            if (!questions.length) {
                list.innerHTML = '<p class="text-muted small mb-0">Aucune question pour l\'instant — ajoutez-en une ci-dessous.</p>';
                return;
            }
            list.innerHTML = questions.map(function (q, i) {
                return '<div class="border rounded p-2 mb-2 small"><strong>' + (i + 1) + '. ' + escapeHtml(q.questionText) + '</strong>' +
                    '<ul class="mb-0 mt-1">' + (q.options || []).map(function (opt, idx) {
                        var isCorrect = idx === q.correctOptionIndex;
                        return '<li' + (isCorrect ? ' class="text-success fw-semibold"' : '') + '>' + escapeHtml(opt) +
                            (isCorrect ? ' ✓' : '') + '</li>';
                    }).join("") + '</ul></div>';
            }).join("");
        }).catch(function () {});
    }

    $("addCourseQuestionBtn").addEventListener("click", function () {
        if (!courseQuestionsForCategory) return;
        var questionText = $("newQuestionText").value.trim();
        if (!questionText) { alert("L'intitulé de la question est obligatoire."); return; }

        var optionInputs = Array.prototype.slice.call($("newQuestionOptions").querySelectorAll("input[type=text]"));
        var options = optionInputs.map(function (inp) { return inp.value.trim(); }).filter(Boolean);
        if (options.length < 2) { alert("Au moins 2 réponses sont nécessaires."); return; }

        var checked = document.querySelector('input[name="newQuestionCorrect"]:checked');
        var correctOptionIndex = checked ? Number(checked.value) : 0;
        if (correctOptionIndex >= options.length) correctOptionIndex = 0; // repli si une réponse vide a été sautée

        // Va dans la même banque que le Centre d'Évaluation (jeux) — taguée avec la
        // catégorie/rubrique, pas dans un système séparé propre à la Formation.
        sendJson("/api/quiz-questions", "POST", {
            questionText: questionText, type: "MCQ", difficulty: "MEDIUM", category: courseQuestionsForCategory,
            options: options, correctOptionIndex: correctOptionIndex, points: 10, active: true
        }).then(function () {
            $("newQuestionText").value = "";
            optionInputs.forEach(function (inp) { inp.value = ""; });
            document.querySelector('input[name="newQuestionCorrect"][value="0"]').checked = true;
            refreshCourseQuestionsList();
        }).catch(function (e) { alert("Erreur : " + e.message); });
    });

    $("doneCourseQuestionsBtn").addEventListener("click", function () {
        courseQuestionsForCategory = null;
        $("courseQuestionsCard").style.display = "none";
    });

    // ===== Espace QA/admin : sélecteurs de cours (résultats) =====

    function loadQaCourseSelects() {
        getJson("/api/courses").then(function (courses) {
            coursesCache = courses;
            var optionsHtml = courses.map(function (c) {
                return '<option value="' + c.courseId + '" data-type="' + c.type + '">' + escapeHtml(c.title) + '</option>';
            }).join("");
            $("resultsCourseSelect").innerHTML = '<option value="">— Choisir —</option>' + optionsHtml;
        }).catch(function (e) { console.error(e); });
    }

    // ===== Espace QA/admin : résultats =====

    var lastAttemptsForResults = [];

    function renderResultsTable() {
        var table = $("resultsTable");
        var courseId = $("resultsCourseSelect").value;
        var teamCode = $("resultsTeamSelect").value;

        if (!courseId) {
            table.innerHTML = '<tr><td colspan="4" class="text-muted text-center">Choisissez un cours.</td></tr>';
            return;
        }

        var attempts = teamCode
            ? lastAttemptsForResults.filter(function (a) { return a.teamCode === teamCode; })
            : lastAttemptsForResults;

        if (!attempts.length) {
            table.innerHTML = '<tr><td colspan="4" class="text-muted text-center">Aucune tentative pour l\'instant.</td></tr>';
            return;
        }
        table.innerHTML = attempts.map(function (a) {
            var statusBadge = a.status === "DONE"
                ? '<span class="badge text-bg-success">Terminé</span>'
                : '<span class="badge text-bg-warning">' + STATUS_LABELS[a.status] + '</span>';
            return "<tr>" +
                "<td>" + escapeHtml(a.userFullName) + " (" + escapeHtml(a.username) + ")</td>" +
                "<td>" + statusBadge + "</td>" +
                "<td>" + (a.score != null ? a.score + "%" : "—") + "</td>" +
                "<td>" + (a.completedAt ? new Date(a.completedAt).toLocaleString("fr-FR") : "—") + "</td>" +
                "</tr>";
        }).join("");
    }

    function loadResultsTeamOptions() {
        // Équipes de l'onglet Shift (dbo.Teams), même référentiel que l'assignation des
        // cours — voir loadCourseServiceOptions.
        getJson("/api/teams").then(function (teams) {
            var select = $("resultsTeamSelect");
            select.innerHTML = '<option value="">Toutes les équipes</option>' + teams.map(function (t) {
                return '<option value="' + escapeHtml(t.code) + '">' + escapeHtml(t.label) + '</option>';
            }).join("");
        }).catch(function () {});
    }

    $("resultsCourseSelect").addEventListener("change", function () {
        var courseId = this.value;
        if (!courseId) {
            lastAttemptsForResults = [];
            renderResultsTable();
            return;
        }
        getJson("/api/courses/" + courseId + "/attempts").then(function (attempts) {
            lastAttemptsForResults = attempts;
            renderResultsTable();
        }).catch(function (e) { console.error(e); });
    });

    $("resultsTeamSelect").addEventListener("change", renderResultsTable);

    // ===== Bannière (image + texte pilotés depuis Administration) =====

    // Même liste que preferences.js (FONT_STACKS), dupliquée volontairement : ici c'est un
    // réglage global (SiteSetting appliqué à tous), pas la préférence personnelle par agent.
    var BANNER_FONT_STACKS = {
        system: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
        classic: "Arial, Helvetica, sans-serif",
        serif: "Georgia, 'Times New Roman', serif",
        rounded: "'Trebuchet MS', 'Comic Sans MS', sans-serif",
        mono: "'Courier New', Consolas, monospace"
    };

    function loadFormationBanner() {
        fetch("/api/site-settings/public-value/formation.banner.imageUrl", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : {};
        }).then(function (result) {
            if (!result.value) return;
            var layer = document.getElementById("formationBannerImageLayer");
            layer.style.backgroundImage = "url('" + result.value + "')";
            layer.style.opacity = "0.35";
        }).catch(function () { /* pas de bannière configurée — le dégradé par défaut reste affiché */ });

        var keys = [
            "formation.banner.title", "formation.banner.subtitle", "formation.banner.fontFamily",
            "formation.banner.titleSize", "formation.banner.titleColor", "formation.banner.subtitleColor"
        ];
        Promise.all(keys.map(function (key) {
            return fetch("/api/site-settings/public-value/" + key, { credentials: "same-origin" })
                .then(function (res) { return res.ok ? res.json() : { value: "" }; })
                .catch(function () { return { value: "" }; });
        })).then(function (results) {
            var title = results[0].value, subtitle = results[1].value, font = results[2].value,
                size = results[3].value, titleColor = results[4].value, subtitleColor = results[5].value;

            var titleEl = $("formationBannerTitle");
            var subtitleEl = $("formationBannerSubtitle");
            if (title) titleEl.textContent = title;
            if (subtitle) subtitleEl.textContent = subtitle;
            if (font && BANNER_FONT_STACKS[font]) {
                titleEl.style.fontFamily = BANNER_FONT_STACKS[font];
                subtitleEl.style.fontFamily = BANNER_FONT_STACKS[font];
            }
            if (size) titleEl.style.fontSize = size;
            if (titleColor) titleEl.style.color = titleColor;
            if (subtitleColor) subtitleEl.style.color = subtitleColor;
        });
    }

    // ===== Init =====

    var contentEditorEl = document.getElementById("newCourseContentEditor");
    if (contentEditorEl) newCourseContentEditor = window.RccRichText.create(contentEditorEl, "");

    window.RccSession.init().then(function (session) {
        if (session) {
            currentProfile = session.profile;
            applyQaVisibility(session.profile);
            loadCourseCategoryImages().then(loadMyCoursesTable);
        }
    });

    // Accès pour formation.js (onglet « Quiz & défis », publication QA).
    window.RccTraining = {
        openCoursePlayer: function (courseId) { openCoursePlayer(courseId); },
        reload: function () { loadMyCoursesTable(); loadQaCourseSelects(); }
    };

    loadFormationBanner();
    loadMyCoursesTable();
    loadQaCourseSelects();
    loadCourseServiceOptions();
    loadResultsTeamOptions();

    // Lien direct depuis la recherche globale (session.js) — ouvre le cours exact par ID
    // sans obliger l'agent à le retrouver dans la liste.
    var openCourseId = new URLSearchParams(window.location.search).get("openCourseId");
    if (openCourseId) {
        setTimeout(function () { openCoursePlayer(Number(openCourseId)); }, 500);
    }

    // Rafraîchissement périodique : si un cours est supprimé côté QA pendant qu'un
    // conseiller a déjà "Mes cours" ouvert dans un autre onglet/poste, il disparaît de
    // sa liste sans qu'il ait besoin de recharger la page manuellement. Se met en pause
    // pendant que le lecteur de cours est ouvert pour ne pas interrompre une tentative
    // en cours, et s'arrête si l'onglet passe en arrière-plan (économie de requêtes).
    var COURSES_REFRESH_INTERVAL_MS = 60000;
    setInterval(function () {
        if (document.hidden) return;
        if (attemptInProgress) return;
        loadMyCoursesTable();
    }, COURSES_REFRESH_INTERVAL_MS);
})();