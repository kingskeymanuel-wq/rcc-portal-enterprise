"use strict";

/**
 * Lecture d'une journée de pointage — même logique que ShiftTimeline.java (serveur), pour que
 * les frises horaires (Suivi de shift : Team Leader, RH, Superviseur ; Ma Performance) et le
 * minuteur affichent exactement la même chose.
 *
 * - LOGOUT (déconnexion sans « Fin de shift ») ouvre une période « offline » ;
 * - LOGIN après LOGOUT le même jour la referme et restaure l'état d'avant (continuité) ;
 * - un LOGIN sans LOGOUT (session expirée, onglet fermé) ne remet rien à zéro.
 */
window.RccShiftTimeline = (function () {

    var STATE_AFTER = {
        PAUSE_START: "ON_PAUSE", LUNCH_START: "ON_LUNCH", TRAINING_START: "ON_TRAINING", MEETING_START: "ON_MEETING",
        PAUSE_END: "WORKING", LUNCH_END: "WORKING", TRAINING_END: "WORKING", MEETING_END: "WORKING",
        SHIFT_END: "SHIFT_ENDED"
    };

    /** Type de segment affiché : work (vert), pause (rouge), offline (gris hachuré). */
    function segmentType(state) {
        if (state === "ON_PAUSE" || state === "ON_LUNCH") return "pause";
        if (state === "DISCONNECTED") return "offline";
        return "work"; // WORKING, ON_TRAINING, ON_MEETING
    }

    /**
     * @param events  événements bruts {eventType, occurredAt}
     * @param openEnd Date de fin pour un segment encore ouvert (null = laissé ouvert, to=null)
     * @returns {state, since, segments:[{type,state,from:Date,to:Date|null}], absences:[{from:Date,to:Date|null}]}
     */
    function analyze(events, openEnd) {
        var sorted = (events || []).slice().sort(function (a, b) { return new Date(a.occurredAt) - new Date(b.occurredAt); });
        var state = "NOT_STARTED", since = null, segStart = null;
        var before = null, sinceBefore = null;
        var segments = [], absences = [];

        function close(at) {
            if (segStart && state !== "NOT_STARTED" && state !== "SHIFT_ENDED" && at > segStart) {
                segments.push({ type: segmentType(state), state: state, from: segStart, to: at });
            }
        }
        function closeAbsence(at) {
            var last = absences[absences.length - 1];
            if (last && !last.to) last.to = at;
        }

        sorted.forEach(function (e) {
            var at = new Date(e.occurredAt);
            var type = e.eventType;
            if (type === "LOGIN") {
                if (state === "DISCONNECTED") {
                    close(at); closeAbsence(at);
                    state = before; since = sinceBefore; segStart = at;
                } else if (state === "NOT_STARTED" || state === "SHIFT_ENDED") {
                    state = "WORKING"; since = at; segStart = at;
                }
            } else if (type === "LOGOUT") {
                if (state !== "NOT_STARTED" && state !== "SHIFT_ENDED" && state !== "DISCONNECTED") {
                    close(at);
                    before = state; sinceBefore = since;
                    state = "DISCONNECTED"; since = at; segStart = at;
                    absences.push({ from: at, to: null });
                }
            } else if (STATE_AFTER[type]) {
                if (state === "DISCONNECTED") closeAbsence(at);
                close(at);
                state = STATE_AFTER[type]; since = at;
                segStart = state === "SHIFT_ENDED" ? null : at;
            }
        });

        if (segStart && state !== "NOT_STARTED" && state !== "SHIFT_ENDED") {
            var end = openEnd || null;
            segments.push({ type: segmentType(state), state: state, from: segStart, to: end && end > segStart ? end : null });
        }
        return { state: state, since: since, segments: segments, absences: absences };
    }

    function minutesBetween(from, to) {
        return Math.max(0, Math.round(((to || new Date()) - from) / 60000));
    }

    function hhmm(date) {
        return String(date.getHours()).padStart(2, "0") + ":" + String(date.getMinutes()).padStart(2, "0");
    }

    function formatDuration(min) {
        if (min < 60) return min + " min";
        return Math.floor(min / 60) + "h" + String(min % 60).padStart(2, "0");
    }

    /** Texte court d'une absence : « 10:02 → 11:15 (1h13) » ou « depuis 10:02 (45 min) ». */
    function describeAbsence(a) {
        var min = minutesBetween(a.from, a.to);
        return a.to
            ? hhmm(a.from) + " → " + hhmm(a.to) + " (" + formatDuration(min) + ")"
            : "depuis " + hhmm(a.from) + " (" + formatDuration(min) + ")";
    }

    function totalAbsenceMinutes(absences) {
        return (absences || []).reduce(function (sum, a) { return sum + minutesBetween(a.from, a.to); }, 0);
    }

    return {
        analyze: analyze,
        describeAbsence: describeAbsence,
        totalAbsenceMinutes: totalAbsenceMinutes,
        formatDuration: formatDuration,
        hhmm: hhmm
    };
})();
