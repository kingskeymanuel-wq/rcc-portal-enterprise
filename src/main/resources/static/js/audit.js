"use strict";

(function () {

    var EVENT_LABELS = {
        login_success: "Connexion réussie",
        login_failed: "Échec de connexion",
        locked: "Compte verrouillé",
        password_reset: "Réinitialisation de mot de passe"
    };

    var escapeHtml = RccApi.escapeHtml;

    var getJson = RccApi.getJson;

    function postJson(url, body) {
        return fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body || {})
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || ("HTTP " + res.status))); });
            return res.status === 204 ? null : res.json();
        });
    }

    function formatDate(iso) {
        return new Date(iso).toLocaleString("fr-FR");
    }

    // ===== Journal de connexions =====

    function renderLogins(rows) {
        var body = document.getElementById("loginsBody");
        if (!rows.length) {
            body.innerHTML = '<tr><td colspan="4" class="text-center text-muted">Aucun événement.</td></tr>';
            return;
        }
        body.innerHTML = rows.map(function (r) {
            return "" +
                "<tr>" +
                "<td>" + escapeHtml(r.fullName || r.username) + "</td>" +
                "<td>" + escapeHtml(EVENT_LABELS[r.eventType] || r.eventType) + "</td>" +
                "<td>" + formatDate(r.occurredAt) + "</td>" +
                "<td>" + escapeHtml(r.ipAddress || "—") + "</td>" +
                "</tr>";
        }).join("");
    }

    function loadLogins() {
        getJson("/api/audit/logins").then(renderLogins).catch(function (e) {
            document.getElementById("loginsBody").innerHTML =
                '<tr><td colspan="4" class="text-center text-danger">Erreur (' + escapeHtml(e.message) + ')</td></tr>';
        });
    }

    function loadBroadcastTargetOptions() {
        fetch("/api/kb/countries", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : [];
        }).then(function (countries) {
            document.getElementById("broadcastCountry").innerHTML = '<option value="">— Toutes filiales —</option>' +
                countries.map(function (c) { return '<option value="' + c.countryCode + '">' + c.label + '</option>'; }).join("");
        }).catch(function () {});

        fetch("/api/procedures/services", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : [];
        }).then(function (services) {
            document.getElementById("broadcastService").innerHTML = '<option value="">— Tous services —</option>' +
                services.map(function (s) { return '<option value="' + s.code + '">' + s.name + '</option>'; }).join("");
        }).catch(function () {});
    }

    function wireBroadcastForm() {
        loadBroadcastTargetOptions();
        document.getElementById("broadcastForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            var content = document.getElementById("contentInput").value.trim();
            if (!content) return;
            var resultBox = document.getElementById("broadcastResult");
            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Envoi en cours…";

            RccApi.sendJson("/api/mon-rcc/notifications", "POST", {
                content: content,
                countryCode: document.getElementById("broadcastCountry").value || null,
                serviceCode: document.getElementById("broadcastService").value || null,
                activity: document.getElementById("broadcastActivity").value.trim() || null
            })
                .then(function () {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = "Notification envoyée.";
                    document.getElementById("broadcastForm").reset();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    function wireBroadcastEmailForm() {
        document.getElementById("broadcastEmailForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            var subject = document.getElementById("emailSubjectInput").value.trim();
            var body = document.getElementById("emailBodyInput").value.trim();
            var resultBox = document.getElementById("broadcastEmailResult");
            if (!subject || !body) return;

            resultBox.className = "small mt-2 text-muted";
            resultBox.textContent = "Envoi en cours…";

            postJson("/api/audit/broadcast-email", { subject: subject, body: body })
                .then(function (result) {
                    resultBox.className = "small mt-2 text-success";
                    resultBox.textContent = result.sentCount + " e-mail(s) envoyé(s) sur " + result.recipientCount + " destinataire(s) avec une adresse renseignée.";
                    document.getElementById("broadcastEmailForm").reset();
                })
                .catch(function (e) {
                    resultBox.className = "small mt-2 text-danger";
                    resultBox.textContent = "Erreur : " + e.message;
                });
        });
    }

    function wireTabs() {
        var buttons = document.querySelectorAll("#auTabs [data-pane]");
        buttons.forEach(function (b) {
            b.addEventListener("click", function () {
                buttons.forEach(function (x) {
                    x.classList.toggle("on", x === b);
                    document.getElementById(x.getAttribute("data-pane")).style.display = x === b ? "" : "none";
                });
            });
        });
    }

    function init() {
        wireTabs();
        if (window.RccAuditUsage) window.RccAuditUsage.init();
        wireBroadcastForm();
        wireBroadcastEmailForm();
        loadLogins();
    }

    document.addEventListener("DOMContentLoaded", init);
})();
