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

    // Applique le thème et la police immédiatement (avant même DOMContentLoaded) pour éviter le flash.
    applyTheme();
    applyFont();
    document.addEventListener("DOMContentLoaded", applyTranslations);

    return { t: t, getLanguage: getLanguage, getTheme: getTheme, setLanguage: setLanguage, setTheme: setTheme,
             getFont: getFont, setFont: setFont, FONT_STACKS: FONT_STACKS };
})();
