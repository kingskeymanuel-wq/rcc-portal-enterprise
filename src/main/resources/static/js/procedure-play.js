"use strict";

(function () {
    var getJson = RccApi.getJson;
    var escapeHtml = RccApi.escapeHtml;

    var procedureId = window.location.pathname.split("/")[2];
    var history = [];

    function appendBubble(text, who) {
        var thread = document.getElementById("ppThread");
        var row = document.createElement("div");
        row.className = "pp-bubble-row" + (who === "user" ? " user" : "");
        var bubble = document.createElement("div");
        bubble.className = "pp-bubble pp-bubble-" + who;
        bubble.textContent = text;
        row.appendChild(bubble);
        thread.appendChild(row);
        window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" });
        return row;
    }

    function renderNode(node) {
        document.getElementById("ppStepLabel").textContent = "Étape " + (history.length + 1);
        document.getElementById("ppProgressFill").style.width = Math.min(90, (history.length + 1) * 18) + "%";

        var botRow = appendBubble(node.questionText, "bot");

        if (node.suggestionLabel) {
            var suggestion = document.createElement("div");
            suggestion.className = "pp-suggestion";
            suggestion.innerHTML = '<i class="bi bi-lightbulb"></i> <a href="' + escapeHtml(node.suggestionUrl) + '" target="_blank" rel="noopener">' +
                escapeHtml(node.suggestionLabel) + '</a>';
            botRow.querySelector(".pp-bubble").appendChild(suggestion);
        }

        var optionsWrap = document.createElement("div");
        optionsWrap.className = "pp-options";
        document.getElementById("ppThread").appendChild(optionsWrap);

        var options = node.options || [];
        if (!options.length) {
            optionsWrap.innerHTML = '<p class="text-muted small">Aucune réponse configurée pour cette question.</p>';
            return;
        }

        options.forEach(function (o) {
            var btn = document.createElement("button");
            btn.className = "btn btn-outline-primary";
            btn.textContent = o.label;
            btn.addEventListener("click", function () {
                optionsWrap.remove();
                appendBubble(o.label, "user");
                history.push({ question: node.questionText, answerLabel: o.label });

                if (o.nextNodeId) {
                    getJson("/api/procedures/workflow/nodes/" + o.nextNodeId)
                        .then(renderNode)
                        .catch(function (e) { appendBubble("Erreur : " + e.message, "bot"); });
                } else if (o.outcome) {
                    renderOutcome(o.outcome);
                }
            });
            optionsWrap.appendChild(btn);
        });
    }

    function renderOutcome(outcome) {
        document.getElementById("ppProgressFill").style.width = "100%";
        var label = outcome === "FIDELISATION" ? "Fidélisation — dossier réglé" : "Clôturé";
        var icon = outcome === "FIDELISATION" ? "bi-check-circle-fill" : "bi-flag-fill";
        var row = document.createElement("div");
        row.className = "text-center py-4";
        row.innerHTML = '<i class="bi ' + icon + ' text-success" style="font-size:2.5rem;"></i>' +
            '<p class="fw-semibold mt-2">' + label + '</p>' +
            '<button class="btn btn-outline-primary btn-sm" id="ppRestartInlineBtn">Recommencer le parcours</button>';
        document.getElementById("ppThread").appendChild(row);
        document.getElementById("ppRestartInlineBtn").addEventListener("click", start);
    }

    function start() {
        history = [];
        document.getElementById("ppThread").innerHTML = "";
        document.getElementById("ppProgressFill").style.width = "8%";
        getJson("/api/procedures/" + procedureId + "/workflow/start")
            .then(renderNode)
            .catch(function () {
                document.getElementById("ppThread").innerHTML =
                    '<p class="text-muted text-center">Aucun parcours interactif configuré pour cette procédure.</p>';
            });
    }

    function loadTitle() {
        getJson("/api/procedures/" + procedureId).then(function (p) {
            document.getElementById("ppProcedureTitle").textContent = p.title;
        }).catch(function () {});
    }

    // ===== UN INSTANT =====

    var unInstantTimerInterval = null;
    var unInstantStartedAt = null;

    function wireUnInstant() {
        var modalEl = document.getElementById("unInstantModal");
        var modal = new bootstrap.Modal(modalEl);

        document.getElementById("unInstantBtn").addEventListener("click", function () {
            unInstantStartedAt = Date.now();
            document.getElementById("unInstantTimer").textContent = "00:00";
            unInstantTimerInterval = setInterval(function () {
                var elapsed = Math.floor((Date.now() - unInstantStartedAt) / 1000);
                var m = String(Math.floor(elapsed / 60)).padStart(2, "0");
                var s = String(elapsed % 60).padStart(2, "0");
                document.getElementById("unInstantTimer").textContent = m + ":" + s;
            }, 1000);
            modal.show();
        });

        modalEl.addEventListener("hidden.bs.modal", function () {
            clearInterval(unInstantTimerInterval);
        });

        document.getElementById("unInstantResumeBtn").addEventListener("click", function () {
            modal.hide();
        });

        document.getElementById("unInstantRafBtn").addEventListener("click", function () {
            modal.hide();
            var fab = document.getElementById("ralphFab");
            var panel = document.getElementById("ralphPanel");
            if (fab && panel) {
                fab.classList.add("d-none");
                panel.classList.remove("d-none");
                document.getElementById("ralphFabInput").focus();
            }
        });
    }

    function init() {
        loadTitle();
        wireUnInstant();
        document.getElementById("ppRestartBtn").addEventListener("click", start);
        start();
        if (window.RccSession) window.RccSession.init();
    }

    document.addEventListener("DOMContentLoaded", init);
})();
