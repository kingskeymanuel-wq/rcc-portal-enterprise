"use strict";

/**
 * « Mes formations » dans les portails agent (Outbound / Télévente…) : formations de son équipe, progression,
 * obligatoires à terminer, prochaine session et reprise directe de la leçon en cours. Données : /api/training/formations.
 */
window.RccAgentTrainings = (function () {
    var esc = RccApi.escapeHtml;
    var CLOSED = ["ARCHIVED", "CLOSED", "TERMINEE"];

    function openFirstLesson(formationId, btn) {
        btn.disabled = true;
        RccApi.getJson("/api/training/formations/" + formationId + "/lessons").then(function (lessons) {
            if (!lessons || !lessons.length) { btn.disabled = false; alert("Cette formation n'a pas encore de leçon."); return; }
            var next = lessons.filter(function (l) { return !l.completed; })[0] || lessons[0];
            window.location.href = "/training/lesson/" + next.lessonId;
        }).catch(function (e) { btn.disabled = false; alert(e.message); });
    }

    function card(f) {
        var pct = typeof f.myProgressPercent === "number" ? Math.max(0, Math.min(100, f.myProgressPercent)) : 0;
        var state = pct >= 100 ? "done" : pct > 0 ? "doing" : "todo";
        var when = f.scheduledDate ? new Date(f.scheduledDate + "T00:00:00").toLocaleDateString("fr-FR", { day: "2-digit", month: "short" }) +
            (f.scheduledTime ? " · " + String(f.scheduledTime).slice(0, 5) : "") : "";
        return '<article class="at-card ' + state + '">' +
            '<div class="at-top">' + (f.mandatory ? '<span class="at-badge must">Obligatoire</span>' : '') +
            (f.category ? '<span class="at-badge">' + esc(f.category) + '</span>' : '') +
            (when ? '<span class="at-when"><i class="bi bi-calendar-event"></i> ' + esc(when) + '</span>' : '') + '</div>' +
            '<h6>' + esc(f.title) + '</h6>' +
            (f.description ? '<p>' + esc(f.description) + '</p>' : '') +
            '<div class="at-meta"><span><i class="bi bi-journal-text"></i> ' + (f.lessonCount || 0) + ' leçon(s)</span>' +
            (f.durationMinutes ? '<span><i class="bi bi-clock"></i> ' + f.durationMinutes + ' min</span>' : '') + '</div>' +
            '<div class="at-progress"><div class="at-bar"><i style="width:' + pct + '%"></i></div><b>' + pct + ' %</b></div>' +
            '<button type="button" class="at-btn ' + state + '" data-formation="' + f.formationId + '">' +
            (state === "done" ? '<i class="bi bi-arrow-repeat"></i> Revoir' : state === "doing" ? '<i class="bi bi-play-fill"></i> Continuer' : '<i class="bi bi-play-circle"></i> Commencer') + '</button></article>';
    }

    function mount(root, opts) {
        opts = opts || {};
        root.classList.add("at-root");
        root.innerHTML = '<div class="at-head"><div><span class="at-kicker"><i class="bi bi-mortarboard-fill"></i> ' + esc(opts.kicker || "Formation") + '</span>' +
            '<h5>Mes formations</h5><small>' + esc(opts.subtitle || "Les formations de votre équipe — reprenez là où vous vous êtes arrêté.") + '</small></div>' +
            '<a class="at-all" href="/training"><i class="bi bi-grid"></i> Espace formation</a></div>' +
            '<div class="at-stats"></div><div class="at-filters"></div><div class="at-list"><div class="at-empty">Chargement des formations…</div></div>';
        var filter = "open";
        var list = [];

        function render() {
            var active = list.filter(function (f) { return !f.status || CLOSED.indexOf(String(f.status).toUpperCase()) === -1; });
            var pctOf = function (f) { return typeof f.myProgressPercent === "number" ? f.myProgressPercent : 0; };
            var done = active.filter(function (f) { return pctOf(f) >= 100; });
            var doing = active.filter(function (f) { return pctOf(f) > 0 && pctOf(f) < 100; });
            var todo = active.filter(function (f) { return pctOf(f) === 0; });
            var must = active.filter(function (f) { return f.mandatory && pctOf(f) < 100; });
            var avg = active.length ? Math.round(active.reduce(function (a, f) { return a + pctOf(f); }, 0) / active.length) : 0;
            root.querySelector(".at-stats").innerHTML = [
                ["bi-graph-up-arrow", avg + " %", "progression moyenne"], ["bi-play-circle", doing.length, "en cours"],
                ["bi-check2-circle", done.length, "terminée(s)"], ["bi-exclamation-diamond", must.length, "obligatoire(s) à finir"]
            ].map(function (k, i) { return '<div class="at-stat s' + i + '"><i class="bi ' + k[0] + '"></i><b>' + k[1] + '</b><span>' + k[2] + '</span></div>'; }).join("");
            root.querySelector(".at-filters").innerHTML = [["open", "À suivre", doing.length + todo.length], ["must", "Obligatoires", must.length], ["done", "Terminées", done.length], ["all", "Toutes", active.length]]
                .map(function (x) { return '<button type="button" data-f="' + x[0] + '"' + (filter === x[0] ? ' class="on"' : '') + '>' + x[1] + ' <span>' + x[2] + '</span></button>'; }).join("");
            var shown = filter === "open" ? doing.concat(todo) : filter === "must" ? must : filter === "done" ? done : active;
            shown = shown.slice().sort(function (a, b) { return (b.mandatory ? 1 : 0) - (a.mandatory ? 1 : 0) || pctOf(b) - pctOf(a); });
            root.querySelector(".at-list").innerHTML = shown.length ? shown.map(card).join("")
                : '<div class="at-empty"><i class="bi bi-mortarboard"></i> ' + (active.length ? "Rien dans cette catégorie." : "Aucune formation programmée pour votre équipe pour l'instant.") + '</div>';
        }

        root.addEventListener("click", function (e) {
            var f = e.target.closest("[data-f]");
            if (f) { filter = f.getAttribute("data-f"); render(); return; }
            var b = e.target.closest("[data-formation]");
            if (b) openFirstLesson(b.getAttribute("data-formation"), b);
        });
        function load() {
            return RccApi.getJson("/api/training/formations").then(function (r) { list = Array.isArray(r) ? r : []; render(); })
                .catch(function (e) { root.querySelector(".at-list").innerHTML = '<div class="at-empty text-danger">' + esc(e.message) + '</div>'; });
        }
        setInterval(function () { if (!document.hidden) load(); }, 5 * 60 * 1000);
        return load();
    }

    return { mount: mount };
})();
