"use strict";

/**
 * Autres banques sur la carte « Agences / Carte » (bank-map.js) + fiche détaillée commune
 * (Ecobank et autres banques) : logo, coordonnées, VISITE DE RUE Google Street View que l'on
 * parcourt à la souris (glisser pour regarder autour, cliquer sur les flèches pour avancer),
 * plan OpenStreetMap et itinéraire.
 *
 * Données : sièges / agences principales relevés sur les annuaires publics (BCEAO, annuaires
 * CI, sites des banques) en septembre 2026. Les positions sont approximatives (à l'échelle de
 * la rue) : à affiner si besoin. Aucune clé API : Street View et le plan sont intégrés en iframe.
 */
window.OtherBanks = (function () {

    var BANKS = {
        CI: [
            { id: "sgci", name: "Société Générale Côte d'Ivoire", short: "SG", color: "#e60028", domain: "societegenerale.ci",
              city: "Abidjan", address: "5-7, avenue Joseph Anoma, Plateau — 01 BP 1355 Abidjan 01", phone: "+225 27 20 20 12 34", lat: 5.31975, lng: -4.01735 },
            { id: "nsia", name: "NSIA Banque Côte d'Ivoire", short: "NS", color: "#005ca9", domain: "nsiabanque.ci",
              city: "Abidjan", address: "8-10, avenue Joseph Anoma, Plateau — 01 BP 1274 Abidjan 01", phone: "+225 27 20 20 07 20", lat: 5.32005, lng: -4.01690 },
            { id: "sib", name: "Société Ivoirienne de Banque (SIB)", short: "SIB", color: "#c8102e", domain: "sib.ci",
              city: "Abidjan", address: "34, boulevard de la République, immeuble Alpha 2000, Plateau", phone: "+225 27 20 20 00 00", lat: 5.32360, lng: -4.02030 },
            { id: "bicici", name: "BICICI", short: "BI", color: "#00915a", domain: "bicici.com",
              city: "Abidjan", address: "Avenue Franchet d'Espérey, Plateau", phone: "+225 27 20 24 24 24", lat: 5.31880, lng: -4.01975 },
            { id: "scb", name: "Standard Chartered Bank Côte d'Ivoire", short: "SC", color: "#0473ea", domain: "sc.com",
              city: "Abidjan", address: "23, boulevard de la République, Plateau — 17 BP 1141 Abidjan 17", phone: "+225 27 20 30 32 00", lat: 5.32180, lng: -4.02060 },
            { id: "coris", name: "Coris Bank International CI", short: "CB", color: "#d71920", domain: "corisbank.ci",
              city: "Abidjan", address: "Bd de la République, rue n°23 angle avenue Marchand, Plateau — 01 BP 4690 Abidjan 01", phone: "+225 27 20 20 94 50", lat: 5.32440, lng: -4.02000 },
            { id: "uba", name: "UBA Côte d'Ivoire", short: "UBA", color: "#d42e12", domain: "ubagroup.com",
              city: "Abidjan", address: "Bd Botreau Roussel, rue du Commerce, immeuble Kharrat, Plateau — 17 BP 808 Abidjan 17", phone: null, lat: 5.32220, lng: -4.01780 },
            { id: "baci", name: "Banque Atlantique Côte d'Ivoire", short: "BA", color: "#e87722", domain: "banqueatlantique.net",
              city: "Abidjan", address: "Immeuble Atlantique, avenue Noguès, Plateau", phone: null, lat: 5.32140, lng: -4.01860 },
            { id: "boa", name: "Bank of Africa Côte d'Ivoire", short: "BOA", color: "#008751", domain: "boacoteivoire.com",
              city: "Abidjan", address: "Angle avenue Terrasson de Fougères et rue Gourgas, Plateau — 01 BP 4132 Abidjan 01", phone: null, lat: 5.32570, lng: -4.01720 },
            { id: "orabank", name: "Orabank Côte d'Ivoire", short: "ORA", color: "#f39200", domain: "orabank.net",
              city: "Abidjan", address: "Rue des Banques × boulevard de la République, Plateau", phone: null, lat: 5.32280, lng: -4.01980 },
            { id: "bni", name: "Banque Nationale d'Investissement (BNI)", short: "BNI", color: "#006837", domain: "bni.ci",
              city: "Abidjan", address: "Avenue Marchand, immeuble SCIAM, Plateau", phone: null, lat: 5.32480, lng: -4.01880 },
            { id: "bridge", name: "Bridge Bank Group Côte d'Ivoire", short: "BB", color: "#0d3c6e", domain: "bridgebankgroup.com",
              city: "Abidjan", address: "33, avenue du Général de Gaulle, Plateau — 01 BP 13002 Abidjan 01", phone: null, lat: 5.32080, lng: -4.02220 }
        ]
    };

    function esc(s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; }

    function logoHtml(b, size) {
        var initials = '<span class="ob-logo-fallback" style="background:' + b.color + '">' + esc(b.short) + '</span>';
        if (!b.domain) return '<span class="ob-logo" style="width:' + size + 'px;height:' + size + 'px">' + initials + '</span>';
        return '<span class="ob-logo" style="width:' + size + 'px;height:' + size + 'px">' +
            '<img src="https://www.google.com/s2/favicons?sz=128&domain=' + encodeURIComponent(b.domain) + '" alt="" ' +
            'onerror="this.remove()" loading="lazy">' + initials + '</span>';
    }

    function markerIcon(b) {
        return L.divIcon({
            className: "ob-marker-wrap",
            html: '<span class="ob-marker" style="--c:' + b.color + '"><i>' + esc(b.short) + '</i></span>',
            iconSize: [38, 38], iconAnchor: [19, 38], popupAnchor: [0, -34]
        });
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
            '<div class="flex-grow-1 min-w-0"><div class="ob-eyebrow">' + esc(b.kind || "Autre banque") + (b.city ? " · " + esc(b.city) : "") + '</div><h5>' + esc(b.name) + '</h5></div>' +
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

    // ── Couche « autres banques » sur la carte des agences ─────────────────

    /** Ajoute la couche à une carte Leaflet montée par BankMap. Retourne un contrôleur. */
    function attach(map, root, country) {
        var list = BANKS[(country || "").toUpperCase()] || [];
        var bar = root.querySelector(".ob-bar");
        var listBox = root.querySelector(".ob-list");
        if (!bar) {
            bar = document.createElement("div");
            bar.className = "ob-bar";
            var canvas = root.querySelector(".bank-map-canvas");
            canvas.parentNode.insertBefore(bar, canvas);
            listBox = document.createElement("div");
            listBox.className = "ob-list";
            root.querySelector(".bank-map-list").insertAdjacentElement("afterend", listBox);
        }
        if (root._obLayer) { map.removeLayer(root._obLayer); root._obLayer = null; }
        if (!list.length) { bar.innerHTML = ""; listBox.innerHTML = ""; return; }

        var layer = L.layerGroup();
        var markers = {};
        list.forEach(function (b) {
            var m = L.marker([b.lat, b.lng], { icon: markerIcon(b), title: b.name, riseOnHover: true });
            m.bindPopup('<div class="ob-pop">' + logoHtml(b, 34) + '<div><b>' + esc(b.name) + '</b><small>' + esc(b.address) + '</small>' +
                '<button type="button" class="btn btn-sm btn-primary mt-2" data-ob="' + b.id + '"><i class="bi bi-person-walking"></i> Détails & visite de rue</button></div></div>');
            m.on("popupopen", function (e) {
                var btn = e.popup.getElement().querySelector("[data-ob]");
                if (btn) btn.addEventListener("click", function () { openDetails(withKind(b)); });
            });
            markers[b.id] = m;
            layer.addLayer(m);
        });
        root._obLayer = layer;
        var visible = true;
        try { visible = localStorage.getItem("rcc.map.otherBanks") !== "0"; } catch (e) { /* ignore */ }
        if (visible) layer.addTo(map);

        bar.innerHTML = '<label class="ob-switch"><input type="checkbox"' + (visible ? " checked" : "") + '><span></span> Autres banques <b>' + list.length + '</b></label>' +
            '<div class="ob-chips">' + list.map(function (b) {
                return '<button type="button" data-focus="' + b.id + '" style="--c:' + b.color + '" title="' + esc(b.name) + '">' + logoHtml(b, 20) + '<span>' + esc(b.short) + '</span></button>';
            }).join("") + '</div>';
        bar.querySelector(".ob-switch input").addEventListener("change", function () {
            if (this.checked) layer.addTo(map); else map.removeLayer(layer);
            try { localStorage.setItem("rcc.map.otherBanks", this.checked ? "1" : "0"); } catch (e) { /* ignore */ }
        });
        Array.prototype.forEach.call(bar.querySelectorAll("[data-focus]"), function (btn) {
            btn.addEventListener("click", function () {
                var b = list.filter(function (x) { return x.id === btn.getAttribute("data-focus"); })[0];
                if (!map.hasLayer(layer)) { layer.addTo(map); bar.querySelector(".ob-switch input").checked = true; }
                map.setView([b.lat, b.lng], 17);
                markers[b.id].openPopup();
            });
        });

        listBox.innerHTML = '<div class="ob-list-head"><i class="bi bi-bank2"></i> Autres banques de la place <span>' + list.length + '</span></div>' +
            '<div class="ob-grid">' + list.map(function (b, i) {
                return '<button type="button" class="ob-card" data-open="' + b.id + '" style="--c:' + b.color + ';animation-delay:' + (i * 40) + 'ms">' + logoHtml(b, 40) +
                    '<div class="min-w-0"><b>' + esc(b.name) + '</b><small>' + esc(b.address) + '</small>' +
                    '<span class="ob-card-go"><i class="bi bi-person-walking"></i> Visite de rue</span></div></button>';
            }).join("") + '</div>';
        Array.prototype.forEach.call(listBox.querySelectorAll("[data-open]"), function (btn) {
            btn.addEventListener("click", function () {
                openDetails(withKind(list.filter(function (x) { return x.id === btn.getAttribute("data-open"); })[0]));
            });
        });
    }

    function withKind(b) {
        return Object.assign({ kind: "Autre banque", approx: true }, b);
    }

    /** Fiche d'une agence Ecobank (depuis bank-map.js). */
    function openBranch(branch) {
        openDetails({
            name: "Ecobank — " + branch.name, short: "ECO", color: "#0057B8", domain: "ecobank.com", kind: "Agence Ecobank" + (branch.branchType ? " · " + branch.branchType : ""),
            city: branch.city, address: branch.address, phone: branch.phone, hours: branch.openingHours,
            lat: branch.latitude, lng: branch.longitude, approx: branch.branchType === "À compléter"
        });
    }

    return { attach: attach, openDetails: openDetails, openBranch: openBranch, banks: function (c) { return BANKS[(c || "").toUpperCase()] || []; } };
})();
