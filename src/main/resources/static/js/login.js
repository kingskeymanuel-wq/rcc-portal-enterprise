"use strict";

/* ==========================================================
   RCC Portal Enterprise
   Login Enterprise V2
========================================================== */

const API = "/api/auth";

// Délai avant d'afficher un message "ça prend plus de temps que prévu" pendant
// que l'appel réseau (login/OTP) est encore en cours — n'annule PAS la requête,
// sert uniquement à rassurer l'utilisateur si la gateway d'auth est lente.
const SLOW_NETWORK_HINT_MS = 4000;

// Délai maximum avant d'abandonner un appel réseau côté client — légèrement
// au-dessus du timeout backend (rcc.auth.gateway.timeout-ms, 15000 ms par
// défaut) pour laisser au serveur le temps de renvoyer sa propre erreur avant
// que le client n'abandonne de son côté.
const FETCH_TIMEOUT_MS = 20000;

let challengeId = null;
let otpModal = null;

/**
 * « Impossible de joindre le serveur » uniquement pour une vraie panne réseau (fetch rejeté).
 * Toute autre erreur (script de la page non chargé, bug) est affichée telle quelle : sinon un
 * simple fichier CSS/JS refusé passait pour un serveur injoignable.
 */
function networkErrorMessage(error) {
  const msg = error && error.message ? String(error.message) : "";
  if (error instanceof TypeError && /fetch|network|load failed/i.test(msg)) {
    return "Impossible de joindre le serveur RCC.";
  }
  return "Erreur de la page de connexion (" + (msg || "inconnue") + "). Rechargez la page avec Ctrl+F5.";
}
let excelliamPasswordModal = null;

/* ==========================================================
   ELEMENTS
========================================================== */

const loginForm =
    document.getElementById("loginForm");

const username =
    document.getElementById("gate-user");

const password =
    document.getElementById("gate-pwd");

const loginButton =
    document.getElementById("gate-submit");

const loader =
    document.getElementById("loginLoader");

const errorBox =
    document.getElementById("gate-err");

const otpInput =
    document.getElementById("gate-otp");

const otpButton =
    document.getElementById("otp-submit");

const otpError =
    document.getElementById("otp-error");

const excelliamNewPassword =
    document.getElementById("excelliam-new-password");

const excelliamConfirmPassword =
    document.getElementById("excelliam-confirm-password");

const excelliamPasswordButton =
    document.getElementById("excelliam-password-submit");

const excelliamPasswordError =
    document.getElementById("excelliam-password-error");

/* ==========================================================
   INITIALISATION
========================================================== */

document.addEventListener("DOMContentLoaded", () => {

  const modalElement =
      document.getElementById("otpModal");

  if (modalElement) {

    otpModal =
        new bootstrap.Modal(modalElement);

  }

  const excelliamModalElement =
      document.getElementById("excelliamPasswordModal");

  if (excelliamModalElement) {

    excelliamPasswordModal =
        new bootstrap.Modal(excelliamModalElement);

  }

  hideError();

  username.focus();

});

/* ==========================================================
   OUTILS
========================================================== */

function showLoader() {

  if (loader) {

    loader.classList.add("show");

  }

  loginButton.disabled = true;

}

function hideLoader() {

  if (loader) {

    loader.classList.remove("show");

  }

  loginButton.disabled = false;

}

function showError(message) {

  errorBox.textContent = message;

  errorBox.classList.add("show");

}

function hideError() {

  errorBox.textContent = "";

  errorBox.classList.remove("show");

}

function showOtpError(message) {

  otpError.textContent = message;

  otpError.classList.add("show");

}

function hideOtpError() {

  otpError.textContent = "";

  otpError.classList.remove("show");

}

function showExcelliamPasswordError(message) {

  excelliamPasswordError.textContent = message;

  excelliamPasswordError.classList.add("show");

}

function hideExcelliamPasswordError() {

  excelliamPasswordError.textContent = "";

  excelliamPasswordError.classList.remove("show");

}

/**
 * fetch() avec timeout côté client (AbortController) + message d'attente
 * affiché après SLOW_NETWORK_HINT_MS si le serveur n'a toujours pas répondu.
 * Corrige le symptôme "l'appli semble figée" pendant les 15s du timeout
 * backend de la gateway d'auth : l'utilisateur est prévenu au lieu de fixer
 * un loader silencieux.
 *
 * @param {string} url
 * @param {RequestInit} options
 * @param {(msg: string) => void} onSlow appelé une seule fois si l'appel traîne
 * @returns {Promise<Response>}
 */
