"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };

    var TRANSLATE_SUBTITLE = "Traduction instantanée entre n'importe quelle paire de langues — sans IA, avec une source locale hors-ligne en priorité (voir docs/TRANSLATION_OFFLINE_SETUP.md).";
    var SPELLCHECK_SUBTITLE = "Correction orthographe et grammaire — moteur local à base de règles (LanguageTool), sans dépendance à une IA et sans appel réseau.";

    // ===== Bascule des onglets Traduction / Correcteur =====

    function showTab(tab) {
        var isTranslate = tab === "translate";
        $("trPaneTranslate").style.display = isTranslate ? "" : "none";
        $("trPaneSpellcheck").style.display = isTranslate ? "none" : "";
        $("trTabTranslateBtn").classList.toggle("active", isTranslate);
        $("trTabSpellcheckBtn").classList.toggle("active", !isTranslate);
        $("diagnoseBtn").style.display = isTranslate ? "" : "none";
        $("trSubtitle").textContent = isTranslate ? TRANSLATE_SUBTITLE : SPELLCHECK_SUBTITLE;
    }

    // ===== Correcteur =====

    var lastCheckedText = "";
    var currentIssues = [];

    function escapeHtml(s) {
        var div = document.createElement("div");
        div.textContent = s;
        return div.innerHTML;
    }

    function updateCharCount() {
        var len = $("scEditor").textContent.length;
        $("scCharCount").textContent = len + " caractère(s)";
    }

    function setStatus(message, isError) {
        var box = $("scStatus");
        box.textContent = message || "";
        box.className = "tr-status" + (isError ? " text-danger" : "");
    }

    /** Reconstruit l'éditeur avec les zones fautives surlignées, sans perdre le texte brut. */
    function renderHighlighted(text, issues) {
        if (!issues.length) {
            $("scEditor").textContent = text;
            return;
        }
        var sorted = issues.slice().sort(function (a, b) { return a.offset - b.offset; });
        var html = "";
        var cursor = 0;
        sorted.forEach(function (issue, idx) {
            if (issue.offset < cursor) return; // chevauchement — on ignore, prudence plutôt que casser l'affichage
            html += escapeHtml(text.substring(cursor, issue.offset));
            var fragment = text.substring(issue.offset, issue.offset + issue.length);
            html += '<mark class="sc-issue" data-issue-index="' + idx + '">' + escapeHtml(fragment) + '</mark>';
            cursor = issue.offset + issue.length;
        });
        html += escapeHtml(text.substring(cursor));
        $("scEditor").innerHTML = html;

        Array.prototype.forEach.call($("scEditor").querySelectorAll(".sc-issue"), function (mark) {
            mark.addEventListener("click", function () {
                var idx = Number(mark.getAttribute("data-issue-index"));
                var card = document.querySelector('.sc-issue-card[data-issue-index="' + idx + '"]');
                if (card) card.scrollIntoView({ behavior: "smooth", block: "nearest" });
            });
        });
    }

    function renderIssuesList(text, issues) {
        var list = $("scIssuesList");
        if (!issues.length) {
            list.innerHTML = '<div class="text-success"><i class="bi bi-check-circle"></i> Aucune erreur détectée.</div>';
            return;
        }
        list.innerHTML = issues.map(function (issue, idx) {
            var suggestionsHtml = (issue.suggestions || []).slice(0, 5).map(function (s) {
                return '<button type="button" class="btn btn-sm btn-outline-primary sc-suggestion-btn" ' +
                    'data-issue-index="' + idx + '" data-suggestion="' + escapeHtml(s) + '">' + escapeHtml(s) + '</button>';
            }).join("");
            return '<div class="sc-issue-card" data-issue-index="' + idx + '">' +
                '<div class="sc-msg">' + escapeHtml(issue.shortMessage || issue.message) + '</div>' +
                (suggestionsHtml ? '<div class="mt-1">' + suggestionsHtml + '</div>' : '') +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(list.querySelectorAll(".sc-suggestion-btn"), function (btn) {
            btn.addEventListener("click", function () {
                applySuggestion(Number(btn.getAttribute("data-issue-index")), btn.getAttribute("data-suggestion"));
            });
        });
    }

    /** Remplace le fragment fautif par la suggestion choisie, puis relance une vérification
     *  complète — plus simple et plus fiable que de recalculer tous les offsets suivants à la main. */
    function applySuggestion(issueIndex, suggestion) {
        var issue = currentIssues[issueIndex];
        if (!issue) return;
        var text = lastCheckedText;
        var updated = text.substring(0, issue.offset) + suggestion + text.substring(issue.offset + issue.length);
        $("scEditor").textContent = updated;
        placeCursorAtEnd($("scEditor"));
        runCheck();
    }

    function placeCursorAtEnd(el) {
        el.focus();
        var range = document.createRange();
        range.selectNodeContents(el);
        range.collapse(false);
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
    }

    function runCheck() {
        var text = $("scEditor").textContent;
        updateCharCount();
        if (!text.trim()) {
            currentIssues = [];
            $("scIssuesList").innerHTML = '<div class="text-muted">Cliquez sur « Vérifier » pour analyser votre texte.</div>';
            setStatus("");
            return;
        }

        setStatus("Vérification…");
        fetch("/api/spellcheck/check", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text: text, lang: $("scLangSelect").value })
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
            return res.json();
        }).then(function (result) {
            lastCheckedText = text;
            currentIssues = result.issues || [];
            renderHighlighted(text, currentIssues);
            renderIssuesList(text, currentIssues);
            setStatus(currentIssues.length
                ? currentIssues.length + " correction(s) suggérée(s)."
                : "Aucune erreur détectée.");
        }).catch(function (e) {
            var message = e.message || "";
            try { var parsed = JSON.parse(message); message = parsed.message || message; } catch (ignore) {}
            setStatus("Correcteur indisponible : " + message, true);
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        $("trTabTranslateBtn").addEventListener("click", function () { showTab("translate"); });
        $("trTabSpellcheckBtn").addEventListener("click", function () { showTab("spellcheck"); });

        $("scEditor").addEventListener("input", updateCharCount);
        $("scCheckBtn").addEventListener("click", runCheck);
    });
})();
