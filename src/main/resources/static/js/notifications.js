"use strict";

(function () {

    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    function formatDate(iso) {
        return new Date(iso).toLocaleString(RccPreferences.getLanguage() === "en" ? "en-US" : "fr-FR");
    }

    var allRows = [];
    var filter = "all";

    function render(source) {
        allRows = source || [];
        var unread = allRows.filter(function (r) { return !r.isRead; }).length;
        var counter = document.getElementById("notifUnreadCount");
        if (counter) counter.textContent = unread;
        var readAll = document.getElementById("notifReadAllBtn");
        if (readAll) readAll.disabled = unread === 0;
        var rows = filter === "unread" ? allRows.filter(function (r) { return !r.isRead; }) : allRows;
        var container = document.getElementById("notificationsList");
        if (!rows.length) {
            container.innerHTML = '<p class="text-muted text-center py-4">' + RccPreferences.t("notifications.empty") + '</p>';
            return;
        }

        container.innerHTML = rows.map(function (r) {
            var status = r.isRead
                ? '<span class="badge bg-secondary">' + RccPreferences.t("notifications.read") + '</span>'
                : '<button class="btn btn-sm btn-outline-primary mark-read-btn" data-id="' + r.id + '">' +
                  RccPreferences.t("notifications.markRead") + '</button>';

            var actionBtn = "";
            if (r.actionType === "UNLOCK_ACCOUNT" && r.actionTarget) {
                actionBtn = '<button class="btn btn-sm btn-success unlock-account-btn me-2" ' +
                    'data-id="' + r.id + '" data-username="' + escapeHtml(r.actionTarget) + '">' +
                    '<i class="bi bi-unlock-fill"></i> Réactiver le compte</button>';
            } else if (r.actionType === "OPEN_KB_ARTICLE" && r.actionTarget) {
                actionBtn = '<a class="btn btn-sm btn-outline-primary me-2" ' +
                    'href="/knowledge?article=' + encodeURIComponent(r.actionTarget) + '">' +
                    '<i class="bi bi-folder2-open"></i> Ouvrir le dossier</a>';
            } else if (r.actionType === "OPEN_WORKFLOW_REQUEST" && r.actionTarget) {
                actionBtn = '<a class="btn btn-sm btn-outline-primary me-2" ' +
                    'href="/workflow?request=' + encodeURIComponent(r.actionTarget) + '">' +
                    '<i class="bi bi-clipboard-check"></i> Vérifier la demande</a>';
            } else if (r.actionType === "QA_EVALUATION_REVIEW" && r.actionTarget) {
                actionBtn = '<a class="btn btn-sm btn-outline-primary me-2" href="/team-leader">' +
                    '<i class="bi bi-headset"></i> Consulter</a>';
            } else if (r.actionType === "COMPETITION_TEAM_SELECTION" && r.actionTarget) {
                actionBtn = '<a class="btn btn-sm btn-warning me-2" href="/team-leader">' +
                    '<i class="bi bi-trophy-fill"></i> Choisir les membres</a>';
            }

            var unreadClass = r.isRead ? "" : "bg-light";
            return '<div class="list-group-item d-flex justify-content-between align-items-center ' + unreadClass + '">' +
                '<div><i class="bi bi-bell' + (r.isRead ? "" : "-fill text-primary") + ' me-2"></i>' +
                escapeHtml(r.content) + '<div class="small text-muted">' + formatDate(r.createdAt) + '</div></div>' +
                '<div>' + actionBtn + status + '</div>' +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".mark-read-btn"), function (btn) {
            btn.addEventListener("click", function () {
                sendJson("/api/mon-rcc/notifications/" + btn.getAttribute("data-id") + "/read", "POST")
                    .then(load)
                    .catch(function (e) { alert("Erreur : " + e.message); });
            });
        });

        Array.prototype.forEach.call(container.querySelectorAll(".unlock-account-btn"), function (btn) {
            btn.addEventListener("click", function () {
                var username = btn.getAttribute("data-username");
                if (!confirm("Réactiver le compte « " + username + " » ? Le compteur d'échecs de connexion sera remis à zéro.")) return;
                btn.disabled = true;
                fetch("/api/users/" + encodeURIComponent(username) + "/unlock", { method: "POST", credentials: "same-origin" })
                    .then(function (res) { if (!res.ok) throw new Error("HTTP " + res.status); })
                    .then(function () { return sendJson("/api/mon-rcc/notifications/" + btn.getAttribute("data-id") + "/read", "POST"); })
                    .then(load)
                    .catch(function (e) {
                        btn.disabled = false;
                        alert("Erreur : " + e.message);
                    });
            });
        });
    }

    function load() {
        getJson("/api/mon-rcc/notifications/me").then(render).catch(function (e) {
            document.getElementById("notificationsList").innerHTML =
                '<p class="text-danger text-center">Erreur : ' + escapeHtml(e.message) + '</p>';
        });
    }

    Array.prototype.forEach.call(document.querySelectorAll("[data-notif-filter]"), function (b) {
        b.addEventListener("click", function () {
            filter = b.getAttribute("data-notif-filter");
            Array.prototype.forEach.call(document.querySelectorAll("[data-notif-filter]"), function (x) { x.classList.toggle("active", x === b); });
            render(allRows);
        });
    });
    var readAllBtn = document.getElementById("notifReadAllBtn");
    if (readAllBtn) readAllBtn.addEventListener("click", function () {
        readAllBtn.disabled = true;
        sendJson("/api/mon-rcc/notifications/read-all", "POST").then(load)
            .catch(function (e) { readAllBtn.disabled = false; alert("Erreur : " + e.message); });
    });

    window.RccSession.init();
    load();
})();
