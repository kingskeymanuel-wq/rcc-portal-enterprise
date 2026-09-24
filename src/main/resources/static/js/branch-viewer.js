"use strict";

/**
 * Fiche détaillée d'une agence Ecobank (onglet « Agences / Carte », bank-map.js) : coordonnées,
 * VISITE DE RUE Google Street View que l'on parcourt à la souris (glisser pour regarder autour,
 * cliquer sur les flèches pour avancer), plan OpenStreetMap et itinéraire. Aucune clé API :
 * Street View et le plan sont intégrés en iframe. Uniquement Ecobank — aucune autre banque.
 */
window.BranchViewer = (function () {

    function esc(s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; }

    function logoHtml(b, size) {
        var initials = '<span class="ob-logo-fallback" style="background:' + b.color + '">' + esc(b.short) + '</span>';
        if (b.logo) return '<span class="ob-logo" style="width:' + size + 'px;height:' + size + 'px"><img src="' + esc(b.logo) + '" alt="" onerror="this.remove()">' + initials + '</span>';
        if (!b.domain) return '<span class="ob-logo" style="width:' + size + 'px;height:' + size + 'px">' + initials + '</span>';
        return '<span class="ob-logo" style="width:' + size + 'px;height:' + size + 'px">' +
            '<img src="https://www.google.com/s2/favicons?sz=128&domain=' + encodeURIComponent(b.domain) + '" alt="" ' +
            'onerror="this.remove()" loading="lazy">' + initials + '</span>';
    }

    // ── Fiche détaillée (modale) ─────────────────────────────────────────────

    var modal = null, modalEl = null;

    function streetViewUrl(b) {
        return "https://maps.google.com/maps?layer=c&cbll=" + b.lat + "," + b.lng + "&cbp=11,0,0,0,0&output=svembed";
    }
    function osmEmbed(b) {
        var d = 0.0035;
        return "https://www.openstreetmap.org/export/embed.html?bbox=" + (b.lng - d) + "," + (b.lat - d) + "," + (b.lng + d) + "," + (b.lat + d) + "&layer=mapnik&marker=" + b.lat + "," + b.lng;
    }

    function ensureModal() {
        if (modal) return;
        var wrap = document.createElement("div");
        wrap.innerHTML = '<div class="modal fade ob-modal" id="obDetailModal" tabindex="-1"><div class="modal-dialog modal-xl modal-dialog-centered modal-fullscreen-lg-down"><div class="modal-content">' +
            '<div class="ob-head" id="obHead"></div>' +
            '<div class="modal-body p-0"><div class="ob-layout">' +
            '<div class="ob-view"><div class="ob-tabs"><button type="button" class="active" data-v="street"><i class="bi bi-person-walking"></i> Visite de rue</button>' +
            '<button type="button" data-v="map"><i class="bi bi-map"></i> Plan</button>' +
            '<button type="button" data-v="route"><i class="bi bi-sign-turn-right"></i> Itinéraire</button></div>' +
            '<form class="ob-route" id="obRoute" hidden>' +
            '<div class="ob-route-from"><i class="bi bi-record-circle"></i><input id="obRouteFrom" placeholder="Point de départ (adresse, quartier…) ou « Ma position »" autocomplete="off">' +
            '<button type="button" class="ob-route-me" id="obRouteMe" title="Utiliser ma position"><i class="bi bi-crosshair"></i> Ma position</button></div>' +
            '<div class="ob-route-to"><i class="bi bi-geo-alt-fill"></i><span id="obRouteTo"></span></div>' +
            '<div class="ob-route-bar"><div class="ob-route-mode" id="obRouteMode">' +
            '<button type="button" class="active" data-m="d"><i class="bi bi-car-front"></i> Voiture</button>' +
            '<button type="button" data-m="w"><i class="bi bi-person-walking"></i> À pied</button>' +
            '<button type="button" data-m="r"><i class="bi bi-bus-front"></i> Transport</button></div>' +
            '<button type="submit" class="ob-route-go"><i class="bi bi-signpost-split"></i> Calculer</button></div>' +
            '<div class="ob-route-msg" id="obRouteMsg"></div></form>' +
            '<div class="ob-frame" id="obFrame"></div>' +
            '<div class="ob-hint" id="obHint"><i class="bi bi-mouse"></i> Glissez avec la souris pour regarder autour · cliquez sur les flèches au sol pour avancer dans la rue · molette pour zoomer</div></div>' +
            '<aside class="ob-info" id="obInfo"></aside></div></div></div></div></div>';
        document.body.appendChild(wrap.firstChild);
        modalEl = document.getElementById("obDetailModal");
        modal = new bootstrap.Modal(modalEl);
        Array.prototype.forEach.call(modalEl.querySelectorAll(".ob-tabs button"), function (btn) {
            btn.addEventListener("click", function () { showView(btn.getAttribute("data-v")); });
        });
        modalEl.addEventListener("hidden.bs.modal", function () { document.getElementById("obFrame").innerHTML = ""; });
        document.getElementById("obRoute").addEventListener("submit", function (e) { e.preventDefault(); drawRoute(); });
        document.getElementById("obRouteMe").addEventListener("click", useMyPosition);
        Array.prototype.forEach.call(modalEl.querySelectorAll("#obRouteMode [data-m]"), function (b) {
            b.addEventListener("click", function () {
                routeMode = b.getAttribute("data-m");
                Array.prototype.forEach.call(modalEl.querySelectorAll("#obRouteMode [data-m]"), function (x) { x.classList.toggle("active", x === b); });
                if (routeFrom) drawRoute();
            });
        });
    }

    var current = null;
    var routeMode = "d";   // d = voiture, w = à pied, r = transports
    var routeFrom = null;  // texte ou "lat,lng" ; mémorisé pour la session

    /** Itinéraire intégré dans la page (Google Maps en iframe, sans clé ni nouvel onglet). */
    function routeUrl(from, b, mode) {
        return "https://maps.google.com/maps?saddr=" + encodeURIComponent(from) + "&daddr=" + b.lat + "," + b.lng +
            "&dirflg=" + mode + "&hl=fr&output=embed";
    }

    function setRouteMsg(text, isError) {
        var m = document.getElementById("obRouteMsg");
        m.textContent = text || "";
        m.className = "ob-route-msg" + (isError ? " err" : "");
    }

    function useMyPosition() {
        if (!navigator.geolocation) { setRouteMsg("La géolocalisation n'est pas disponible sur ce poste : saisissez une adresse de départ.", true); return; }
        var btn = document.getElementById("obRouteMe");
        btn.disabled = true;
        setRouteMsg("Recherche de votre position…");
        navigator.geolocation.getCurrentPosition(function (pos) {
            btn.disabled = false;
            var here = pos.coords.latitude.toFixed(6) + "," + pos.coords.longitude.toFixed(6);
            document.getElementById("obRouteFrom").value = "Ma position (" + pos.coords.latitude.toFixed(4) + ", " + pos.coords.longitude.toFixed(4) + ")";
            document.getElementById("obRouteFrom").setAttribute("data-coords", here);
            drawRoute();
        }, function () {
            btn.disabled = false;
            setRouteMsg("Position refusée ou introuvable : saisissez une adresse de départ (ex. « Plateau, Abidjan »).", true);
        }, { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 });
    }

    function drawRoute() {
        var input = document.getElementById("obRouteFrom");
        var from = input.getAttribute("data-coords") && input.value.indexOf("Ma position") === 0 ? input.getAttribute("data-coords") : input.value.trim();
        if (!from) { setRouteMsg("Indiquez un point de départ ou cliquez sur « Ma position ».", true); input.focus(); return; }
        if (from.indexOf(",") === -1 && !/abidjan|c[oô]te d.ivoire|bouak|yamoussoukro|san.p[ée]dro/i.test(from) && current.city) from += ", " + current.city;
        routeFrom = from;
        setRouteMsg("");
        document.getElementById("obFrame").innerHTML = '<div class="ob-loading"><span class="spinner-border"></span><span>Calcul de l\'itinéraire…</span></div>' +
            '<iframe src="' + routeUrl(from, current, routeMode) + '" allowfullscreen loading="lazy" referrerpolicy="no-referrer-when-downgrade" ' +
            'onload="this.previousElementSibling && this.previousElementSibling.remove()"></iframe>';
    }

    function showView(v) {
        Array.prototype.forEach.call(modalEl.querySelectorAll(".ob-tabs button"), function (b) { b.classList.toggle("active", b.getAttribute("data-v") === v); });
        var frame = document.getElementById("obFrame");
        var routeForm = document.getElementById("obRoute");
        routeForm.hidden = v !== "route";
        modalEl.querySelector(".ob-view").classList.toggle("ob-view-route", v === "route");
        document.getElementById("obHint").style.display = v === "street" ? "" : "none";
        if (v === "route") {
            document.getElementById("obRouteTo").textContent = current.name + (current.address ? " — " + current.address : "");
            if (routeFrom) { drawRoute(); return; }
            frame.innerHTML = '<div class="ob-route-empty"><i class="bi bi-signpost-2"></i><b>D\'où partez-vous ?</b>' +
                '<span>Saisissez une adresse ou cliquez sur « Ma position », puis « Calculer ». L\'itinéraire s\'affiche ici, sans quitter le portail.</span></div>';
            setTimeout(function () { document.getElementById("obRouteFrom").focus(); }, 50);
            return;
        }
        frame.innerHTML = '<div class="ob-loading"><span class="spinner-border"></span><span>Chargement de la ' + (v === "street" ? "visite de rue" : "carte") + '…</span></div>' +
            '<iframe src="' + (v === "street" ? streetViewUrl(current) : osmEmbed(current)) + '" allowfullscreen loading="lazy" referrerpolicy="no-referrer-when-downgrade" ' +
            'onload="this.previousElementSibling && this.previousElementSibling.remove()"></iframe>';
        document.getElementById("obHint").style.display = v === "street" ? "" : "none";
    }

    var openOnRoute = false;

    /** b : { name, short, color, domain, address, phone, city, lat, lng, approx, kind } */
    function openDetails(b) {
        if (b.lat == null || b.lng == null) { alert("Position non renseignée pour « " + b.name + " »."); return; }
        ensureModal();
        current = b;
        var gmaps = "https://www.google.com/maps/search/?api=1&query=" + b.lat + "," + b.lng;
        var pano = "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=" + b.lat + "," + b.lng;
        document.getElementById("obHead").style.setProperty("--c", b.color || "#0057B8");
        document.getElementById("obHead").innerHTML = logoHtml(b, 54) +
            '<div class="flex-grow-1 min-w-0"><div class="ob-eyebrow">' + esc(b.kind || "Agence Ecobank") + (b.city ? " · " + esc(b.city) : "") + '</div><h5>' + esc(b.name) + '</h5></div>' +
            '<button type="button" class="btn-close btn-close-white" data-bs-dismiss="modal"></button>';
        document.getElementById("obInfo").innerHTML =
            '<div class="ob-row"><i class="bi bi-geo-alt-fill"></i><div><small>Adresse</small><b>' + esc(b.address || "—") + '</b></div></div>' +
            (b.phone ? '<div class="ob-row"><i class="bi bi-telephone-fill"></i><div><small>Téléphone</small><b><a href="tel:' + esc(b.phone.replace(/\s/g, "")) + '">' + esc(b.phone) + '</a></b></div></div>' : "") +
            (b.hours ? '<div class="ob-row"><i class="bi bi-clock-fill"></i><div><small>Horaires</small><b>' + esc(b.hours) + '</b></div></div>' : "") +
            (b.domain ? '<div class="ob-row"><i class="bi bi-globe2"></i><div><small>Site</small><b><a href="https://' + esc(b.domain) + '" target="_blank" rel="noopener">' + esc(b.domain) + '</a></b></div></div>' : "") +
            '<div class="ob-row"><i class="bi bi-crosshair"></i><div><small>Coordonnées</small><b>' + b.lat.toFixed(5) + ", " + b.lng.toFixed(5) + '</b>' +
            (b.approx ? '<em>Position approximative (à l\'échelle de la rue)</em>' : "") + '</div></div>' +
            '<div class="ob-actions"><button type="button" class="btn btn-primary" id="obRouteBtn"><i class="bi bi-sign-turn-right"></i> Itinéraire</button>' +
            '<a class="btn btn-light" href="' + pano + '" target="_blank" rel="noopener"><i class="bi bi-person-walking"></i> Street View plein écran</a>' +
            '<a class="btn btn-light" href="' + gmaps + '" target="_blank" rel="noopener"><i class="bi bi-box-arrow-up-right"></i> Google Maps</a></div>' +
            '<p class="ob-foot">Si la visite de rue reste grise, aucune prise de vue n\'existe exactement à ce point : ouvrez « Street View plein écran » et avancez depuis la rue la plus proche.</p>';
        document.getElementById("obRouteBtn").addEventListener("click", function () { showView("route"); });
        showView(openOnRoute ? "route" : "street");
        openOnRoute = false;
        modal.show();
    }

    /** Fiche d'une agence Ecobank (depuis bank-map.js). */
    function openBranch(branch) {
        openDetails({
            name: "Ecobank — " + branch.name, short: "ECO", color: "#0057B8", domain: "ecobank.com", logo: "/images/ecobank-logo.png", kind: "Agence Ecobank" + (branch.branchType ? " · " + branch.branchType : ""),
            city: branch.city, address: branch.address, phone: branch.phone, hours: branch.openingHours,
            lat: branch.latitude, lng: branch.longitude, approx: branch.branchType === "À compléter"
        });
    }

    return { openDetails: openDetails, openBranch: openBranch };
})();
