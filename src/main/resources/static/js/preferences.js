"use strict";

/**
 * Préférences transverses (langue, thème clair/sombre) — appliquées
 * automatiquement au chargement de chaque page, avant même l'affichage
 * du contenu, pour éviter tout flash de la mauvaise langue/du mauvais thème.
 * Persistées en local (localStorage) — synchronisation multi-appareil pas
 * encore faite, mais l'API RccPreferences ci-dessous peut être branchée
 * plus tard sur un stockage serveur sans changer les appels des autres pages.
 */
window.RccPreferences = (function () {

    var DICTIONARY = {
        fr: {
            "nav.dashboard": "Accueil",
            "nav.users": "Utilisateurs",
            "nav.performance": "Ma Performance",
            "nav.monrcc": "MON RCC",
            "nav.procedures": "Procédure de traitement",
            "nav.mailTemplates": "Masques de mail",
            "nav.knowledge": "Knowledge Base",
            "nav.training": "Formation",
            "nav.games": "Évaluation",
            "nav.teamLeader": "Portail Team Leader",
            "nav.outboundDashboard": "Ventes & RDV",
            "nav.workflow": "Workflow",
            "nav.qa": "Quality Assurance",
            "nav.reports": "Reporting",
            "nav.shift": "Suivi de shift",
            "nav.hrParcours": "Mon Parcours",
            "nav.audit": "Audit",
            "nav.administration": "Administration",
            "nav.dataAnalysis": "Analyse de données",
            "nav.logout": "Déconnexion",
            "shift.pause": "Pause",
            "shift.lunch": "Pause déjeuner",
            "shift.training": "Formation",
            "shift.meeting": "Réunion",
            "shift.resume": "Reprendre",
            "shift.end": "Fin de shift",
            "header.search": "Rechercher une procédure, un article, un cours...",
            "dashboard.tools": "Vos outils",
            "dashboard.externalTools": "Outils & Portails",
            "settings.title": "Paramètres",
            "settings.subtitle": "Votre profil et vos préférences",
            "settings.myProfile": "Mon profil",
            "settings.phone": "Téléphone",
            "settings.birthdate": "Date de naissance",
            "settings.bio": "Bio",
            "settings.save": "Enregistrer",
            "settings.appearance": "Apparence",
            "settings.language": "Langue",
            "settings.theme": "Thème",
            "settings.themeLight": "Clair",
            "settings.themeDark": "Sombre",
            "notifications.title": "Notifications",
            "notifications.subtitle": "Toutes vos notifications",
            "notifications.empty": "Aucune notification.",
            "notifications.markRead": "Marquer comme lue",
            "notifications.read": "Lue"
        },
        en: {
            "nav.dashboard": "Home",
            "nav.users": "Users",
            "nav.performance": "My Performance",
            "nav.monrcc": "MY RCC",
            "nav.procedures": "Procedures",
            "nav.mailTemplates": "Mail Templates",
            "nav.knowledge": "Knowledge Base",
            "nav.training": "Training",
            "nav.games": "Evaluation",
            "nav.teamLeader": "Team Leader Portal",
            "nav.outboundDashboard": "Sales & Appointments",
            "nav.workflow": "Workflow",
            "nav.qa": "Quality Assurance",
            "nav.reports": "Reporting",
            "nav.shift": "Shift Tracking",
            "nav.hrParcours": "My Track",
            "nav.audit": "Audit",
            "nav.administration": "Administration",
            "nav.dataAnalysis": "Data Analysis",
            "nav.logout": "Log out",
            "shift.pause": "Pause",
            "shift.lunch": "Lunch break",
            "shift.training": "Training",
            "shift.meeting": "Meeting",
            "shift.resume": "Resume",
            "shift.end": "End shift",
            "header.search": "Search a procedure, an article, a course...",
            "dashboard.tools": "Your tools",
            "dashboard.externalTools": "Tools & Portals",
            "settings.title": "Settings",
            "settings.subtitle": "Your profile and preferences",
            "settings.myProfile": "My profile",
            "settings.phone": "Phone",
            "settings.birthdate": "Date of birth",
            "settings.bio": "Bio",
            "settings.save": "Save",
            "settings.appearance": "Appearance",
            "settings.language": "Language",
            "settings.theme": "Theme",
            "settings.themeLight": "Light",
            "settings.themeDark": "Dark",
            "notifications.title": "Notifications",
            "notifications.subtitle": "All your notifications",
            "notifications.empty": "No notifications.",
            "notifications.markRead": "Mark as read",
            "notifications.read": "Read"
        }
    };

    function getLanguage() {
        return localStorage.getItem("rcc_lang") || "fr";
    }

    function getTheme() {
        return localStorage.getItem("rcc_theme") || "light";
    }

    var FONT_STACKS = {
        system: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
        classic: "Arial, Helvetica, sans-serif",
        serif: "Georgia, 'Times New Roman', serif",
        rounded: "'Trebuchet MS', 'Comic Sans MS', sans-serif",
        mono: "'Courier New', Consolas, monospace"
    };

    function getFont() {
        return localStorage.getItem("rcc_font") || "system";
    }

    function applyFont() {
        var font = getFont();
        document.documentElement.style.setProperty("--rcc-font-family", FONT_STACKS[font] || FONT_STACKS.system);
        document.documentElement.setAttribute("data-font", font);
    }

    function setFont(font) {
        localStorage.setItem("rcc_font", font);
        applyFont();
    }

    function t(key) {
        var lang = getLanguage();
        return (DICTIONARY[lang] && DICTIONARY[lang][key]) || (DICTIONARY.fr[key]) || key;
    }

    function applyTranslations() {
        var lang = getLanguage();
        document.documentElement.setAttribute("lang", lang);

        Array.prototype.forEach.call(document.querySelectorAll("[data-i18n]"), function (el) {
            el.textContent = t(el.getAttribute("data-i18n"));
        });
        Array.prototype.forEach.call(document.querySelectorAll("[data-i18n-placeholder]"), function (el) {
            el.setAttribute("placeholder", t(el.getAttribute("data-i18n-placeholder")));
        });
    }

    function applyTheme() {
        document.documentElement.setAttribute("data-theme", getTheme());
    }

    function setLanguage(lang) {
        localStorage.setItem("rcc_lang", lang);
        applyTranslations();
    }

    function setTheme(theme) {
        localStorage.setItem("rcc_theme", theme);
        applyTheme();
    }

    // ── Préférences génériques (accessibilité, notifications) ─────────────────
    var DEFAULTS = {
        fontScale: "100", reduceMotion: "0", contrast: "0", density: "comfort",
        notifSound: "1", notifDesktop: "0", notifPollSeconds: "60", accent: "blue"
    };
    var ACCENTS = { blue: ["#0057B8", "#004a9e"], green: ["#00A651", "#008a44"], violet: ["#6a2bd9", "#5723b5"], orange: ["#e8710a", "#c65f06"], teal: ["#0d7f86", "#0a6a70"] };

    function get(key) {
        try { var v = localStorage.getItem("rcc_" + key); return v === null ? DEFAULTS[key] : v; } catch (e) { return DEFAULTS[key]; }
    }
    function set(key, value) {
        try { localStorage.setItem("rcc_" + key, String(value)); } catch (e) { /* stockage indisponible */ }
        applyAccessibility();
    }

    /** Taille du texte, animations réduites, contraste renforcé, densité, couleur d'accent. */
    function applyAccessibility() {
        var root = document.documentElement;
        root.style.fontSize = (Number(get("fontScale")) || 100) + "%";
        root.toggleAttribute("data-reduce-motion", get("reduceMotion") === "1");
        root.toggleAttribute("data-high-contrast", get("contrast") === "1");
        root.setAttribute("data-density", get("density"));
        var accent = ACCENTS[get("accent")] || ACCENTS.blue;
        root.style.setProperty("--bs-primary", accent[0]);
        root.style.setProperty("--rcc-accent", accent[0]);
        root.style.setProperty("--rcc-accent-dark", accent[1]);
        root.setAttribute("data-accent", get("accent"));
    }

    function injectAccessibilityCss() {
        if (document.getElementById("rccA11yCss")) return;
        var st = document.createElement("style");
        st.id = "rccA11yCss";
        st.textContent =
            "html[data-reduce-motion] *, html[data-reduce-motion] *::before, html[data-reduce-motion] *::after{animation-duration:.001ms!important;animation-iteration-count:1!important;transition-duration:.001ms!important;scroll-behavior:auto!important}" +
            "html[data-high-contrast] body{color:#000}html[data-high-contrast] .text-muted,html[data-high-contrast] small{color:#1f2937!important}" +
            "html[data-high-contrast] .card,html[data-high-contrast] .dashboard-card{border:1.5px solid #4a5568!important}" +
            "html[data-high-contrast] a{text-decoration:underline}html[data-high-contrast] :focus-visible{outline:3px solid #ffbf00!important;outline-offset:2px}" +
            "html[data-density=compact] .card-body{padding:.75rem}html[data-density=compact] .container-fluid{padding-left:.75rem;padding-right:.75rem}" +
            "html[data-density=compact] .mb-4{margin-bottom:1rem!important}html[data-density=compact] .g-4{--bs-gutter-y:1rem;--bs-gutter-x:1rem}" +
            "html:not([data-accent=blue]) .btn-primary{--bs-btn-bg:var(--rcc-accent);--bs-btn-border-color:var(--rcc-accent);--bs-btn-hover-bg:var(--rcc-accent-dark);--bs-btn-hover-border-color:var(--rcc-accent-dark);--bs-btn-active-bg:var(--rcc-accent-dark)}" +
            "html:not([data-accent=blue]) .btn-outline-primary{--bs-btn-color:var(--rcc-accent);--bs-btn-border-color:var(--rcc-accent);--bs-btn-hover-bg:var(--rcc-accent);--bs-btn-hover-border-color:var(--rcc-accent);--bs-btn-active-bg:var(--rcc-accent)}" +
            "html:not([data-accent=blue]) .text-primary{color:var(--rcc-accent)!important}html:not([data-accent=blue]) .bg-primary{background-color:var(--rcc-accent)!important}";
        (document.head || document.documentElement).appendChild(st);
    }

    // ── Notifications (son + bureau) ─────────────────────────────────────────
    function playSound() {
        try {
            var Ctx = window.AudioContext || window.webkitAudioContext;
            if (!Ctx) return;
            var ctx = new Ctx(), now = ctx.currentTime;
            [880, 1320].forEach(function (f, i) {
                var o = ctx.createOscillator(), g = ctx.createGain();
                o.type = "sine"; o.frequency.value = f;
                g.gain.setValueAtTime(0.0001, now + i * 0.14);
                g.gain.exponentialRampToValueAtTime(0.18, now + i * 0.14 + 0.02);
                g.gain.exponentialRampToValueAtTime(0.0001, now + i * 0.14 + 0.22);
                o.connect(g); g.connect(ctx.destination);
                o.start(now + i * 0.14); o.stop(now + i * 0.14 + 0.25);
            });
            setTimeout(function () { ctx.close(); }, 800);
        } catch (e) { /* audio bloqué */ }
    }

    function desktopNotify(title, body) {
        if (!("Notification" in window) || Notification.permission !== "granted") return;
        try {
            var n = new Notification(title, { body: body || "", icon: "/images/ecobank-logo.png", tag: "rcc-notif" });
            n.onclick = function () { window.focus(); window.location.href = "/notifications"; };
        } catch (e) { /* ignore */ }
    }

    /** Appelé par session.js quand de nouvelles notifications non lues arrivent. */
    function notifyNew(count, latest) {
        if (get("notifSound") === "1") playSound();
        if (get("notifDesktop") === "1") desktopNotify(count > 1 ? count + " nouvelles notifications RCC" : "Nouvelle notification RCC", latest || "");
    }

    // Applique le thème et la police immédiatement (avant même DOMContentLoaded) pour éviter le flash.
    applyTheme();
    applyFont();
    injectAccessibilityCss();
    applyAccessibility();
    document.addEventListener("DOMContentLoaded", applyTranslations);

    return { t: t, getLanguage: getLanguage, getTheme: getTheme, setLanguage: setLanguage, setTheme: setTheme,
             getFont: getFont, setFont: setFont, FONT_STACKS: FONT_STACKS,
             get: get, set: set, ACCENTS: ACCENTS, playSound: playSound, desktopNotify: desktopNotify, notifyNew: notifyNew };
})();
