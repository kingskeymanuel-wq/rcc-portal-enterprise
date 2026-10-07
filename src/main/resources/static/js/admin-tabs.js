"use strict";

/**
 * Administration — un onglet à la fois : Personnes, Base de données, Équipes & accès, Apparence, Référentiel SLA,
 * Maintenance. L'onglet ouvert est gardé dans l'adresse (#base…) pour revenir au même endroit après un rechargement.
 */
(function () {
    function show(key) {
        var nav = document.getElementById("adminTabs");
        if (!nav) return;
        var btn = nav.querySelector('[data-tab="' + key + '"]') || nav.querySelector("[data-tab]");
        key = btn.getAttribute("data-tab");
        nav.querySelectorAll("[data-tab]").forEach(function (b) { b.classList.toggle("on", b === btn); b.setAttribute("aria-selected", b === btn); });
        document.querySelectorAll(".adm-pane").forEach(function (p) { p.hidden = p.getAttribute("data-pane") !== key; });
        if (location.hash !== "#" + key) history.replaceState(null, "", "#" + key);
    }

    document.addEventListener("DOMContentLoaded", function () {
        var nav = document.getElementById("adminTabs");
        if (!nav) return;
        nav.addEventListener("click", function (e) {
            var b = e.target.closest("[data-tab]");
            if (b) { show(b.getAttribute("data-tab")); window.scrollTo({ top: 0, behavior: "smooth" }); }
        });
        // « Ajouter un utilisateur » (en-tête) : le formulaire est dans l'onglet Personnes.
        var create = document.getElementById("createUserBtn");
        if (create) create.addEventListener("click", function () { show("personnes"); });
        show((location.hash || "").replace("#", "") || "personnes");
    });
})();
