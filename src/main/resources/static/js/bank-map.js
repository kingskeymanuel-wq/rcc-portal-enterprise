"use strict";

/* ==========================================================
   Carte des banques — onglet de la Base de connaissance.
   Ouverte depuis knowledge.js (openCountryModal → window.BankMap.open).

   Choix technique : Leaflet + tuiles OpenStreetMap. Carte géographique
   RÉELLE (zoom/pan/marqueurs cliquables, comme Google Maps dans l'usage)
   mais sans dépendance à Google Maps ni clé API — conformément à la
   demande ("on ne s'appuie pas sur Google Maps, on va juste choisir
   l'affichage"). La recherche d'adresse pour positionner une nouvelle
   agence utilise Nominatim (moteur de géocodage libre d'OpenStreetMap,
   gratuit, sans clé), jamais l'API Google Places/Geocoding.
========================================================== */

window.BankMap = (function () {

    var $ = function (id) { return document.getElementById(id); };
    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;
    var escapeHtml = RccApi.escapeHtml;

    var map = null;
    var markers = []; // { marker, branch }
    var allBranches = [];
    var currentCountryCode = null;
    var currentCity = null; // null = toutes les villes du pays
    var canEdit = false;

    var formModal = null;
    var formMap = null;
    var formMarker = null;

    // Icône par défaut Leaflet — les chemins relatifs par défaut ne fonctionnent pas
    // hors bundler, on pointe explicitement vers les images du même CDN que leaflet.js.
    var defaultIcon = null;
    function icon() {
        if (!defaultIcon) {
            defaultIcon = L.icon({
                iconUrl: "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/images/marker-icon.png",
                iconRetinaUrl: "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/images/marker-icon-2x.png",
                shadowUrl: "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/images/marker-shadow.png",
                iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41]
            });
        }
        return defaultIcon;
    }

    function ensureMap() {
        if (map) {
            // La modale pays est display:none avant ouverture — Leaflet a besoin d'un
            // recalcul de taille une fois le conteneur réellement visible.
            setTimeout(function () { map.invalidateSize(); }, 200);
            return map;
        }
        map = L.map("bankMapLeaflet", { scrollWheelZoom: false });
        L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
            attribution: "&copy; contributeurs OpenStreetMap",
            maxZoom: 19
        }).addTo(map);
        return map;
    }

    function clearMarkers() {
        markers.forEach(function (m) { map.removeLayer(m.marker); });
        markers = [];
    }

    function branchPopupHtml(b) {
        var lines = [];
        lines.push('<strong>' + escapeHtml(b.name) + '</strong>');
        if (b.branchType) lines.push('<span class="badge text-bg-secondary">' + escapeHtml(b.branchType) + '</span>');
        if (b.address) lines.push('<div class="small mt-1"><i class="bi bi-geo-alt"></i> ' + escapeHtml(b.address) + '</div>');
        if (b.phone) lines.push('<div class="small"><i class="bi bi-telephone"></i> ' + escapeHtml(b.phone) + '</div>');
        if (b.openingHours) lines.push('<div class="small"><i class="bi bi-clock"></i> ' + escapeHtml(b.openingHours) + '</div>');
        if (b.managerName) lines.push('<div class="small"><i class="bi bi-person"></i> ' + escapeHtml(b.managerName) + '</div>');
        return lines.join("");
    }

    function renderCityChips(cities) {
        var container = $("bankMapCityChips");
        var allChip = '<span class="badge rounded-pill bg-secondary bank-city-chip' +
            (currentCity === null ? " active" : "") + '" data-city="">Toutes les villes (' + allBranches.length + ')</span>';
        var chips = cities.map(function (c) {
            return '<span class="badge rounded-pill bg-secondary bank-city-chip' +
                (currentCity === c.city ? " active" : "") + '" data-city="' + escapeHtml(c.city) + '">' +
                escapeHtml(c.city) + ' (' + c.branchCount + ')</span>';
        }).join("");
        container.innerHTML = allChip + chips;

        Array.prototype.forEach.call(container.querySelectorAll(".bank-city-chip"), function (chip) {
            chip.addEventListener("click", function () {
                currentCity = chip.getAttribute("data-city") || null;
                render();
            });
        });
    }

    function renderBranchList(branches) {
        var container = $("bankMapBranchList");
        if (!branches.length) {
            container.innerHTML = "";
            return;
        }
        container.innerHTML = branches.map(function (b, idx) {
            return '<div class="col-md-6">' +
                '<div class="card card-body bank-branch-card p-2" data-idx="' + idx + '">' +
                '<div class="d-flex justify-content-between align-items-start">' +
                '<div>' +
                '<div class="fw-semibold">' + escapeHtml(b.name) + '</div>' +
                '<div class="small text-muted">' + escapeHtml(b.city) + (b.address ? " — " + escapeHtml(b.address) : "") + '</div>' +
                (b.phone ? '<div class="small"><i class="bi bi-telephone"></i> ' + escapeHtml(b.phone) + '</div>' : "") +
                (b.openingHours ? '<div class="small text-muted"><i class="bi bi-clock"></i> ' + escapeHtml(b.openingHours) + '</div>' : "") +
                '</div>' +
                (canEdit ? '<button class="btn btn-sm btn-outline-secondary bank-branch-edit-btn" data-idx="' + idx + '"><i class="bi bi-pencil"></i></button>' : "") +
                '</div></div></div>';
        }).join("");

        Array.prototype.forEach.call(container.querySelectorAll(".bank-branch-card"), function (card) {
            card.addEventListener("click", function (evt) {
                if (evt.target.closest(".bank-branch-edit-btn")) return;
                var b = branches[Number(card.getAttribute("data-idx"))];
                focusBranch(b);
            });
        });
        Array.prototype.forEach.call(container.querySelectorAll(".bank-branch-edit-btn"), function (btn) {
            btn.addEventListener("click", function () {
                openForm(branches[Number(btn.getAttribute("data-idx"))]);
            });
        });
    }

    function focusBranch(b) {
        if (b.latitude == null || b.longitude == null) return;
        map.setView([b.latitude, b.longitude], 15);
        var found = markers.filter(function (m) { return m.branch === b; })[0];
        if (found) found.marker.openPopup();
    }

    function render() {
        var branches = currentCity ? allBranches.filter(function (b) { return b.city === currentCity; }) : allBranches;

        clearMarkers();
        var withCoords = [];
        branches.forEach(function (b) {
            if (b.latitude == null || b.longitude == null) return;
            var marker = L.marker([b.latitude, b.longitude], { icon: icon() }).addTo(map);
            marker.bindPopup(branchPopupHtml(b));
            markers.push({ marker: marker, branch: b });
            withCoords.push([b.latitude, b.longitude]);
        });

        if (withCoords.length === 1) {
            map.setView(withCoords[0], 14);
        } else if (withCoords.length > 1) {
            map.fitBounds(withCoords, { padding: [30, 30] });
        } else {
            map.setView([6.0, 2.0], 4); // vue Afrique de l'Ouest par défaut si aucune coordonnée
        }

        renderBranchList(branches);
        $("bankMapEmptyState").style.display = allBranches.length ? "none" : "";
    }

    function open(countryCode, editAllowed) {
        currentCountryCode = countryCode;
        currentCity = null;
        canEdit = !!editAllowed;
        $("bankMapAddBtn").style.display = canEdit ? "" : "none";

        ensureMap();

        Promise.all([
            getJson("/api/bank-branches?country=" + encodeURIComponent(countryCode)),
            getJson("/api/bank-branches/cities?country=" + encodeURIComponent(countryCode))
        ]).then(function (results) {
            allBranches = results[0];
            renderCityChips(results[1]);
            render();
        }).catch(function () {
            allBranches = [];
            $("bankMapCityChips").innerHTML = "";
            $("bankMapBranchList").innerHTML = "";
            $("bankMapEmptyState").style.display = "";
        });
    }

    /* ==========================================================
       Formulaire admin — ajout/modification (QA/ADMIN uniquement)
    ========================================================== */

    function ensureFormModal() {
        if (formModal) return;
        formModal = new bootstrap.Modal($("bankBranchFormModal"));

        $("bankBranchFormModal").addEventListener("shown.bs.modal", function () {
            if (!formMap) {
                formMap = L.map("bankBranchFormMap").setView([6.0, 2.0], 4);
                L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
                    attribution: "&copy; contributeurs OpenStreetMap", maxZoom: 19
                }).addTo(formMap);
                formMap.on("click", function (e) {
                    setFormLatLon(e.latlng.lat, e.latlng.lng);
                });
            }
            setTimeout(function () { formMap.invalidateSize(); }, 200);
        });

        $("bankBranchFormGeocodeBtn").addEventListener("click", geocodeAddress);
        $("bankBranchFormSaveBtn").addEventListener("click", saveForm);
        $("bankBranchFormDeleteBtn").addEventListener("click", deleteForm);
        $("bankMapAddBtn").addEventListener("click", function () { openForm(null); });
    }

    function setFormLatLon(lat, lon) {
        $("bankBranchFormLat").value = lat;
        $("bankBranchFormLon").value = lon;
        if (formMarker) {
            formMarker.setLatLng([lat, lon]);
        } else {
            formMarker = L.marker([lat, lon], { icon: icon(), draggable: true }).addTo(formMap);
            formMarker.on("dragend", function () {
                var pos = formMarker.getLatLng();
                $("bankBranchFormLat").value = pos.lat;
                $("bankBranchFormLon").value = pos.lng;
            });
        }
        formMap.setView([lat, lon], 15);
    }

    /** Recherche d'adresse via Nominatim (OpenStreetMap) — gratuit, sans clé, pas Google.
     *  Respecte l'usage raisonnable de l'API publique (un appel par clic du bouton "Localiser"). */
    function geocodeAddress() {
        var query = $("bankBranchFormAddress").value.trim();
        var resultsBox = $("bankBranchFormGeocodeResults");
        if (!query) { resultsBox.innerHTML = ""; return; }

        var city = $("bankBranchFormCity").value.trim();
        var fullQuery = city ? query + ", " + city : query;

        resultsBox.innerHTML = '<div class="list-group-item small text-muted">Recherche…</div>';

        fetch("https://nominatim.openstreetmap.org/search?format=json&limit=5&q=" + encodeURIComponent(fullQuery))
            .then(function (res) { return res.json(); })
            .then(function (results) {
                if (!results.length) {
                    resultsBox.innerHTML = '<div class="list-group-item small text-muted">Aucun résultat — ajustez le texte ou placez le point directement sur la carte.</div>';
                    return;
                }
                resultsBox.innerHTML = results.map(function (r, idx) {
                    return '<button type="button" class="list-group-item list-group-item-action small geocode-result-btn" data-idx="' + idx + '">' +
                        escapeHtml(r.display_name) + '</button>';
                }).join("");
                Array.prototype.forEach.call(resultsBox.querySelectorAll(".geocode-result-btn"), function (btn) {
                    btn.addEventListener("click", function () {
                        var r = results[Number(btn.getAttribute("data-idx"))];
                        setFormLatLon(parseFloat(r.lat), parseFloat(r.lon));
                        resultsBox.innerHTML = "";
                    });
                });
            })
            .catch(function () {
                resultsBox.innerHTML = '<div class="list-group-item small text-danger">Recherche indisponible pour le moment — placez le point directement sur la carte.</div>';
            });
    }

    function openForm(branch) {
        ensureFormModal();
        $("bankBranchFormError").textContent = "";
        $("bankBranchFormGeocodeResults").innerHTML = "";
        $("bankBranchFormTitle").textContent = branch ? "Modifier l'agence" : "Ajouter une agence";
        $("bankBranchFormId").value = branch ? branch.id : "";
        $("bankBranchFormName").value = branch ? branch.name : "";
        $("bankBranchFormType").value = branch ? (branch.branchType || "Agence") : "Agence";
        $("bankBranchFormCity").value = branch ? branch.city : (currentCity || "");
        $("bankBranchFormPhone").value = branch ? (branch.phone || "") : "";
        $("bankBranchFormAddress").value = branch ? (branch.address || "") : "";
        $("bankBranchFormLat").value = branch && branch.latitude != null ? branch.latitude : "";
        $("bankBranchFormLon").value = branch && branch.longitude != null ? branch.longitude : "";
        $("bankBranchFormHours").value = branch ? (branch.openingHours || "") : "";
        $("bankBranchFormManager").value = branch ? (branch.managerName || "") : "";
        $("bankBranchFormDeleteBtn").style.display = branch ? "" : "none";

        formModal.show();

        setTimeout(function () {
            if (!formMap) return;
            if (formMarker) { formMap.removeLayer(formMarker); formMarker = null; }
            if (branch && branch.latitude != null && branch.longitude != null) {
                setFormLatLon(branch.latitude, branch.longitude);
            } else {
                formMap.setView([6.0, 2.0], 4);
            }
        }, 250);
    }

    function saveForm() {
        var id = $("bankBranchFormId").value;
        var lat = $("bankBranchFormLat").value;
        var lon = $("bankBranchFormLon").value;
        var name = $("bankBranchFormName").value.trim();
        var city = $("bankBranchFormCity").value.trim();

        if (!name || !city) {
            $("bankBranchFormError").textContent = "Le nom et la ville sont obligatoires.";
            return;
        }

        var payload = {
            countryCode: currentCountryCode,
            city: city,
            name: name,
            address: $("bankBranchFormAddress").value.trim() || null,
            latitude: lat !== "" ? parseFloat(lat) : null,
            longitude: lon !== "" ? parseFloat(lon) : null,
            phone: $("bankBranchFormPhone").value.trim() || null,
            openingHours: $("bankBranchFormHours").value.trim() || null,
            managerName: $("bankBranchFormManager").value.trim() || null,
            branchType: $("bankBranchFormType").value,
            active: true
        };

        var request = id ? sendJson("/api/bank-branches/" + id, "PUT", payload)
                          : sendJson("/api/bank-branches", "POST", payload);

        request.then(function () {
            formModal.hide();
            open(currentCountryCode, canEdit); // recharge la liste/carte avec les données à jour
        }).catch(function (e) {
            $("bankBranchFormError").textContent = "Erreur : " + e.message;
        });
    }

    function deleteForm() {
        var id = $("bankBranchFormId").value;
        if (!id) return;
        if (!window.confirm("Supprimer définitivement cette agence ?")) return;

        sendJson("/api/bank-branches/" + id, "DELETE").then(function () {
            formModal.hide();
            open(currentCountryCode, canEdit);
        }).catch(function (e) {
            $("bankBranchFormError").textContent = "Erreur : " + e.message;
        });
    }

    return { open: open };

})();
