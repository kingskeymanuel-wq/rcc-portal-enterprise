"use strict";

/**
 * Éditeur d'image générique réutilisable — rognage (glisser un rectangle) + effets
 * (luminosité, contraste, niveaux de gris, sépia). Utilisation :
 *
 *   window.RccImageEditor.open(file, function (editedBlob) {
 *       // editedBlob est un Blob JPEG prêt à uploader (FormData.append("file", editedBlob, "image.jpg"))
 *   });
 *
 * N'importe quel champ <input type="file"> du site peut passer son fichier ici avant
 * l'upload final, au lieu d'envoyer le fichier brut.
 */
(function () {
    var modalEl, canvas, ctx, img;
    var cropRect = null;
    var dragging = false;
    var dragStart = null;
    var filters = { brightness: 100, contrast: 100, grayscale: 0, sepia: 0 };
    var onDone = null;

    function ensureModal() {
        if (modalEl) return;
        modalEl = document.createElement("div");
        modalEl.className = "modal fade";
        modalEl.id = "rccImageEditorModal";
        modalEl.innerHTML =
            '<div class="modal-dialog modal-dialog-centered modal-lg">' +
            '<div class="modal-content">' +
            '<div class="modal-header"><h5 class="modal-title"><i class="bi bi-crop"></i> Modifier l\'image</h5>' +
            '<button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>' +
            '<div class="modal-body">' +
            '<p class="small text-muted mb-2">Glissez sur l\'image pour définir un cadrage (optionnel — sans sélection, l\'image entière est conservée).</p>' +
            '<div style="max-height:420px;overflow:auto;text-align:center;background:#f1f3f5;border-radius:.5rem;">' +
            '<canvas id="rccImageEditorCanvas" style="max-width:100%;cursor:crosshair;"></canvas>' +
            '</div>' +
            '<div class="row g-2 mt-3">' +
            '<div class="col-md-3"><label class="form-label small mb-0">Luminosité</label><input type="range" class="form-range" id="rccIeBrightness" min="50" max="150" value="100"></div>' +
            '<div class="col-md-3"><label class="form-label small mb-0">Contraste</label><input type="range" class="form-range" id="rccIeContrast" min="50" max="150" value="100"></div>' +
            '<div class="col-md-3"><label class="form-label small mb-0">Niveaux de gris</label><input type="range" class="form-range" id="rccIeGrayscale" min="0" max="100" value="0"></div>' +
            '<div class="col-md-3"><label class="form-label small mb-0">Sépia</label><input type="range" class="form-range" id="rccIeSepia" min="0" max="100" value="0"></div>' +
            '</div>' +
            '<button type="button" class="btn btn-sm btn-outline-secondary mt-2" id="rccIeResetCropBtn">Retirer le cadrage</button>' +
            '</div>' +
            '<div class="modal-footer">' +
            '<button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">Annuler</button>' +
            '<button type="button" class="btn btn-primary" id="rccIeApplyBtn"><i class="bi bi-check-lg"></i> Appliquer</button>' +
            '</div></div></div>';
        document.body.appendChild(modalEl);

        canvas = document.getElementById("rccImageEditorCanvas");
        ctx = canvas.getContext("2d");

        canvas.addEventListener("mousedown", function (evt) {
            var rect = canvas.getBoundingClientRect();
            var scaleX = canvas.width / rect.width, scaleY = canvas.height / rect.height;
            dragging = true;
            dragStart = { x: (evt.clientX - rect.left) * scaleX, y: (evt.clientY - rect.top) * scaleY };
            cropRect = null;
        });
        canvas.addEventListener("mousemove", function (evt) {
            if (!dragging) return;
            var rect = canvas.getBoundingClientRect();
            var scaleX = canvas.width / rect.width, scaleY = canvas.height / rect.height;
            var x = (evt.clientX - rect.left) * scaleX, y = (evt.clientY - rect.top) * scaleY;
            cropRect = {
                x: Math.min(dragStart.x, x), y: Math.min(dragStart.y, y),
                w: Math.abs(x - dragStart.x), h: Math.abs(y - dragStart.y)
            };
            redraw();
        });
        window.addEventListener("mouseup", function () { dragging = false; });

        ["Brightness", "Contrast", "Grayscale", "Sepia"].forEach(function (name) {
            document.getElementById("rccIe" + name).addEventListener("input", function () {
                filters[name.toLowerCase()] = Number(this.value);
                redraw();
            });
        });
        document.getElementById("rccIeResetCropBtn").addEventListener("click", function () { cropRect = null; redraw(); });
        document.getElementById("rccIeApplyBtn").addEventListener("click", applyAndClose);
    }

    function cssFilter() {
        return "brightness(" + filters.brightness + "%) contrast(" + filters.contrast + "%) " +
            "grayscale(" + filters.grayscale + "%) sepia(" + filters.sepia + "%)";
    }

    function redraw() {
        ctx.filter = "none";
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.filter = cssFilter();
        ctx.drawImage(img, 0, 0);
        ctx.filter = "none";
        if (cropRect && cropRect.w > 4 && cropRect.h > 4) {
            ctx.save();
            ctx.fillStyle = "rgba(0,0,0,.45)";
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.clearRect(cropRect.x, cropRect.y, cropRect.w, cropRect.h);
            ctx.filter = cssFilter();
            ctx.drawImage(img, cropRect.x, cropRect.y, cropRect.w, cropRect.h, cropRect.x, cropRect.y, cropRect.w, cropRect.h);
            ctx.filter = "none";
            ctx.strokeStyle = "#0d6efd";
            ctx.lineWidth = 2;
            ctx.strokeRect(cropRect.x, cropRect.y, cropRect.w, cropRect.h);
            ctx.restore();
        }
    }

    function applyAndClose() {
        var out = document.createElement("canvas");
        var region = (cropRect && cropRect.w > 4 && cropRect.h > 4)
            ? cropRect : { x: 0, y: 0, w: canvas.width, h: canvas.height };
        out.width = region.w;
        out.height = region.h;
        var outCtx = out.getContext("2d");
        outCtx.filter = cssFilter();
        outCtx.drawImage(img, region.x, region.y, region.w, region.h, 0, 0, region.w, region.h);

        out.toBlob(function (blob) {
            bootstrap.Modal.getInstance(modalEl).hide();
            if (onDone) onDone(blob);
        }, "image/jpeg", 0.92);
    }

    /**
     * Ouvre l'éditeur pour le fichier donné. callback(editedBlob) est appelé une fois
     * "Appliquer" cliqué — jamais appelé si l'utilisateur annule/ferme la modale.
     */
    function open(file, callback) {
        ensureModal();
        onDone = callback;
        cropRect = null;
        filters = { brightness: 100, contrast: 100, grayscale: 0, sepia: 0 };
        document.getElementById("rccIeBrightness").value = 100;
        document.getElementById("rccIeContrast").value = 100;
        document.getElementById("rccIeGrayscale").value = 0;
        document.getElementById("rccIeSepia").value = 0;

        var reader = new FileReader();
        reader.onload = function (evt) {
            img = new Image();
            img.onload = function () {
                canvas.width = img.naturalWidth;
                canvas.height = img.naturalHeight;
                redraw();
                new bootstrap.Modal(modalEl).show();
            };
            img.src = evt.target.result;
        };
        reader.readAsDataURL(file);
    }

    window.RccImageEditor = { open: open };
})();
