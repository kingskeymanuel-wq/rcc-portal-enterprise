"use strict";

/**
 * Heure de Côte d'Ivoire sur tous les postes, quel que soit le fuseau réglé sur l'ordinateur.
 *
 * Le navigateur calcule et affiche les heures dans le fuseau du poste : un PC resté à l'heure de Paris décalait les
 * pointages, minuteurs de shift, plannings et dates affichées. Ce script, chargé en tout premier sur chaque page,
 * fait travailler toutes les dates du portail à l'heure ivoirienne (GMT, UTC+0 toute l'année, sans heure d'été) :
 *   - lectures et écritures d'heure (getHours, setHours…) et décalage horaire (getTimezoneOffset = 0) ;
 *   - dates sans fuseau reçues du serveur (« 2026-10-06T08:30:00 ») lues comme heure ivoirienne ;
 *   - new Date(année, mois, jour, heure…) construite à l'heure ivoirienne ;
 *   - affichages (toLocaleString, Intl.DateTimeFormat) dans le fuseau Africa/Abidjan.
 * L'instant absolu (getTime, Date.now) reste celui de l'horloge du poste : seule la lecture en heures change.
 */
(function () {
    if (window.__rccTimeZone) return;
    var TZ = "Africa/Abidjan";
    window.__rccTimeZone = TZ;

    var NativeDate = Date;
    var proto = NativeDate.prototype;

    // ── Lectures / écritures : l'heure ivoirienne est l'heure UTC ──────────────────────────────
    [["FullYear"], ["Month"], ["Date"], ["Day"], ["Hours"], ["Minutes"], ["Seconds"], ["Milliseconds"]].forEach(function (p) {
        var name = p[0];
        proto["get" + name] = proto["getUTC" + name];
        if (name !== "Day") proto["set" + name] = proto["setUTC" + name];
    });
    proto.getTimezoneOffset = function () { return 0; };

    // ── Affichages : toujours dans le fuseau ivoirien ─────────────────────────────────────────
    function withZone(opts) {
        var o = {};
        if (opts) for (var k in opts) if (Object.prototype.hasOwnProperty.call(opts, k)) o[k] = opts[k];
        if (!o.timeZone) o.timeZone = TZ;
        return o;
    }
    ["toLocaleString", "toLocaleDateString", "toLocaleTimeString"].forEach(function (m) {
        var original = proto[m];
        proto[m] = function (locales, opts) { return original.call(this, locales, withZone(opts)); };
    });
    var utcString = proto.toUTCString;
    proto.toString = function () { return isNaN(this.getTime()) ? "Invalid Date" : utcString.call(this) + " (heure de Côte d'Ivoire)"; };
    proto.toDateString = function () { return isNaN(this.getTime()) ? "Invalid Date" : utcString.call(this).slice(0, 16); };
    proto.toTimeString = function () { return isNaN(this.getTime()) ? "Invalid Date" : utcString.call(this).slice(17, 25) + " GMT+0000 (heure de Côte d'Ivoire)"; };

    if (window.Intl && Intl.DateTimeFormat) {
        var NativeDTF = Intl.DateTimeFormat;
        var DTF = function (locales, opts) { return new NativeDTF(locales, withZone(opts)); };
        DTF.prototype = NativeDTF.prototype;
        DTF.supportedLocalesOf = NativeDTF.supportedLocalesOf;
        Intl.DateTimeFormat = DTF;
    }

    // ── Construction : textes sans fuseau et composants = heure ivoirienne ─────────────────────
    var LOCAL_ISO = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?)$/;
    function asIvorian(v) {
        if (typeof v !== "string") return v;
        var m = LOCAL_ISO.exec(v.trim());
        if (!m) return v;
        var time = m[2].replace(/(\.\d{3})\d+$/, "$1"); // fractions de seconde Java (jusqu'à 9 chiffres)
        return m[1] + "T" + time + "Z";
    }

    function RccDate(y, mo, d, h, mi, s, ms) {
        if (!(this instanceof RccDate)) return new NativeDate().toString(); // Date() appelée sans new
        var n = arguments.length;
        if (n === 0) return new NativeDate();
        if (n === 1) return new NativeDate(y instanceof NativeDate ? y.getTime() : asIvorian(y));
        return new NativeDate(NativeDate.UTC.apply(null, arguments));
    }
    RccDate.prototype = proto;           // instanceof Date reste vrai pour toutes les dates, natives comprises
    proto.constructor = RccDate;
    RccDate.now = NativeDate.now;
    RccDate.UTC = NativeDate.UTC;
    RccDate.parse = function (s) { return NativeDate.parse(asIvorian(s)); };
    try { Object.defineProperty(RccDate, "name", { value: "Date" }); } catch (e) { /* propriété figée */ }
    window.Date = RccDate;
})();
