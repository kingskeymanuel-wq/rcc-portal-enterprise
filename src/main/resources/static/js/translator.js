"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };

    var MAX_LENGTH = 5000;
    var PREFS_KEY = "rcc.translator.langs";

    // "auto" n'apparaît que côté source. Couverture réelle selon les sources configurées
    // (Azure et LibreTranslate couvrent la quasi-totalité ; Argos/DeepL moins).
    var LANGUAGES = [
        ["auto", "Détecter la langue"],
        ["fr", "Français"], ["en", "Anglais"], ["es", "Espagnol"], ["pt", "Portugais"],
        ["ar", "Arabe"], ["de", "Allemand"], ["it", "Italien"], ["nl", "Néerlandais"],
        ["zh-CN", "Chinois (simplifié)"], ["ja", "Japonais"], ["ko", "Coréen"], ["ru", "Russe"],
        ["tr", "Turc"], ["hi", "Hindi"], ["id", "Indonésien"], ["vi", "Vietnamien"], ["th", "Thaï"],
        ["pl", "Polonais"], ["sv", "Suédois"], ["el", "Grec"], ["he", "Hébreu"],
        ["sw", "Swahili"], ["ha", "Haoussa"], ["yo", "Yoruba"], ["ig", "Igbo"], ["am", "Amharique"],
        ["zu", "Zoulou"], ["so", "Somali"], ["wo", "Wolof"], ["ln", "Lingala"], ["mg", "Malgache"],
        ["rw", "Kinyarwanda"], ["ny", "Chichewa"], ["st", "Sesotho"], ["xh", "Xhosa"], ["ti", "Tigrinya"]
    ];

    var sourceLang = "auto";
    var targetLang = "en";
    var detectedLang = null;
    var lastRequestKey = "";
    var requestSeq = 0;
    var debounceTimer = null;
    var recognition = null;
    var recognizing = false;

    function escapeHtml(s) {
        var div = document.createElement("div");
        div.textContent = s == null ? "" : String(s);
        return div.innerHTML;
    }

    function populateSelect(select, includeAuto) {
        select.innerHTML = LANGUAGES.filter(function (l) { return includeAuto || l[0] !== "auto"; })
            .map(function (l) { return '<option value="' + l[0] + '">' + l[1] + '</option>'; }).join("");
    }

    function languageLabel(code) {
        if (!code) return "";
        var lower = code.toLowerCase();
        var found = LANGUAGES.filter(function (l) { return l[0].toLowerCase() === lower || l[0].toLowerCase() === lower.split("-")[0]; })[0];
        return found ? found[1] : code;
    }

    function hasLanguage(code) {
        return LANGUAGES.some(function (l) { return l[0] === code; });
    }

    function savePrefs() {
        try { localStorage.setItem(PREFS_KEY, JSON.stringify({ source: sourceLang, target: targetLang })); } catch (ignore) {}
    }

    function loadPrefs() {
        try {
            var saved = JSON.parse(localStorage.getItem(PREFS_KEY) || "null");
            if (saved && hasLanguage(saved.source)) sourceLang = saved.source;
            if (saved && hasLanguage(saved.target) && saved.target !== "auto") targetLang = saved.target;
        } catch (ignore) {}
    }

    function setStatus(message, isError) {
        var box = $("trStatus");
        box.textContent = message || "";
        box.className = "tr-status" + (isError ? " text-danger" : "");
    }

    function parseError(e) {
        var message = (e && e.message) || "";
        try {
            // Corps d'erreur du portail : {"error":{"code","message"}} — avant, « [object Object] ».
            var parsed = JSON.parse(message);
            var err = parsed && parsed.error;
            message = (err && typeof err === "object" ? err.message : err) || parsed.message || message;
        } catch (ignore) {}
        if (typeof message !== "string") message = JSON.stringify(message);
        return message || "erreur inconnue";
    }

    function doTranslate(force) {
        var text = $("sourceText").value;
        $("charCount").textContent = text.length + " / " + MAX_LENGTH;
        $("clearBtn").style.display = text ? "" : "none";

        if (!text.trim()) {
            requestSeq++; // invalide toute réponse encore en vol
            $("targetText").textContent = "";
            $("detectedLangLabel").textContent = "";
            $("retryBtn").style.display = "none";
            lastRequestKey = "";
            detectedLang = null;
            setStatus("");
            return;
        }
        var key = sourceLang + "|" + targetLang + "|" + text;
        if (!force && key === lastRequestKey) return;
        lastRequestKey = key;

        // Numéro de requête : une réponse lente d'une frappe précédente ne doit jamais écraser
        // la traduction du texte actuel (cause des traductions « incohérentes » à l'écran).
        var seq = ++requestSeq;
        setStatus("Traduction…");
        $("targetText").classList.add("opacity-50");
        fetch("/api/ralph/translate", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text: text, sourceLang: sourceLang, targetLang: targetLang })
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
            return res.json();
        }).then(function (result) {
            if (seq !== requestSeq) return;
            $("targetText").classList.remove("opacity-50");
            $("targetText").textContent = result.translated || "";
            $("retryBtn").style.display = "none";
            detectedLang = result.detectedSourceLang || null;
            var parts = [];
            if (sourceLang === "auto" && detectedLang) parts.push("Détecté : " + languageLabel(detectedLang));
            if (result.provider) parts.push("via " + result.provider);
            $("detectedLangLabel").textContent = parts.join(" · ");
            setStatus("");
        }).catch(function (e) {
            if (seq !== requestSeq) return;
            $("targetText").classList.remove("opacity-50");
            $("targetText").textContent = "";
            $("retryBtn").style.display = "";
            lastRequestKey = ""; // autorise une nouvelle tentative sur le même texte
            setStatus("Traduction indisponible : " + parseError(e), true);
        });
    }

    function scheduleTranslate() {
        clearTimeout(debounceTimer);
        var text = $("sourceText").value;
        $("charCount").textContent = text.length + " / " + MAX_LENGTH;
        $("clearBtn").style.display = text ? "" : "none";
        debounceTimer = setTimeout(function () { doTranslate(false); }, 600);
    }

    function copyText(text, btn) {
        if (!text) return;
        var done = function () {
            if (!btn) return;
            var icon = btn.querySelector("i");
            if (!icon) return;
            icon.className = "bi bi-check2";
            setTimeout(function () { icon.className = "bi bi-copy"; }, 1200);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done).catch(function () {});
        }
    }

    function speak(text, lang) {
        if (!text || !window.speechSynthesis) return;
        window.speechSynthesis.cancel();
        var utterance = new SpeechSynthesisUtterance(text);
        var effective = lang === "auto" ? detectedLang : lang;
        if (effective) utterance.lang = effective;
        window.speechSynthesis.speak(utterance);
    }

    function initSpeechRecognition() {
        var SpeechRecognitionCtor = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (!SpeechRecognitionCtor) {
            $("micBtn").disabled = true;
            $("micBtn").title = "Dictée vocale non prise en charge par ce navigateur";
            return;
        }
        recognition = new SpeechRecognitionCtor();
        recognition.continuous = false;
        recognition.interimResults = false;
        recognition.onresult = function (event) {
            var transcript = event.results[0][0].transcript;
            var current = $("sourceText").value;
            $("sourceText").value = (current ? current + " " : "") + transcript;
            doTranslate(false);
        };
        recognition.onerror = function () { setStatus("Dictée vocale : erreur de reconnaissance.", true); };
        recognition.onend = function () {
            recognizing = false;
            $("micBtn").classList.remove("active-rec");
        };
    }

    function toggleMic() {
        if (!recognition) return;
        if (recognizing) { recognition.stop(); return; }
        var lang = sourceLang === "auto" ? (detectedLang || "fr") : sourceLang;
        recognition.lang = lang === "fr" ? "fr-FR" : lang;
        try {
            recognition.start();
            recognizing = true;
            $("micBtn").classList.add("active-rec");
            setStatus("Parlez maintenant…");
        } catch (e) { /* déjà démarré — ignore */ }
    }

    function swapLanguages() {
        // Depuis « Détecter la langue », on utilise la langue détectée plutôt que de refuser.
        var effectiveSource = sourceLang === "auto" ? detectedLang : sourceLang;
        if (!effectiveSource || !hasLanguage(effectiveSource)) {
            setStatus("Choisissez d'abord la langue source (aucune langue détectée pour l'instant).", true);
            return;
        }
        var translated = $("targetText").textContent;
        sourceLang = targetLang;
        targetLang = effectiveSource;
        $("sourceLangSelect").value = sourceLang;
        $("targetLangSelect").value = targetLang;
        if (translated) $("sourceText").value = translated;
        savePrefs();
        doTranslate(true);
    }

    function runDiagnose() {
        var box = $("diagnoseResult");
        box.style.display = "";
        box.innerHTML = '<div class="text-muted small"><span class="spinner-border spinner-border-sm"></span> Test des sources de traduction en cours…</div>';
        fetch("/api/ralph/translate/diagnose", { credentials: "same-origin" })
            .then(function (res) {
                if (!res.ok) throw new Error("HTTP " + res.status);
                return res.json();
            })
            .then(function (rows) {
                var badgeFor = function (status) {
                    if (status === "OK") return '<span class="badge bg-success">OK</span>';
                    if (status === "NON CONFIGURÉ") return '<span class="badge bg-secondary">Non configuré</span>';
                    return '<span class="badge bg-danger">Échec</span>';
                };
                box.innerHTML = '<div class="table-responsive"><table class="table table-sm table-bordered mb-0">' +
                    '<thead><tr><th>Source (ordre d\'essai)</th><th>Statut</th><th>Détail</th></tr></thead><tbody>' +
                    rows.map(function (r) {
                        return "<tr><td>" + escapeHtml(r.source) + "</td><td>" + badgeFor(r.status) +
                            "</td><td class=\"small\">" + escapeHtml(r.detail) + "</td></tr>";
                    }).join("") + "</tbody></table></div>";
            })
            .catch(function (e) { box.innerHTML = '<div class="text-danger small">Diagnostic indisponible : ' + escapeHtml(e.message) + '</div>'; });
    }

    function loadProviders() {
        fetch("/api/ralph/translate/providers", { credentials: "same-origin" })
            .then(function (res) { return res.ok ? res.json() : []; })
            .then(function (providers) {
                var box = $("trProviders");
                if (!box) return;
                box.textContent = providers && providers.length
                    ? "Sources actives : " + providers.join(" → ")
                    : "Aucune source de traduction configurée — cliquez sur « Diagnostiquer la connexion ».";
                box.className = "small mt-1 " + (providers && providers.length ? "text-muted" : "text-danger");
            })
            .catch(function () {});
    }

    function init() {
        loadPrefs();
        populateSelect($("sourceLangSelect"), true);
        populateSelect($("targetLangSelect"), false);
        $("sourceLangSelect").value = sourceLang;
        $("targetLangSelect").value = targetLang;
        $("sourceText").setAttribute("maxlength", String(MAX_LENGTH));
        $("charCount").textContent = "0 / " + MAX_LENGTH;

        $("sourceLangSelect").addEventListener("change", function () {
            sourceLang = this.value;
            savePrefs();
            doTranslate(true);
        });
        $("targetLangSelect").addEventListener("change", function () {
            targetLang = this.value;
            savePrefs();
            doTranslate(true);
        });
        $("swapLangBtn").addEventListener("click", swapLanguages);
        $("sourceText").addEventListener("input", scheduleTranslate);
        $("sourceText").addEventListener("keydown", function (e) {
            if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                clearTimeout(debounceTimer);
                doTranslate(true);
            }
        });
        $("clearBtn").addEventListener("click", function () {
            $("sourceText").value = "";
            doTranslate(false);
            $("sourceText").focus();
        });
        $("retryBtn").addEventListener("click", function () { doTranslate(true); });
        $("copySourceBtn").addEventListener("click", function () { copyText($("sourceText").value, this); });
        $("copyTargetBtn").addEventListener("click", function () { copyText($("targetText").textContent, this); });
        $("speakSourceBtn").addEventListener("click", function () { speak($("sourceText").value, sourceLang); });
        $("speakTargetBtn").addEventListener("click", function () { speak($("targetText").textContent, targetLang); });
        $("micBtn").addEventListener("click", toggleMic);
        $("diagnoseBtn").addEventListener("click", runDiagnose);

        initSpeechRecognition();
        loadProviders();
        if (window.RccSession) window.RccSession.init().catch(function () {});
    }

    document.addEventListener("DOMContentLoaded", init);
})();
