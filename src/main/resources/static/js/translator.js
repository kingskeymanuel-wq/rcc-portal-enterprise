"use strict";

(function () {
    var $ = function (id) { return document.getElementById(id); };

    // Liste volontairement large — MyMemory (et la traduction locale Argos, si configurée)
    // couvrent la quasi-totalité de ces codes ISO 639-1. "auto" n'apparaît que côté source.
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
    var lastTranslatedSourceText = "";
    var debounceTimer = null;
    var recognition = null;
    var recognizing = false;

    function populateSelect(select, includeAuto) {
        select.innerHTML = LANGUAGES.filter(function (l) { return includeAuto || l[0] !== "auto"; })
            .map(function (l) { return '<option value="' + l[0] + '">' + l[1] + '</option>'; }).join("");
    }

    function languageLabel(code) {
        var found = LANGUAGES.filter(function (l) { return l[0] === code; })[0];
        return found ? found[1] : code;
    }

    function setStatus(message, isError) {
        var box = $("trStatus");
        box.textContent = message || "";
        box.className = "tr-status" + (isError ? " text-danger" : "");
    }

    function doTranslate() {
        var text = $("sourceText").value;
        $("charCount").textContent = text.length + " / 4000";
        $("clearBtn").style.display = text ? "" : "none";

        if (!text.trim()) {
            $("targetText").textContent = "";
            $("detectedLangLabel").textContent = "";
            lastTranslatedSourceText = "";
            setStatus("");
            return;
        }
        if (text === lastTranslatedSourceText) return;

        setStatus("Traduction…");
        fetch("/api/ralph/translate", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text: text, sourceLang: sourceLang, targetLang: targetLang })
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
            return res.json();
        }).then(function (result) {
            lastTranslatedSourceText = text;
            $("targetText").textContent = result.translated || "";
            if (sourceLang === "auto" && result.detectedSourceLang) {
                $("detectedLangLabel").textContent = "Détecté : " + languageLabel(result.detectedSourceLang);
            } else {
                $("detectedLangLabel").textContent = "";
            }
            setStatus("");
        }).catch(function (e) {
            var message = e.message || "";
            try { var parsed = JSON.parse(message); message = parsed.message || message; } catch (ignore) {}
            setStatus("Traduction indisponible : " + message, true);
        });
    }

    function scheduleTranslate() {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(doTranslate, 500);
    }

    function copyText(text) {
        if (!text) return;
        navigator.clipboard.writeText(text).catch(function () {});
    }

    function speak(text, lang) {
        if (!text || !window.speechSynthesis) return;
        window.speechSynthesis.cancel();
        var utterance = new SpeechSynthesisUtterance(text);
        utterance.lang = lang === "auto" ? "" : lang;
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
            $("sourceText").value = ($("sourceText").value ? $("sourceText").value + " " : "") + transcript;
            doTranslate();
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
        recognition.lang = sourceLang === "auto" ? "fr-FR" : sourceLang;
        try {
            recognition.start();
            recognizing = true;
            $("micBtn").classList.add("active-rec");
            setStatus("Parlez maintenant…");
        } catch (e) { /* déjà démarré — ignore */ }
    }

    function swapLanguages() {
        if (sourceLang === "auto") { setStatus("Impossible d'inverser depuis « Détecter la langue ».", true); return; }
        var newSource = targetLang;
        var newTarget = sourceLang;
        var sourceValue = $("targetText").textContent;
        sourceLang = newSource; targetLang = newTarget;
        $("sourceLangSelect").value = sourceLang;
        $("targetLangSelect").value = targetLang;
        $("sourceText").value = sourceValue;
        lastTranslatedSourceText = "";
        doTranslate();
    }

    function runDiagnose() {
        var box = $("diagnoseResult");
        box.style.display = "";
        box.innerHTML = '<div class="text-muted small"><span class="spinner-border spinner-border-sm"></span> Test des sources de traduction en cours…</div>';
        fetch("/api/ralph/translate/diagnose", { credentials: "same-origin" })
            .then(function (res) { return res.json(); })
            .then(function (rows) {
                box.innerHTML = '<div class="table-responsive"><table class="table table-sm table-bordered mb-0">' +
                    '<thead><tr><th>Source</th><th>Statut</th><th>Détail</th></tr></thead><tbody>' +
                    rows.map(function (r) {
                        var badge = r.status === "OK" ? '<span class="badge bg-success">OK</span>' : '<span class="badge bg-danger">Échec</span>';
                        return "<tr><td>" + r.source + "</td><td>" + badge + "</td><td class=\"small\">" + r.detail + "</td></tr>";
                    }).join("") + "</tbody></table></div>";
            })
            .catch(function (e) { box.innerHTML = '<div class="text-danger small">Diagnostic indisponible : ' + e.message + '</div>'; });
    }

    function init() {
        populateSelect($("sourceLangSelect"), true);
        populateSelect($("targetLangSelect"), false);
        $("sourceLangSelect").value = sourceLang;
        $("targetLangSelect").value = targetLang;

        $("sourceLangSelect").addEventListener("change", function () {
            sourceLang = this.value;
            lastTranslatedSourceText = "";
            doTranslate();
        });
        $("targetLangSelect").addEventListener("change", function () {
            targetLang = this.value;
            lastTranslatedSourceText = "";
            doTranslate();
        });
        $("swapLangBtn").addEventListener("click", swapLanguages);
        $("sourceText").addEventListener("input", scheduleTranslate);
        $("clearBtn").addEventListener("click", function () {
            $("sourceText").value = "";
            doTranslate();
            $("sourceText").focus();
        });
        $("retryBtn").addEventListener("click", function () { lastTranslatedSourceText = ""; doTranslate(); });
        $("copySourceBtn").addEventListener("click", function () { copyText($("sourceText").value); });
        $("copyTargetBtn").addEventListener("click", function () { copyText($("targetText").textContent); });
        $("speakSourceBtn").addEventListener("click", function () { speak($("sourceText").value, sourceLang); });
        $("speakTargetBtn").addEventListener("click", function () { speak($("targetText").textContent, targetLang); });
        $("micBtn").addEventListener("click", toggleMic);
        $("diagnoseBtn").addEventListener("click", runDiagnose);

        initSpeechRecognition();
        window.RccSession.init().catch(function () {});
    }

    document.addEventListener("DOMContentLoaded", init);
})();
