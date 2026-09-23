"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };

    var TRANSLATE_SUBTITLE = "Traduction instantanée entre n'importe quelle paire de langues — sans IA générative, plusieurs moteurs en repli (voir docs/TRANSLATION_OFFLINE_SETUP.md).";
    var SPELLCHECK_SUBTITLE = "Correction orthographe et grammaire — moteur à base de règles (LanguageTool), sans IA, vocabulaire bancaire reconnu.";
    var LANG_KEY = "rcc.spellcheck.lang";
    var TAB_KEY = "rcc.translator.tab";

    var TYPE_META = {
        misspelling: { label: "Orthographe", color: "#dc3545" },
        grammar: { label: "Grammaire", color: "#fd7e14" },
        typographical: { label: "Ponctuation", color: "#0d6efd" },
        style: { label: "Style", color: "#6f42c1" },
        other: { label: "Autre", color: "#6c757d" }
    };

    // ===== Bascule des onglets Traduction / Correcteur =====

    function showTab(tab) {
        var isTranslate = tab === "translate";
        $("trPaneTranslate").style.display = isTranslate ? "" : "none";
        $("trPaneSpellcheck").style.display = isTranslate ? "none" : "";
        $("trTabTranslateBtn").classList.toggle("active", isTranslate);
        $("trTabSpellcheckBtn").classList.toggle("active", !isTranslate);
        $("diagnoseBtn").style.display = isTranslate ? "" : "none";
        $("diagnoseResult").style.display = "none";
        $("trSubtitle").textContent = isTranslate ? TRANSLATE_SUBTITLE : SPELLCHECK_SUBTITLE;
        var providers = $("trProviders");
        if (providers) providers.style.display = isTranslate ? "" : "none";
        try { localStorage.setItem(TAB_KEY, tab); } catch (ignore) {}
    }

    // ===== Correcteur =====

    var lastCheckedText = null;
    var currentIssues = [];
    var ignoredWords = {};
    var checkSeq = 0;

    function escapeHtml(s) {
        var div = document.createElement("div");
        div.textContent = s == null ? "" : String(s);
        return div.innerHTML;
    }

    /**
     * Texte brut de l'éditeur. innerText (et non textContent) : dans un contenteditable,
     * chaque retour à la ligne crée un <div>/<br> que textContent ignore — les mots de deux
     * lignes se retrouvaient collés (« bonjour » + « Madame » → « bonjourMadame »), ce qui
     * produisait de fausses erreurs et décalait toutes les positions de surlignage.
     */
    function editorText() {
        var text = $("scEditor").innerText || "";
        return text.replace(/ /g, " ").replace(/\r\n?/g, "\n").replace(/\n$/, "");
    }

    function updateCharCount() {
        var len = editorText().length;
        $("scCharCount").textContent = len + " caractère(s)";
    }

    function setStatus(message, isError) {
        var box = $("scStatus");
        box.textContent = message || "";
        box.className = "tr-status" + (isError ? " text-danger" : "");
    }

    function metaFor(issue) {
        return TYPE_META[issue.type] || TYPE_META.other;
    }

    /** Reconstruit l'éditeur avec les zones fautives surlignées (le serveur renvoie des erreurs triées et sans chevauchement). */
    function renderHighlighted(text, issues) {
        var html = "";
        var cursor = 0;
        issues.forEach(function (issue, idx) {
            if (issue.offset < cursor || issue.offset + issue.length > text.length) return;
            html += escapeHtml(text.substring(cursor, issue.offset));
            var fragment = text.substring(issue.offset, issue.offset + issue.length);
            html += '<mark class="sc-issue" style="border-bottom-color:' + metaFor(issue).color + '" data-issue-index="' + idx + '" title="' +
                escapeHtml(issue.shortMessage || issue.message) + '">' + escapeHtml(fragment) + '</mark>';
            cursor = issue.offset + issue.length;
        });
        html += escapeHtml(text.substring(cursor));
        $("scEditor").innerHTML = html;

        Array.prototype.forEach.call($("scEditor").querySelectorAll(".sc-issue"), function (mark) {
            mark.addEventListener("click", function () {
                var idx = mark.getAttribute("data-issue-index");
                var card = document.querySelector('.sc-issue-card[data-issue-index="' + idx + '"]');
                if (!card) return;
                card.scrollIntoView({ behavior: "smooth", block: "nearest" });
                card.classList.add("sc-issue-card-active");
                setTimeout(function () { card.classList.remove("sc-issue-card-active"); }, 1200);
            });
        });
    }

    function renderIssuesList(text, issues) {
        var list = $("scIssuesList");
        $("scFixAllBtn").disabled = !issues.some(function (i) { return i.suggestions && i.suggestions.length; });
        if (!issues.length) {
            list.innerHTML = '<div class="text-success"><i class="bi bi-check-circle"></i> Aucune erreur détectée.</div>';
            return;
        }
        list.innerHTML = issues.map(function (issue, idx) {
            var meta = metaFor(issue);
            var fragment = text.substring(issue.offset, issue.offset + issue.length);
            var suggestionsHtml = (issue.suggestions || []).slice(0, 5).map(function (s) {
                return '<button type="button" class="btn btn-sm btn-outline-primary sc-suggestion-btn" ' +
                    'data-issue-index="' + idx + '" data-suggestion="' + escapeHtml(s) + '">' + (s ? escapeHtml(s) : "<em>(supprimer)</em>") + '</button>';
            }).join("");
            var ignoreBtn = '<button type="button" class="btn btn-sm btn-link text-muted sc-ignore-btn p-0 ms-1" data-issue-index="' + idx + '">Ignorer</button>';
            return '<div class="sc-issue-card" data-issue-index="' + idx + '" style="border-left:3px solid ' + meta.color + '">' +
                '<div class="d-flex justify-content-between align-items-start gap-2">' +
                    '<span class="badge rounded-pill" style="background:' + meta.color + '">' + meta.label + '</span>' +
                    '<code class="small text-truncate">' + escapeHtml(fragment) + '</code>' +
                '</div>' +
                '<div class="sc-msg mt-1">' + escapeHtml(issue.message || issue.shortMessage) + '</div>' +
                '<div class="mt-1">' + suggestionsHtml + ignoreBtn + '</div>' +
                '</div>';
        }).join("");

        Array.prototype.forEach.call(list.querySelectorAll(".sc-suggestion-btn"), function (btn) {
            btn.addEventListener("click", function () {
                applySuggestion(Number(btn.getAttribute("data-issue-index")), btn.getAttribute("data-suggestion"));
            });
        });
        Array.prototype.forEach.call(list.querySelectorAll(".sc-ignore-btn"), function (btn) {
            btn.addEventListener("click", function () { ignoreIssue(Number(btn.getAttribute("data-issue-index"))); });
        });
    }

    function showResult(text, issues) {
        lastCheckedText = text;
        currentIssues = issues.filter(function (i) {
            return !ignoredWords[text.substring(i.offset, i.offset + i.length).toLowerCase()];
        });
        renderHighlighted(text, currentIssues);
        renderIssuesList(text, currentIssues);
        setStatus(currentIssues.length
            ? currentIssues.length + " correction(s) suggérée(s)."
            : "Aucune erreur détectée.");
    }

    /**
     * Remplace le fragment fautif par la suggestion choisie. Si l'agent a modifié le texte
     * depuis la dernière vérification, les positions ne sont plus fiables : on revérifie
     * d'abord au lieu d'écraser ses modifications (ancien comportement).
     */
    function applySuggestion(issueIndex, suggestion) {
        var issue = currentIssues[issueIndex];
        if (!issue) return;
        if (editorText() !== lastCheckedText) {
            setStatus("Le texte a changé depuis la vérification — nouvelle vérification…");
            runCheck();
            return;
        }
        var text = lastCheckedText;
        var updated = text.substring(0, issue.offset) + suggestion + text.substring(issue.offset + issue.length);
        $("scEditor").textContent = updated;
        runCheck();
    }

    /** Applique la première suggestion de chaque erreur, de la fin vers le début (les positions restent valables). */
    function fixAll() {
        if (editorText() !== lastCheckedText) { runCheck(); return; }
        var text = lastCheckedText;
        var applicable = currentIssues.filter(function (i) { return i.suggestions && i.suggestions.length; });
        if (!applicable.length) return;
        applicable.slice().sort(function (a, b) { return b.offset - a.offset; }).forEach(function (issue) {
            text = text.substring(0, issue.offset) + issue.suggestions[0] + text.substring(issue.offset + issue.length);
        });
        $("scEditor").textContent = text;
        runCheck();
    }

    function ignoreIssue(issueIndex) {
        var issue = currentIssues[issueIndex];
        if (!issue || lastCheckedText == null) return;
        var word = lastCheckedText.substring(issue.offset, issue.offset + issue.length).toLowerCase();
        if (issue.type === "misspelling") ignoredWords[word] = true; // ignoré pour toute la session
        var remaining = currentIssues.filter(function (_, idx) { return idx !== issueIndex; });
        showResult(lastCheckedText, remaining);
    }

    function runCheck() {
        var text = editorText();
        updateCharCount();
        if (!text.trim()) {
            currentIssues = [];
            lastCheckedText = null;
            $("scFixAllBtn").disabled = true;
            $("scIssuesList").innerHTML = '<div class="text-muted">Cliquez sur « Vérifier » pour analyser votre texte.</div>';
            setStatus("");
            return;
        }

        var seq = ++checkSeq;
        setStatus("Vérification…");
        $("scCheckBtn").disabled = true;
        fetch("/api/spellcheck/check", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text: text, lang: $("scLangSelect").value })
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
            return res.json();
        }).then(function (result) {
            if (seq !== checkSeq) return;
            // Texte modifié pendant la vérification : on ne surligne pas des positions périmées.
            if (editorText() !== text) { setStatus("Texte modifié pendant la vérification — cliquez à nouveau sur « Vérifier »."); return; }
            showResult(text, result.issues || []);
            var info = [];
            if ($("scLangSelect").value === "auto" && result.language) info.push("Langue : " + result.language);
            if (result.engine) info.push(result.engine);
            $("scEngineInfo").textContent = info.join(" · ");
        }).catch(function (e) {
            if (seq !== checkSeq) return;
            var message = e.message || "";
            try { var parsed = JSON.parse(message); message = parsed.message || message; } catch (ignore) {}
            setStatus("Correcteur indisponible : " + message, true);
        }).finally(function () {
            if (seq === checkSeq) $("scCheckBtn").disabled = false;
        });
    }

    function loadLanguages() {
        var select = $("scLangSelect");
        var saved = null;
        try { saved = localStorage.getItem(LANG_KEY); } catch (ignore) {}
        fetch("/api/spellcheck/languages", { credentials: "same-origin" })
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (langs) {
                if (!langs || !langs.length) return;
                select.innerHTML = langs.map(function (l) {
                    return '<option value="' + escapeHtml(l.code) + '">' + escapeHtml(l.label) + '</option>';
                }).join("");
                select.value = langs.some(function (l) { return l.code === saved; }) ? saved : "fr";
            })
            .catch(function () {});
    }

    document.addEventListener("DOMContentLoaded", function () {
        $("trTabTranslateBtn").addEventListener("click", function () { showTab("translate"); });
        $("trTabSpellcheckBtn").addEventListener("click", function () { showTab("spellcheck"); });
        var savedTab = null;
        try { savedTab = localStorage.getItem(TAB_KEY); } catch (ignore) {}
        if (savedTab === "spellcheck") showTab("spellcheck");

        var editor = $("scEditor");
        editor.addEventListener("input", updateCharCount);
        // Collage en texte brut : le HTML collé (Word, Outlook) apportait styles et balises
        // cachées qui faussaient les positions des erreurs.
        editor.addEventListener("paste", function (e) {
            var data = e.clipboardData || window.clipboardData;
            if (!data) return;
            e.preventDefault();
            var plain = data.getData("text/plain") || "";
            if (document.queryCommandSupported && document.queryCommandSupported("insertText")) {
                document.execCommand("insertText", false, plain);
            } else {
                var sel = window.getSelection();
                if (!sel.rangeCount) return;
                sel.deleteFromDocument();
                sel.getRangeAt(0).insertNode(document.createTextNode(plain));
                sel.collapseToEnd();
            }
            updateCharCount();
        });
        editor.addEventListener("keydown", function (e) {
            if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); runCheck(); }
        });

        $("scCheckBtn").addEventListener("click", runCheck);
        $("scFixAllBtn").addEventListener("click", fixAll);
        $("scCopyBtn").addEventListener("click", function () {
            var text = editorText();
            if (text && navigator.clipboard) navigator.clipboard.writeText(text).catch(function () {});
        });
        $("scLangSelect").addEventListener("change", function () {
            try { localStorage.setItem(LANG_KEY, this.value); } catch (ignore) {}
            if (lastCheckedText != null) runCheck();
        });
        loadLanguages();
    });
})();
