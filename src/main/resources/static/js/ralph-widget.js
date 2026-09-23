"use strict";

(function () {
    function escapeHtml(s) {
        if (window.RccApi && RccApi.escapeHtml) return RccApi.escapeHtml(s);
        var div = document.createElement("div");
        div.textContent = s == null ? "" : String(s);
        return div.innerHTML;
    }

    /** Langue de réponse de RAF — mémorisée par agent (localStorage), français par défaut. */
    function getRafLanguage() {
        return localStorage.getItem("raf_lang") || "fr";
    }
    function setRafLanguage(lang) {
        localStorage.setItem("raf_lang", lang);
        applyRafLanguageToStaticUI();
    }

    /** Interface RAF traduite — le contenu généré par l'IA suit lui la langue via le paramètre lang de /ask. */
    var RAF_I18N = {
        fr: {
            agentsConsulted: "Agents consultés", step: "Étape", dueBy: "À annoncer au client :", draft: "Brouillon prêt à envoyer (champs surlignés à compléter)", copy: "Copier", copied: "Copié", why: "Pourquoi cette réponse ?",
            greeting: "Bonjour ! Je suis RAF : fiche appel, procédures guidées, SLA, glossaire, agences, modèles de mail, ton planning — uniquement à partir des données du portail, sans IA externe.",
            inputPlaceholder: "Votre question…",
            detailBtn: "Donne-moi plus de détails",
            detailAskText: "Donne-moi plus de détails",
            newConversation: "Nouvelle conversation. Posez-moi une question sur une procédure ou un article.",
            confidence: "Confiance",
            sources: "Sources",
            error: "Erreur",
            micUnavailable: "Micro indisponible : le site doit être servi en HTTPS (ou localhost) pour que le navigateur autorise la reconnaissance vocale.",
            collapse: "Réduire la fenêtre",
            expand: "Agrandir la fenêtre",
            micTitle: "Parler à RAF",
            translateTitle: "Traduire un texte",
            settingsTitle: "Langue de RAF",
            resetTitle: "Nouvelle conversation",
            fileTitle: "Importer un fichier à analyser"
        },
        en: {
            agentsConsulted: "Agents consulted", step: "Step", dueBy: "Tell the customer:", draft: "Draft ready to send (highlighted fields to complete)", copy: "Copy", copied: "Copied", why: "Why this answer?",
            greeting: "Hello! I'm RAF: call cards, guided procedures, SLAs, glossary, branches, mail templates, your schedule — only from portal data, no external AI.",
            inputPlaceholder: "Your question…",
            detailBtn: "Give me more details",
            detailAskText: "Give me more details",
            newConversation: "New conversation. Ask me a question about a procedure or an article.",
            confidence: "Confidence",
            sources: "Sources",
            error: "Error",
            micUnavailable: "Microphone unavailable: the site must be served over HTTPS (or localhost) for the browser to allow speech recognition.",
            collapse: "Collapse window",
            expand: "Expand window",
            micTitle: "Talk to RAF",
            translateTitle: "Translate a text",
            settingsTitle: "RAF language",
            resetTitle: "New conversation",
            fileTitle: "Import a file to analyze"
        },
        pt: {
            agentsConsulted: "Agentes consultados", step: "Passo", dueBy: "A anunciar ao cliente:", draft: "Rascunho pronto (campos destacados a completar)", copy: "Copiar", copied: "Copiado", why: "Porquê esta resposta?",
            greeting: "Olá! Sou o RAF: ficha de chamada, procedimentos guiados, SLA, glossário, agências, modelos de e-mail, o seu horário — apenas com dados do portal, sem IA externa.",
            inputPlaceholder: "Sua pergunta…",
            detailBtn: "Dê-me mais detalhes",
            detailAskText: "Dê-me mais detalhes",
            newConversation: "Nova conversa. Faça-me uma pergunta sobre um procedimento ou um artigo.",
            confidence: "Confiança",
            sources: "Fontes",
            error: "Erro",
            micUnavailable: "Microfone indisponível: o site deve ser servido via HTTPS (ou localhost) para que o navegador permita o reconhecimento de voz.",
            collapse: "Reduzir janela",
            expand: "Expandir janela",
            micTitle: "Falar com o RAF",
            translateTitle: "Traduzir um texto",
            settingsTitle: "Idioma do RAF",
            resetTitle: "Nova conversa",
            fileTitle: "Importar um ficheiro para analisar"
        },
        es: {
            agentsConsulted: "Agentes consultados", step: "Paso", dueBy: "A anunciar al cliente:", draft: "Borrador listo (campos resaltados a completar)", copy: "Copiar", copied: "Copiado", why: "¿Por qué esta respuesta?",
            greeting: "¡Hola! Soy RAF: ficha de llamada, procedimientos guiados, SLA, glosario, agencias, plantillas de correo, tu horario — solo con datos del portal, sin IA externa.",
            inputPlaceholder: "Tu pregunta…",
            detailBtn: "Dame más detalles",
            detailAskText: "Dame más detalles",
            newConversation: "Nueva conversación. Hazme una pregunta sobre un procedimiento o un artículo.",
            confidence: "Confianza",
            sources: "Fuentes",
            error: "Error",
            micUnavailable: "Micrófono no disponible: el sitio debe servirse por HTTPS (o localhost) para que el navegador permita el reconocimiento de voz.",
            collapse: "Reducir ventana",
            expand: "Ampliar ventana",
            micTitle: "Hablar con RAF",
            translateTitle: "Traducir un texto",
            settingsTitle: "Idioma de RAF",
            resetTitle: "Nueva conversación",
            fileTitle: "Importar un archivo para analizar"
        }
    };

    function t(key) {
        var lang = getRafLanguage();
        return (RAF_I18N[lang] && RAF_I18N[lang][key]) || RAF_I18N.fr[key] || key;
    }

    /** Applique la langue choisie à tout le texte statique du panneau (pas seulement les réponses IA). */
    function applyRafLanguageToStaticUI() {
        var thread = document.getElementById("ralphThread");
        if (thread && thread.children.length === 1) {
            // Seul le message d'accueil initial est présent — sûr de le retraduire sans perdre l'historique de la conversation.
            thread.children[0].textContent = t("greeting");
        }
        var input = document.getElementById("ralphFabInput");
        if (input) input.placeholder = t("inputPlaceholder");

        var micBtn = document.getElementById("ralphMicBtn");
        if (micBtn && !micBtn.title.indexOf("HTTPS") >= 0) micBtn.title = t("micTitle");
        var translateBtn = document.getElementById("ralphTranslateBtn");
        if (translateBtn) translateBtn.title = t("translateTitle");
        var settingsBtn = document.getElementById("ralphSettingsBtn");
        if (settingsBtn) settingsBtn.title = t("settingsTitle");
        var resetBtn = document.getElementById("ralphResetBtn");
        if (resetBtn) resetBtn.title = t("resetTitle");
        var fileBtn = document.getElementById("ralphFileBtn");
        if (fileBtn) fileBtn.title = t("fileTitle");
        var expandBtn = document.getElementById("ralphExpandBtn");
        if (expandBtn) {
            var expanded = document.getElementById("ralphPanel").classList.contains("ralph-panel-expanded");
            expandBtn.title = expanded ? t("collapse") : t("expand");
        }
    }

    /** Rendu markdown minimal et sûr pour les réponses de RAF (gras, listes à puces, séparateur) —
     *  échappe le HTML d'abord, interprète ensuite **gras**, "- puce" et "---". */
    function renderRafMarkdown(text) {
        if (!text) return "";
        var escaped = escapeHtml(text);
        var html = escaped
            .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
            .replace(/(^|\s)_(.+?)_(?=\s|$)/gm, "$1<em>$2</em>")
            .replace(/(https?:\/\/[^\s<]+)/g, '<a href="$1" target="_blank" rel="noopener">$1</a>')
            .replace(/^---$/gm, "<hr>")
            .replace(/^- (.+)$/gm, "<li>$1</li>")
            .replace(/\n/g, "<br>");
        html = html.replace(/(<li>.*?<\/li>(<br>)?)+/gs, function (match) {
            return "<ul class=\"mb-1\">" + match.replace(/<br>/g, "") + "</ul>";
        });
        return html;
    }

    function appendMessage(thread, text, who) {
        var div = document.createElement("div");
        div.className = "ralph-msg ralph-msg-" + who;
        if (who === "bot") { div.innerHTML = renderRafMarkdown(text); } else { div.textContent = text; }
        thread.appendChild(div);
        thread.scrollTop = thread.scrollHeight;
        return div;
    }

    function appendResults(thread, results) {
        if (!results || !results.length) return;
        var wrap = document.createElement("div");
        wrap.style.maxWidth = "85%";
        wrap.style.display = "flex";
        wrap.style.flexDirection = "column";
        wrap.style.gap = "6px";
        results.forEach(function (r) {
            var badge = SOURCE_BADGES[r.sourceType] || "📋 Étape de procédure";
            var card = document.createElement("button");
            card.type = "button";
            card.className = "ralph-result-card ralph-result-bubble";
            card.style.cursor = "pointer";
            card.style.textAlign = "left";
            card.style.border = "1px solid #dbe4ee";
            card.style.background = "#fff";
            card.style.borderRadius = "10px";
            card.style.transition = "background .15s ease, border-color .15s ease";
            card.innerHTML = "<strong>" + escapeHtml(r.title) + "</strong>" +
                '<div class="text-muted" style="font-size:.75rem;">' + badge + "</div>" +
                "<div>" + escapeHtml(r.snippet) + "</div>";
            card.addEventListener("mouseenter", function () { card.style.background = "#f0f6ff"; card.style.borderColor = "#0057B8"; });
            card.addEventListener("mouseleave", function () { card.style.background = "#fff"; card.style.borderColor = "#dbe4ee"; });
            // Cliquer une bulle = reformuler avec son titre exact, exactement ce que RAF
            // demande dans son message de clarification — évite à l'agent de devoir retaper.
            card.addEventListener("click", function () { openSource(thread, r); });
            wrap.appendChild(card);
        });
        thread.appendChild(wrap);
        thread.scrollTop = thread.scrollHeight;
    }

    /** Badge discret indiquant si RAF a complété sa réponse avec le web (source externe). */
    function appendSourceBadge(thread, source) {
        if (source !== "WEB" && source !== "MIXED") return;
        var badge = document.createElement("div");
        badge.className = "text-muted";
        badge.style.fontSize = ".72rem";
        badge.style.maxWidth = "85%";
        badge.style.margin = "2px 0 4px";
        badge.textContent = source === "WEB"
            ? "🌐 Réponse complétée par une recherche web (aucune procédure interne trouvée)."
            : "🌐 Réponse complétée par des sources web en plus des procédures internes.";
        thread.appendChild(badge);
    }

    /** Confiance + sources consultées, dans l'ordre de priorité Ecobank (procédures > KB > Copilot > web). */
    function appendConfidence(thread, confidencePercent, sourcesConsulted) {
        if (confidencePercent == null && (!sourcesConsulted || !sourcesConsulted.length)) return;
        var badge = document.createElement("div");
        badge.className = "text-muted";
        badge.style.fontSize = ".72rem";
        badge.style.maxWidth = "85%";
        badge.style.margin = "0 0 6px";
        var parts = [];
        if (confidencePercent != null) parts.push(t("confidence") + " : " + confidencePercent + "%");
        if (sourcesConsulted && sourcesConsulted.length) parts.push(t("sources") + " : " + sourcesConsulted.join(", "));
        badge.textContent = parts.join(" · ");
        thread.appendChild(badge);
    }

    /** Bouton rapide pour développer la dernière réponse — envoie la demande de détails standard. */
    function appendDetailButton(thread, onClick) {
        var wrap = document.createElement("div");
        wrap.style.margin = "0 0 8px";
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn btn-sm btn-outline-secondary";
        btn.textContent = t("detailBtn");
        btn.addEventListener("click", onClick);
        wrap.appendChild(btn);
        thread.appendChild(wrap);
        thread.scrollTop = thread.scrollHeight;
    }

    function appendWebResults(thread, webResults) {
        if (!webResults || !webResults.length) return;
        var wrap = document.createElement("div");
        wrap.style.maxWidth = "85%";
        webResults.forEach(function (r) {
            var card = document.createElement("div");
            card.className = "ralph-result-card";
            var link = r.url ? '<a href="' + escapeHtml(r.url) + '" target="_blank" rel="noopener">' + escapeHtml(r.title) + "</a>" : escapeHtml(r.title);
            card.innerHTML = "<strong>" + link + "</strong>" +
                '<div class="text-muted" style="font-size:.75rem;">Source web</div>' +
                "<div>" + escapeHtml(r.snippet) + "</div>";
            wrap.appendChild(card);
        });
        thread.appendChild(wrap);
        thread.scrollTop = thread.scrollHeight;
    }

    var SOURCE_BADGES = {
        ARTICLE: "📖 Article Knowledge Base", COURSE: "🎓 Formation", PROCEDURE: "📋 Procédure",
        SLA: "⏱ Référentiel SLA", TERM: "📖 Glossaire", BRANCH: "🏦 Agence", MAIL_TEMPLATE: "✉ Modèle de mail",
        QUIZ: "✔ Q/R vérifiée QA", SCHEDULE: "🗓 Mon planning"
    };

    /** Clic sur une source citée : ouvre l'élément exact (procédure en mode RAF, article, cours...). */
    function openSource(thread, r) {
        if (r.sourceType === "PROCEDURE" && r.id != null) return ask(thread, r.title, "raf:proc:" + r.id);
        if (r.sourceType === "SLA" && r.id != null) return ask(thread, r.title, "raf:sla:" + r.id);
        if (r.sourceType === "MAIL_TEMPLATE" && r.id != null) return ask(thread, r.title, "raf:tpl:" + r.id);
        if (r.sourceType === "ARTICLE" && r.id != null) { window.location.href = "/knowledge?article=" + r.id; return; }
        if (r.sourceType === "COURSE" && r.id != null) { window.location.href = "/training?openCourseId=" + r.id; return; }
        if (r.sourceType === "TERM") return ask(thread, "c'est quoi " + r.title);
        ask(thread, r.title);
    }

    function scrollDown(thread) { thread.scrollTop = thread.scrollHeight; }

    /** Boutons de relance proposés par RAF (mode guidé, clarification, exemples...). */
    function appendSuggestions(thread, suggestions) {
        if (!suggestions || !suggestions.length) return false;
        var wrap = document.createElement("div");
        wrap.className = "ralph-chips";
        wrap.style.cssText = "display:flex;flex-wrap:wrap;gap:6px;margin:2px 0 8px;max-width:92%;";
        suggestions.forEach(function (sug) {
            var btn = document.createElement("button");
            btn.type = "button";
            btn.className = "btn btn-sm btn-outline-primary";
            btn.style.cssText = "border-radius:14px;font-size:.78rem;padding:2px 10px;";
            btn.textContent = sug.label;
            btn.addEventListener("click", function () {
                // Anti double-clic uniquement : les commandes de RAF sont rejouables sans risque.
                btn.disabled = true;
                setTimeout(function () { btn.disabled = false; }, 1500);
                ask(thread, sug.query || sug.label, sug.command || null);
            });
            wrap.appendChild(btn);
        });
        thread.appendChild(wrap);
        scrollDown(thread);
        return true;
    }

    /** « Agents consultés » — transparence sur qui a répondu (grisé = consulté sans résultat retenu). */
    function appendAgents(thread, traces) {
        if (!traces || !traces.length) return;
        var div = document.createElement("div");
        div.style.cssText = "font-size:.7rem;margin:0 0 6px;max-width:92%;color:#6b7280;";
        div.innerHTML = "🧭 " + escapeHtml(t("agentsConsulted")) + " : " + traces.map(function (tr) {
            return '<span title="' + escapeHtml("routeur " + tr.routerScore + "% · correspondance " + tr.matchScore + "%") + '" style="' +
                (tr.used ? "color:#0057B8;font-weight:600;" : "opacity:.55;") + '">' + escapeHtml(tr.label) + (tr.used ? " ✓" : "") + "</span>";
        }).join(" · ");
        thread.appendChild(div);
    }

    /** Élément interactif : étape guidée, échéance SLA, brouillon de mail. */
    function renderAction(thread, action) {
        if (!action || !action.payload) return;
        var p = action.payload;
        var box = document.createElement("div");
        box.className = "ralph-result-card";
        box.style.cssText = "max-width:92%;border:1px solid #dbe4ee;border-radius:10px;padding:8px 10px;margin:0 0 6px;background:#f8fbff;";
        if (action.type === "GUIDED_STEP") {
            var pct = Math.round(100 * p.step / p.total);
            box.innerHTML = '<div style="font-size:.72rem;color:#6b7280;">' + escapeHtml(t("step")) + " " + p.step + "/" + p.total + "</div>" +
                '<div style="height:6px;background:#e5e7eb;border-radius:3px;margin:4px 0;"><div style="height:6px;border-radius:3px;background:#0057B8;width:' + pct + '%;"></div></div>';
        } else if (action.type === "SLA_DUE") {
            box.innerHTML = "⏱ <strong>" + escapeHtml(t("dueBy")) + "</strong> " + escapeHtml(p.dueLabel || p.dueAt);
        } else if (action.type === "DRAFT") {
            var text = (p.subject ? p.subject + "\n\n" : "") + (p.body || "");
            box.innerHTML = '<div style="font-size:.72rem;color:#6b7280;margin-bottom:4px;">✉ ' + escapeHtml(t("draft")) + "</div>" +
                '<div style="white-space:pre-wrap;font-size:.82rem;">' + escapeHtml(text).replace(/\[([^\]]+)\]/g, '<mark>[$1]</mark>') + "</div>";
            var copy = document.createElement("button");
            copy.type = "button";
            copy.className = "btn btn-sm btn-outline-secondary mt-1";
            copy.textContent = "📋 " + t("copy");
            copy.addEventListener("click", function () {
                if (navigator.clipboard) navigator.clipboard.writeText(text).then(function () { copy.textContent = "✓ " + t("copied"); });
            });
            box.appendChild(copy);
        } else {
            return;
        }
        thread.appendChild(box);
        scrollDown(thread);
    }

    /** « Pourquoi cette réponse ? » — trace lisible du routage et des sources. */
    function appendWhy(thread, reasoning) {
        if (!reasoning || !reasoning.length) return;
        var d = document.createElement("details");
        d.style.cssText = "font-size:.72rem;color:#6b7280;margin:0 0 8px;max-width:92%;";
        d.innerHTML = "<summary style=\"cursor:pointer;\">" + escapeHtml(t("why")) + "</summary><ul class=\"mb-0 ps-3\">" +
            reasoning.map(function (r) { return "<li>" + escapeHtml(r) + "</li>"; }).join("") + "</ul>";
        thread.appendChild(d);
    }

    var welcomeShown = false;

    /** Exemples cliquables à la première ouverture — RAF montre ce qu'il sait faire. */
    function showWelcome(thread) {
        if (welcomeShown) return;
        welcomeShown = true;
        fetch("/api/ralph/welcome?lang=" + encodeURIComponent(getRafLanguage()), { credentials: "same-origin" })
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (w) { if (w) appendSuggestions(thread, w.suggestions); })
            .catch(function () {});
    }

    var lastQuestionWasVoice = false;

    /** Pose une question à RAF, ou envoie une commande de bouton (cmd = « raf:proc:12:step:2 »...). */
    function ask(thread, keyword, cmd) {
        appendMessage(thread, keyword, "user");
        var typing = appendMessage(thread, "…", "bot");
        var wasVoice = lastQuestionWasVoice;
        lastQuestionWasVoice = false;

        var url = "/api/ralph/ask?lang=" + encodeURIComponent(getRafLanguage()) +
            (cmd ? "&cmd=" + encodeURIComponent(cmd) : "&keyword=" + encodeURIComponent(keyword));
        fetch(url, { credentials: "same-origin" })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                return res.json();
            })
            .then(function (result) {
                typing.innerHTML = renderRafMarkdown(result.explanation);
                renderAction(thread, result.action);
                if (!appendSuggestions(thread, result.suggestions) && result.source !== "LOCAL" && result.source !== "NONE") {
                    appendDetailButton(thread, function () { ask(thread, t("detailAskText"), "raf:details"); });
                }
                appendResults(thread, result.results);
                appendSourceBadge(thread, result.source);
                appendWebResults(thread, result.webResults);
                appendConfidence(thread, result.confidencePercent, result.sourcesConsulted);
                appendAgents(thread, result.agentsConsulted);
                appendWhy(thread, result.reasoning);
                scrollDown(thread);
                if (wasVoice) speak(result.explanation.replace(/\*\*/g, ""));
            })
            .catch(function (e) {
                typing.textContent = t("error") + " : " + e.message;
            });
    }

    function resetConversation(thread) {
        fetch("/api/ralph/reset-conversation", { method: "POST", credentials: "same-origin" }).finally(function () {
            thread.innerHTML = "";
            appendMessage(thread, t("newConversation"), "bot");
        });
    }

    function analyzeFile(thread, file) {
        appendMessage(thread, "📎 " + file.name, "user");
        var typing = appendMessage(thread, "Lecture du fichier…", "bot");

        var formData = new FormData();
        formData.append("file", file);

        fetch("/api/ralph/analyze-file", { method: "POST", credentials: "same-origin", body: formData })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                return res.json();
            })
            .then(function (result) {
                typing.innerHTML = renderRafMarkdown(result.answer);
            })
            .catch(function (e) {
                typing.textContent = t("error") + " : " + e.message;
            });
    }

    function speak(text) {
        if (!("speechSynthesis" in window)) return;
        window.speechSynthesis.cancel(); // n'empile jamais plusieurs réponses parlées
        var utterance = new SpeechSynthesisUtterance(text);
        utterance.lang = "fr-FR";
        window.speechSynthesis.speak(utterance);
    }

    function wireVoiceDialogue(input, form, micBtn) {
        var SpeechRecognitionCtor = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (!SpeechRecognitionCtor) {
            micBtn.classList.add("d-none"); // navigateur qui ne supporte pas la reconnaissance vocale
            return;
        }

        // La reconnaissance vocale du navigateur exige un contexte sécurisé (HTTPS, ou
        // localhost) — sur http:// simple (fréquent en interne sur un réseau d'entreprise),
        // elle échoue systématiquement et silencieusement. On le détecte tout de suite plutôt
        // que de laisser le micro sembler "ne pas marcher" sans explication.
        var isSecureContext = window.isSecureContext ||
            location.protocol === "https:" || location.hostname === "localhost" || location.hostname === "127.0.0.1";
        if (!isSecureContext) {
            micBtn.title = t("micUnavailable");
            micBtn.addEventListener("click", function () {
                alert("Le micro ne peut pas fonctionner tant que le site n'est pas servi en HTTPS (ou via localhost). " +
                    "C'est une restriction du navigateur, pas un bug de RAF — contactez l'IT pour activer HTTPS sur ce site.");
            });
            return;
        }

        var recognition = new SpeechRecognitionCtor();
        recognition.lang = "fr-FR";
        recognition.interimResults = false;
        recognition.maxAlternatives = 1;
        var listening = false;

        var ERROR_MESSAGES = {
            "not-allowed": "Accès au micro refusé — autorisez le microphone pour ce site dans les réglages du navigateur.",
            "service-not-allowed": "Le service de reconnaissance vocale du navigateur est bloqué (souvent un pare-feu/proxy d'entreprise qui coupe l'accès aux serveurs vocaux de Google, utilisés par Chrome).",
            "no-speech": "Aucune voix détectée — réessayez en parlant juste après avoir cliqué sur le micro.",
            "audio-capture": "Aucun microphone détecté sur cet appareil.",
            "network": "Erreur réseau pendant la reconnaissance vocale — le service de transcription du navigateur nécessite un accès internet, qui semble bloqué ici (réseau d'entreprise fermé)."
        };

        function resetMicButton() {
            listening = false;
            micBtn.classList.remove("btn-danger");
            micBtn.classList.add("btn-outline-secondary");
        }

        recognition.addEventListener("result", function (evt) {
            var transcript = evt.results[0][0].transcript;
            input.value = transcript;
            lastQuestionWasVoice = true;
            form.dispatchEvent(new Event("submit", { cancelable: true }));
        });
        recognition.addEventListener("end", resetMicButton);
        recognition.addEventListener("error", function (evt) {
            resetMicButton();
            var message = ERROR_MESSAGES[evt.error] || ("Erreur de reconnaissance vocale (" + evt.error + ").");
            console.warn("RAF micro :", evt.error);
            alert(message);
        });

        micBtn.addEventListener("click", function () {
            if (listening) { recognition.stop(); return; }
            listening = true;
            micBtn.classList.remove("btn-outline-secondary");
            micBtn.classList.add("btn-danger"); // rouge = écoute en cours
            try {
                recognition.start();
            } catch (e) {
                resetMicButton();
                alert("Impossible de démarrer le micro : " + e.message);
            }
        });
    }

    function init() {
        var fab = document.getElementById("ralphFab");
        var panel = document.getElementById("ralphPanel");
        var thread = document.getElementById("ralphThread");
        var form = document.getElementById("ralphFabForm");
        var input = document.getElementById("ralphFabInput");
        var closeBtn = document.getElementById("ralphCloseBtn");
        var resetBtn = document.getElementById("ralphResetBtn");
        var expandBtn = document.getElementById("ralphExpandBtn");
        var fileBtn = document.getElementById("ralphFileBtn");
        var fileInput = document.getElementById("ralphFileInput");
        if (!fab || !panel) return; // page sans le fragment sidebar (ex. login)

        // Analyse de fichier réservée à l'IT (Admin) — le bouton reste invisible pour les autres profils.
        fetch("/api/auth/me", { credentials: "same-origin" }).then(function (res) {
            return res.ok ? res.json() : null;
        }).then(function (user) {
            if (user && user.role && user.role.toUpperCase() === "ADMIN") {
                fileBtn.classList.remove("d-none");
            }
        }).catch(function () {});
        fileBtn.classList.add("d-none");

        var micBtn = document.getElementById("ralphMicBtn");
        if (micBtn) wireVoiceDialogue(input, form, micBtn);

        var translateSubmitBtn = document.getElementById("ralphTranslateSubmitBtn");
        if (translateSubmitBtn) {
            translateSubmitBtn.addEventListener("click", function () {
                var text = document.getElementById("ralphTranslateSource").value.trim();
                var resultBox = document.getElementById("ralphTranslateResult");
                if (!text) return;
                translateSubmitBtn.disabled = true;
                fetch("/api/ralph/translate", {
                    method: "POST", credentials: "same-origin",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ text: text, sourceLang: "auto", targetLang: document.getElementById("ralphTranslateTargetLang").value })
                })
                    .then(function (res) {
                        if (!res.ok) return res.text().then(function (msg) { return Promise.reject(new Error(msg || "HTTP " + res.status)); });
                        return res.json();
                    })
                    .then(function (result) { resultBox.value = result.translated; })
                    .catch(function (e) {
                        var message = e.message || "";
                        try { var parsed = JSON.parse(message); message = parsed.message || message; } catch (ignore) {}
                        resultBox.value = "";
                        alert("Traduction impossible : " + message);
                    })
                    .finally(function () { translateSubmitBtn.disabled = false; });
            });
        }

        var translateCopyBtn = document.getElementById("ralphTranslateCopyBtn");
        if (translateCopyBtn) {
            translateCopyBtn.addEventListener("click", function () {
                var resultBox = document.getElementById("ralphTranslateResult");
                if (!resultBox.value) return;
                resultBox.select();
                document.execCommand("copy");
            });
        }

        if (expandBtn) {
            expandBtn.addEventListener("click", function () {
                var expanded = panel.classList.toggle("ralph-panel-expanded");
                expandBtn.querySelector("i").className = expanded ? "bi bi-arrows-angle-contract" : "bi bi-arrows-angle-expand";
                expandBtn.title = expanded ? t("collapse") : t("expand");
            });
        }
        applyRafLanguageToStaticUI();

        // Choix de la langue de réponse de RAF
        var langMenu = document.getElementById("ralphLanguageMenu");
        if (langMenu) {
            var markActiveLang = function () {
                var current = getRafLanguage();
                Array.prototype.forEach.call(langMenu.querySelectorAll(".ralph-lang-option"), function (btn) {
                    btn.classList.toggle("active", btn.getAttribute("data-lang") === current);
                });
            };
            markActiveLang();
            Array.prototype.forEach.call(langMenu.querySelectorAll(".ralph-lang-option"), function (btn) {
                btn.addEventListener("click", function () {
                    setRafLanguage(btn.getAttribute("data-lang"));
                    markActiveLang();
                });
            });
        }

        // Traduction — ouvre une modale dédiée (coller un texte, choisir la langue, traduire)
        var translateBtn = document.getElementById("ralphTranslateBtn");
        if (translateBtn) {
            translateBtn.addEventListener("click", function () {
                var modalEl = document.getElementById("ralphTranslateModal");
                if (!modalEl) return;
                document.getElementById("ralphTranslateSource").value = "";
                document.getElementById("ralphTranslateResult").value = "";
                document.getElementById("ralphTranslateTargetLang").value = getRafLanguage();
                new bootstrap.Modal(modalEl).show();
            });
        }

        fab.addEventListener("click", function () {
            fab.classList.add("launching");
            setTimeout(function () {
                fab.classList.remove("launching");
                fab.classList.add("d-none");
                panel.classList.remove("d-none");
                input.focus();
                showWelcome(thread);
            }, 480);
        });

        closeBtn.addEventListener("click", function () {
            panel.classList.add("d-none");
            fab.classList.remove("d-none");
        });

        if (resetBtn) {
            resetBtn.addEventListener("click", function () { resetConversation(thread); });
        }

        fileBtn.addEventListener("click", function () { fileInput.click(); });
        fileInput.addEventListener("change", function () {
            var file = fileInput.files[0];
            if (!file) return;
            analyzeFile(thread, file);
            fileInput.value = "";
        });

        form.addEventListener("submit", function (evt) {
            evt.preventDefault();
            var keyword = input.value.trim();
            if (!keyword) return;
            input.value = "";
            ask(thread, keyword);
        });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
