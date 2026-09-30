"use strict";

/**
 * Traducteur & correcteur → onglet « Réécriture » : le message de l'agent reformulé en style professionnel,
 * courtois, concis ou plus simple (/api/spellcheck/rewrite). IA si l'administrateur l'a configurée (case « IA »),
 * sinon règles locales sans IA. La proposition peut remplacer le texte, être vérifiée ou traduite.
 */
(function () {
    var $ = function (id) { return document.getElementById(id); };
    var style = "professionnel", seq = 0, lastOutput = "";

    function esc(s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; }

    function setStatus(msg, err) {
        var b = $("rwStatus");
        b.textContent = msg || "";
        b.className = "tr-status" + (err ? " text-danger" : "");
    }

    function setOutput(text) {
        lastOutput = text || "";
        $("rwOutput").textContent = lastOutput;
        ["rwCopyBtn", "rwUseBtn", "rwToCheckBtn", "rwToTranslateBtn"].forEach(function (id) { $(id).disabled = !lastOutput; });
    }

    function run() {
        var text = $("rwSource").value;
        if (!text.trim()) { setStatus("Saisissez d'abord le texte à réécrire.", true); return; }
        var mySeq = ++seq;
        setStatus("Réécriture…");
        $("rwBtn").disabled = true;
        $("rwOutput").classList.add("opacity-50");
        fetch("/api/spellcheck/rewrite", {
            method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text: text, lang: $("rwLangSelect").value, style: style, ai: $("rwAi").checked })
        }).then(function (res) {
            if (!res.ok) return res.text().then(function (t) { var m = t; try { m = JSON.parse(t).error.message; } catch (e) { /* texte */ } throw new Error(m || "HTTP " + res.status); });
            return res.json();
        }).then(function (r) {
            if (mySeq !== seq) return;
            setOutput(r.text);
            $("rwChanges").innerHTML = (r.changes || []).map(function (c) { return "<li>" + esc(c) + "</li>"; }).join("");
            $("rwEngine").textContent = r.engine || "";
            setStatus("");
        }).catch(function (e) {
            if (mySeq !== seq) return;
            setStatus("Réécriture impossible : " + e.message, true);
        }).finally(function () {
            if (mySeq === seq) { $("rwBtn").disabled = false; $("rwOutput").classList.remove("opacity-50"); }
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        if (!$("trPaneRewrite")) return;
        $("rwStyles").addEventListener("click", function (e) {
            var b = e.target.closest("[data-style]");
            if (!b) return;
            style = b.getAttribute("data-style");
            this.querySelectorAll("[data-style]").forEach(function (x) { x.classList.toggle("on", x === b); });
            if ($("rwSource").value.trim()) run();
        });
        $("rwSource").addEventListener("input", function () { $("rwCharCount").textContent = this.value.length + " / 5000"; });
        $("rwSource").addEventListener("keydown", function (e) { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); run(); } });
        $("rwBtn").addEventListener("click", run);
        $("rwCopyBtn").addEventListener("click", function () { if (lastOutput && navigator.clipboard) navigator.clipboard.writeText(lastOutput).catch(function () {}); });
        $("rwUseBtn").addEventListener("click", function () {
            $("rwSource").value = lastOutput;
            $("rwSource").dispatchEvent(new Event("input"));
        });
        $("rwToCheckBtn").addEventListener("click", function () { if (window.RccTranslatorTabs) window.RccTranslatorTabs.toSpellcheck(lastOutput); });
        $("rwToTranslateBtn").addEventListener("click", function () { if (window.RccTranslatorTabs) window.RccTranslatorTabs.toTranslate(lastOutput); });
        fetch("/api/spellcheck/rewrite/styles", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : null; }).then(function (info) {
            if (info && info.ai) $("rwAiWrap").style.setProperty("display", "flex", "important");
            else $("rwAi").checked = false;
        }).catch(function () { $("rwAi").checked = false; });
    });
})();