async function fetchWithTimeout(url, options, onSlow) {

  const controller = new AbortController();

  const abortTimer = setTimeout(
      () => controller.abort(),
      FETCH_TIMEOUT_MS
  );

  const slowHintTimer = onSlow
      ? setTimeout(
          () => onSlow("Connexion en cours, cela peut prendre quelques secondes…"),
          SLOW_NETWORK_HINT_MS
      )
      : null;

  try {

    return await fetch(url, { ...options, signal: controller.signal });

  } finally {

    clearTimeout(abortTimer);

    if (slowHintTimer) {

      clearTimeout(slowHintTimer);

    }

  }

}

/**
 * Parse une Response en JSON en gérant proprement le cas où le serveur a
 * répondu avec autre chose que du JSON (page d'erreur HTML d'un proxy,
 * 502/504, etc.) — évite qu'une réponse non-JSON tombe dans le catch
 * générique avec un message qui laisse croire que le serveur est injoignable
 * alors qu'il a bien répondu.
 *
 * @param {Response} response
 * @returns {Promise<any>}
 */
async function parseJsonSafe(response) {

  const contentType = response.headers.get("content-type") || "";

  if (!contentType.includes("application/json")) {

    throw new Error(
        "Réponse inattendue du serveur (statut " + response.status + ")."
    );

  }

  return response.json();

}

/* ==========================================================
   PASSWORD
========================================================== */

function togglePassword() {

  const icon =
      document.getElementById("eyeIcon");

  if (password.type === "password") {

    password.type = "text";

    icon.className =
        "bi bi-eye-slash-fill";

  } else {

    password.type = "password";

    icon.className =
        "bi bi-eye-fill";

  }

}

window.togglePassword =
    togglePassword;

/* ==========================================================
   LOGIN - ETAPE 1
========================================================== */

loginForm.addEventListener("submit", async function (event) {

  event.preventDefault();

  hideError();

  hideOtpError();

  if (username.value.trim() === "") {

    showError("Veuillez saisir votre identifiant.");

    username.focus();

    return;

  }

  if (password.value.trim() === "") {

    showError("Veuillez saisir votre mot de passe.");

    password.focus();

    return;

  }

  showLoader();

  try {

    const response = await fetchWithTimeout(
        API + "/login",
        {
          method: "POST",
          credentials: "same-origin",
          headers: {
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            username: username.value.trim(),
            password: password.value
          })
        },
        showError
    );

    hideLoader();

    const data = await parseJsonSafe(response);

    if (!response.ok) {

      if (data.code === "account_not_registered" || data.code === "no_role") {

        showError(
            (data.message || "Compte non reconnu.") +
            " Utilisez le lien « Contactez le support IT » ci-dessous pour nous prévenir."
        );

      } else {

        showError(
            data.message ||
            "Échec de l'authentification."
        );

      }

      return;

    }

    /*
     * LoginChallengeResponse
     */

    // Compte EXCELLIAM reconnu mais sans mot de passe encore défini (première connexion, ou
    // après réinitialisation par un admin) — propose l'écran de création plutôt qu'une erreur.
    if (data.requiresPasswordSetup) {

      showExcelliamPasswordModal();

      return;

    }

    // Compte EXCELLIAM (prestataire externe, pas de MFA possible — voir AuthService.initiateLogin) :
    // le cookie de session est déjà posé côté serveur dans cette même réponse, rien à faire ici
    // d'autre que rediriger — jamais d'écran OTP affiché pour ce cas.
    if (!data.twoFactorRequired && data.session) {

      fetch("/api/users/me/team-status", { credentials: "same-origin" })
          .then(function (res) { return res.ok ? res.json() : { redirectTo: null }; })
          .then(function (status) {
            window.location.replace(status.redirectTo || "/dashboard");
          })
          .catch(function () { window.location.replace("/dashboard"); });

      return;

    }

    challengeId = data.challengeId;

    if (!challengeId) {

      showError(

          "Le serveur n'a pas retourné de challenge MFA."

      );

      return;

    }

    otpInput.value = "";

    hideOtpError();

    otpModal.show();

    otpInput.focus();

  }

  catch (error) {

    hideLoader();

    console.error(error);

    if (error && error.name === "AbortError") {

      showError(
          "Le serveur RCC met trop de temps à répondre. Réessayez dans un instant."
      );

    } else {

      showError(networkErrorMessage(error));

    }

  }

});
/* ==========================================================
   LOGIN - ETAPE 2 (MFA)
========================================================== */

