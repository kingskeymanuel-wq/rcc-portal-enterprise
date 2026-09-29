"use strict";

/**
 * Sélecteur de jours du planning Team Leader : choisir des jours précis sur plusieurs semaines et plusieurs mois.
 * Deux mois affichés côte à côte (navigation ‹ ›), clic sur un jour, Maj+clic pour une plage, clic sur un numéro
 * de semaine ou sur le nom du mois pour tout (dé)sélectionner, raccourcis : jours de la semaine choisis
 * (ex. chaque lundi et mercredi), Lun–Ven, week-ends, sur 1, 2 ou 3 mois.
 * RccDayPicker.mount(root, { onChange }) → { days(), clear(), count() }.
 */
window.RccDayPicker = (function () {
    var DOW = ["L", "M", "M", "J", "V", "S", "D"];
    var DOW_LONG = ["lundis", "mardis", "mercredis", "jeudis", "vendredis", "samedis", "dimanches"];
    var MONTHS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"];

    function iso(d) { return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); }
    function parse(s) { return new Date(s + "T00:00:00"); }
    function dow(d) { return (d.getDay() + 6) % 7; } // lundi = 0
    function isoWeek(d) {
        var t = new Date(d.getFullYear(), d.getMonth(), d.getDate());
        t.setDate(t.getDate() + 3 - dow(t));
        var first = new Date(t.getFullYear(), 0, 4);
        return 1 + Math.round(((t - first) / 86400000 - 3 + dow(first)) / 7);
    }

    function mount(root, opts) {
        opts = opts || {};
        var sel = {};
        var today = new Date(); today.setHours(0, 0, 0, 0);
        var start = new Date(today.getFullYear(), today.getMonth(), 1);
        var last = null;
        var span = 1; // raccourcis appliqués sur 1, 2 ou 3 mois à partir du premier mois affiché

        root.classList.add("dp-root");
        root.innerHTML =
            '<div class="dp-tools">' +
            '  <div class="dp-dows" title="Choisir un jour de la semaine sur les mois ciblés">' + DOW.map(function (d, i) { return '<button type="button" data-dow="' + i + '" title="Tous les ' + DOW_LONG[i] + '">' + d + '</button>'; }).join("") + '</div>' +
            '  <button type="button" class="dp-q" data-q="week">Lun–Ven</button><button type="button" class="dp-q" data-q="weekend">Week-ends</button>' +
            '  <button type="button" class="dp-q" data-q="all">Tous les jours</button>' +
            '  <span class="dp-span">sur <select class="dp-span-sel"><option value="1">1 mois</option><option value="2">2 mois</option><option value="3">3 mois</option></select></span>' +
            '  <button type="button" class="dp-q dp-clear" data-q="clear"><i class="bi bi-eraser"></i> Effacer</button>' +
            '</div>' +
            '<div class="dp-nav"><button type="button" data-nav="-1" aria-label="Mois précédent"><i class="bi bi-chevron-left"></i></button>' +
            '<span class="dp-hint">Clic : un jour · Maj+clic : une plage · clic sur « S. » ou le mois : tout le bloc</span>' +
            '<button type="button" data-nav="1" aria-label="Mois suivant"><i class="bi bi-chevron-right"></i></button></div>' +
            '<div class="dp-months"></div>' +
            '<div class="dp-summary"></div>';

        function monthsShown() { return [0, 1].map(function (i) { return new Date(start.getFullYear(), start.getMonth() + i, 1); }); }

        function daysOfMonth(m) {
            var out = [], d = new Date(m);
            while (d.getMonth() === m.getMonth()) { out.push(new Date(d)); d.setDate(d.getDate() + 1); }
            return out;
        }

        function targetDays() {
            var out = [];
            for (var i = 0; i < span; i++) out = out.concat(daysOfMonth(new Date(start.getFullYear(), start.getMonth() + i, 1)));
            return out.filter(function (d) { return d >= today; });
        }

        function setMany(days, on) { days.forEach(function (d) { if (on) sel[iso(d)] = true; else delete sel[iso(d)]; }); }
        function allSelected(days) { return days.length && days.every(function (d) { return sel[iso(d)]; }); }

        function render() {
            root.querySelector(".dp-months").innerHTML = monthsShown().map(function (m) {
                var days = daysOfMonth(m);
                var html = '<div class="dp-month"><button type="button" class="dp-mtitle" data-month="' + iso(m) + '">' + MONTHS[m.getMonth()] + ' ' + m.getFullYear() +
                    ' <small>' + days.filter(function (d) { return sel[iso(d)]; }).length + ' j</small></button>' +
                    '<div class="dp-grid"><span class="dp-wk-h">S.</span>' + DOW.map(function (d) { return '<span class="dp-h">' + d + '</span>'; }).join("");
                var cells = [];
                for (var i = 0; i < dow(days[0]); i++) cells.push(null);
                days.forEach(function (d) { cells.push(d); });
                while (cells.length % 7) cells.push(null);
                for (var r = 0; r < cells.length; r += 7) {
                    var row = cells.slice(r, r + 7);
                    var firstDay = row.filter(Boolean)[0];
                    html += '<button type="button" class="dp-wk" data-week="' + iso(firstDay) + '" title="Toute la semaine">' + isoWeek(firstDay) + '</button>';
                    row.forEach(function (d) {
                        if (!d) { html += '<span class="dp-e"></span>'; return; }
                        var k = iso(d), past = d < today;
                        html += '<button type="button" class="dp-d' + (sel[k] ? " on" : "") + (dow(d) > 4 ? " we" : "") + (past ? " past" : "") +
                            (k === iso(today) ? " today" : "") + '" data-day="' + k + '"' + (past ? " disabled" : "") + '>' + d.getDate() + '</button>';
                    });
                }
                return html + '</div></div>';
            }).join("");
            var keys = Object.keys(sel).sort();
            var summary = root.querySelector(".dp-summary");
            if (!keys.length) {
                summary.innerHTML = '<i class="bi bi-calendar-plus"></i> Aucun jour choisi — cliquez des jours ou utilisez les raccourcis.';
            } else {
                var weeks = {}, months = {};
                keys.forEach(function (k) { var d = parse(k); weeks[d.getFullYear() + "-" + isoWeek(d)] = 1; months[k.slice(0, 7)] = 1; });
                summary.innerHTML = '<i class="bi bi-calendar-check"></i> <b>' + keys.length + ' jour(s)</b> sur ' + Object.keys(weeks).length + ' semaine(s) et ' +
                    Object.keys(months).length + ' mois — du ' + parse(keys[0]).toLocaleDateString("fr-FR") + ' au ' + parse(keys[keys.length - 1]).toLocaleDateString("fr-FR");
            }
            if (opts.onChange) opts.onChange(keys);
        }

        root.addEventListener("click", function (e) {
            var b = e.target.closest("button");
            if (!b || !root.contains(b)) return;
            if (b.hasAttribute("data-nav")) {
                start = new Date(start.getFullYear(), start.getMonth() + Number(b.getAttribute("data-nav")), 1);
                render(); return;
            }
            if (b.hasAttribute("data-day")) {
                var k = b.getAttribute("data-day"), d = parse(k);
                if (e.shiftKey && last) {
                    var a = parse(last), on = !!sel[last], lo = a < d ? a : d, hi = a < d ? d : a, range = [];
                    for (var x = new Date(lo); x <= hi; x.setDate(x.getDate() + 1)) if (x >= today) range.push(new Date(x));
                    setMany(range, on);
                } else {
                    if (sel[k]) delete sel[k]; else sel[k] = true;
                    last = k;
                }
                render(); return;
            }
            if (b.hasAttribute("data-week")) {
                var w0 = parse(b.getAttribute("data-week")); w0.setDate(w0.getDate() - dow(w0));
                var week = [];
                for (var i = 0; i < 7; i++) { var wd = new Date(w0); wd.setDate(w0.getDate() + i); if (wd >= today) week.push(wd); }
                setMany(week, !allSelected(week)); render(); return;
            }
            if (b.hasAttribute("data-month")) {
                var md = daysOfMonth(parse(b.getAttribute("data-month"))).filter(function (d) { return d >= today; });
                setMany(md, !allSelected(md)); render(); return;
            }
            if (b.hasAttribute("data-dow")) {
                var n = Number(b.getAttribute("data-dow"));
                var ds = targetDays().filter(function (d) { return dow(d) === n; });
                setMany(ds, !allSelected(ds)); render(); return;
            }
            var q = b.getAttribute("data-q");
            if (q === "clear") { sel = {}; last = null; render(); return; }
            if (q) {
                var t = targetDays().filter(function (d) { return q === "all" || (q === "week" ? dow(d) < 5 : dow(d) > 4); });
                setMany(t, !allSelected(t)); render();
            }
        });
        root.querySelector(".dp-span-sel").addEventListener("change", function () { span = Number(this.value); });
        render();

        return {
            days: function () { return Object.keys(sel).sort(); },
            count: function () { return Object.keys(sel).length; },
            clear: function () { sel = {}; render(); }
        };
    }

    return { mount: mount };
})();
