"use strict";

/**
 * Utilitaires fetch partagés — évite de dupliquer getJson/sendJson/escapeHtml
 * dans chaque page. Comportement standardisé : redirection vers /login sur
 * 401, message d'erreur lisible sur tout autre statut non-2xx.
 */
window.RccApi = (function () {

    /**
     * Le backend renvoie ses erreurs au format { "error": { "code", "message" } }
     * (voir GlobalExceptionHandler). Sans ce parsing, un simple `new Error(text)`
     * affiche le JSON brut dans les popups d'erreur ("Erreur : {"error":{...}}")
     * au lieu du message lisible — d'où cette extraction avant de rejeter.
     */
    function toErrorMessage(text, status) {
        if (text) {
            try {
                var parsed = JSON.parse(text);
                if (parsed && parsed.error && parsed.error.message) {
                    return parsed.error.message;
                }
            } catch (e) {
                // Pas du JSON (ex. erreur HTML de conteneur) — on retombe sur le texte brut.
            }
        }
        return text || ("HTTP " + status);
    }

    function getJson(url) {
        return fetch(url, { credentials: "same-origin" }).then(function (res) {
            if (res.status === 401) {
                window.location.href = "/login";
                return Promise.reject(new Error("unauthenticated"));
            }
            if (!res.ok) {
                return res.text().then(function (t) { return Promise.reject(new Error(toErrorMessage(t, res.status))); });
            }
            return res.json();
        });
    }

    function sendJson(url, method, body) {
        return fetch(url, {
            method: method,
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body === undefined ? {} : body)
        }).then(function (res) {
            if (res.status === 401) {
                window.location.href = "/login";
                return Promise.reject(new Error("unauthenticated"));
            }
            if (!res.ok) {
                return res.text().then(function (t) { return Promise.reject(new Error(toErrorMessage(t, res.status))); });
            }
            return res.status === 204 ? null : res.json();
        });
    }

    function escapeHtml(str) {
        var div = document.createElement("div");
        div.textContent = str == null ? "" : str;
        return div.innerHTML;
    }

    return { getJson: getJson, sendJson: sendJson, escapeHtml: escapeHtml };
})();
