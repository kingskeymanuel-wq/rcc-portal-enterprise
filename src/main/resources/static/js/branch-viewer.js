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
            '<button type="button" data-v="map"><i class="bi bi-map"></i> Plan</button></div>' +
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
    }

    var current = null;
    function showView(v) {
        Array.prototype.forEach.call(modalEl.querySelectorAll(".ob-tabs button"), function (b) { b.classList.toggle("active", b.getAttribute("data-v") === v); });
        var frame = document.getElementById("obFrame");
        frame.innerHTML = '<div class="ob-loading"><span class="spinner-border"></span><span>Chargement de la ' + (v === "street" ? "visite de rue" : "carte") + '…</span></div>' +
            '<iframe src="' + (v === "street" ? streetViewUrl(current) : osmEmbed(current)) + '" allowfullscreen loading="lazy" referrerpolicy="no-referrer-when-downgrade" ' +
            'onload="this.previousElementSibling && this.previousElementSibling.remove()"></iframe>';
        document.getElementById("obHint").style.display = v === "street" ? "" : "none";
    }

    /** b : { name, short, color, domain, address, phone, city, lat, lng, approx, kind } */
    function openDetails(b) {
        if (b.lat == null || b.lng == null) { alert("Position non renseignée pour « " + b.name + " »."); return; }
        ensureModal();
        current = b;
        var gmaps = "https://www.google.com/maps/search/?api=1&query=" + b.lat + "," + b.lng;
        var pano = "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=" + b.lat + "," + b.lng;
        var route = "https://www.google.com/maps/dir/?api=1&destination=" + b.lat + "," + b.lng;
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
            '<div class="ob-actions"><a class="btn btn-primary" href="' + route + '" target="_blank" rel="noopener"><i class="bi bi-sign-turn-right"></i> Itinéraire</a>' +
            '<a class="btn btn-light" href="' + pano + '" target="_blank" rel="noopener"><i class="bi bi-person-walking"></i> Street View plein écran</a>' +
            '<a class="btn btn-light" href="' + gmaps + '" target="_blank" rel="noopener"><i class="bi bi-box-arrow-up-right"></i> Google Maps</a></div>' +
            '<p class="ob-foot">Si la visite de rue reste grise, aucune prise de vue n\'existe exactement à ce point : ouvrez « Street View plein écran » et avancez depuis la rue la plus proche.</p>';
        showView("street");
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
