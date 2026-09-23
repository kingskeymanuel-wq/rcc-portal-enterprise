"use strict";

(function () {

    var getJson = RccApi.getJson;
    var sendJson = RccApi.sendJson;

    // ===== Profil =====

    function loadProfile() {
        getJson("/api/user-profiles/me").then(function (p) {
            document.getElementById("profileName").textContent = p.name || p.matricule;
            document.getElementById("profileUsername").textContent = p.matricule ? "(" + p.matricule + ")" : "";
            document.getElementById("phoneInput").value = p.phone || "";
            document.getElementById("birthdateInput").value = p.birthdate || "";
            document.getElementById("bioInput").value = p.bio || "";
            if (p.photoUrl) document.getElementById("photoPreview").src = p.photoUrl;
        }).catch(function (e) {
            document.getElementById("profileError").textContent = "Erreur de chargement du profil : " + e.message;
            document.getElementById("profileError").style.display = "";
        });
    }

    function wireProfileForm() {
        document.getElementById("profileForm").addEventListener("submit", function (evt) {
            evt.preventDefault();
            document.getElementById("profileSaved").style.display = "none";
            document.getElementById("profileError").style.display = "none";

            var payload = {
                photoUrl: null, // la photo se met à jour via son propre endpoint d'upload, pas ici
                birthdate: document.getElementById("birthdateInput").value || null,
                phone: document.getElementById("phoneInput").value.trim() || null,
                bio: document.getElementById("bioInput").value.trim() || null
            };

            sendJson("/api/user-profiles/me", "PUT", payload)
                .then(function () {
                    document.getElementById("profileSaved").style.display = "";
                })
                .catch(function (e) {
                    document.getElementById("profileError").textContent = "Erreur : " + e.message;
                    document.getElementById("profileError").style.display = "";
                });
        });
    }

    // ===== Upload de la photo (fichier réel, pas une URL) =====

    function wirePhotoUpload() {
        var input = document.getElementById("photoFileInput");
        var status = document.getElementById("photoUploadStatus");

        input.addEventListener("change", function () {
            var file = input.files[0];
            if (!file) return;

            document.getElementById("photoPreview").src = URL.createObjectURL(file);
            status.textContent = "Envoi en cours…";
            status.className = "form-text";

            var formData = new FormData();
            formData.append("file", file);

            fetch("/api/user-profiles/me/photo", { method: "POST", credentials: "same-origin", body: formData })
                .then(function (res) {
                    if (!res.ok) return res.text().then(function (t) { return Promise.reject(new Error(t || "HTTP " + res.status)); });
                    return res.json();
                })
                .then(function (profile) {
                    document.getElementById("photoPreview").src = profile.photoUrl;
                    status.textContent = "Photo mise à jour.";
                    status.className = "form-text text-success";
                })
                .catch(function (e) {
                    status.textContent = "Erreur : " + e.message;
                    status.className = "form-text text-danger";
                });
        });
    }

    // ===== Apparence (langue + thème) =====

    function wireAppearance() {
        var lang = RccPreferences.getLanguage();
        document.getElementById(lang === "en" ? "langEn" : "langFr").checked = true;

        var theme = RccPreferences.getTheme();
        document.getElementById(theme === "dark" ? "themeDark" : "themeLight").checked = true;

        document.querySelectorAll('input[name="langChoice"]').forEach(function (radio) {
            radio.addEventListener("change", function () { RccPreferences.setLanguage(radio.value); });
        });
        document.querySelectorAll('input[name="themeChoice"]').forEach(function (radio) {
            radio.addEventListener("change", function () { RccPreferences.setTheme(radio.value); });
        });

        var fontSelect = document.getElementById("fontChoice");
        if (fontSelect) {
            fontSelect.value = RccPreferences.getFont();
            fontSelect.addEventListener("change", function () { RccPreferences.setFont(fontSelect.value); });
        }
    }

    function wirePhotoLightbox() {
        document.getElementById("photoPreview").addEventListener("click", function () {
            document.getElementById("photoLightboxImg").src = this.src;
            new bootstrap.Modal(document.getElementById("photoLightbox")).show();
        });
    }

    function init() {
        wireProfileForm();
        wirePhotoUpload();
        wireAppearance();
        wirePhotoLightbox();
        loadProfile();
    }

    document.addEventListener("DOMContentLoaded", init);
})();