otpButton.addEventListener("click", async function () {

  hideOtpError();

  if (otpInput.value.trim() === "") {

    showOtpError("Veuillez saisir votre code OTP.");

    otpInput.focus();

    return;

  }

  otpButton.disabled = true;

  try {

    const response = await fetchWithTimeout(
        API + "/mfa",
        {
          method: "POST",
          credentials: "same-origin",
          headers: {
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            username: username.value.trim(),
            challengeId: challengeId,
            otp: otpInput.value.trim()
          })
        },
        showOtpError
    );

    const tokens = await parseJsonSafe(response);

    otpButton.disabled = false;

    if (!response.ok) {

      showOtpError(

          tokens.message ||

          "Code OTP incorrect."

      );

      return;

    }

    /* ==========================================
       SESSION — le cookie HttpOnly a déjà été posé
       par le serveur dans la réponse ci-dessus, rien
       à faire ici côté JavaScript.
    ========================================== */

    if (otpModal) {

      otpModal.hide();

    }

    fetch("/api/users/me/team-status", { credentials: "same-origin" })
        .then(function (res) { return res.ok ? res.json() : { redirectTo: null }; })
        .then(function (status) {
          window.location.replace(status.redirectTo || "/dashboard");
        })
        .catch(function () { window.location.replace("/dashboard"); });

  }

  catch (error) {

    otpButton.disabled = false;

    console.error(error);

    if (error && error.name === "AbortError") {

      showOtpError(
          "Le serveur met trop de temps à répondre. Réessayez dans un instant."
      );

    } else {

      showOtpError(

          "Impossible de joindre le serveur."

      );

    }

  }

});

/* ==========================================================
   OTP
========================================================== */

otpInput.addEventListener("keydown", function (event) {

  if (event.key === "Enter") {

    event.preventDefault();

    otpButton.click();

  }

});

/* ==========================================================
   USERNAME
========================================================== */

username.addEventListener("keydown", function (event) {

  if (event.key === "Enter") {

    password.focus();

  }

});

/* ==========================================================
   PASSWORD
========================================================== */

password.addEventListener("keydown", function (event) {

  if (event.key === "Enter") {

    loginButton.click();

  }

});

/* ==========================================================
   MOT DE PASSE OUBLIÉ / CONTACTER LE SUPPORT IT
   RCC ne peut pas modifier un mot de passe Active Directory —
   ces deux actions notifient l'équipe IT (en app + e-mail)
   plutôt que d'essayer de réinitialiser quoi que ce soit ici.
========================================================== */

var forgotPasswordLink = document.getElementById("forgotPasswordLink");
if (forgotPasswordLink) {
  forgotPasswordLink.addEventListener("click", function (evt) {
    evt.preventDefault();
    var username = (document.getElementById("gate-user") || {}).value || "";
    fetch("/api/auth/forgot-password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: username })
    })
        .then(function (res) { return res.json(); })
        .then(function (data) { alert(data.message || "L'équipe IT a été notifiée."); })
        .catch(function () { alert("Impossible de contacter le serveur pour le moment. Réessayez plus tard."); });
  });
}

var contactItLink = document.getElementById("contactItLink");
if (contactItLink) {
  contactItLink.addEventListener("click", function (evt) {
    evt.preventDefault();
    var username = (document.getElementById("gate-user") || {}).value || "";
    fetch("/api/auth/contact-it", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: username, message: "Clic sur « Contactez le support IT » depuis la page de connexion." })
    })
        .then(function (res) { return res.json(); })
        .then(function (data) { alert(data.message || "L'équipe IT a été notifiée."); })
        .catch(function () { alert("Impossible de contacter le serveur pour le moment. Réessayez plus tard."); });
  });
}

/* ==========================================================
   APPARENCE PILOTABLE — photo de connexion (image + opacité),
   réglable depuis Administration > Apparence par QA/Admin,
   sans jamais avoir besoin de retoucher le code.
========================================================== */
fetch("/api/site-settings/public")
    .then(function (res) { return res.ok ? res.json() : {}; })
    .then(function (settings) {
      var photo = document.querySelector(".login-left-photo");
      if (!photo) return;
      if (settings["login.hero.imageUrl"]) {
        photo.style.backgroundImage = "url('" + settings["login.hero.imageUrl"] + "')";
      }
      if (settings["login.hero.opacity"]) {
        photo.style.opacity = settings["login.hero.opacity"];
      }
    })
    .catch(function () { /* réglages indisponibles — la photo par défaut du CSS reste affichée */ });

/* ==========================================================
   CAROUSEL — plusieurs photos de connexion qui défilent l'une
   après l'autre (complète l'image unique ci-dessus : si plusieurs
   images sont configurées, elles prennent le relais en boucle).
========================================================== */
fetch("/api/site-settings/login-hero-images")
    .then(function (res) { return res.ok ? res.json() : []; })
    .then(function (urls) {
      if (!urls || urls.length < 2) return; // une seule image (ou aucune) — le réglage simple ci-dessus suffit
      var photo = document.querySelector(".login-left-photo");
      if (!photo) return;
      var index = 0;
      photo.style.transition = "opacity 1s ease-in-out";
      setInterval(function () {
        index = (index + 1) % urls.length;
        photo.style.opacity = "0";
        setTimeout(function () {
          photo.style.backgroundImage = "url('" + urls[index] + "')";
          photo.style.opacity = document.body.getAttribute("data-hero-opacity") || ".14";
        }, 500);
      }, 6000);
    })
    .catch(function () { /* carousel indisponible — l'image simple reste affichée */ });

