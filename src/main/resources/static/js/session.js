"use strict";

/**
 * Récupère qui est connecté (GET /api/auth/me), en déduit un "profil" simple
 * (ADMIN / QA / AGENT), filtre la sidebar selon data-roles, remplit le header,
 * et gère le suivi de shift (pause/pause déjeuner/fin de shift). À inclure sur
 * toutes les pages protégées, AVANT les scripts propres à chaque page.
 */
window.RccSession = (function () {

    var PROFILE_LABELS = {
        ADMIN: "Administrateur",
        QA: "Quality Assurance",
        QA_SUPERVISOR: "Superviseur Qualité Assurance",
        FORMATEUR: "Formateur",
        RH: "Ressources Humaines",
        EXCELLIAM: "Excelliam (Prestataire Planning)",
        SUPERVISOR: "Superviseur",
        TEAM_LEADER: "Team Leader",
        AGENT: "Agent"
    };

    var SHIFT_STATE_LABELS = {
        NOT_STARTED: "Shift non démarré",
        WORKING: "En poste",
        ON_PAUSE: "En pause",
        ON_LUNCH: "En pause déjeuner",
        ON_TRAINING: "En formation",
        ON_MEETING: "En réunion",
        SHIFT_ENDED: "Shift terminé",
        DISCONNECTED: "Déconnecté"
    };

    // Empêche l'envoi d'un deuxième événement de shift tant qu'une requête
    // est déjà en cours (anti double-clic / double-soumission).
    var shiftActionInFlight = false;

    // Minuteur en direct — heure de début du statut courant + intervalle qui le fait tourner.
    var shiftCurrentStateSince = null;
    var shiftTimerInterval = null;

    function computeProfile(user) {
        if (user.role && user.role.toUpperCase() === "ADMIN") return "ADMIN";
        if (user.role && user.role.toUpperCase() === "RH") return "RH";
        if (user.role && user.role.toUpperCase() === "EXCELLIAM") return "EXCELLIAM";
        if (user.role && user.role.toUpperCase() === "SUPERVISOR") return "SUPERVISOR";
        if (user.role && user.role.toUpperCase() === "TEAM_LEADER") return "TEAM_LEADER";
        if (user.service && user.service.toUpperCase().replace(/_/g, " ") === "SUPERVISEUR QA") return "QA_SUPERVISOR";
        if (user.service && user.service.toLowerCase().replace(/_/g, " ") === "quality assurance") return "QA";
        if (user.service && user.service.toUpperCase() === "FORMATEUR") return "FORMATEUR";
        return "AGENT";
    }

    /** Extrait le segment de chemin utilisé comme "featureCode" pour une URL (ex. "/users" -> "users"). */
    function featureCodeFromPath(pathname) {
        var segment = pathname.split("/").filter(Boolean)[0];
        return segment ? segment.toLowerCase() : "";
    }

    /** Calcule si une fonctionnalité (featureCode) est autorisée pour l'utilisateur courant —
     *  même ordre de priorité que la sidebar : rôle par défaut < interdiction TabPermission
     *  (équipe/rôle) < dérogation individuelle (UserFeaturePermission), la plus spécifique
     *  l'emportant toujours. Exposée pour pouvoir aussi masquer des éléments hors sidebar
     *  (voir applyFeatureElementVisibility) avec exactement la même règle.
     */
    function computeFeatureAllowed(featureCode, roleAllowed, overridesByCode, denied) {
        var isAllowed = roleAllowed;
        if (denied[featureCode]) isAllowed = false;
        if (Object.prototype.hasOwnProperty.call(overridesByCode, featureCode)) {
            isAllowed = overridesByCode[featureCode];
        }
        return isAllowed;
    }

    /** Masque tout élément marqué data-feature-code="xxx" ailleurs sur la page si cette
     *  fonctionnalité est refusée à l'utilisateur — pour les fonctionnalités qui n'ont pas leur
     *  propre lien de sidebar (ex. "users" : gestion des utilisateurs, une section DANS la page
     *  Administration plutôt qu'un portail séparé). Un lien de sidebar n'a pas besoin de cet
     *  attribut, applySidebarVisibility s'en charge déjà via son href. */
    function applyFeatureElementVisibility(overridesByCode, deniedTabCodes) {
        var denied = {};
        (deniedTabCodes || []).forEach(function (code) { denied[code.toLowerCase()] = true; });
        var elements = document.querySelectorAll("[data-feature-code]");
        Array.prototype.forEach.call(elements, function (el) {
            var featureCode = el.getAttribute("data-feature-code").toLowerCase();
            // Pas de rôle par défaut connu ici (pas de data-roles sur ces éléments) : on ne
            // masque que sur interdiction explicite (TabPermission ou dérogation individuelle),
            // jamais par défaut — cohérent avec le fait que ces sections vivent déjà derrière
            // une page réservée à un rôle (ex. /administration = ADMIN uniquement).
            var isAllowed = true;
            if (denied[featureCode]) isAllowed = false;
            if (Object.prototype.hasOwnProperty.call(overridesByCode, featureCode)) {
                isAllowed = overridesByCode[featureCode];
            }
            el.style.display = isAllowed ? "" : "none";
        });
    }

    function applySidebarVisibility(profile, permissionOverrides, deniedTabCodes, isOutboundAgent) {
        var links = document.querySelectorAll(".sidebar-menu a[data-roles]");
        var currentPath = window.location.pathname;
        var overridesByCode = {};
        (permissionOverrides || []).forEach(function (p) { overridesByCode[p.featureCode] = p.isAllowed; });
        var denied = {};
        (deniedTabCodes || []).forEach(function (code) { denied[code.toLowerCase()] = true; });
        var outboundAgent = !!isOutboundAgent;

        Array.prototype.forEach.call(links, function (link) {
            var linkPath = new URL(link.href, window.location.origin).pathname;
            var featureCode = featureCodeFromPath(linkPath);

            var allowed = link.getAttribute("data-roles").split(",");
            var isAllowed = allowed.indexOf(profile) !== -1;

            // Un conseiller Outbound a ses propres masques de mail (Digitalisation/Prêt/
            // Assurance — voir seedOutboundMailTemplatesIfMissing()) donc GARDE ce lien ; en
            // revanche les procédures/Knowledge Base/Formation génériques (pensées pour
            // Inbound Voix/Mail) ne le concernent pas — remplacées par l'onglet "Parcours de
            // vente" de son propre tableau de bord Outbound.
            if ((featureCode === "procedures" || featureCode === "knowledge" || featureCode === "training" || featureCode === "performance") && outboundAgent) isAllowed = false;
            if (featureCode === "outbound-dashboard" && !outboundAgent) isAllowed = false;

            isAllowed = computeFeatureAllowed(featureCode, isAllowed, overridesByCode, denied);

            link.style.display = isAllowed ? "" : "none";

            // Surligne le lien correspondant à la page réellement affichée, pas
            // "Dashboard" en permanence (class="active" codé en dur dans le HTML).
            link.classList.toggle("active", linkPath === currentPath);
        });

        applyFeatureElementVisibility(overridesByCode, deniedTabCodes);
    }

    function applyHeader(user, profile) {
        var nameEl = document.getElementById("headerUserName");
        var roleEl = document.getElementById("headerUserRole");
        if (nameEl) nameEl.textContent = user.name || user.username || "—";
        if (roleEl) roleEl.textContent = PROFILE_LABELS[profile] || profile;

        var avatarEl = document.getElementById("headerUserAvatar");
        if (avatarEl) {
            fetch("/api/user-profiles/me", { credentials: "same-origin" })
                .then(function (res) { return res.ok ? res.json() : null; })
                .then(function (p) { if (p && p.photoUrl) avatarEl.src = p.photoUrl; })
                .catch(function () {});
        }
    }

    // ===== Suivi de shift =====

    var getJson = RccApi.getJson;

    function postJson(url, body) {
        return fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body || {})
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || ("HTTP " + res.status))); });
            return res.json();
        });
    }

    function setShiftButtonsDisabled(disabled) {
        [ "shiftPauseBtn", "shiftLunchBtn", "shiftTrainingBtn", "shiftMeetingBtn", "shiftResumeBtn", "shiftEndBtn" ].forEach(function (id) {
            var btn = document.getElementById(id);
            if (btn) btn.disabled = disabled;
        });
    }

    /**
     * Trouve l'heure de début du statut courant à partir du dernier événement du jour
     * (todayEvents est déjà trié par occurredAt croissant côté backend), puis fait tourner
     * un minuteur en direct — vert en poste, rouge en pause, blanc si le shift n'a pas
     * commencé ou est terminé (rien à chronométrer).
     */
    function updateShiftTimer(status) {
        var el = document.getElementById("shiftTimer");
        if (!el) return;

        clearInterval(shiftTimerInterval);

        var events = status.todayEvents || [];
        var lastEvent = events.length ? events[events.length - 1] : null;

        if ((status.currentState === "SHIFT_ENDED" || status.currentState === "NOT_STARTED") || !lastEvent) {
            shiftCurrentStateSince = null;
            updateResumeInfo(null);
            el.textContent = "--:--:--";
            el.style.color = "#ffffff";
            return;
        }

        // Début de l'état courant calculé par le serveur : après une déconnexion/reconnexion le
        // même jour, il est conservé — le minuteur reprend en continuité (absence comprise) au
        // lieu de repartir de zéro. Repli sur le dernier événement pour un ancien serveur.
        shiftCurrentStateSince = new Date(status.currentStateSince || lastEvent.occurredAt).getTime();
        updateResumeInfo(status);
        el.style.color = (status.currentState === "ON_PAUSE" || status.currentState === "ON_LUNCH") ? "#ef4444"
            : status.currentState === "ON_TRAINING" ? "#0057B8"
            : status.currentState === "ON_MEETING" ? "#F5A623"
            : "#22c55e";

        function tick() {
            var elapsed = Math.max(0, Math.floor((Date.now() - shiftCurrentStateSince) / 1000));
            var h = String(Math.floor(elapsed / 3600)).padStart(2, "0");
            var m = String(Math.floor((elapsed % 3600) / 60)).padStart(2, "0");
            var s = String(elapsed % 60).padStart(2, "0");
            el.textContent = h + ":" + m + ":" + s;
        }
        tick();
        shiftTimerInterval = setInterval(tick, 1000);
    }

    function hhmm(iso) {
        var d = new Date(iso);
        return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
    }

    function durationLabel(min) {
        return min < 60 ? min + " min" : Math.floor(min / 60) + "h" + String(min % 60).padStart(2, "0");
    }

    /**
     * Précise la continuité du minuteur après une reconnexion le même jour : heure de
     * déconnexion, heure de reprise et temps d'absence inclus dans le minuteur.
     */
    function updateResumeInfo(status) {
        var info = document.getElementById("shiftResumeInfo");
        if (!info) return;
        if (!status || !status.lastDisconnectedAt || !status.lastReconnectedAt) {
            info.style.display = "none";
            info.textContent = "";
            return;
        }
        var text = "Reprise à " + hhmm(status.lastReconnectedAt) + " (déconnecté à " + hhmm(status.lastDisconnectedAt) + ")";
        if (status.absenceMinutesInCurrentState > 0) {
            text += " — minuteur en continuité, dont " + durationLabel(status.absenceMinutesInCurrentState) + " d'absence";
        }
        if (status.absenceMinutesToday > status.absenceMinutesInCurrentState) {
            text += " · absence totale du jour : " + durationLabel(status.absenceMinutesToday);
        }
        info.textContent = text;
        info.title = (status.absences || []).map(function (a) {
            return hhmm(a.disconnectedAt) + " → " + (a.reconnectedAt ? hhmm(a.reconnectedAt) : "…") + " (" + durationLabel(a.minutes) + ")";
        }).join("\n");
        info.style.display = "";
    }

    function applyShiftUi(status) {
        var badge = document.getElementById("shiftStatusBadge");
        var pauseBtn = document.getElementById("shiftPauseBtn");
        var lunchBtn = document.getElementById("shiftLunchBtn");
        var trainingBtn = document.getElementById("shiftTrainingBtn");
        var meetingBtn = document.getElementById("shiftMeetingBtn");
        var resumeBtn = document.getElementById("shiftResumeBtn");
        var endBtn = document.getElementById("shiftEndBtn");
        var logoutBtn = document.getElementById("shiftLogoutBtn");

        if (!badge) return; // page sans sidebar (ex. login)

        badge.textContent = SHIFT_STATE_LABELS[status.currentState] || status.currentState;

        updateShiftTimer(status);

        pauseBtn.style.display = "none";
        lunchBtn.style.display = "none";
        trainingBtn.style.display = "none";
        meetingBtn.style.display = "none";
        resumeBtn.style.display = "none";
        endBtn.style.display = "none";
        // Déconnexion simple possible tant que le shift n'est pas terminé.
        if (logoutBtn) logoutBtn.style.display = status.currentState === "SHIFT_ENDED" ? "none" : "";

        // NOT_STARTED traité comme WORKING pour l'affichage : l'agent est bien
        // connecté (sinon il serait déjà redirigé vers /login) — seul l'événement
        // LOGIN n'a pas été posé (session ouverte avant l'ajout du suivi de shift,
        // par exemple). Il ne doit jamais rester sans aucun bouton cliquable.
        if (status.currentState === "WORKING" || status.currentState === "NOT_STARTED") {
            pauseBtn.style.display = "";
            lunchBtn.style.display = "";
            trainingBtn.style.display = "";
            meetingBtn.style.display = "";
            endBtn.style.display = "";
        } else if (status.currentState === "ON_PAUSE" || status.currentState === "ON_LUNCH"
                || status.currentState === "ON_TRAINING" || status.currentState === "ON_MEETING") {
            resumeBtn.style.display = "";
            endBtn.style.display = "";
        }
        // SHIFT_ENDED : aucun bouton (tous restent display:none), volontaire.

        // Une resynchronisation ne doit jamais laisser un bouton désactivé par
        // une action précédente encore verrouillée si aucune requête n'est en cours.
        if (!shiftActionInFlight) setShiftButtonsDisabled(false);
    }

    function refreshShiftStatus() {
        return getJson("/api/shift/me").then(applyShiftUi).catch(function (e) { console.error(e); });
    }

    function recordShiftEvent(eventType) {
        if (shiftActionInFlight) return Promise.resolve(); // clic ignoré : une requête est déjà en cours
        shiftActionInFlight = true;
        setShiftButtonsDisabled(true);

        return postJson("/api/shift/me/event", { eventType: eventType })
            .then(function (status) {
                shiftActionInFlight = false;
                applyShiftUi(status);
                return status;
            })
            .catch(function (e) {
                shiftActionInFlight = false;
                alert("Erreur : " + e.message);
                // Resynchronise l'UI avec le véritable état backend (ex : un
                // deuxième clic arrivé après que le premier a déjà fait
                // transitionner l'état — l'UI doit refléter la réalité, pas
                // rester bloquée sur un bouton qui ne devrait plus être actif).
                return refreshShiftStatus();
            });
    }

    // Profils qui n'ont pas de shift personnel à pointer — ils reçoivent un bouton
    // Déconnexion à la place du widget de suivi de shift dans la barre latérale.
    var NO_SHIFT_PROFILES = ["TEAM_LEADER", "SUPERVISOR", "RH", "EXCELLIAM", "ADMIN"];

    /** Remplace le contenu du widget de shift par un simple bouton Déconnexion. */
    function wireSidebarLogoutButton(shiftWidget) {
        // Le bouton est inséré après l'application initiale des traductions (DOMContentLoaded) :
        // on traduit donc le libellé nous-mêmes via RccPreferences plutôt que de compter sur
        // data-i18n, qui ne serait relu qu'à un futur changement de langue.
        var label = (window.RccPreferences && window.RccPreferences.t) ? window.RccPreferences.t("nav.logout") : "Déconnexion";
        shiftWidget.innerHTML =
            '<div class="d-grid">' +
            '<button id="sidebarLogoutBtn" type="button" class="btn btn-sm btn-outline-light">' +
            '<i class="bi bi-box-arrow-right"></i> <span id="sidebarLogoutBtnLabel"></span>' +
            '</button>' +
            '</div>';
        document.getElementById("sidebarLogoutBtnLabel").textContent = label;
        var btn = document.getElementById("sidebarLogoutBtn");
        if (!btn) return;
        btn.addEventListener("click", function () {
            if (!confirm("Voulez-vous vous déconnecter ?")) return;
            btn.disabled = true;
            fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" })
                .catch(function () {})
                .finally(function () { window.location.href = "/login"; });
        });
    }

    function wireShiftButtons() {
        var pauseBtn = document.getElementById("shiftPauseBtn");
        var lunchBtn = document.getElementById("shiftLunchBtn");
        var trainingBtn = document.getElementById("shiftTrainingBtn");
        var meetingBtn = document.getElementById("shiftMeetingBtn");
        var resumeBtn = document.getElementById("shiftResumeBtn");
        var endBtn = document.getElementById("shiftEndBtn");

        if (!pauseBtn) return;

        pauseBtn.addEventListener("click", function () { recordShiftEvent("PAUSE_START"); });
        lunchBtn.addEventListener("click", function () { recordShiftEvent("LUNCH_START"); });
        trainingBtn.addEventListener("click", function () { recordShiftEvent("TRAINING_START"); });
        meetingBtn.addEventListener("click", function () { recordShiftEvent("MEETING_START"); });

        var RESUME_EVENT_BY_STATE = {
            ON_LUNCH: "LUNCH_END", ON_TRAINING: "TRAINING_END", ON_MEETING: "MEETING_END", ON_PAUSE: "PAUSE_END"
        };
        resumeBtn.addEventListener("click", function () {
            if (shiftActionInFlight) return;
            getJson("/api/shift/me").then(function (status) {
                recordShiftEvent(RESUME_EVENT_BY_STATE[status.currentState] || "PAUSE_END");
            });
        });

        var logoutBtn = document.getElementById("shiftLogoutBtn");
        if (logoutBtn) {
            logoutBtn.addEventListener("click", function () {
                if (shiftActionInFlight) return;
                if (!confirm("Vous déconnecter sans terminer votre shift ?\n\n" +
                        "L'heure de déconnexion est enregistrée et visible par votre Team Leader, les RH et le superviseur. " +
                        "Si vous vous reconnectez aujourd'hui, votre minuteur reprendra en continuité (temps d'absence indiqué).")) return;
                logoutBtn.disabled = true;
                fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" })
                    .catch(function () {})
                    .finally(function () { window.location.href = "/login"; });
            });
        }

        endBtn.addEventListener("click", function () {
            if (shiftActionInFlight) return;
            if (!confirm("Terminer votre shift ? Vous serez déconnecté.")) return;
            recordShiftEvent("SHIFT_END").then(function () {
                return fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
            }).finally(function () {
                window.location.href = "/login";
            });
        });
    }

    var globalSearchModal = null;
    var globalSearchDebounceTimer = null;

    function wireGlobalSearch() {
        var form = document.getElementById("globalSearchForm");
        if (!form) return; // page sans header (ex. login)

        var input = document.getElementById("globalSearchInput");
        var modalEl = document.getElementById("globalSearchModal");

        form.addEventListener("submit", function (evt) {
            evt.preventDefault();
            runGlobalSearch(input.value.trim(), modalEl);
        });

        input.addEventListener("input", function () {
            var term = input.value.trim();
            clearTimeout(globalSearchDebounceTimer);
            if (term.length < 2) return; // pas de recherche prématurée sur 1 caractère
            globalSearchDebounceTimer = setTimeout(function () { runGlobalSearch(term, modalEl); }, 350);
        });
    }

    var globalSearchSeq = 0;

    function runGlobalSearch(term, modalEl) {
        if (!term) return;
        if (!globalSearchModal) globalSearchModal = new bootstrap.Modal(modalEl);
        var resultsBox = document.getElementById("globalSearchResults");
        document.getElementById("globalSearchModalTitle").innerHTML = '<i class="bi bi-search"></i> Résultats pour « ' + RccApi.escapeHtml(term) + ' »';
        resultsBox.innerHTML = '<p class="text-muted text-center">Recherche en cours…</p>';
        globalSearchModal.show();

        // Numéro de requête : avec la recherche « au fil de la frappe », une réponse lente pour
        // « car » pouvait arriver après celle de « carte bloquée » et écraser les bons résultats.
        var seq = ++globalSearchSeq;
        fetch("/api/ralph/search?keyword=" + encodeURIComponent(term), { credentials: "same-origin" })
            .then(function (res) { return res.ok ? res.json() : { results: [], webResults: [] }; })
            .then(function (data) {
                if (seq !== globalSearchSeq) return;
                renderGlobalSearchResults(data.results || [], data.webResults || [], term);
            })
            .catch(function () {
                if (seq !== globalSearchSeq) return;
                resultsBox.innerHTML = '<p class="text-danger text-center">La recherche a échoué. Réessayez.</p>';
            });
    }

    var SEARCH_TYPE_META = {
        ARTICLE: { icon: "bi-book-fill", label: "Knowledge Base" },
        PROCEDURE: { icon: "bi-list-check", label: "Procédure" },
        COURSE: { icon: "bi-mortarboard-fill", label: "Formation" },
        QUIZ: { icon: "bi-clipboard-check", label: "Évaluation" }
    };

    function renderGlobalSearchResults(results, webResults, term) {
        var resultsBox = document.getElementById("globalSearchResults");
        var webHtml = "";
        if (webResults && webResults.length) {
            webHtml = '<div class="small text-muted mt-2 mb-1"><i class="bi bi-globe"></i> Résultats web</div>' +
                webResults.map(function (w) {
                    return '<a href="' + escapeHref(w.url) + '" target="_blank" rel="noopener" ' +
                        'class="d-flex gap-3 p-2 mb-1 rounded text-decoration-none text-reset global-search-result-link">' +
                        '<div style="font-size:1.3rem;" class="text-muted"><i class="bi bi-box-arrow-up-right"></i></div>' +
                        '<div class="flex-grow-1">' +
                            '<div class="fw-semibold">' + RccApi.escapeHtml(w.title) + '</div>' +
                            (w.snippet ? '<div class="small text-muted">' + RccApi.escapeHtml(w.snippet) + '</div>' : "") +
                        '</div></a>';
                }).join("");
        }

        if (!results.length && !webHtml) {
            resultsBox.innerHTML = '<p class="text-muted text-center">Aucun résultat pour « ' + RccApi.escapeHtml(term) + ' ».</p>' +
                '<div class="text-center"><a class="btn btn-sm btn-outline-primary" target="_blank" rel="noopener" ' +
                'href="https://www.google.com/search?q=' + encodeURIComponent(term) + '">' +
                '<i class="bi bi-box-arrow-up-right"></i> Chercher « ' + RccApi.escapeHtml(term) + ' » sur Google</a></div>';
            return;
        }
        var internalHtml = results.map(function (r) {
            var meta = SEARCH_TYPE_META[r.sourceType] || { icon: "bi-file-earmark", label: r.sourceType };
            // Lien précis par ID — ouvre directement l'élément concerné (article, procédure,
            // cours) sans repasser par une recherche à reformuler ni un second clic.
            var href = r.sourceType === "ARTICLE" ? "/knowledge?article=" + r.id
                : r.sourceType === "PROCEDURE" ? "/procedures?openProcedureId=" + r.id
                : r.sourceType === "COURSE" ? "/training?openCourseId=" + r.id
                : "/games";
            return '<a href="' + href + '" class="d-flex gap-3 p-2 mb-1 rounded text-decoration-none text-reset global-search-result-link">' +
                '<div style="font-size:1.3rem;" class="text-primary"><i class="bi ' + meta.icon + '"></i></div>' +
                '<div class="flex-grow-1">' +
                    '<div class="small text-muted">' + meta.label + '</div>' +
                    '<div class="fw-semibold">' + RccApi.escapeHtml(r.title) + '</div>' +
                    (r.snippet ? '<div class="small text-muted">' + RccApi.escapeHtml(r.snippet) + '</div>' : "") +
                '</div></a>';
        }).join("");

        resultsBox.innerHTML = internalHtml + webHtml +
            '<div class="text-center mt-2 pt-2 border-top">' +
                '<a class="small text-muted" target="_blank" rel="noopener" href="https://www.google.com/search?q=' + encodeURIComponent(term) + '">' +
                '<i class="bi bi-box-arrow-up-right"></i> Chercher aussi « ' + RccApi.escapeHtml(term) + ' » sur Google</a></div>';
    }

    /** Échappe une URL pour l'attribut href — évite l'injection tout en gardant l'URL fonctionnelle. */
    function escapeHref(url) {
        return RccApi.escapeHtml(url || "#");
    }

    function refreshNotificationBadge() {
        var badge = document.getElementById("notifBadge");
        if (!badge) return;
        fetch("/api/mon-rcc/notifications/me", { credentials: "same-origin" })
            .then(function (res) { return res.ok ? res.json() : []; })
            .then(function (rows) {
                var unread = rows.filter(function (r) { return !r.isRead; }).length;
                if (unread > 0) {
                    badge.textContent = unread > 9 ? "9+" : String(unread);
                    badge.style.display = "";
                } else {
                    badge.style.display = "none";
                }
            })
            .catch(function () {});
    }

    function init() {
        wireGlobalSearch();
        return fetch("/api/auth/me", { credentials: "same-origin" })
            .then(function (res) {
                if (!res.ok) throw new Error("HTTP " + res.status);
                return res.json();
            })
            .then(function (user) {
                if (!user || !user.username) {
                    window.location.href = "/login";
                    return null;
                }
                var profile = computeProfile(user);

                // Source unique de vérité pour "cet agent est-il Outbound ?" — calculé côté
                // serveur via TeamClassifier (fiable), plutôt qu'un champ "activity" qui
                // n'existe pas sur le payload JWT (/api/auth/me) et valait donc toujours
                // undefined ici — la détection Outbound côté client ne fonctionnait jamais.
                var teamStatusPromise = fetch("/api/users/me/team-status", { credentials: "same-origin" })
                    .then(function (res) { return res.ok ? res.json() : { redirectTo: null }; })
                    .catch(function () { return { redirectTo: null }; });

                return Promise.all([
                    fetch("/api/users/me/permissions", { credentials: "same-origin" })
                        .then(function (res) { return res.ok ? res.json() : []; })
                        .catch(function () { return []; }),
                    fetch("/api/tab-permissions/me", { credentials: "same-origin" })
                        .then(function (res) { return res.ok ? res.json() : { deniedTabCodes: [] }; })
                        .catch(function () { return { deniedTabCodes: [] }; }),
                    teamStatusPromise
                ]).then(function (results) {
                    var permissionOverrides = results[0];
                    var deniedTabCodes = results[1].deniedTabCodes || [];
                    var teamStatus = results[2];

                    var isOutboundAgent = profile === "AGENT" && teamStatus.redirectTo === "/outbound-dashboard";
                    applySidebarVisibility(profile, permissionOverrides, deniedTabCodes, isOutboundAgent);
                    applyHeader(user, profile);

                    // Suivi de shift (pointeuse personnelle) — sans objet pour ces profils : ils
                    // ne pointent pas comme un agent (Team Leader, Superviseur, RH, Excelliam,
                    // Administrateur). Le widget de shift est remplacé par un simple bouton
                    // Déconnexion, à l'emplacement où s'affichait le suivi de shift, pour qu'ils
                    // gardent un moyen de se déconnecter depuis ces portails.
                    var shiftWidget = document.querySelector(".sidebar-footer");
                    if (shiftWidget && NO_SHIFT_PROFILES.indexOf(profile) !== -1) {
                        wireSidebarLogoutButton(shiftWidget);
                    } else {
                        wireShiftButtons();
                        refreshShiftStatus();
                    }

                    refreshNotificationBadge();
                    return { user: user, profile: profile, isOutboundAgent: isOutboundAgent };
                });
            })
            .catch(function (e) {
                console.error("RccSession init failed:", e);
                window.location.href = "/login";
                return null;
            });
    }

    return { init: init };
})();

document.addEventListener("DOMContentLoaded", function () {
    window.RccSession.init();
});