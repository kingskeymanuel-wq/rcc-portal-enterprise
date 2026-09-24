"use strict";

/**
 * Traducteur & correcteur — mode « Agrandir » : l'outil occupe tout l'écran (et passe en plein écran
 * navigateur quand c'est possible). Échap ou le même bouton pour réduire.
 */
(function () {
    var wrap = document.getElementById("trWrap");
    var btn = document.getElementById("trExpandBtn");
    if (!wrap || !btn) return;

    function setExpanded(on, fromFullscreenChange) {
        wrap.classList.toggle("tr-is-expanded", on);
        document.body.classList.toggle("tr-expanded", on);
        btn.setAttribute("aria-pressed", on ? "true" : "false");
        btn.title = on ? "Réduire" : "Agrandir l'outil (plein écran)";
        btn.innerHTML = on ? '<i class="bi bi-fullscreen-exit"></i> <span>Réduire</span>'
                           : '<i class="bi bi-arrows-fullscreen"></i> <span>Agrandir</span>';
        if (!fromFullscreenChange) {
            try {
                if (on && wrap.requestFullscreen && !document.fullscreenElement) wrap.requestFullscreen().catch(function () {});
                if (!on && document.fullscreenElement) document.exitFullscreen().catch(function () {});
            } catch (e) { /* plein écran refusé : le mode agrandi CSS suffit */ }
        }
        var focusTarget = document.getElementById("trPaneTranslate").style.display === "none"
            ? document.getElementById("scEditor") : document.getElementById("sourceText");
        if (on && focusTarget) setTimeout(function () { focusTarget.focus(); }, 150);
    }

    btn.addEventListener("click", function () { setExpanded(!wrap.classList.contains("tr-is-expanded")); });
    document.addEventListener("keydown", function (e) {
        if (e.key === "Escape" && wrap.classList.contains("tr-is-expanded")) setExpanded(false);
    });
    // Sortie du plein écran navigateur (Échap) → on réduit aussi l'outil.
    document.addEventListener("fullscreenchange", function () {
        if (!document.fullscreenElement && wrap.classList.contains("tr-is-expanded")) setExpanded(false, true);
    });
})();
