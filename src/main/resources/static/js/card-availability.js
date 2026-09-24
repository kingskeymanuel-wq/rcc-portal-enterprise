"use strict";

/**
 * Onglet « Disponibilité des cartes » de la Base de connaissances.
 * Pour la filiale choisie : tableau cartes × villes. QA/ADMIN cochent les cases (disponible
 * ou non) et ajoutent une précision par case ; les agents consultent, filtrent par ville ou
 * cherchent une carte. API : /api/card-availability (CardAvailabilityController).
 */
window.CardAvailability = (function () {

    function escapeHtml(s) {
        var div = document.createElement("div");
        div.textContent = s == null ? "" : String(s);
        return div.innerHTML;
    }

    function request(method, url, body) {
        return fetch(url, {
            method: method, credentials: "same-origin",
            headers: body ? { "Content-Type": "application/json" } : {},
            body: body ? JSON.stringify(body) : undefined
        }).then(function (res) {
            if (res.status === 204) return null;
            return res.text().then(function (t) {
                var data = null;
                try { data = t ? JSON.parse(t) : null; } catch (ignore) {}
                if (!res.ok) {
                    var msg = data && data.error && data.error.message ? data.error.message : ("HTTP " + res.status);
                    return Promise.reject(new Error(msg));
                }
                return data;
            });
        });
    }

    function formatDate(iso) {
        if (!iso) return "";
        var d = new Date(iso);
        return isNaN(d) ? "" : d.toLocaleDateString("fr-FR") + " à " + d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
    }

    function key(cardId, city) { return cardId + "|" + city.toLowerCase(); }

    function mount(root) {
        var state = { country: null, canEdit: false, data: null, cells: {}, extraCities: [], cityFilter: "", search: "" };
        var body = root.querySelector(".card-av-body");
        var dialog = null;

        root.querySelector(".card-av-search").addEventListener("input", function () {
            state.search = this.value.trim().toLowerCase();
            render();
        });
        root.querySelector(".card-av-city-filter").addEventListener("change", function () {
            state.cityFilter = this.value;
            render();
        });
        root.querySelector(".card-av-add-card").addEventListener("click", function () { openCardDialog(null); });
        root.querySelector(".card-av-add-city").addEventListener("click", addCity);
        root.querySelector(".card-av-standard").addEventListener("click", addStandard);

        function load(country, canEdit) {
            state.country = country;
            state.canEdit = !!canEdit;
            state.extraCities = [];
            state.cityFilter = "";
            root.querySelector(".card-av-city-filter").value = "";
            Array.prototype.forEach.call(root.querySelectorAll(".card-av-qa"), function (el) { el.style.display = state.canEdit ? "" : "none"; });
            body.innerHTML = '<div class="text-center text-muted py-4"><span class="spinner-border spinner-border-sm"></span> Chargement…</div>';
            if (window.CardAgencyReport) window.CardAgencyReport.load(country, state.canEdit);
            return request("GET", "/api/card-availability?country=" + encodeURIComponent(country)).then(function (data) {
                state.data = data;
                state.cells = {};
                (data.cells || []).forEach(function (c) { state.cells[key(c.cardId, c.city)] = c; });
                render();
            }).catch(function (e) {
                body.innerHTML = '<div class="alert alert-warning small mb-0">Disponibilités indisponibles pour le moment : ' + escapeHtml(e.message) + "</div>";
            });
        }

        function cities() {
            var all = (state.data.cities || []).slice();
            state.extraCities.forEach(function (c) {
                if (!all.some(function (x) { return x.toLowerCase() === c.toLowerCase(); })) all.push(c);
            });
            return all;
        }

        function render() {
            if (!state.data) return;
            var allCities = cities();
            var cards = (state.data.cards || []).filter(function (c) {
                return !state.search || (c.name + " " + (c.category || "") + " " + (c.details || "")).toLowerCase().indexOf(state.search) >= 0;
            });

            // Filtre ville (liste déroulante)
            var filter = root.querySelector(".card-av-city-filter");
            var current = state.cityFilter;
            filter.innerHTML = '<option value="">Toutes les villes</option>' + allCities.map(function (c) {
                return '<option value="' + escapeHtml(c) + '"' + (c === current ? " selected" : "") + ">" + escapeHtml(c) + "</option>";
            }).join("");

            var updated = root.querySelector(".card-av-updated");
            updated.textContent = state.data.lastUpdatedAt
                ? "Mis à jour le " + formatDate(state.data.lastUpdatedAt) + (state.data.lastUpdatedBy ? " par " + state.data.lastUpdatedBy : "")
                : "";
            root.querySelector(".card-av-standard").style.display = state.canEdit && !(state.data.cards || []).length ? "" : "none";

            if (!(state.data.cards || []).length) {
                body.innerHTML = '<div class="card-av-empty"><i class="bi bi-credit-card-2-front"></i>' +
                    "<p class=\"mb-1 fw-semibold\">Aucune carte renseignée pour cette filiale.</p>" +
                    '<p class="small text-muted mb-0">' + (state.canEdit
                        ? "Ajoutez les cartes proposées (ou partez du modèle standard), puis cochez les villes où elles sont disponibles."
                        : "L'équipe QA n'a pas encore renseigné les disponibilités de cartes pour cette filiale.") + "</p></div>";
                return;
            }
            if (!allCities.length) {
                body.innerHTML = '<div class="card-av-empty"><i class="bi bi-geo-alt"></i><p class="mb-1 fw-semibold">Aucune ville pour cette filiale.</p>' +
                    '<p class="small text-muted mb-0">' + (state.canEdit ? "Ajoutez une ville (bouton « Ville ») ou des agences dans l'onglet « Agences / Carte »." : "Les villes seront ajoutées par l'équipe QA.") + "</p></div>";
                return;
            }
            if (!cards.length) {
                body.innerHTML = '<p class="text-muted text-center py-3 mb-0">Aucune carte ne correspond à « ' + escapeHtml(state.search) + " ».</p>";
                return;
            }
            body.innerHTML = state.cityFilter ? renderCity(cards, state.cityFilter) : renderMatrix(cards, allCities);
            wire();
        }

        function statusOf(cardId, city) {
            var c = state.cells[key(cardId, city)];
            return { cell: c, status: !c ? "unknown" : (c.available ? "yes" : "no") };
        }

        function cardLabel(card) {
            return '<div class="card-av-card-name">' + escapeHtml(card.name) +
                (card.category ? ' <span class="card-av-cat">' + escapeHtml(card.category) + "</span>" : "") + "</div>" +
                (card.details ? '<div class="card-av-card-details">' + escapeHtml(card.details) + "</div>" : "") +
                (state.canEdit ? '<div class="card-av-card-actions"><button type="button" class="btn btn-link btn-sm p-0 card-av-edit" data-card="' + card.id + '">Modifier</button>' +
                    ' · <button type="button" class="btn btn-link btn-sm p-0 text-danger card-av-delete" data-card="' + card.id + '">Supprimer</button></div>' : "");
        }

        function cellHtml(card, city) {
            var s = statusOf(card.id, city);
            var note = s.cell && s.cell.note ? s.cell.note : "";
            var title = (s.status === "yes" ? "Disponible" : s.status === "no" ? "Indisponible" : "Non renseigné") +
                (note ? " — " + note : "") + (s.cell && s.cell.updatedBy ? " (" + s.cell.updatedBy + ")" : "");
            var inner;
            if (state.canEdit) {
                inner = '<input type="checkbox" class="form-check-input card-av-check" data-card="' + card.id + '" data-city="' + escapeHtml(city) + '"' +
                    (s.status === "yes" ? " checked" : "") + (s.status === "unknown" ? ' data-unknown="1"' : "") + ' aria-label="' + escapeHtml(card.name + " — " + city) + '">' +
                    '<button type="button" class="card-av-note-btn' + (note ? " has-note" : "") + '" data-card="' + card.id + '" data-city="' + escapeHtml(city) + '" title="Précision">' +
                    '<i class="bi ' + (note ? "bi-chat-left-text-fill" : "bi-chat-left") + '"></i></button>';
            } else {
                inner = '<span class="card-av-dot card-av-' + s.status + '"><i class="bi ' +
                    (s.status === "yes" ? "bi-check-lg" : s.status === "no" ? "bi-x-lg" : "bi-dash") + '"></i></span>';
            }
            return '<td class="card-av-cell card-av-cell-' + s.status + '" title="' + escapeHtml(title) + '">' + inner +
                (note ? '<div class="card-av-note">' + escapeHtml(note) + "</div>" : "") + "</td>";
        }

        function renderMatrix(cards, allCities) {
            var head = allCities.map(function (city) {
                var count = cards.filter(function (c) { return statusOf(c.id, city).status === "yes"; }).length;
                return '<th class="card-av-city"><button type="button" class="card-av-city-btn" data-city="' + escapeHtml(city) + '">' + escapeHtml(city) + "</button>" +
                    '<span class="card-av-city-count">' + count + "/" + cards.length + "</span></th>";
            }).join("");
            var rows = cards.map(function (card, i) {
                return '<tr style="animation-delay:' + Math.min(i * 30, 300) + 'ms"><th class="card-av-rowhead">' + cardLabel(card) + "</th>" +
                    allCities.map(function (city) { return cellHtml(card, city); }).join("") + "</tr>";
            }).join("");
            return '<div class="card-av-table-wrap"><table class="card-av-table"><thead><tr><th class="card-av-rowhead">Carte</th>' + head + "</tr></thead>" +
                "<tbody>" + rows + "</tbody></table></div>" + legend();
        }

        /** Vue « par ville » : la réponse directe à « la carte X est-elle disponible à Y ? ». */
        function renderCity(cards, city) {
            var groups = { yes: [], no: [], unknown: [] };
            cards.forEach(function (card) { groups[statusOf(card.id, city).status].push(card); });
            function block(kind, label, icon) {
                if (!groups[kind].length) return "";
                return '<h6 class="card-av-group card-av-group-' + kind + '"><i class="bi ' + icon + '"></i> ' + label + " (" + groups[kind].length + ")</h6>" +
                    '<div class="row g-2 mb-3">' + groups[kind].map(function (card) {
                        var s = statusOf(card.id, city);
                        return '<div class="col-md-6 col-xl-4"><div class="card-av-tile card-av-tile-' + kind + '">' + cardLabel(card) +
                            (s.cell && s.cell.note ? '<div class="card-av-note mt-1"><i class="bi bi-info-circle"></i> ' + escapeHtml(s.cell.note) + "</div>" : "") +
                            (state.canEdit ? '<div class="mt-2 d-flex align-items-center gap-2"><input type="checkbox" class="form-check-input card-av-check m-0" data-card="' + card.id + '" data-city="' + escapeHtml(city) + '"' + (kind === "yes" ? " checked" : "") + '> <span class="small">Disponible</span>' +
                                '<button type="button" class="card-av-note-btn' + (s.cell && s.cell.note ? " has-note" : "") + '" data-card="' + card.id + '" data-city="' + escapeHtml(city) + '"><i class="bi bi-chat-left-text"></i> Précision</button></div>' : "") +
                            "</div></div>";
                    }).join("") + "</div>";
            }
            return '<div class="card-av-city-view"><h5 class="mb-3"><i class="bi bi-geo-alt-fill text-danger"></i> ' + escapeHtml(city) + "</h5>" +
                block("yes", "Disponibles", "bi-check-circle-fill") +
                block("no", "Indisponibles", "bi-x-circle-fill") +
                block("unknown", "Non renseignées", "bi-question-circle") + "</div>";
        }

        function legend() {
            return '<div class="card-av-legend"><span><span class="card-av-dot card-av-yes"><i class="bi bi-check-lg"></i></span> Disponible</span>' +
                '<span><span class="card-av-dot card-av-no"><i class="bi bi-x-lg"></i></span> Indisponible</span>' +
                '<span><span class="card-av-dot card-av-unknown"><i class="bi bi-dash"></i></span> Non renseigné</span>' +
                "<span class=\"text-muted\">Cliquez sur une ville pour voir ses cartes.</span></div>";
        }

        function wire() {
            Array.prototype.forEach.call(body.querySelectorAll(".card-av-city-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    state.cityFilter = btn.getAttribute("data-city");
                    render();
                });
            });
            if (!state.canEdit) return;
            Array.prototype.forEach.call(body.querySelectorAll(".card-av-check"), function (box) {
                box.addEventListener("change", function () {
                    var cardId = Number(box.getAttribute("data-card"));
                    var city = box.getAttribute("data-city");
                    var existing = state.cells[key(cardId, city)];
                    saveCell(cardId, city, box.checked, existing ? existing.note : null, box);
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll(".card-av-note-btn"), function (btn) {
                btn.addEventListener("click", function () {
                    openNoteDialog(Number(btn.getAttribute("data-card")), btn.getAttribute("data-city"));
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll(".card-av-edit"), function (btn) {
                btn.addEventListener("click", function () {
                    var id = Number(btn.getAttribute("data-card"));
                    openCardDialog(state.data.cards.filter(function (c) { return c.id === id; })[0]);
                });
            });
            Array.prototype.forEach.call(body.querySelectorAll(".card-av-delete"), function (btn) {
                btn.addEventListener("click", function () {
                    var id = Number(btn.getAttribute("data-card"));
                    var card = state.data.cards.filter(function (c) { return c.id === id; })[0];
                    if (!confirm("Supprimer la carte « " + card.name + " » et toutes ses disponibilités ?")) return;
                    request("DELETE", "/api/card-availability/cards/" + id).then(reload).catch(function (e) { alert("Erreur : " + e.message); });
                });
            });
        }

        function saveCell(cardId, city, available, note, box) {
            if (box) box.disabled = true;
            return request("PUT", "/api/card-availability/cells", { cardId: cardId, city: city, available: available, note: note || null })
                .then(function (cell) {
                    state.cells[key(cell.cardId, cell.city)] = cell;
                    state.data.lastUpdatedAt = cell.updatedAt;
                    state.data.lastUpdatedBy = cell.updatedBy;
                    render();
                    var td = body.querySelector('.card-av-check[data-card="' + cardId + '"][data-city="' + cssEscape(city) + '"]');
                    if (td && td.closest("td")) td.closest("td").classList.add("card-av-flash");
                })
                .catch(function (e) {
                    if (box) { box.disabled = false; box.checked = !available; }
                    alert("Enregistrement impossible : " + e.message);
                });
        }

        function cssEscape(s) {
            return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/"/g, '\\"');
        }

        function reload() { return load(state.country, state.canEdit); }

        function addCity() {
            var name = (prompt("Nom de la ville à ajouter :") || "").trim().replace(/\s+/g, " ");
            if (!name) return;
            state.extraCities.push(name);
            render();
        }

        function addStandard() {
            request("POST", "/api/card-availability/cards/standard?country=" + encodeURIComponent(state.country))
                .then(reload).catch(function (e) { alert("Erreur : " + e.message); });
        }

        // ----- Petite fenêtre commune (carte / précision) -----

        function ensureDialog() {
            if (dialog) return dialog;
            var el = document.createElement("div");
            el.className = "modal fade";
            el.tabIndex = -1;
            el.innerHTML = '<div class="modal-dialog modal-dialog-centered"><div class="modal-content">' +
                '<div class="modal-header"><h5 class="modal-title"></h5><button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>' +
                '<div class="modal-body"></div>' +
                '<div class="modal-footer"><button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">Annuler</button>' +
                '<button type="button" class="btn btn-primary card-av-dialog-save">Enregistrer</button></div></div></div>';
            document.body.appendChild(el);
            dialog = { el: el, modal: new bootstrap.Modal(el), onSave: null };
            el.querySelector(".card-av-dialog-save").addEventListener("click", function () { if (dialog.onSave) dialog.onSave(); });
            return dialog;
        }

        function openCardDialog(card) {
            var d = ensureDialog();
            d.el.querySelector(".modal-title").textContent = card ? "Modifier la carte" : "Ajouter une carte";
            d.el.querySelector(".modal-body").innerHTML =
                '<div class="mb-2"><label class="form-label small">Nom de la carte *</label><input class="form-control" name="name" maxlength="150" placeholder="ex. Visa Classic"></div>' +
                '<div class="mb-2"><label class="form-label small">Famille</label><input class="form-control" name="category" maxlength="80" placeholder="Débit, Crédit, Prépayée…"></div>' +
                '<div class="mb-2"><label class="form-label small">Détails (délai, frais, plafonds, conditions)</label><textarea class="form-control" name="details" rows="3" maxlength="1000"></textarea></div>';
            var f = function (n) { return d.el.querySelector('[name="' + n + '"]'); };
            f("name").value = card ? card.name : "";
            f("category").value = card && card.category ? card.category : "";
            f("details").value = card && card.details ? card.details : "";
            d.onSave = function () {
                var name = f("name").value.trim();
                if (!name) { f("name").focus(); return; }
                var payload = { countryCode: state.country, name: name, category: f("category").value, details: f("details").value,
                    sortOrder: card ? card.sortOrder : (state.data.cards || []).length };
                request(card ? "PUT" : "POST", "/api/card-availability/cards" + (card ? "/" + card.id : ""), payload)
                    .then(function () { d.modal.hide(); return reload(); })
                    .catch(function (e) { alert("Erreur : " + e.message); });
            };
            d.modal.show();
            setTimeout(function () { f("name").focus(); }, 300);
        }

        function openNoteDialog(cardId, city) {
            var d = ensureDialog();
            var card = state.data.cards.filter(function (c) { return c.id === cardId; })[0];
            var cell = state.cells[key(cardId, city)];
            d.el.querySelector(".modal-title").textContent = card.name + " — " + city;
            d.el.querySelector(".modal-body").innerHTML =
                '<div class="form-check form-switch mb-3"><input class="form-check-input" type="checkbox" name="available" id="cardAvDialogAvailable">' +
                '<label class="form-check-label" for="cardAvDialogAvailable">Disponible dans cette ville</label></div>' +
                '<label class="form-label small">Précision (agences concernées, stock, délai de délivrance…)</label>' +
                '<textarea class="form-control" name="note" rows="3" maxlength="500"></textarea>';
            d.el.querySelector('[name="available"]').checked = !!(cell && cell.available);
            d.el.querySelector('[name="note"]').value = cell && cell.note ? cell.note : "";
            d.onSave = function () {
                saveCell(cardId, city, d.el.querySelector('[name="available"]').checked, d.el.querySelector('[name="note"]').value)
                    .then(function () { d.modal.hide(); });
            };
            d.modal.show();
        }

        return { load: load };
    }

    return { mount: mount };
})();
