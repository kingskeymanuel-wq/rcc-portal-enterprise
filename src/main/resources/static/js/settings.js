"use strict";

/**
 * Paramètres : profil (téléphone, anniversaire, bio), photo ou AVATAR (dessiné ici puis envoyé
 * comme une photo — visible partout dans le portail), apparence (langue, thème, accent, police,
 * densité), accessibilité (taille du texte, animations, contraste), notifications (son, alertes
 * du navigateur, fréquence) et compte (mot de passe AD, ce poste, déconnexion).
 * Les préférences d'affichage sont appliquées tout de suite par RccPreferences (preferences.js).
 */
(function () {

    var $ = function (id) { return document.getElementById(id); };
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var esc = RccApi.escapeHtml;
    var P = window.RccPreferences;

    var profile = {};
    var session = null;

    function toast(message, error) {
        var t = $("stToast");
        t.textContent = message;
        t.classList.toggle("error", !!error);
        t.classList.add("show");
        clearTimeout(toast.timer);
        toast.timer = setTimeout(function () { t.classList.remove("show"); }, 2800);
    }

    // ── Navigation entre sections ────────────────────────────────────────────
    function showSection(name) {
        Array.prototype.forEach.call(document.querySelectorAll("#stNav [data-sec]"), function (b) { b.classList.toggle("active", b.getAttribute("data-sec") === name); });
        Array.prototype.forEach.call(document.querySelectorAll(".st-sec"), function (s) { s.classList.toggle("active", s.getAttribute("data-sec") === name); });
        try { history.replaceState(null, "", "#" + name); } catch (e) { /* ignore */ }
    }

    function wireNav() {
        Array.prototype.forEach.call(document.querySelectorAll("#stNav [data-sec]"), function (b) {
            b.addEventListener("click", function () { showSection(b.getAttribute("data-sec")); });
        });
        $("heroAvatarBtn").addEventListener("click", function () { showSection("photo"); });
        var hash = (location.hash || "").replace("#", "");
        if (hash && document.querySelector('.st-sec[data-sec="' + hash + '"]')) showSection(hash);
    }

    // ── Profil ───────────────────────────────────────────────────────────────
    function completeness() {
        var items = [profile.photoUrl, $("phoneInput").value.trim(), $("birthdateInput").value, $("bioInput").value.trim()];
        var pct = Math.round(items.filter(Boolean).length / items.length * 100);
        $("completePct").textContent = pct + "%";
        $("completeRing").style.strokeDashoffset = String(314.16 * (1 - pct / 100));
    }

    function renderIdentity() {
        var u = (session && session.user) || {};
        var name = profile.name || u.name || u.username || "—";
        $("profileName").textContent = name;
        $("profileUsername").textContent = profile.matricule || u.username || "";
        var ROLE = { AGENT: "Conseiller", TEAM_LEADER: "Team Leader", SUPERVISOR: "Superviseur", QA: "Quality Assurance", QA_SUPERVISOR: "Superviseur QA",
            RH: "Ressources humaines", ADMIN: "Administrateur", FORMATEUR: "Formateur", EXCELLIAM: "Excelliam" };
        var role = session ? (ROLE[session.profile] || session.profile) : "";
        var team = (u.activity || u.service || "").replace(/_/g, " ");
        $("heroRole").textContent = role;
        $("heroTeam").textContent = team;
        var info = [
            ["bi-person-badge", "Identifiant", profile.matricule || u.username],
            ["bi-envelope", "E-mail", u.email],
            ["bi-briefcase", "Profil", role],
            ["bi-people", "Équipe", team],
            ["bi-geo-alt", "Filiale", u.affiliateBranch || u.affiliate]
        ].filter(function (x) { return x[2]; });
        $("identityGrid").innerHTML = info.map(function (x, i) {
            return '<div class="st-info" style="animation-delay:' + (i * 50) + 'ms"><i class="bi ' + x[0] + '"></i><div class="min-w-0"><small>' + x[1] + '</small><b>' + esc(x[2]) + '</b></div></div>';
        }).join("");
    }

    function loadProfile() {
        return getJson("/api/user-profiles/me").then(function (p) {
            profile = p || {};
            $("phoneInput").value = profile.phone || "";
            $("birthdateInput").value = profile.birthdate || "";
            $("bioInput").value = profile.bio || "";
            $("bioCount").textContent = $("bioInput").value.length;
            if (profile.photoUrl) $("photoPreview").src = profile.photoUrl;
            renderIdentity();
            completeness();
            renderAvatars();
        }).catch(function (e) {
            $("profileError").textContent = "Erreur de chargement du profil : " + e.message;
            $("profileError").style.display = "";
        });
    }

    function wireProfileForm() {
        var form = $("profileForm");
        form.addEventListener("input", function () {
            $("dirtyHint").classList.add("show");
            $("bioCount").textContent = $("bioInput").value.length;
            completeness();
        });
        form.addEventListener("submit", function (evt) {
            evt.preventDefault();
            $("profileSaved").style.display = "none";
            $("profileError").style.display = "none";
            sendJson("/api/user-profiles/me", "PUT", {
                photoUrl: null, // la photo se met à jour via son propre endpoint d'upload
                birthdate: $("birthdateInput").value || null,
                phone: $("phoneInput").value.trim() || null,
                bio: $("bioInput").value.trim() || null
            }).then(function () {
                $("dirtyHint").classList.remove("show");
                toast("Profil enregistré ✓");
            }).catch(function (e) {
                $("profileError").textContent = "Erreur : " + e.message;
                $("profileError").style.display = "";
            });
        });
    }

    // ── Photo ────────────────────────────────────────────────────────────────
    function uploadPhoto(file, label) {
        var status = $("photoUploadStatus");
        status.textContent = "Envoi en cours…";
        status.className = "form-text";
        var formData = new FormData();
        formData.append("file", file);
        return fetch("/api/user-profiles/me/photo", { method: "POST", credentials: "same-origin", body: formData })
            .then(function (res) {
                if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                return res.json();
            })
            .then(function (p) {
                profile.photoUrl = p.photoUrl;
                $("photoPreview").src = p.photoUrl;
                Array.prototype.forEach.call(document.querySelectorAll(".user-avatar, #headerAvatar, .header-avatar img"), function (img) { if (img.tagName === "IMG") img.src = p.photoUrl; });
                status.textContent = "";
                completeness();
                toast(label || "Photo mise à jour ✓");
            })
            .catch(function (e) {
                status.textContent = "Erreur : " + e.message;
                status.className = "form-text text-danger";
                toast("Envoi impossible : " + e.message, true);
            });
    }

    function wirePhotoUpload() {
        var input = $("photoFileInput"), drop = $("photoDrop");
        var take = function (file) {
            if (!file) return;
            if (!/^image\//.test(file.type)) { toast("Choisissez une image (JPG, PNG, WEBP).", true); return; }
            $("photoPreview").src = URL.createObjectURL(file);
            uploadPhoto(file);
        };
        input.addEventListener("change", function () { take(input.files[0]); input.value = ""; });
        ["dragenter", "dragover"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add("drag"); }); });
        ["dragleave", "drop"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove("drag"); }); });
        drop.addEventListener("drop", function (e) { take(e.dataTransfer.files[0]); });
    }

    // ── Avatars (dessinés sur canvas → PNG envoyé comme photo) ────────────────
    var PALETTES = [["#0057B8", "#2f7de1"], ["#00A651", "#7ee2b0"], ["#6a2bd9", "#b28cff"], ["#e8710a", "#ffc46b"], ["#0d7f86", "#5fd0c9"],
        ["#c2185b", "#ff8ab5"], ["#0d1b3e", "#3a5a9a"], ["#b33e4c", "#ff9aa5"]];
    var AVATARS = {
        people: ["👩🏾‍💼", "👨🏿‍💼", "👩🏽‍💼", "👨🏾‍💼", "🧑🏾‍💻", "👩🏿‍💻", "👨🏽‍💻", "👩🏾‍🦱", "👨🏿‍🦲", "🧕🏾", "👩🏿", "🧔🏿"],
        fun: ["🎧", "🦁", "🐘", "🦅", "⭐", "🌍", "🚀", "💎", "🌿", "🔥", "🎯", "☀️"]
    };
    var selectedAvatar = null;

    function initialsOf() {
        var name = profile.name || (session && session.user && session.user.name) || "?";
        var parts = name.replace(/[^\p{L}\s-]/gu, " ").split(/\s+/).filter(Boolean);
        return ((parts[0] || "?")[0] + (parts[1] ? parts[1][0] : "")).toUpperCase();
    }

    function drawAvatar(def, size) {
        var c = document.createElement("canvas");
        c.width = c.height = size;
        var ctx = c.getContext("2d");
        var g = ctx.createLinearGradient(0, 0, size, size);
        g.addColorStop(0, def.pal[0]);
        g.addColorStop(1, def.pal[1]);
        ctx.fillStyle = g;
        ctx.fillRect(0, 0, size, size);
        // Halo décoratif
        ctx.fillStyle = "rgba(255,255,255,.14)";
        ctx.beginPath(); ctx.arc(size * .85, size * .12, size * .38, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = "rgba(255,255,255,.08)";
        ctx.beginPath(); ctx.arc(size * .1, size * .95, size * .3, 0, Math.PI * 2); ctx.fill();
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        if (def.kind === "initials") {
            ctx.fillStyle = "#fff";
            ctx.font = "800 " + Math.round(size * .4) + "px 'Segoe UI', Roboto, Arial, sans-serif";
            ctx.fillText(initialsOf(), size / 2, size / 2 + size * .02);
        } else {
            ctx.font = Math.round(size * .56) + "px 'Segoe UI Emoji','Apple Color Emoji','Noto Color Emoji',sans-serif";
            ctx.fillText(def.emoji, size / 2, size / 2 + size * .04);
        }
        return c;
    }

    function avatarDefs(kind) {
        if (kind === "initials") return PALETTES.map(function (pal) { return { kind: "initials", pal: pal }; });
        return AVATARS[kind].map(function (emoji, i) { return { kind: kind, emoji: emoji, pal: PALETTES[i % PALETTES.length] }; });
    }

    var currentKind = "people";
    function renderAvatars() {
        var defs = avatarDefs(currentKind);
        var grid = $("avatarGrid");
        grid.innerHTML = "";
        defs.forEach(function (def, i) {
            var btn = document.createElement("button");
            btn.type = "button";
            btn.className = "st-avatar";
            btn.style.animationDelay = (i * 30) + "ms";
            btn.title = def.kind === "initials" ? "Initiales " + initialsOf() : def.emoji;
            var img = document.createElement("img");
            img.alt = "";
            img.src = drawAvatar(def, 160).toDataURL("image/png");
            btn.appendChild(img);
            btn.addEventListener("click", function () {
                Array.prototype.forEach.call(grid.querySelectorAll(".st-avatar"), function (x) { x.classList.toggle("active", x === btn); });
                selectedAvatar = def;
                $("photoPreview").src = img.src;
                $("avatarApplyBtn").disabled = false;
                $("avatarHint").textContent = "Aperçu dans le bandeau — cliquez sur « Utiliser cet avatar » pour l'enregistrer.";
            });
            grid.appendChild(btn);
        });
    }

    function wireAvatars() {
        Array.prototype.forEach.call(document.querySelectorAll("#avatarKind [data-kind]"), function (b) {
            b.addEventListener("click", function () {
                currentKind = b.getAttribute("data-kind");
                Array.prototype.forEach.call(document.querySelectorAll("#avatarKind [data-kind]"), function (x) { x.classList.toggle("active", x === b); });
                renderAvatars();
            });
        });
        $("avatarApplyBtn").addEventListener("click", function () {
            if (!selectedAvatar) return;
            var btn = this;
            btn.disabled = true;
            drawAvatar(selectedAvatar, 512).toBlob(function (blob) {
                var file = new File([blob], "avatar.png", { type: "image/png" });
                uploadPhoto(file, "Avatar enregistré ✓").then(function () {
                    $("avatarHint").textContent = "Avatar enregistré : il apparaît dans tout le portail.";
                });
            }, "image/png");
        });
    }

    // ── Apparence ────────────────────────────────────────────────────────────
    function segBind(id, key, onChange) {
        var seg = $(id);
        var sync = function () {
            Array.prototype.forEach.call(seg.querySelectorAll("[data-v]"), function (b) { b.classList.toggle("active", b.getAttribute("data-v") === P.get(key)); });
        };
        Array.prototype.forEach.call(seg.querySelectorAll("[data-v]"), function (b) {
            b.addEventListener("click", function () { P.set(key, b.getAttribute("data-v")); sync(); if (onChange) onChange(); });
        });
        sync();
    }

    function wireAppearance() {
        $(P.getLanguage() === "en" ? "langEn" : "langFr").checked = true;
        $(P.getTheme() === "dark" ? "themeDark" : "themeLight").checked = true;
        document.querySelectorAll('input[name="langChoice"]').forEach(function (r) {
            r.addEventListener("change", function () { P.setLanguage(r.value); toast(r.value === "en" ? "Language: English" : "Langue : français"); });
        });
        document.querySelectorAll('input[name="themeChoice"]').forEach(function (r) {
            r.addEventListener("change", function () { P.setTheme(r.value); });
        });

        var font = $("fontChoice");
        var preview = function () { $("fontPreview").style.fontFamily = P.FONT_STACKS[font.value] || ""; };
        font.value = P.getFont();
        preview();
        font.addEventListener("change", function () { P.setFont(font.value); preview(); });

        var NAMES = { blue: "Bleu Ecobank", green: "Vert", violet: "Violet", orange: "Orange", teal: "Turquoise" };
        var sw = $("accentSwatches");
        sw.innerHTML = Object.keys(P.ACCENTS).map(function (k) {
            return '<button type="button" class="st-swatch" data-accent="' + k + '" title="' + NAMES[k] + '" style="background:' + P.ACCENTS[k][0] + ';color:' + P.ACCENTS[k][0] + '"></button>';
        }).join("");
        var syncAccent = function () {
            Array.prototype.forEach.call(sw.querySelectorAll("[data-accent]"), function (b) { b.classList.toggle("active", b.getAttribute("data-accent") === P.get("accent")); });
        };
        Array.prototype.forEach.call(sw.querySelectorAll("[data-accent]"), function (b) {
            b.addEventListener("click", function () { P.set("accent", b.getAttribute("data-accent")); syncAccent(); });
        });
        syncAccent();
        segBind("densitySeg", "density");
    }

    // ── Accessibilité ────────────────────────────────────────────────────────
    function toggleBind(id, key, onChange) {
        var box = $(id);
        box.checked = P.get(key) === "1";
        box.addEventListener("change", function () { P.set(key, box.checked ? "1" : "0"); if (onChange) onChange(box.checked); });
    }

    function wireAccessibility() {
        var range = $("fontScale");
        range.value = P.get("fontScale");
        $("fontScaleLabel").textContent = range.value + " %";
        range.addEventListener("input", function () { P.set("fontScale", range.value); $("fontScaleLabel").textContent = range.value + " %"; });
        toggleBind("reduceMotion", "reduceMotion");
        toggleBind("highContrast", "contrast");
    }

    // ── Notifications ────────────────────────────────────────────────────────
    function desktopState() {
        var el = $("desktopStatus");
        if (!("Notification" in window)) { el.textContent = "Non disponible sur ce navigateur."; return; }
        var s = Notification.permission;
        el.textContent = s === "granted" ? "Autorisées par le navigateur — une alerte s'affichera même en arrière-plan."
            : s === "denied" ? "Bloquées dans les réglages du navigateur (icône cadenas à gauche de l'adresse) : autorisez-les puis réessayez."
            : "Le navigateur vous demandera l'autorisation à l'activation.";
    }

    function wireNotifications() {
        toggleBind("notifSound", "notifSound", function (on) { if (on) P.playSound(); });
        var desk = $("notifDesktop");
        desk.checked = P.get("notifDesktop") === "1";
        desk.addEventListener("change", function () {
            if (!desk.checked) { P.set("notifDesktop", "0"); return; }
            if (!("Notification" in window)) { desk.checked = false; desktopState(); return; }
            Notification.requestPermission().then(function (perm) {
                desk.checked = perm === "granted";
                P.set("notifDesktop", desk.checked ? "1" : "0");
                desktopState();
                if (desk.checked) P.desktopNotify("Notifications activées", "Vous serez prévenu(e) des nouvelles notifications du portail RCC.");
            });
        });
        desktopState();
        $("testSoundBtn").addEventListener("click", function () { P.playSound(); });
        $("testDesktopBtn").addEventListener("click", function () {
            if (!("Notification" in window) || Notification.permission !== "granted") { toast("Activez d'abord les notifications du navigateur.", true); return; }
            P.desktopNotify("Test — Portail RCC", "Les notifications fonctionnent sur ce poste ✓");
        });
        segBind("pollSeg", "notifPollSeconds");
    }

    // ── Compte ───────────────────────────────────────────────────────────────
    function renderSessionInfo() {
        var ua = navigator.userAgent;
        var browser = /Edg\//.test(ua) ? "Microsoft Edge" : /Chrome\//.test(ua) ? "Google Chrome" : /Firefox\//.test(ua) ? "Mozilla Firefox" : /Safari\//.test(ua) ? "Safari" : "Navigateur";
        var os = /Windows/.test(ua) ? "Windows" : /Mac OS/.test(ua) ? "macOS" : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Linux/.test(ua) ? "Linux" : "";
        var rows = [
            ["Navigateur", browser + (os ? " · " + os : "")],
            ["Écran", window.screen.width + " × " + window.screen.height],
            ["Langue du portail", P.getLanguage() === "en" ? "English" : "Français"],
            ["Page ouverte à", new Date().toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })]
        ];
        $("sessionInfo").innerHTML = rows.map(function (r) { return '<div><span>' + r[0] + '</span><b>' + esc(r[1]) + '</b></div>'; }).join("");
    }

    function wireAccount() {
        renderSessionInfo();
        $("resetPrefsBtn").addEventListener("click", function () {
            if (!confirm("Rétablir la langue, le thème, la police, l'accessibilité et les notifications par défaut sur ce poste ?")) return;
            ["lang", "theme", "font", "fontScale", "reduceMotion", "contrast", "density", "notifSound", "notifDesktop", "notifPollSeconds", "accent"].forEach(function (k) {
                try { localStorage.removeItem("rcc_" + k); } catch (e) { /* ignore */ }
            });
            location.reload();
        });
        $("logoutBtn").addEventListener("click", function () {
            if (!confirm("Voulez-vous vous déconnecter ?")) return;
            this.disabled = true;
            fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" })
                .catch(function () {})
                .finally(function () { window.location.href = "/login"; });
        });
    }

    function wirePhotoLightbox() {
        $("photoPreview").addEventListener("dblclick", function () {
            $("photoLightboxImg").src = this.src;
            new bootstrap.Modal($("photoLightbox")).show();
        });
    }

    function init() {
        wireNav();
        wireProfileForm();
        wirePhotoUpload();
        wireAvatars();
        wireAppearance();
        wireAccessibility();
        wireNotifications();
        wireAccount();
        wirePhotoLightbox();
        var sessionReady = window.RccSession && window.RccSession.init ? window.RccSession.init().catch(function () { return null; }) : Promise.resolve(null);
        sessionReady.then(function (s) { session = s; return loadProfile(); });
    }

    document.addEventListener("DOMContentLoaded", init);
})();
