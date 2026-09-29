"use strict";

/**
 * Page Audit (administration) : onglets, diffusion (notification portail et/ou e-mail, ciblage filiale/équipe
 * avec aperçu de l'audience réelle) et journal de connexions classé par filiale, mis à jour automatiquement.
 */
(function () {

    var esc = RccApi.escapeHtml;
    var getJson = RccApi.getJson;

    var FILIALES = [["CI", "Côte d'Ivoire"], ["TG", "Togo"]];
    var FILIALE_LABEL = { CI: "Côte d'Ivoire", TG: "Togo" };
    var EVENTS = {
        login_success: { label: "Connexion réussie", cls: "ok", icon: "bi-check-circle-fill" },
        login_failed: { label: "Échec de connexion", cls: "ko", icon: "bi-x-circle-fill" },
        locked: { label: "Compte verrouillé", cls: "warn", icon: "bi-lock-fill" },
        password_reset: { label: "Mot de passe réinitialisé", cls: "info", icon: "bi-key-fill" }
    };
    var TEAMS = {
        INBOUND_VOICE: "Inbound Voix", INBOUND_MAIL: "Inbound Mail", TCHAT: "Tchat", RAFIKI: "Rafiki", CIB: "CIB",
        OUTBOUND: "Outbound", RH: "RH", QA: "Qualité", SUPERVISION: "Supervision", AGENCE: "Agence", FORMATION: "Formation", AUTRE: "Autre"
    };

    function $(id) { return document.getElementById(id); }

    function wireChips(box, onChange) {
        box.addEventListener("click", function (e) {
            var b = e.target.closest("button[data-v]");
            if (!b) return;
            box.querySelectorAll("button").forEach(function (x) { x.classList.toggle("on", x === b); });
            onChange(b.getAttribute("data-v"));
        });
    }

    function chipValue(box) {
        var on = box.querySelector("button.on");
        return on ? on.getAttribute("data-v") : "";
    }

    function initials(name) {
        var w = String(name || "?").replace(/—.*$/, "").trim().split(/\s+/);
        return ((w[0] || "?").charAt(0) + (w[1] ? w[1].charAt(0) : "")).toUpperCase();
    }

    // ============================ Diffusion ============================

    var bcSent = [];

    function bcChannels() {
        var out = {};
        $("bcChannels").querySelectorAll("input").forEach(function (i) { out[i.value] = i.checked; });
        return out;
    }

    function bcTarget() {
        return { countryCode: chipValue($("bcCountries")) || null, activity: chipValue($("bcTeams")) || null, serviceCode: $("bcService").value || null };
    }

    function bcTargetLabel(t) {
        var parts = [];
        if (t.countryCode) parts.push(FILIALE_LABEL[t.countryCode] || t.countryCode);
        if (t.activity) parts.push("équipe " + (TEAMS[t.activity] || t.activity));
        if (t.serviceCode) parts.push("service " + $("bcService").selectedOptions[0].textContent);
        return parts.length ? parts.join(" · ") : "Tout le portail";
    }

    var audTimer = null;
    function refreshAudience() {
        clearTimeout(audTimer);
        audTimer = setTimeout(function () {
            var t = bcTarget();
            var q = Object.keys(t).filter(function (k) { return t[k]; }).map(function (k) { return k + "=" + encodeURIComponent(t[k]); }).join("&");
            $("bcAudTarget").textContent = bcTargetLabel(t);
            getJson("/api/audit/audience" + (q ? "?" + q : "")).then(function (a) {
                $("bcAudTotal").textContent = a.total;
                $("bcAudEmail").textContent = a.withEmail;
                var by = a.byCountry || {};
                $("bcAudCountries").innerHTML = Object.keys(by).map(function (c) {
                    return '<span><b>' + esc(c) + '</b> ' + esc(FILIALE_LABEL[c] || c) + ' · ' + by[c] + '</span>';
                }).join("") || '<span class="bc-none">Aucun destinataire</span>';
                $("bcAudTotal").parentNode.classList.toggle("zero", !a.total);
            }).catch(function () { $("bcAudTotal").textContent = "—"; });
        }, 200);
    }

    function refreshPreview() {
        var ch = bcChannels();
        $("bcSubjectWrap").hidden = !ch.EMAIL;
        $("bcChannels").querySelectorAll("label").forEach(function (l) { l.classList.toggle("on", l.querySelector("input").checked); });
        var text = $("bcContent").value.trim();
        $("bcCount").textContent = $("bcContent").value.length;
        $("bcPrevText").textContent = text || "Votre message apparaîtra ici.";
        $("bcPrevSubject").textContent = ch.EMAIL && $("bcSubject").value.trim() ? $("bcSubject").value.trim() : "Annonce RCC";
        var label = ch.NOTIF && ch.EMAIL ? "Envoyer la notification et l'e-mail" : ch.EMAIL ? "Envoyer l'e-mail" : "Envoyer la notification";
        $("bcSendLabel").textContent = label;
        $("bcSend").disabled = !text || (!ch.NOTIF && !ch.EMAIL);
    }

    function showResult(kind, html) {
        var box = $("bcResult");
        box.hidden = false;
        box.className = "bc-result " + kind;
        box.innerHTML = html;
    }

    function renderHistory() {
        $("bcHistory").innerHTML = bcSent.length ? bcSent.map(function (h) {
            return '<li><span class="bc-h-time">' + h.time + '</span><div><b>' + esc(h.text) + '</b><small>' + esc(h.meta) + '</small></div></li>';
        }).join("") : '<li class="bc-empty">Aucune diffusion depuis l\'ouverture de la page.</li>';
    }

    function wireBroadcast() {
        FILIALES.forEach(function (f) {
            $("bcCountries").insertAdjacentHTML("beforeend", '<button type="button" data-v="' + f[0] + '"><b>' + f[0] + '</b> ' + esc(f[1]) + '</button>');
        });
        wireChips($("bcCountries"), refreshAudience);
        wireChips($("bcTeams"), refreshAudience);
        $("bcService").addEventListener("change", refreshAudience);
        fetch("/api/procedures/services", { credentials: "same-origin" }).then(function (r) { return r.ok ? r.json() : []; }).then(function (services) {
            $("bcService").innerHTML = '<option value="">Tous les services</option>' +
                services.map(function (s) { return '<option value="' + esc(s.code) + '">' + esc(s.name) + '</option>'; }).join("");
        }).catch(function () {});

        $("bcChannels").addEventListener("change", function () {
            $("bcContent").maxLength = bcChannels().EMAIL && !bcChannels().NOTIF ? 2000 : 500;
            $("bcMax").textContent = $("bcContent").maxLength;
            refreshPreview();
        });
        ["bcContent", "bcSubject"].forEach(function (id) { $(id).addEventListener("input", refreshPreview); });
        $("bcReset").addEventListener("click", function () {
            $("bcContent").value = ""; $("bcSubject").value = ""; $("bcResult").hidden = true; refreshPreview();
        });

        $("bcForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            var ch = bcChannels();
            var content = $("bcContent").value.trim();
            var subject = $("bcSubject").value.trim();
            if (!content) return;
            if (ch.EMAIL && !subject) { showResult("ko", '<i class="bi bi-exclamation-triangle"></i> Indiquez l\'objet de l\'e-mail.'); $("bcSubject").focus(); return; }
            var t = bcTarget();
            var total = $("bcAudTotal").textContent;
            if (!confirm("Envoyer à " + total + " destinataire(s) — " + bcTargetLabel(t) + " ?")) return;
            $("bcSend").disabled = true;
            showResult("wait", '<span class="spinner-border spinner-border-sm"></span> Envoi en cours…');
            var done = [];
            var chain = Promise.resolve();
            if (ch.NOTIF) {
                chain = chain.then(function () {
                    return RccApi.sendJson("/api/mon-rcc/notifications", "POST", { content: content, countryCode: t.countryCode, serviceCode: t.serviceCode, activity: t.activity })
                        .then(function () { done.push("notification envoyée à " + total + " destinataire(s)"); });
                });
            }
            if (ch.EMAIL) {
                chain = chain.then(function () {
                    return RccApi.sendJson("/api/audit/broadcast-email", "POST", { subject: subject, body: content, countryCode: t.countryCode, serviceCode: t.serviceCode, activity: t.activity })
                        .then(function (r) { done.push(r.sentCount + " e-mail(s) envoyé(s) sur " + r.recipientCount + " adresse(s)"); });
                });
            }
            chain.then(function () {
                showResult("ok", '<i class="bi bi-check-circle-fill"></i> ' + esc(done.join(" · ")) + '.');
                bcSent.unshift({ time: new Date().toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" }), text: content.slice(0, 90),
                    meta: (ch.NOTIF ? "Notification" : "") + (ch.NOTIF && ch.EMAIL ? " + " : "") + (ch.EMAIL ? "E-mail" : "") + " · " + bcTargetLabel(t) });
                renderHistory();
                $("bcContent").value = ""; $("bcSubject").value = "";
            }).catch(function (e) {
                showResult("ko", '<i class="bi bi-exclamation-triangle-fill"></i> ' + esc(e.message) + (done.length ? " (déjà fait : " + esc(done.join(", ")) + ")" : ""));
            }).finally(refreshPreview);
        });
        refreshPreview();
        refreshAudience();
    }

    // ======================= Journal de connexions =======================

    var lgRows = [];
    var lg = { country: "", event: "", days: 7, q: "" };

    function dayKey(d) { return d.getFullYear() + "-" + (d.getMonth() + 1) + "-" + d.getDate(); }

    function dayLabel(d) {
        var today = new Date(); var y = new Date(); y.setDate(y.getDate() - 1);
        if (dayKey(d) === dayKey(today)) return "Aujourd'hui";
        if (dayKey(d) === dayKey(y)) return "Hier";
        var s = d.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long" });
        return s.charAt(0).toUpperCase() + s.slice(1);
    }

    function splitName(full) {
        var parts = String(full || "").split(/\s+[—–-]\s+/);
        return { name: parts[0], role: parts.slice(1).join(" — ") };
    }

    function inPeriod(r) {
        if (!lg.days) return true;
        var from = new Date(); from.setHours(0, 0, 0, 0); from.setDate(from.getDate() - (lg.days - 1));
        return new Date(r.occurredAt) >= from;
    }

    function matches(r) {
        if (!lg.q) return true;
        var hay = [r.fullName, r.username, TEAMS[r.team], r.ipAddress].join(" ").toLowerCase();
        return hay.indexOf(lg.q) !== -1;
    }

    function renderKpis(rows) {
        var ok = rows.filter(function (r) { return r.eventType === "login_success"; });
        var users = {};
        ok.forEach(function (r) { users[r.username] = 1; });
        var ko = rows.filter(function (r) { return r.eventType === "login_failed"; }).length;
        var locked = rows.filter(function (r) { return r.eventType === "locked"; }).length;
        var rate = rows.length ? Math.round(ok.length * 100 / (ok.length + ko || 1)) : 0;
        $("lgKpis").innerHTML = [
            ["blue", "bi-box-arrow-in-right", ok.length, "connexions réussies"],
            ["green", "bi-people-fill", Object.keys(users).length, "utilisateurs connectés"],
            ["orange", "bi-x-octagon", ko, "échecs · " + rate + " % de réussite"],
            ["violet", "bi-lock-fill", locked, "comptes verrouillés"]
        ].map(function (k) {
            return '<div class="us-kpi"><span class="us-kpi-ico ' + k[0] + '"><i class="bi ' + k[1] + '"></i></span><div><b>' + k[2] + '</b><small>' + k[3] + '</small></div></div>';
        }).join("");
    }

    function renderCountries(base) {
        var counts = { "": base.length };
        base.forEach(function (r) { var c = r.countryCode || "CI"; counts[c] = (counts[c] || 0) + 1; });
        var codes = FILIALES.map(function (f) { return f[0]; });
        Object.keys(counts).forEach(function (c) { if (c && codes.indexOf(c) === -1) codes.push(c); });
        $("lgCountries").innerHTML = '<button type="button" data-v=""' + (lg.country ? "" : ' class="on"') + '><i class="bi bi-globe2"></i> Toutes filiales <span>' + counts[""] + '</span></button>' +
            codes.map(function (c) {
                return '<button type="button" data-v="' + c + '"' + (lg.country === c ? ' class="on"' : "") + '><b>' + c + '</b> ' + esc(FILIALE_LABEL[c] || c) + ' <span>' + (counts[c] || 0) + '</span></button>';
            }).join("");
    }

    function rowHtml(r) {
        var ev = EVENTS[r.eventType] || { label: r.eventType, cls: "info", icon: "bi-dot" };
        var n = splitName(r.fullName || r.username);
        var d = new Date(r.occurredAt);
        return '<div class="lg-row">' +
            '<span class="us-av lg-av ' + ev.cls + '">' + esc(initials(n.name)) + '</span>' +
            '<div class="lg-who"><b>' + esc(n.name) + '</b><small>' + esc(n.role || r.username || "") + '</small></div>' +
            '<span class="lg-team">' + esc(TEAMS[r.team] || "—") + '</span>' +
            '<span class="lg-ev ' + ev.cls + '"><i class="bi ' + ev.icon + '"></i> ' + esc(ev.label) + '</span>' +
            '<span class="lg-time"><i class="bi bi-clock"></i> ' + d.toLocaleTimeString("fr-FR") + '</span>' +
            '<span class="lg-ip">' + esc(r.ipAddress || "—") + '</span>' +
            '</div>';
    }

    function renderLogins() {
        var base = lgRows.filter(inPeriod).filter(matches);
        renderCountries(base.filter(function (r) { return !lg.event || r.eventType === lg.event; }));
        var rows = base.filter(function (r) {
            return (!lg.country || (r.countryCode || "CI") === lg.country) && (!lg.event || r.eventType === lg.event);
        });
        renderKpis(base.filter(function (r) { return !lg.country || (r.countryCode || "CI") === lg.country; }));
        if (!rows.length) {
            $("lgBody").innerHTML = '<div class="lg-empty"><i class="bi bi-inbox"></i> Aucun événement pour ces filtres.</div>';
            return;
        }
        var groups = {};
        var order = [];
        rows.forEach(function (r) {
            var c = r.countryCode || "CI";
            if (!groups[c]) { groups[c] = []; order.push(c); }
            groups[c].push(r);
        });
        order.sort(function (a, b) { return FILIALES.map(function (f) { return f[0]; }).indexOf(a) - FILIALES.map(function (f) { return f[0]; }).indexOf(b); });
        $("lgBody").innerHTML = order.map(function (c) {
            var list = groups[c];
            var ok = list.filter(function (r) { return r.eventType === "login_success"; }).length;
            var ko = list.filter(function (r) { return r.eventType !== "login_success"; }).length;
            var people = {};
            list.forEach(function (r) { people[r.username] = 1; });
            var html = '<section class="lg-card"><header class="lg-head"><span class="lg-flag">' + esc(c) + '</span>' +
                '<div><h6>' + esc(FILIALE_LABEL[c] || c) + '</h6><small>' + Object.keys(people).length + ' utilisateur(s) · ' + list.length + ' événement(s)</small></div>' +
                '<div class="lg-head-stats"><span class="ok"><i class="bi bi-check-circle-fill"></i> ' + ok + '</span><span class="ko"><i class="bi bi-exclamation-circle-fill"></i> ' + ko + '</span></div>' +
                '<div class="lg-bar"><span style="width:' + Math.round(ok * 100 / list.length) + '%"></span></div></header>';
            var lastDay = null;
            list.forEach(function (r) {
                var d = new Date(r.occurredAt);
                if (dayKey(d) !== lastDay) {
                    lastDay = dayKey(d);
                    var n = list.filter(function (x) { return dayKey(new Date(x.occurredAt)) === lastDay; }).length;
                    html += '<div class="lg-day"><i class="bi bi-calendar3"></i> ' + esc(dayLabel(d)) + ' <span>' + n + '</span></div>';
                }
                html += rowHtml(r);
            });
            return html + '</section>';
        }).join("");
    }

    function loadLogins() {
        return getJson("/api/audit/logins?limit=500").then(function (rows) {
            lgRows = rows || [];
            $("lgUpdated").textContent = "actualisé à " + new Date().toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
            renderLogins();
        }).catch(function (e) {
            $("lgBody").innerHTML = '<div class="lg-empty text-danger">Erreur (' + esc(e.message) + ')</div>';
        });
    }

    function wireLogins() {
        wireChips($("lgCountries"), function (v) { lg.country = v; renderLogins(); });
        wireChips($("lgEvents"), function (v) { lg.event = v; renderLogins(); });
        $("lgPeriod").addEventListener("click", function (e) {
            var b = e.target.closest("button[data-days]");
            if (!b) return;
            $("lgPeriod").querySelectorAll("button").forEach(function (x) { x.classList.toggle("on", x === b); });
            lg.days = Number(b.getAttribute("data-days"));
            renderLogins();
        });
        $("lgSearch").addEventListener("input", function () { lg.q = this.value.trim().toLowerCase(); renderLogins(); });
        loadLogins();
        setInterval(function () { if (!document.hidden) loadLogins(); }, 60000);
    }

    function wireTabs() {
        var buttons = document.querySelectorAll("#auTabs [data-pane]");
        buttons.forEach(function (b) {
            b.addEventListener("click", function () {
                buttons.forEach(function (x) {
                    x.classList.toggle("on", x === b);
                    $(x.getAttribute("data-pane")).style.display = x === b ? "" : "none";
                });
            });
        });
    }

    function init() {
        wireTabs();
        if (window.RccAuditUsage) window.RccAuditUsage.init();
        wireBroadcast();
        wireLogins();
    }

    document.addEventListener("DOMContentLoaded", init);
})();