/* ==========================================================
   CARTES "FONCTIONNALITÉ" — éditables depuis le portail IT
   (Administration > Apparence). Si l'API ne répond pas, les
   cartes par défaut codées dans la page restent affichées.
========================================================== */
fetch("/api/site-settings/public")
    .then(function (res) { return res.ok ? res.json() : {}; })
    .then(function (settings) {
      var container = document.getElementById("loginFeaturesContainer");
      if (settings["login.features.fontFamily"] && container) {
        container.style.fontFamily = settings["login.features.fontFamily"];
      }
      if (settings["login.features.lineHeight"] && container) {
        container.style.lineHeight = settings["login.features.lineHeight"];
      }
    })
    .catch(function () {});

fetch("/api/login-feature-cards/public")
    .then(function (res) { return res.ok ? res.json() : null; })
    .then(function (cards) {
      if (!cards || !cards.length) return; // API indisponible ou aucune carte — les cartes par défaut restent
      var container = document.getElementById("loginFeaturesContainer");
      if (!container) return;
      // NOTE SÉCURITÉ : c.icon est désormais échappé comme title/subtitle — avant ce correctif,
      // seuls title/subtitle passaient par escapeHtmlLogin(), pas icon, ce qui permettait une
      // injection HTML/JS (XSS stocké) via ce champ si un compte admin compromis (ou un admin
      // malveillant) l'utilisait pour y placer autre chose qu'une classe d'icône Bootstrap.
      container.innerHTML = cards.map(function (c) {
        return '<div class="feature"><i class="bi ' + escapeHtmlLogin(c.icon) + '"></i><div><h5>' + escapeHtmlLogin(c.title) +
            '</h5><small>' + escapeHtmlLogin(c.subtitle || "") + '</small></div></div>';
      }).join("");
    })
    .catch(function () { /* cartes par défaut codées dans la page conservées */ });

function escapeHtmlLogin(s) {
  return (s || "").replace(/[&<>"']/g, function (c) {
    return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
  });
}

/* ==========================================================
   EXCELLIAM — création du mot de passe à la première connexion
========================================================== */

function showExcelliamPasswordModal() {

  excelliamNewPassword.value = "";
  excelliamConfirmPassword.value = "";
  hideExcelliamPasswordError();

  if (excelliamPasswordModal) {
    excelliamPasswordModal.show();
  }

  excelliamNewPassword.focus();

}

excelliamPasswordButton.addEventListener("click", async () => {

  hideExcelliamPasswordError();

  const newPwd = excelliamNewPassword.value;
  const confirmPwd = excelliamConfirmPassword.value;

  if (!newPwd || newPwd.length < 8) {
    showExcelliamPasswordError("Le mot de passe doit contenir au moins 8 caractères.");
    return;
  }

  if (newPwd !== confirmPwd) {
    showExcelliamPasswordError("Les deux mots de passe ne correspondent pas.");
    return;
  }

  excelliamPasswordButton.disabled = true;

  try {

    const response = await fetchWithTimeout(
        API + "/excelliam/set-password",
        {
          method: "POST",
          credentials: "same-origin",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            username: username.value.trim(),
            newPassword: newPwd
          })
        },
        showExcelliamPasswordError
    );

    const data = await parseJsonSafe(response);

    excelliamPasswordButton.disabled = false;

    if (!response.ok) {
      showExcelliamPasswordError(data.message || "Impossible de créer le mot de passe.");
      return;
    }

    if (excelliamPasswordModal) {
      excelliamPasswordModal.hide();
    }

    // Mot de passe créé — on relance directement la connexion avec ce même mot de passe,
    // plutôt que de forcer l'utilisateur à retaper son username/mot de passe une seconde fois.
    password.value = newPwd;
    loginForm.dispatchEvent(new Event("submit", { cancelable: true }));

  } catch (error) {

    excelliamPasswordButton.disabled = false;
    console.error(error);

    if (error && error.name === "AbortError") {

      showExcelliamPasswordError(
          "Le serveur met trop de temps à répondre. Réessayez dans un instant."
      );

    } else {

      showExcelliamPasswordError(networkErrorMessage(error));

    }

  }

});

/* ==========================================================
   DEBUG
========================================================== */

console.log(

    "RCC Portal Enterprise Login V2 chargé."

);