"use strict";

/**
 * Base de connaissance « Pas à pas » — accompagnements guidés par plateforme. Source unique, utilisée par :
 *   - la base de connaissance (onglet « Pas à pas ») ;
 *   - l'appel de campagne Outbound (boutons « Accompagner pas à pas » des formulaires) ;
 *   - le concepteur de formulaires (lien d'une question ou d'une réponse vers un pas à pas).
 *
 * Étape : { title, img (capture réelle) | mock (écran reconstitué), tap: [x %, y %] (zone touchée),
 *           say (à dire au client), tip, warn, next, choices: [{ label, next, hint }], done }
 * source : "guide" = guide officiel d'enrôlement (captures réelles, données personnelles masquées) ;
 *          "généré" = pas à pas indicatif reconstitué d'après l'écran d'accueil de l'application, à valider par l'équipe Digital.
 */
window.RCC_GUIDES = (function () {
    var IMG = "/images/pas-a-pas/ecobank-mobile/";
    var HOME = IMG + "tableau-de-bord.jpg";
    // Zones de l'écran d'accueil (captures du guide) : tuiles « Transaction rapide » et barre du bas.
    var T = { solde: [50, 45], credit: [28, 61], transfert: [75, 61], facture: [28, 71], xcash: [75, 71], pay: [28, 81], partager: [75, 81],
        cartes: [30, 89], aide: [49, 89], notif: [69, 89], autres: [89, 89] };

    function home(title, tap, say, tip) { return { title: title, img: HOME, tap: tap, say: say, tip: tip }; }

    // ───────────── Enrôlement (guide officiel) ─────────────

    var enrolement = {
        id: "enrolement", title: "Télécharger et activer l'application", icon: "bi-download", category: "Démarrer",
        source: "guide", duration: "3 à 5 min",
        summary: "Du téléchargement au tableau de bord : client existant (carte de débit ou banque par Internet) ou nouveau client (ouverture d'un compte Xpress).",
        start: "dl",
        steps: {
            dl: { title: "Rechercher l'application", img: IMG + "telecharger-recherche.jpg", tap: [45, 13],
                say: "Ouvrez Play Store (Android) ou App Store (iPhone) et tapez « Ecobank Mobile Banking » dans la recherche.",
                tip: "Vérifiez que l'éditeur est bien Ecobank : c'est l'application officielle.", next: "store" },
            store: { title: "Installer puis ouvrir", img: IMG + "telecharger-store.jpg", tap: [37, 30],
                say: "Appuyez sur « Installer », puis sur « Ouvrir » une fois le téléchargement terminé.", next: "welcome" },
            welcome: { title: "Écran d'accueil de l'application", img: IMG + "bienvenue.jpg", tap: [50, 91],
                say: "Vous voyez « Welcome to Ecobank mobile » : appuyez sur le bouton vert « Get Started ».", next: "pays" },
            pays: { title: "Pays et numéro de téléphone", img: IMG + "activer-pays-numero.jpg", tap: [50, 91],
                say: "Sélectionnez votre pays, puis saisissez le numéro de téléphone enregistré sur votre compte. Appuyez sur « Continuer ».",
                tip: "Client existant : le numéro DOIT être celui enregistré sur le compte, sinon le code SMS n'arrivera pas. Prospect : son numéro personnel.",
                next: "commencons" },
            commencons: { title: "Client Ecobank ou nouveau client ?", img: IMG + "commencons.jpg",
                say: "L'application vous demande si vous avez déjà un compte Ecobank.",
                choices: [
                    { label: "Oui, j'ai un compte Ecobank", next: "methode", tap: [50, 81], hint: "Client existant : activation avec la carte ou le profil Internet." },
                    { label: "Non, je suis nouveau à Ecobank", next: "devenir", tap: [50, 88], hint: "Prospect : ouverture d'un compte Xpress depuis l'application." }] },
            // Client existant
            methode: { title: "Choisir la méthode d'activation", img: IMG + "activer-compte-choix.jpg",
                say: "Choisissez comment activer votre compte : avec votre carte de débit Ecobank, ou avec votre profil de banque par Internet.",
                choices: [
                    { label: "Utiliser ma carte de débit Ecobank", next: "carte", tap: [50, 81] },
                    { label: "Utiliser mon profil de banque par Internet", next: "internet", tap: [50, 89] }] },
            carte: { title: "Saisir les informations de la carte", img: IMG + "activer-carte-debit.jpg", tap: [50, 91],
                say: "Saisissez le numéro de votre carte, sa date d'expiration et les 3 chiffres du CVV au dos, puis « Continuer ».",
                warn: "Ne demandez JAMAIS au client de vous dicter son numéro de carte ou son CVV : il les saisit lui-même.", next: "otp" },
            internet: { title: "Identifiants de banque par Internet", img: IMG + "activer-profil-internet.jpg", tap: [50, 90],
                say: "Saisissez votre nom d'utilisateur et votre mot de passe Ecobank Online, puis « Continuer ».",
                warn: "Le client ne communique jamais son mot de passe à l'agent.", next: "otp" },
            otp: { title: "Vérification du numéro (code SMS)", img: IMG + "otp.jpg", tap: [50, 35],
                say: "Un code de vérification est envoyé par SMS : il est détecté automatiquement. Sinon, saisissez-le dans les cases.",
                tip: "Pas de SMS ? Attendre la fin du compte à rebours puis « Renvoyer le code ». Vérifier le réseau et que le numéro est bien celui du compte.",
                next: "pin" },
            pin: { title: "Créer le code PIN à 6 chiffres", img: IMG + "code-pin.jpg", tap: [50, 91],
                say: "Créez un code PIN à 6 chiffres, confirmez-le, puis « Continuer ». Il servira à vous connecter et à valider vos opérations.",
                warn: "Code non séquentiel (pas 123456) et jamais communiqué, même à un agent Ecobank.", next: "empreinte" },
            empreinte: { title: "Empreinte digitale (facultatif)", img: IMG + "empreinte.jpg", tap: [50, 72],
                say: "Vous pouvez activer la connexion par empreinte digitale, ou choisir « Pas maintenant ».",
                tip: "Étape facultative : elle ne bloque pas l'enrôlement.", next: "parrainage" },
            parrainage: { title: "Code de parrainage (facultatif)", img: IMG + "parrainage.jpg", tap: [50, 89],
                say: "Si quelqu'un vous a recommandé l'application, saisissez son code de parrainage ; sinon appuyez sur « Ignorer ».", next: "fin" },
            fin: { title: "C'est terminé !", img: HOME, tap: T.solde, done: true,
                say: "Votre application est activée : vous voyez votre compte. Essayons ensemble « Voir mon solde » pour vérifier.",
                tip: "Profitez-en pour faire réaliser une première opération (solde, achat de crédit) : le client repart autonome." },
            // Nouveau client — compte Xpress
            devenir: { title: "Devenir client Ecobank", img: IMG + "devenir-client.jpg", tap: [50, 81],
                say: "Appuyez sur « Ouvrez un compte Ecobank » : vous allez ouvrir un compte Xpress, directement depuis votre téléphone.", next: "xpress" },
            xpress: { title: "Choisir le compte Xpress", img: IMG + "compte-xpress.jpg", tap: [35, 80],
                say: "Sélectionnez « Compte Xpress » : un compte numérique ouvert instantanément, avec un minimum d'informations.",
                tip: "Argument : retraits et dépôts dans les points Xpress, transactions avec d'autres banques et les portefeuilles mobiles.", next: "securite" },
            securite: { title: "Questions de sécurité", img: IMG + "questions-securite.jpg", tap: [50, 91],
                say: "Choisissez deux questions de sécurité et leurs réponses : elles serviront à vérifier votre identité si vous oubliez votre code PIN.", next: "identite" },
            identite: { title: "Pièce d'identité", img: IMG + "identification.jpg", tap: [50, 91],
                say: "Indiquez le type de pièce, son numéro, sa date d'émission et votre adresse, puis « Créer un profil ».", next: "renseignements" },
            renseignements: { title: "Renseignements personnels", img: IMG + "renseignements.jpg", tap: [50, 91],
                say: "Complétez prénom, nom, date de naissance, e-mail et sexe, puis « Continuer ».", next: "otp2" },
            otp2: { title: "Vérification du numéro (code SMS)", img: IMG + "otp.jpg", tap: [50, 35],
                say: "Le code SMS est détecté automatiquement ; sinon saisissez-le.", next: "pin2" },
            pin2: { title: "Créer le code PIN à 6 chiffres", img: IMG + "code-pin.jpg", tap: [50, 91],
                say: "Créez puis confirmez votre code PIN à 6 chiffres.", warn: "Non séquentiel, jamais communiqué.", next: "empreinte2" },
            empreinte2: { title: "Empreinte et parrainage (facultatifs)", img: IMG + "empreinte.jpg", tap: [50, 80],
                say: "Empreinte digitale et code de parrainage sont facultatifs : « Pas maintenant » / « Ignorer » pour continuer.", next: "verifier" },
            verifier: { title: "Vérifier son identité", img: IMG + "verifier-identite.jpg", tap: [70, 56],
                say: "L'application propose de passer au Compte Xpress Plus (plafonds plus élevés) : une copie de la pièce d'identité et un selfie suffisent. « Vérifiez maintenant » ou « Fais-le plus tard ».",
                tip: "Encouragez « Vérifiez maintenant » : le client débloque de plus grandes limites tout de suite.", next: "autoriser" },
            autoriser: { title: "Autorisations de l'application", img: IMG + "autorisations.jpg", tap: [73, 55],
                say: "Autorisez l'application à accéder aux contacts (et à l'appareil photo pour la vérification) : vous pourrez choisir vos bénéficiaires directement.", next: "fin2" },
            fin2: { title: "Compte Xpress ouvert !", img: HOME, tap: T.solde, done: true,
                say: "Votre compte Xpress est ouvert et accessible : vous pouvez effectuer toutes les opérations de l'application.",
                tip: "Proposez la première opération maintenant (achat de crédit, transfert MoMo) pour ancrer l'usage." }
        }
    };

    // ───────────── Fonctionnalités (pas à pas reconstitués) ─────────────

    function amountScreen(title, rows) { return { title: title, rows: rows }; }

    var solde = {
        id: "solde", title: "Consulter son solde et ses opérations", icon: "bi-wallet2", category: "Comptes", source: "généré", duration: "30 s",
        summary: "Voir le solde disponible et l'historique des dernières opérations.", start: "s1",
        steps: {
            s1: home("Ouvrir l'application", T.solde, "Connectez-vous avec votre code PIN ou votre empreinte. Sur l'accueil, appuyez sur « Voir mon solde » sous votre compte.", "« Afficher tout » montre tous les comptes du client (Xpress, courant, épargne…)."),
            s2: { title: "Solde et dernières opérations", mock: amountScreen("Compte Xpress", [
                { t: "balance", label: "Solde disponible", value: "•••••• XOF" },
                { t: "option", icon: "bi-arrow-down-left", label: "Dépôt Xpress Point", sub: "Crédit" },
                { t: "option", icon: "bi-arrow-up-right", label: "Achat de crédit", sub: "Débit" },
                { t: "option", icon: "bi-arrow-up-right", label: "Paiement facture", sub: "Débit" },
                { t: "button", label: "Télécharger le relevé", hot: true }]), tap: [50, 80],
                say: "Votre solde s'affiche avec les dernières opérations. Appuyez sur une opération pour en voir le détail.", done: true }
        }
    };
    solde.steps.s1.next = "s2";

    var transfert = {
        id: "transfert", title: "Transférer de l'argent", icon: "bi-arrow-left-right", category: "Transférer", source: "généré", duration: "1 à 2 min",
        summary: "Entre ses comptes, vers un client Ecobank, une autre banque du pays, un compte Ecobank en Afrique ou un compte MTN MoMo.", start: "t1",
        steps: {
            t1: home("Transfert de fonds", T.transfert, "Sur l'accueil, appuyez sur « Transfert de fonds ».", null),
            t2: { title: "Type de transfert", mock: amountScreen("Transfert de fonds", [
                { t: "option", icon: "bi-arrow-repeat", label: "Entre mes comptes" },
                { t: "option", icon: "bi-person-check", label: "Vers un client Ecobank" },
                { t: "option", icon: "bi-bank", label: "Vers une autre banque" },
                { t: "option", icon: "bi-globe-europe-africa", label: "Vers un compte Ecobank en Afrique" },
                { t: "option", icon: "bi-phone", label: "Vers Mobile Money (MTN MoMo)" }]),
                say: "Choisissez le type de transfert.",
                choices: [{ label: "Entre mes comptes", next: "own", tap: [50, 24] }, { label: "Vers un client Ecobank", next: "benef", tap: [50, 35] },
                    { label: "Vers une autre banque", next: "benef", tap: [50, 46] }, { label: "Vers l'Afrique (Ecobank)", next: "afrique", tap: [50, 57] },
                    { label: "Vers MTN MoMo", next: "momo", tap: [50, 68] }] },
            own: { title: "Comptes et montant", mock: amountScreen("Entre mes comptes", [
                { t: "select", label: "Compte à débiter", value: "Compte Xpress" }, { t: "select", label: "Compte à créditer", value: "Compte épargne" },
                { t: "field", label: "Montant", value: "25 000 XOF" }, { t: "button", label: "Continuer", hot: true }]), tap: [50, 72],
                say: "Choisissez le compte à débiter, le compte à créditer et le montant, puis « Continuer ».", next: "confirm" },
            benef: { title: "Bénéficiaire", mock: amountScreen("Nouveau bénéficiaire", [
                { t: "select", label: "Banque", value: "Ecobank / autre banque" }, { t: "field", label: "Numéro de compte du bénéficiaire", value: "" },
                { t: "field", label: "Nom du bénéficiaire", value: "" }, { t: "field", label: "Montant", value: "" }, { t: "field", label: "Motif", value: "" },
                { t: "button", label: "Continuer", hot: true }]), tap: [50, 82],
                say: "Choisissez un bénéficiaire enregistré ou ajoutez-en un : banque, numéro de compte, nom, puis montant et motif.",
                tip: "Faire vérifier le nom du bénéficiaire affiché avant de valider : un virement envoyé ne s'annule pas.", next: "confirm" },
            afrique: { title: "Pays et bénéficiaire", mock: amountScreen("Vers l'Afrique", [
                { t: "select", label: "Pays de destination", value: "Sénégal" }, { t: "field", label: "Numéro de compte Ecobank du bénéficiaire", value: "" },
                { t: "field", label: "Montant", value: "" }, { t: "info", text: "Frais et taux de change affichés avant validation." },
                { t: "button", label: "Continuer", hot: true }]), tap: [50, 78],
                say: "Choisissez le pays, saisissez le compte Ecobank du bénéficiaire et le montant : les frais et le taux sont affichés avant de valider.", next: "confirm" },
            momo: { title: "Numéro Mobile Money", mock: amountScreen("Vers MTN MoMo", [
                { t: "select", label: "Opérateur", value: "MTN MoMo" }, { t: "field", label: "Numéro de téléphone", value: "07 00 00 00 00" },
                { t: "field", label: "Montant", value: "10 000 XOF" }, { t: "button", label: "Continuer", hot: true }]), tap: [50, 64],
                say: "Saisissez le numéro MoMo du bénéficiaire (ou choisissez-le dans vos contacts) et le montant.", next: "confirm" },
            confirm: { title: "Vérifier et valider", mock: amountScreen("Confirmation", [
                { t: "summary", items: [["Bénéficiaire", "Nom affiché"], ["Montant", "XOF"], ["Frais", "selon grille"]] },
                { t: "field", label: "Code PIN", value: "••••••" }, { t: "button", label: "Confirmer", hot: true }]), tap: [50, 62],
                say: "Vérifiez le récapitulatif (bénéficiaire, montant, frais) puis validez avec votre code PIN.", next: "ok" },
            ok: { title: "Transfert effectué", mock: amountScreen("", [{ t: "success", text: "Transfert effectué" }, { t: "button", label: "Partager le reçu", hot: true }]), tap: [50, 60],
                say: "Le transfert est effectué : vous pouvez partager le reçu au bénéficiaire.", done: true }
        }
    };
    transfert.steps.t1.next = "t2";

    var credit = {
        id: "credit", title: "Acheter du crédit téléphonique", icon: "bi-phone-vibrate", category: "Payer", source: "généré", duration: "30 s",
        summary: "Recharger un numéro MTN ou Orange, pour soi ou pour un proche.", start: "c1",
        steps: {
            c1: Object.assign(home("Achat de crédit", T.credit, "Sur l'accueil, appuyez sur « Achat de crédit ».", null), { next: "c2" }),
            c2: { title: "Opérateur et numéro", mock: amountScreen("Achat de crédit", [
                { t: "select", label: "Opérateur", value: "MTN / Orange" }, { t: "field", label: "Numéro à recharger", value: "Mon numéro" },
                { t: "field", label: "Montant", value: "1 000 XOF" }, { t: "button", label: "Continuer", hot: true }]), tap: [50, 64],
                say: "Choisissez l'opérateur, le numéro (le vôtre ou celui d'un proche) et le montant.", next: "c3" },
            c3: { title: "Valider", mock: amountScreen("Confirmation", [{ t: "summary", items: [["Numéro", "07 00 00 00 00"], ["Montant", "1 000 XOF"]] },
                { t: "field", label: "Code PIN", value: "••••••" }, { t: "button", label: "Confirmer", hot: true }]), tap: [50, 62],
                say: "Vérifiez et validez avec votre code PIN : le crédit arrive immédiatement.", done: true }
        }
    };

    var facture = {
        id: "facture", title: "Payer une facture ou la scolarité", icon: "bi-receipt", category: "Payer", source: "généré", duration: "1 min",
        summary: "Électricité, eau et frais de scolarité, sans se déplacer.", start: "f1",
        steps: {
            f1: Object.assign(home("Payer une facture", T.facture, "Sur l'accueil, appuyez sur « Payer une facture ».", null), { next: "f2" }),
            f2: { title: "Type de facture", mock: amountScreen("Payer une facture", [
                { t: "option", icon: "bi-lightning-charge", label: "Électricité" }, { t: "option", icon: "bi-droplet", label: "Eau" },
                { t: "option", icon: "bi-mortarboard", label: "Frais de scolarité" }, { t: "option", icon: "bi-tv", label: "Autres factures" }]),
                say: "Choisissez le type de facture.",
                choices: [{ label: "Électricité / eau", next: "f3", tap: [50, 24] }, { label: "Frais de scolarité", next: "f3s", tap: [50, 46] }] },
            f3: { title: "Référence de la facture", mock: amountScreen("Facture", [
                { t: "select", label: "Fournisseur", value: "Électricité / Eau" }, { t: "field", label: "Référence / numéro de contrat", value: "" },
                { t: "button", label: "Rechercher la facture", hot: true }]), tap: [50, 50],
                say: "Saisissez la référence indiquée sur la facture : le montant dû s'affiche.", tip: "La référence figure en haut de la facture papier ou du SMS du fournisseur.", next: "f4" },
            f3s: { title: "École et élève", mock: amountScreen("Scolarité", [
                { t: "select", label: "Établissement", value: "" }, { t: "field", label: "Matricule de l'élève", value: "" }, { t: "field", label: "Montant", value: "" },
                { t: "button", label: "Continuer", hot: true }]), tap: [50, 64],
                say: "Choisissez l'établissement, saisissez le matricule de l'élève et le montant.", next: "f4" },
            f4: { title: "Payer", mock: amountScreen("Confirmation", [{ t: "summary", items: [["Facture", "Référence"], ["Montant", "XOF"]] },
                { t: "field", label: "Code PIN", value: "••••••" }, { t: "button", label: "Payer", hot: true }]), tap: [50, 62],
                say: "Vérifiez le montant et validez avec votre code PIN. Le reçu est disponible dans l'historique.", done: true }
        }
    };

    var xpressCash = {
        id: "xpress-cash", title: "Retirer sans carte (Xpress Cash)", icon: "bi-cash-stack", category: "Transférer", source: "généré", duration: "1 min",
        summary: "Générer un code de retrait pour soi ou un proche, utilisable sans carte.", start: "x1",
        steps: {
            x1: Object.assign(home("Xpress Cash", T.xcash, "Sur l'accueil, appuyez sur « Xpress Cash ».", null), { next: "x2" }),
            x2: { title: "Bénéficiaire et montant", mock: amountScreen("Xpress Cash", [
                { t: "field", label: "Numéro du bénéficiaire", value: "Moi-même ou un proche" }, { t: "field", label: "Montant", value: "20 000 XOF" },
                { t: "field", label: "Code secret (à communiquer)", value: "••••" }, { t: "button", label: "Continuer", hot: true }]), tap: [50, 64],
                say: "Indiquez le bénéficiaire, le montant et un code secret à lui communiquer séparément.", warn: "Le code secret ne s'envoie jamais dans le même message que la référence.", next: "x3" },
            x3: { title: "Code de retrait généré", mock: amountScreen("", [{ t: "success", text: "Référence de retrait envoyée par SMS" },
                { t: "info", text: "Retrait au guichet automatique Ecobank ou dans un point Xpress, avec la référence et le code secret." }]),
                say: "Le bénéficiaire reçoit une référence : il retire l'argent avec cette référence et le code secret, sans carte.", done: true }
        }
    };

    var ecobankPay = {
        id: "ecobank-pay", title: "Payer un marchand (EcobankPay)", icon: "bi-qr-code-scan", category: "Payer", source: "généré", duration: "30 s",
        summary: "Payer en magasin ou en ligne en scannant le QR code du marchand.", start: "p1",
        steps: {
            p1: Object.assign(home("EcobankPay", T.pay, "Sur l'accueil, appuyez sur « EcobankPay ».", null), { next: "p2" }),
            p2: { title: "Scanner le QR code", mock: amountScreen("EcobankPay", [{ t: "scan" }, { t: "button", label: "Saisir le code marchand", hot: false }]), tap: [50, 40],
                say: "Scannez le QR code affiché chez le marchand (ou saisissez son code marchand).", next: "p3" },
            p3: { title: "Montant et validation", mock: amountScreen("Paiement", [{ t: "summary", items: [["Marchand", "Nom du commerce"]] },
                { t: "field", label: "Montant", value: "" }, { t: "field", label: "Code PIN", value: "••••••" }, { t: "button", label: "Payer", hot: true }]), tap: [50, 70],
                say: "Vérifiez le nom du marchand, saisissez le montant et validez avec votre code PIN.", done: true }
        }
    };

    var partager = {
        id: "partager", title: "Partager un paiement", icon: "bi-share", category: "Payer", source: "généré", duration: "1 min",
        summary: "Envoyer une demande de paiement ou partager ses coordonnées pour être payé.", start: "sp1",
        steps: {
            sp1: Object.assign(home("Partager un paiement", T.partager, "Sur l'accueil, appuyez sur « Partager un paiement ».", null), { next: "sp2" }),
            sp2: { title: "Demande de paiement", mock: amountScreen("Partager un paiement", [{ t: "field", label: "Montant demandé", value: "" },
                { t: "field", label: "Motif", value: "" }, { t: "button", label: "Partager le lien", hot: true }]), tap: [50, 50],
                say: "Indiquez le montant et le motif, puis partagez la demande par SMS ou WhatsApp : votre contact paie en un clic.", done: true }
        }
    };

    var cartes = {
        id: "cartes", title: "Cartes prépayées et virtuelles", icon: "bi-credit-card-2-front", category: "Cartes", source: "généré", duration: "1 à 2 min",
        summary: "Recharger une carte prépayée, créer une carte virtuelle d'achat ou cadeau et la recharger.", start: "k1",
        steps: {
            k1: home("Onglet Cartes", T.cartes, "En bas de l'écran, appuyez sur « Cartes ».", null),
            k2: { title: "Que voulez-vous faire ?", mock: amountScreen("Cartes", [
                { t: "option", icon: "bi-arrow-repeat", label: "Recharger une carte prépayée" }, { t: "option", icon: "bi-credit-card", label: "Créer une carte virtuelle" },
                { t: "option", icon: "bi-gift", label: "Carte cadeau" }, { t: "option", icon: "bi-lock", label: "Bloquer / débloquer une carte" }]),
                say: "Choisissez l'opération.",
                choices: [{ label: "Recharger une carte prépayée", next: "k3", tap: [50, 24] }, { label: "Créer une carte virtuelle", next: "k4", tap: [50, 35] },
                    { label: "Bloquer une carte", next: "k5", tap: [50, 57] }] },
            k3: { title: "Recharger", mock: amountScreen("Recharger une carte", [{ t: "select", label: "Carte", value: "Carte prépayée •••• 1234" },
                { t: "field", label: "Montant", value: "" }, { t: "button", label: "Recharger", hot: true }]), tap: [50, 50],
                say: "Choisissez la carte, le montant, puis validez avec votre code PIN : le solde de la carte est mis à jour aussitôt.", done: true },
            k4: { title: "Créer une carte virtuelle", mock: amountScreen("Carte virtuelle", [{ t: "card" }, { t: "field", label: "Montant à charger", value: "" },
                { t: "button", label: "Créer la carte", hot: true }]), tap: [50, 72],
                say: "Indiquez le montant à charger et validez : la carte virtuelle est disponible immédiatement pour les achats en ligne.",
                tip: "Argument sécurité : une carte dédiée aux achats en ligne, rechargée du montant voulu seulement.", done: true },
            k5: { title: "Bloquer une carte", mock: amountScreen("Bloquer une carte", [{ t: "select", label: "Carte", value: "•••• 1234" },
                { t: "option", icon: "bi-lock", label: "Bloquer temporairement" }, { t: "button", label: "Confirmer", hot: true }]), tap: [50, 46],
                say: "En cas de perte ou de doute, bloquez la carte immédiatement depuis l'application.", warn: "Perte ou vol : bloquer d'abord, puis contacter le centre de relation client.", done: true }
        }
    };
    cartes.steps.k1.next = "k2";

    var securite = {
        id: "securite", title: "Sécurité : code PIN, empreinte, code oublié", icon: "bi-shield-lock", category: "Sécurité", source: "généré", duration: "1 min",
        summary: "Changer son code PIN, activer l'empreinte digitale, que faire en cas d'oubli.", start: "q1",
        steps: {
            q1: home("Menu Autres", T.autres, "En bas à droite, appuyez sur « Autres ».", null),
            q2: { title: "Paramètres de sécurité", mock: amountScreen("Sécurité", [
                { t: "option", icon: "bi-key", label: "Changer mon code PIN" }, { t: "option", icon: "bi-fingerprint", label: "Connexion par empreinte" },
                { t: "option", icon: "bi-question-circle", label: "Code PIN oublié" }]),
                say: "Choisissez l'action.",
                choices: [{ label: "Changer le code PIN", next: "q3", tap: [50, 24] }, { label: "Activer l'empreinte", next: "q4", tap: [50, 35] }, { label: "Code PIN oublié", next: "q5", tap: [50, 46] }] },
            q3: { title: "Nouveau code PIN", mock: amountScreen("Changer mon code PIN", [{ t: "field", label: "Code PIN actuel", value: "••••••" },
                { t: "field", label: "Nouveau code PIN", value: "••••••" }, { t: "field", label: "Confirmer", value: "••••••" }, { t: "button", label: "Valider", hot: true }]), tap: [50, 64],
                say: "Saisissez l'ancien code, puis deux fois le nouveau (6 chiffres, non séquentiel).", warn: "Jamais communiqué, même à un agent Ecobank.", done: true },
            q4: { title: "Empreinte digitale", img: IMG + "empreinte.jpg", tap: [50, 72],
                say: "Appuyez sur « Activer une empreinte digitale » et posez votre doigt sur le capteur.", done: true },
            q5: { title: "Code PIN oublié", mock: amountScreen("Code PIN oublié", [{ t: "info", text: "Répondez à vos 2 questions de sécurité, puis confirmez avec le code SMS." },
                { t: "field", label: "Réponse 1", value: "" }, { t: "field", label: "Réponse 2", value: "" }, { t: "button", label: "Continuer", hot: true }]), tap: [50, 64],
                say: "Répondez à vos questions de sécurité (choisies à l'inscription), confirmez avec le code SMS, puis créez un nouveau code PIN.", done: true }
        }
    };
    securite.steps.q1.next = "q2";

    // ───────────── Ecobank Mobile : code PIN oublié (réinitialisation) ─────────────

    var reinitPin = {
        id: "reinitialiser-pin", title: "Code PIN oublié : réinitialiser l'accès", icon: "bi-key-fill", category: "Sécurité", source: "généré", duration: "2 à 4 min",
        summary: "Code PIN oublié, application bloquée ou nouveau téléphone : réactiver l'accès avec la carte, le profil Internet ou les questions de sécurité (compte Xpress).",
        start: "r1",
        steps: {
            r1: { title: "Écran de connexion", mock: { title: "Ecobank Mobile", rows: [
                { t: "info", text: "Bon retour ! Saisissez votre code PIN à 6 chiffres." }, { t: "otp", label: "Code PIN" },
                { t: "link", label: "Code PIN oublié ?", hot: true }, { t: "button", label: "Se connecter" }] },
                say: "Sur l'écran de connexion de l'application, appuyez sur « Code PIN oublié ? ».",
                tip: "Si l'application ne propose pas ce lien (ancienne version), mettez-la d'abord à jour depuis le Play Store ou l'App Store.", next: "r2" },
            r2: { title: "Quelle est la situation ?", mock: { title: "Réinitialiser le code PIN", rows: [
                { t: "option", icon: "bi-credit-card", label: "J'ai une carte de débit Ecobank" }, { t: "option", icon: "bi-laptop", label: "J'ai un profil de banque par Internet" },
                { t: "option", icon: "bi-question-circle", label: "Compte Xpress : questions de sécurité" }, { t: "option", icon: "bi-phone", label: "Nouveau téléphone / application réinstallée" },
                { t: "option", icon: "bi-lock", label: "Application bloquée" }] },
                say: "Choisissez comment vous allez prouver votre identité.",
                choices: [
                    { label: "J'ai une carte de débit Ecobank", next: "r3", hint: "Numéro de carte, date d'expiration et CVV, saisis par le client lui-même." },
                    { label: "J'ai un profil de banque par Internet", next: "r4", hint: "Identifiants Ecobank Online." },
                    { label: "Compte Xpress (sans carte)", next: "r5", hint: "Réponses aux questions de sécurité choisies à l'ouverture." },
                    { label: "Nouveau téléphone / application réinstallée", next: "r9", hint: "Réactivation complète sur le nouvel appareil." },
                    { label: "Application bloquée", next: "r10", hint: "Trop de codes erronés." }] },
            r3: { title: "Vérification par la carte", img: IMG + "activer-carte-debit.jpg", tap: [50, 91],
                say: "Saisissez le numéro de votre carte de débit, sa date d'expiration et les 3 chiffres du CVV au dos, puis « Continuer ».",
                warn: "Le client saisit lui-même ces données : ne jamais se les faire dicter.", next: "r6" },
            r4: { title: "Vérification par le profil Internet", img: IMG + "activer-profil-internet.jpg", tap: [50, 90],
                say: "Saisissez votre nom d'utilisateur et votre mot de passe Ecobank Online, puis « Continuer ».",
                tip: "Mot de passe Ecobank Online oublié aussi ? Le réinitialiser d'abord (pas à pas « Ecobank Online → Mot de passe oublié »).", next: "r6" },
            r5: { title: "Questions de sécurité", img: IMG + "questions-securite.jpg", tap: [50, 90],
                say: "Répondez aux questions de sécurité que vous avez choisies à l'ouverture de votre compte Xpress, puis « Continuer ».",
                tip: "Réponses oubliées : le centre de relation client ou une agence réinitialise l'accès après vérification de l'identité (pièce d'identité).", next: "r6" },
            r6: { title: "Code de vérification (SMS)", img: IMG + "otp.jpg", tap: [50, 35],
                say: "Un code est envoyé par SMS au numéro enregistré : il est détecté automatiquement, sinon saisissez-le.",
                tip: "Pas de SMS : vérifier le réseau, attendre la fin du compte à rebours puis « Renvoyer le code ». Le numéro doit être celui du compte.", next: "r7" },
            r7: { title: "Nouveau code PIN", img: IMG + "code-pin.jpg", tap: [50, 91],
                say: "Créez votre nouveau code PIN à 6 chiffres, confirmez-le, puis « Continuer ».",
                warn: "Pas de date de naissance ni de suite (123456, 111111). Le code ne se communique jamais, même à un agent Ecobank.", next: "r8" },
            r8: { title: "Accès rétabli", mock: { title: "", rows: [{ t: "success", text: "Code PIN modifié" }, { t: "info", text: "Vous pouvez réactiver la connexion par empreinte dans Autres → Sécurité." },
                { t: "button", label: "Se connecter", hot: true }] },
                say: "C'est terminé : connectez-vous avec votre nouveau code PIN.", done: true },
            r9: { title: "Nouveau téléphone : réactiver", img: IMG + "activer-pays-numero.jpg", tap: [50, 91],
                say: "Installez Ecobank Mobile sur le nouveau téléphone, choisissez le pays et saisissez le numéro enregistré sur le compte. L'application reconnaît votre profil : vérifiez ensuite votre identité.",
                tip: "Le numéro de téléphone doit être le même : en cas de changement de numéro, mise à jour en agence avant la réactivation.",
                choices: [{ label: "Avec la carte de débit", next: "r3" }, { label: "Avec le profil Internet", next: "r4" }, { label: "Compte Xpress (questions de sécurité)", next: "r5" }] },
            r10: { title: "Application bloquée", mock: { title: "Accès bloqué", rows: [
                { t: "info", text: "Trop de codes PIN incorrects : l'accès est suspendu par sécurité." },
                { t: "option", icon: "bi-arrow-repeat", label: "Réinitialiser mon code PIN", hot: true }, { t: "option", icon: "bi-telephone", label: "Contacter Ecobank" }] },
                say: "L'accès est bloqué après plusieurs codes erronés. Réinitialisez le code PIN (carte, profil Internet ou questions de sécurité) ; si le blocage persiste, le centre de relation client débloque l'accès après vérification de votre identité.",
                warn: "Ne jamais demander le code PIN ni le code SMS du client pour l'aider.",
                choices: [{ label: "Réinitialiser maintenant", next: "r2" }, { label: "Toujours bloqué : contacter le centre", next: "r11" }] },
            r11: { title: "Déblocage par le centre de relation client", mock: { title: "Contacter Ecobank", rows: [
                { t: "scene", icon: "bi-headset", text: "Vérification d'identité par le conseiller", sub: "Nom, numéro de compte, questions de contrôle — jamais le code PIN ni le code SMS." },
                { t: "button", label: "Appeler le centre de relation client", hot: true }] },
                say: "Le conseiller vérifie votre identité puis débloque l'accès ; vous recréez ensuite votre code PIN depuis l'application.", done: true }
        }
    };

    // ───────────── Ecobank Online (banque par Internet) ─────────────

    var OL = "/images/pas-a-pas/ecobank-online/";
    var SITE = OL + "acces-ecobank-com.jpg";
    function W(title, rows, app) { return { title: title, rows: rows, app: app }; }
    var NAV = ["Comptes", "Virements", "Paiements", "Cartes", "Demandes", "Profil"];
    function login(hotLink) {
        return W("Se connecter", [{ t: "field", label: "Nom d'utilisateur", value: "", hot: !hotLink }, { t: "field", label: "Mot de passe", value: "••••••••" },
            { t: "captcha", label: "Recopiez le code de sécurité" }, { t: "button", label: "Se connecter" },
            { t: "link", label: "Mot de passe oublié ?", hot: hotLink === "mdp" }, { t: "link", label: "Première connexion / S'inscrire", hot: hotLink === "insc" }]);
    }
    function otpStep(next, extra) {
        return { title: "Code de vérification (OTP)", mock: W("Vérification", [{ t: "sms", text: "Votre code Ecobank est 4•••••. Ne le communiquez à personne, même à un agent Ecobank." },
            { t: "otp", label: "Code à usage unique", hot: true }, { t: "button", label: "Valider" }]),
            say: "Saisissez le code à usage unique (OTP) reçu par SMS ou par e-mail pour valider." + (extra || ""),
            warn: "Ce code ne se communique jamais : Ecobank ne le demande ni par téléphone, ni par SMS, ni par e-mail.", next: next };
    }

    var olAcces = {
        id: "connexion", title: "Accéder à Ecobank Online et se connecter", icon: "bi-box-arrow-in-right", category: "Démarrer", source: "généré", duration: "1 min",
        summary: "Depuis ecobank.com : Connexion → Ecobank Online, identifiants, code de sécurité, code SMS, tableau de bord.", start: "a1",
        steps: {
            a1: { title: "Ouvrir ecobank.com", img: SITE, tap: [59.8, 10.4],
                say: "Sur votre ordinateur, ouvrez le site ecobank.com (Côte d'Ivoire) et cliquez sur « Connexion » en haut de la page.",
                tip: "Taper l'adresse soi-même ou passer par un favori — jamais par un lien reçu par SMS, WhatsApp ou e-mail.", next: "a2" },
            a2: { title: "Choisir « Ecobank Online »", img: SITE, tap: [88.2, 43.4],
                say: "Dans le bloc « Connexion » à droite, cliquez sur le bouton « Ecobank Online » : c'est la banque par Internet des particuliers.",
                tip: "Omni Lite et Omni Plus sont les plateformes des entreprises ; EcobankPay sert aux paiements marchands (voir « Quel accès choisir ? »).", next: "a3" },
            a3: { title: "Identifiants et code de sécurité", mock: login(),
                say: "Saisissez votre nom d'utilisateur et votre mot de passe, recopiez le code de sécurité affiché (captcha), puis cliquez sur « Se connecter ».",
                tip: "Code de sécurité illisible : cliquez sur les flèches pour en afficher un autre. Vérifiez que le verrou du navigateur est fermé (https).",
                choices: [{ label: "Connexion normale", next: "a4" }, { label: "Mot de passe oublié", next: "a6", hint: "Suivre le pas à pas « Mot de passe oublié ou compte bloqué »." },
                    { label: "Première connexion", next: "a7", hint: "Suivre le pas à pas « Première connexion et activation »." }] },
            a4: otpStep("a5"),
            a5: { title: "Tableau de bord", mock: W("Tableau de bord", [{ t: "nav", items: NAV, active: 0 }, { t: "balance", label: "Compte courant — solde disponible", value: "•••••• XOF" },
                { t: "table", head: ["Date", "Opération", "Montant"], rows: [["28/09", "Virement reçu", "+ ••••"], ["27/09", "Paiement facture", "- ••••"], ["25/09", "Retrait GAB", "- ••••"]] }]),
                say: "Vous êtes connecté : le tableau de bord affiche vos comptes, vos soldes et vos dernières opérations. Le menu donne accès aux virements, paiements, cartes et demandes.",
                tip: "À la fin, cliquez « Déconnexion » — surtout sur un ordinateur partagé ou dans un cybercafé.", done: true },
            a6: { title: "Mot de passe oublié", mock: login("mdp"),
                say: "Cliquez sur « Mot de passe oublié ? » sous le formulaire et suivez les étapes (nom d'utilisateur, code SMS, questions de sécurité, nouveau mot de passe).", done: true },
            a7: { title: "Première connexion", mock: login("insc"),
                say: "Pour une première connexion, utilisez les identifiants provisoires reçus ou cliquez « Première connexion / S'inscrire » : le pas à pas « Première connexion et activation » détaille chaque écran.", done: true }
        }
    };

    var olActivation = {
        id: "activation", title: "Première connexion et activation", icon: "bi-person-plus", category: "Démarrer", source: "généré", duration: "3 à 5 min",
        summary: "Identifiants provisoires reçus ou inscription en ligne : mot de passe personnel, questions de sécurité, image de sécurité.", start: "v1",
        steps: {
            v1: { title: "D'où viennent vos identifiants ?", mock: W("Bienvenue sur Ecobank Online", [{ t: "info", text: "Accès gratuit pour les titulaires d'un compte Ecobank." },
                { t: "option", icon: "bi-envelope-paper", label: "J'ai reçu mes identifiants (agence ou e-mail)" }, { t: "option", icon: "bi-person-plus", label: "Je n'ai pas encore d'identifiants" }]),
                say: "Le client est titulaire d'un compte Ecobank : l'accès à Ecobank Online est gratuit.",
                choices: [{ label: "Identifiants reçus (agence ou e-mail)", next: "v2" }, { label: "Pas encore d'identifiants", next: "v6", hint: "Inscription en ligne avec le numéro de compte et le téléphone enregistré, sinon en agence." }] },
            v2: { title: "Connexion avec le mot de passe provisoire", mock: W("Se connecter", [{ t: "field", label: "Nom d'utilisateur", value: "" }, { t: "field", label: "Mot de passe provisoire", value: "••••••••" },
                { t: "captcha" }, { t: "button", label: "Se connecter", hot: true }]),
                say: "Connectez-vous avec le nom d'utilisateur et le mot de passe provisoire reçus.", tip: "Le mot de passe provisoire n'est valable qu'un temps limité : l'utiliser rapidement.", next: "v3" },
            v3: { title: "Créer son mot de passe", mock: W("Nouveau mot de passe", [{ t: "field", label: "Mot de passe actuel / provisoire", value: "••••••••" },
                { t: "field", label: "Nouveau mot de passe", value: "" }, { t: "field", label: "Confirmer", value: "" },
                { t: "info", text: "Au moins 8 caractères : majuscule, minuscule, chiffre et caractère spécial." }, { t: "button", label: "Valider", hot: true }]),
                say: "Choisissez votre mot de passe personnel et confirmez-le.",
                warn: "Ne pas réutiliser le mot de passe d'une messagerie ou d'un réseau social ; ne jamais l'écrire près de l'ordinateur.", next: "v4" },
            v4: { title: "Questions de sécurité", mock: W("Questions de sécurité", [{ t: "select", label: "Question 1", value: "Nom de votre premier animal ?" }, { t: "field", label: "Réponse 1", value: "" },
                { t: "select", label: "Question 2", value: "Ville de naissance de votre mère ?" }, { t: "field", label: "Réponse 2", value: "" },
                { t: "select", label: "Question 3", value: "Nom de votre école primaire ?" }, { t: "field", label: "Réponse 3", value: "" }, { t: "button", label: "Enregistrer", hot: true }]),
                say: "Choisissez vos questions de sécurité et des réponses dont vous vous souviendrez : elles serviront si vous oubliez votre mot de passe.", next: "v5" },
            v5: { title: "Image et phrase de sécurité", mock: W("Image de sécurité", [{ t: "tiles", items: [["bi-tree", "Arbre"], ["bi-airplane", "Avion", true], ["bi-flower1", "Fleur"], ["bi-bicycle", "Vélo"], ["bi-cup-hot", "Café"], ["bi-music-note-beamed", "Musique"]] },
                { t: "field", label: "Phrase de sécurité", value: "" }, { t: "button", label: "Terminer", hot: true }]),
                say: "Choisissez une image et une phrase de sécurité : elles s'afficheront à chaque connexion.",
                warn: "Si l'image ou la phrase affichée n'est pas la vôtre, ne saisissez pas votre mot de passe : vous êtes peut-être sur un faux site.", next: "v8" },
            v6: { title: "S'inscrire en ligne", mock: W("Première connexion / S'inscrire", [{ t: "field", label: "Numéro de compte Ecobank", value: "" },
                { t: "field", label: "Téléphone enregistré à la banque", value: "+225 •• •• •• ••" }, { t: "field", label: "E-mail", value: "" }, { t: "captcha" }, { t: "button", label: "Continuer", hot: true }]),
                say: "Sur la page de connexion, cliquez « Première connexion / S'inscrire », puis saisissez votre numéro de compte et le téléphone ou l'e-mail enregistré à la banque.",
                tip: "Le téléphone ou l'e-mail doit être celui connu de la banque : s'il a changé, le mettre à jour en agence d'abord. Inscription impossible en ligne : l'agence crée l'accès.", next: "v7" },
            v7: otpStep("v7b", " Puis choisissez votre nom d'utilisateur."),
            v7b: { title: "Choisir son nom d'utilisateur", mock: W("Identifiants", [{ t: "field", label: "Nom d'utilisateur", value: "", hot: true }, { t: "info", text: "Lettres et chiffres, sans espace. Il vous sera demandé à chaque connexion." },
                { t: "button", label: "Continuer" }]), say: "Choisissez votre nom d'utilisateur, puis créez votre mot de passe.", next: "v3" },
            v8: { title: "Activation terminée", mock: W("", [{ t: "success", text: "Ecobank Online est activé" }, { t: "button", label: "Accéder à mes comptes", hot: true }]),
                say: "Votre accès est activé : vous arrivez sur votre tableau de bord.", done: true }
        }
    };

    var olMdp = {
        id: "mot-de-passe-oublie", title: "Mot de passe oublié ou compte bloqué", icon: "bi-key", category: "Sécurité", source: "généré", duration: "2 à 3 min",
        summary: "Réinitialiser le mot de passe (code SMS + questions de sécurité), nom d'utilisateur oublié, accès bloqué après plusieurs essais.", start: "m1",
        steps: {
            m1: { title: "« Mot de passe oublié ? »", mock: login("mdp"), say: "Sur la page de connexion, cliquez sur « Mot de passe oublié ? ».", next: "m2" },
            m2: { title: "Identifier le compte", mock: W("Mot de passe oublié", [{ t: "field", label: "Nom d'utilisateur", value: "", hot: true },
                { t: "field", label: "E-mail ou téléphone enregistré", value: "" }, { t: "captcha" }, { t: "button", label: "Continuer" }]),
                say: "Saisissez votre nom d'utilisateur et l'e-mail (ou le téléphone) enregistré, recopiez le code de sécurité, puis « Continuer ».",
                choices: [{ label: "Le client connaît son nom d'utilisateur", next: "m3" }, { label: "Nom d'utilisateur oublié aussi", next: "m7" },
                    { label: "Message « accès bloqué »", next: "m8", hint: "Trop de tentatives incorrectes." }] },
            m3: otpStep("m4"),
            m4: { title: "Questions de sécurité", mock: W("Vérification d'identité", [{ t: "field", label: "Nom de votre premier animal ?", value: "" },
                { t: "field", label: "Ville de naissance de votre mère ?", value: "" }, { t: "button", label: "Continuer", hot: true }]),
                say: "Répondez aux questions de sécurité choisies lors de l'activation.",
                tip: "Réponses oubliées : la réinitialisation se fait par le centre de relation client ou en agence, après vérification de l'identité.", next: "m5" },
            m5: { title: "Nouveau mot de passe", mock: W("Nouveau mot de passe", [{ t: "field", label: "Nouveau mot de passe", value: "" }, { t: "field", label: "Confirmer", value: "" },
                { t: "info", text: "Au moins 8 caractères : majuscule, minuscule, chiffre et caractère spécial. Différent des anciens." }, { t: "button", label: "Valider", hot: true }]),
                say: "Créez un nouveau mot de passe et confirmez-le.", next: "m6" },
            m6: { title: "Mot de passe réinitialisé", mock: W("", [{ t: "success", text: "Mot de passe modifié" }, { t: "button", label: "Se connecter", hot: true }]),
                say: "C'est fait : reconnectez-vous avec le nouveau mot de passe.", done: true },
            m7: { title: "Nom d'utilisateur oublié", mock: W("Nom d'utilisateur oublié", [{ t: "field", label: "Numéro de compte", value: "" }, { t: "field", label: "E-mail enregistré", value: "" },
                { t: "captcha" }, { t: "button", label: "Recevoir mon nom d'utilisateur", hot: true }]),
                say: "Utilisez « Nom d'utilisateur oublié ? » lorsque le lien est proposé : il est renvoyé à l'e-mail enregistré. Sinon, le centre de relation client ou l'agence le communique après vérification de votre identité.",
                tip: "Vérifier l'identité du client (questions de contrôle) avant toute aide — sans jamais demander de mot de passe.", next: "m3" },
            m8: { title: "Accès bloqué", mock: W("Accès suspendu", [{ t: "info", text: "Votre accès est suspendu après plusieurs tentatives incorrectes." },
                { t: "scene", icon: "bi-headset", text: "Déblocage par le centre de relation client ou en agence", sub: "Après vérification de l'identité du titulaire." }]),
                say: "Par sécurité, l'accès est bloqué après plusieurs essais incorrects. Le centre de relation client ou l'agence le débloque après vérification de votre identité ; vous définissez ensuite un nouveau mot de passe.",
                warn: "Ne jamais demander le mot de passe ni le code SMS du client pour le débloquer.", done: true }
        }
    };

    var olComptes = {
        id: "comptes-releves", title: "Soldes, historique et relevés", icon: "bi-wallet2", category: "Comptes", source: "généré", duration: "1 min",
        summary: "Consulter ses comptes, rechercher une opération, télécharger un relevé (PDF ou Excel).", start: "c1",
        steps: {
            c1: { title: "Menu « Comptes »", mock: W("Mes comptes", [{ t: "nav", items: NAV, active: 0 }, { t: "option", icon: "bi-wallet2", label: "Compte courant", sub: "Solde disponible •••••• XOF", hot: true },
                { t: "option", icon: "bi-piggy-bank", label: "Compte épargne", sub: "Solde •••••• XOF" }, { t: "option", icon: "bi-phone", label: "Compte Xpress", sub: "Solde •••••• XOF" }]),
                say: "Dans « Comptes », cliquez sur le compte à consulter.", next: "c2" },
            c2: { title: "Détail et historique", mock: W("Compte courant", [{ t: "balance", label: "Solde disponible", value: "•••••• XOF" },
                { t: "select", label: "Rechercher", value: "Du 01/09 au 30/09 · tous montants" },
                { t: "table", head: ["Date", "Libellé", "Montant"], rows: [["28/09", "Virement de …", "+ ••••"], ["27/09", "Facture électricité", "- ••••"], ["25/09", "Retrait GAB", "- ••••"]] }]),
                say: "Le détail affiche le solde disponible et l'historique. Filtrez par période ou par montant pour retrouver une opération ; cliquez une ligne pour son détail.", next: "c3" },
            c3: { title: "Télécharger un relevé", mock: W("Relevé de compte", [{ t: "select", label: "Période", value: "Du 01/09 au 30/09" }, { t: "select", label: "Format", value: "PDF" },
                { t: "button", label: "Télécharger", hot: true }]),
                say: "Pour un relevé, choisissez la période et le format (PDF ou Excel), puis « Télécharger ».",
                tip: "Une attestation ou un relevé certifié (cachet de la banque) se demande dans « Demandes » ou en agence.", done: true }
        }
    };

    var olVirement = {
        id: "virement", title: "Faire un virement", icon: "bi-arrow-left-right", category: "Virements", source: "généré", duration: "2 min",
        summary: "Entre ses comptes, vers un client Ecobank, vers une autre banque, vers l'Afrique ou l'international, ou à plusieurs bénéficiaires.", start: "t1",
        steps: {
            t1: { title: "Menu « Virements »", mock: W("Virements", [{ t: "nav", items: NAV, active: 1 }, { t: "option", icon: "bi-arrow-repeat", label: "Entre mes comptes" },
                { t: "option", icon: "bi-person-check", label: "Vers un compte Ecobank" }, { t: "option", icon: "bi-bank", label: "Vers une autre banque (local)" },
                { t: "option", icon: "bi-globe-europe-africa", label: "Afrique et international" }, { t: "option", icon: "bi-people", label: "Virement groupé (plusieurs bénéficiaires)" }]),
                say: "Ouvrez « Virements » et choisissez le type de virement.",
                choices: [{ label: "Entre mes comptes", next: "t2" }, { label: "Vers un compte Ecobank", next: "t3" }, { label: "Vers une autre banque (local)", next: "t3" },
                    { label: "Afrique et international", next: "t4" }, { label: "Virement groupé", next: "t5" }] },
            t2: { title: "Comptes et montant", mock: W("Entre mes comptes", [{ t: "select", label: "Compte à débiter", value: "Compte courant" }, { t: "select", label: "Compte à créditer", value: "Compte épargne" },
                { t: "field", label: "Montant", value: "XOF" }, { t: "field", label: "Motif", value: "" }, { t: "button", label: "Continuer", hot: true }]),
                say: "Choisissez le compte à débiter, le compte à créditer, le montant et le motif, puis « Continuer ».", next: "t6" },
            t3: { title: "Bénéficiaire et montant", mock: W("Nouveau virement", [{ t: "select", label: "Bénéficiaire", value: "Choisir ou ajouter un bénéficiaire" },
                { t: "field", label: "Banque · numéro de compte (RIB)", value: "" }, { t: "field", label: "Montant", value: "XOF" }, { t: "field", label: "Motif", value: "" }, { t: "button", label: "Continuer", hot: true }]),
                say: "Choisissez un bénéficiaire enregistré (ou ajoutez-le), saisissez le montant et le motif, puis « Continuer ».",
                tip: "Vérifier le nom du bénéficiaire affiché : un virement validé ne s'annule pas.", next: "t6" },
            t4: { title: "Pays, bénéficiaire et frais", mock: W("Afrique et international", [{ t: "select", label: "Pays de destination", value: "Sénégal" },
                { t: "select", label: "Service", value: "Compte Ecobank / Rapidtransfer" }, { t: "field", label: "Bénéficiaire", value: "" }, { t: "field", label: "Montant", value: "" },
                { t: "info", text: "Frais et taux de change affichés avant la validation." }, { t: "button", label: "Continuer", hot: true }]),
                say: "Choisissez le pays et le service, le bénéficiaire et le montant : les frais et le taux de change s'affichent avant de valider.",
                tip: "Virement international hors réseau Ecobank : justificatifs parfois demandés (réglementation des changes).", next: "t6" },
            t5: { title: "Virement groupé", mock: W("Virement groupé", [{ t: "option", icon: "bi-plus-circle", label: "Ajouter des bénéficiaires un par un" }, { t: "option", icon: "bi-file-earmark-spreadsheet", label: "Importer un fichier (modèle fourni)" },
                { t: "summary", items: [["Bénéficiaires", "12"], ["Total", "•••••• XOF"]] }, { t: "button", label: "Continuer", hot: true }]),
                say: "Ajoutez les bénéficiaires un par un ou importez le fichier modèle, vérifiez le total, puis « Continuer ».", next: "t6" },
            t6: { title: "Vérifier le récapitulatif", mock: W("Confirmation", [{ t: "summary", items: [["Bénéficiaire", "Nom affiché"], ["Montant", "XOF"], ["Frais", "selon la grille en vigueur"], ["Date", "Immédiat / programmé"]] },
                { t: "button", label: "Confirmer", hot: true }]),
                say: "Vérifiez le récapitulatif (bénéficiaire, montant, frais, date) puis « Confirmer ».", next: "t7" },
            t7: otpStep("t8"),
            t8: { title: "Virement envoyé", mock: W("", [{ t: "success", text: "Virement effectué" }, { t: "button", label: "Télécharger le reçu", hot: true }]),
                say: "Le virement est enregistré : téléchargez ou imprimez le reçu.",
                tip: "Virement vers une autre banque : délai de traitement interbancaire ; le reçu sert de preuve en cas de réclamation.", done: true }
        }
    };

    var olBenef = {
        id: "beneficiaires", title: "Ajouter et gérer ses bénéficiaires", icon: "bi-person-lines-fill", category: "Virements", source: "généré", duration: "1 min",
        summary: "Enregistrer un bénéficiaire (validé par code SMS), le modifier ou le supprimer.", start: "b1",
        steps: {
            b1: { title: "Liste des bénéficiaires", mock: W("Bénéficiaires", [{ t: "nav", items: NAV, active: 1 }, { t: "option", icon: "bi-person", label: "KOUAME Ange", sub: "Ecobank · ••••4521" },
                { t: "option", icon: "bi-person", label: "SARL Bâtir", sub: "Autre banque · ••••9910" }, { t: "button", label: "+ Ajouter un bénéficiaire", hot: true }]),
                say: "Dans « Virements → Bénéficiaires », cliquez « Ajouter un bénéficiaire ».", next: "b2" },
            b2: { title: "Coordonnées", mock: W("Nouveau bénéficiaire", [{ t: "select", label: "Type", value: "Ecobank / autre banque / international" },
                { t: "field", label: "Nom du bénéficiaire", value: "" }, { t: "field", label: "Banque et numéro de compte (RIB)", value: "" }, { t: "field", label: "Surnom (facultatif)", value: "" },
                { t: "button", label: "Enregistrer", hot: true }]),
                say: "Saisissez le nom et le RIB du bénéficiaire tels qu'ils figurent sur son relevé d'identité bancaire, puis « Enregistrer ».",
                tip: "Copier le RIB depuis le document du bénéficiaire évite les erreurs de saisie.", next: "b3" },
            b3: otpStep("b4"),
            b4: { title: "Bénéficiaire enregistré", mock: W("", [{ t: "success", text: "Bénéficiaire ajouté" }, { t: "info", text: "Il apparaît désormais dans la liste au moment d'un virement." }]),
                say: "Le bénéficiaire est enregistré. Pour le modifier ou le supprimer, ouvrez-le dans la liste.",
                warn: "Une personne qui demande d'ajouter « en urgence » un nouveau bénéficiaire peut être un fraudeur : vérifiez toujours.", done: true }
        }
    };

    var olPaiements = {
        id: "paiements", title: "Payer une facture ou acheter du crédit", icon: "bi-receipt", category: "Paiements", source: "généré", duration: "1 min",
        summary: "Factures (électricité, eau, TV, scolarité…) et recharges téléphoniques depuis le compte.", start: "p1",
        steps: {
            p1: { title: "Menu « Paiements »", mock: W("Paiements", [{ t: "nav", items: NAV, active: 2 }, { t: "tiles", items: [["bi-lightning-charge", "Électricité", true], ["bi-droplet", "Eau"], ["bi-tv", "Télévision"], ["bi-mortarboard", "Scolarité"], ["bi-phone", "Crédit téléphone"], ["bi-three-dots", "Autres"]] }]),
                say: "Ouvrez « Paiements » et choisissez la catégorie (électricité, eau, télévision, scolarité, crédit téléphonique…).",
                choices: [{ label: "Payer une facture", next: "p2" }, { label: "Acheter du crédit téléphonique", next: "p3" }] },
            p2: { title: "Référence de la facture", mock: W("Payer une facture", [{ t: "select", label: "Facturier", value: "Choisir le fournisseur" }, { t: "field", label: "Référence / numéro d'abonné", value: "" },
                { t: "select", label: "Compte à débiter", value: "Compte courant" }, { t: "button", label: "Rechercher la facture", hot: true }]),
                say: "Choisissez le fournisseur, saisissez la référence ou le numéro d'abonné : le montant dû s'affiche. Vérifiez le nom de l'abonné, puis continuez.", next: "p4" },
            p3: { title: "Numéro et montant", mock: W("Crédit téléphonique", [{ t: "select", label: "Opérateur", value: "Orange / MTN / Moov" }, { t: "field", label: "Numéro", value: "" },
                { t: "field", label: "Montant", value: "XOF" }, { t: "button", label: "Continuer", hot: true }]),
                say: "Choisissez l'opérateur, saisissez le numéro et le montant, puis « Continuer ».", next: "p4" },
            p4: otpStep("p5"),
            p5: { title: "Paiement effectué", mock: W("", [{ t: "success", text: "Paiement effectué" }, { t: "button", label: "Télécharger le reçu", hot: true }]),
                say: "Le paiement est fait : gardez le reçu, il fait foi auprès du fournisseur.", done: true }
        }
    };

    var olCartes = {
        id: "cartes", title: "Gérer ses cartes", icon: "bi-credit-card", category: "Cartes", source: "généré", duration: "1 min",
        summary: "Bloquer ou débloquer une carte, signaler une perte ou un vol, demander une nouvelle carte.", start: "k1",
        steps: {
            k1: { title: "Menu « Cartes »", mock: W("Mes cartes", [{ t: "nav", items: NAV, active: 3 }, { t: "card" }, { t: "option", icon: "bi-lock", label: "Bloquer temporairement" },
                { t: "option", icon: "bi-unlock", label: "Débloquer" }, { t: "option", icon: "bi-exclamation-octagon", label: "Signaler une perte ou un vol" }, { t: "option", icon: "bi-plus-square", label: "Demander une carte" }]),
                say: "Ouvrez « Cartes » et choisissez la carte, puis l'action.",
                choices: [{ label: "Bloquer temporairement", next: "k2" }, { label: "Signaler une perte ou un vol", next: "k3" }, { label: "Demander une carte", next: "k4" }] },
            k2: { title: "Blocage temporaire", mock: W("Bloquer la carte", [{ t: "info", text: "La carte est refusée tant qu'elle est bloquée. Vous pouvez la débloquer à tout moment." }, { t: "button", label: "Bloquer", hot: true }]),
                say: "Confirmez le blocage : la carte est refusée jusqu'au déblocage. Pratique si la carte est égarée.", done: true },
            k3: { title: "Perte ou vol", mock: W("Opposition", [{ t: "select", label: "Motif", value: "Perte / vol" }, { t: "info", text: "L'opposition est définitive : une nouvelle carte sera à demander." }, { t: "button", label: "Confirmer l'opposition", hot: true }]),
                say: "Confirmez l'opposition : elle est définitive. Demandez ensuite une nouvelle carte.",
                warn: "En cas d'opérations non reconnues, le signaler aussitôt au centre de relation client (réclamation).", done: true },
            k4: { title: "Demande de carte", mock: W("Nouvelle carte", [{ t: "select", label: "Type de carte", value: "Visa Classic / Gold / prépayée" }, { t: "select", label: "Compte associé", value: "Compte courant" },
                { t: "select", label: "Agence de retrait", value: "Choisir l'agence" }, { t: "button", label: "Envoyer la demande", hot: true }]),
                say: "Choisissez le type de carte, le compte associé et l'agence de retrait, puis envoyez la demande.",
                tip: "Conditions et tarifs selon la grille en vigueur ; le client est prévenu quand la carte est disponible.", done: true }
        }
    };

    var olDemandes = {
        id: "demandes", title: "Demandes : chéquier, attestation, RIB", icon: "bi-envelope-paper", category: "Services", source: "généré", duration: "1 min",
        summary: "Commander un chéquier, demander une attestation ou un relevé certifié, obtenir son RIB.", start: "d1",
        steps: {
            d1: { title: "Menu « Demandes »", mock: W("Demandes", [{ t: "nav", items: NAV, active: 4 }, { t: "option", icon: "bi-journal-text", label: "Commander un chéquier" },
                { t: "option", icon: "bi-file-earmark-check", label: "Attestation / relevé certifié" }, { t: "option", icon: "bi-123", label: "Mon RIB" }, { t: "option", icon: "bi-clock-history", label: "Suivre mes demandes" }]),
                say: "Ouvrez « Demandes » et choisissez le service.",
                choices: [{ label: "Commander un chéquier", next: "d2" }, { label: "Attestation ou relevé certifié", next: "d3" }, { label: "Mon RIB", next: "d4" }] },
            d2: { title: "Chéquier", mock: W("Commander un chéquier", [{ t: "select", label: "Compte", value: "Compte courant" }, { t: "select", label: "Nombre de chèques", value: "25" },
                { t: "select", label: "Agence de retrait", value: "Choisir l'agence" }, { t: "button", label: "Envoyer", hot: true }]),
                say: "Choisissez le compte, le format et l'agence de retrait, puis envoyez la demande.", next: "d5" },
            d3: { title: "Attestation", mock: W("Attestation", [{ t: "select", label: "Type", value: "Attestation de solde / de domiciliation / relevé certifié" }, { t: "field", label: "Motif", value: "" },
                { t: "button", label: "Envoyer", hot: true }]), say: "Choisissez le type d'attestation et précisez le motif, puis envoyez.", next: "d5" },
            d4: { title: "Relevé d'identité bancaire", mock: W("Mon RIB", [{ t: "summary", items: [["Banque", "Ecobank Côte d'Ivoire"], ["Code banque · guichet", "CI059 · •••••"], ["Compte · clé", "•••••••••••• · ••"]] },
                { t: "button", label: "Télécharger le RIB (PDF)", hot: true }]),
                say: "Votre RIB s'affiche : téléchargez-le en PDF pour le transmettre (employeur, bénéficiaire…).", done: true },
            d5: { title: "Suivi de la demande", mock: W("Suivre mes demandes", [{ t: "table", head: ["Date", "Demande", "Statut"], rows: [["01/10", "Chéquier 25", "En cours"], ["20/09", "Attestation", "Disponible"]] }]),
                say: "Suivez l'avancement dans « Suivre mes demandes » ; vous êtes prévenu quand c'est prêt.", tip: "Délais et frais selon la grille en vigueur.", done: true }
        }
    };

    var olProfil = {
        id: "profil-securite", title: "Profil et sécurité en ligne", icon: "bi-shield-check", category: "Sécurité", source: "généré", duration: "1 min",
        summary: "Changer son mot de passe, ses questions de sécurité, vérifier ses coordonnées ; les réflexes anti-fraude.", start: "s1",
        steps: {
            s1: { title: "Menu « Profil »", mock: W("Profil", [{ t: "nav", items: NAV, active: 5 }, { t: "option", icon: "bi-key", label: "Changer mon mot de passe" },
                { t: "option", icon: "bi-question-circle", label: "Questions de sécurité" }, { t: "option", icon: "bi-telephone", label: "Mes coordonnées (téléphone, e-mail)" }, { t: "option", icon: "bi-box-arrow-right", label: "Déconnexion" }]),
                say: "Ouvrez « Profil » pour gérer votre sécurité.",
                choices: [{ label: "Changer le mot de passe", next: "s2" }, { label: "Coordonnées à mettre à jour", next: "s3" }, { label: "Réflexes anti-fraude", next: "s4" }] },
            s2: { title: "Changer le mot de passe", mock: W("Changer mon mot de passe", [{ t: "field", label: "Mot de passe actuel", value: "••••••••" }, { t: "field", label: "Nouveau mot de passe", value: "" },
                { t: "field", label: "Confirmer", value: "" }, { t: "button", label: "Valider", hot: true }]),
                say: "Saisissez le mot de passe actuel puis deux fois le nouveau, et validez avec le code SMS.", done: true },
            s3: { title: "Téléphone et e-mail", mock: W("Mes coordonnées", [{ t: "field", label: "Téléphone", value: "+225 •• •• •• ••" }, { t: "field", label: "E-mail", value: "•••@•••" },
                { t: "info", text: "Les codes SMS et e-mails de sécurité sont envoyés à ces coordonnées." }]),
                say: "Vérifiez que téléphone et e-mail sont à jour : c'est là qu'arrivent les codes de sécurité.",
                tip: "La modification du numéro de téléphone se fait en agence (pièce d'identité) : c'est une protection contre la fraude.", done: true },
            s4: { title: "Réflexes anti-fraude", mock: W("Sécurité", [{ t: "info", text: "Ecobank ne vous demandera JAMAIS votre mot de passe, code PIN ou code SMS." },
                { t: "option", icon: "bi-link-45deg", label: "Taper l'adresse ecobank.com soi-même" }, { t: "option", icon: "bi-image", label: "Vérifier son image de sécurité" },
                { t: "option", icon: "bi-pc-display", label: "Pas d'ordinateur public ; se déconnecter" }, { t: "option", icon: "bi-bell", label: "Activer les alertes SMS / e-mail" }]),
                say: "Ecobank ne demande jamais vos codes. Tapez l'adresse vous-même, vérifiez votre image de sécurité, déconnectez-vous et activez les alertes.",
                warn: "Message suspect ou opération inconnue : changer le mot de passe et appeler immédiatement le centre de relation client.", done: true }
        }
    };

    var olOrientation = {
        id: "quel-acces", title: "Quel accès choisir ? (Ecobank Online, Omni, EcobankPay)", icon: "bi-signpost-split", category: "Démarrer", source: "généré", duration: "30 s",
        summary: "Orienter le client vers la bonne plateforme selon son profil : particulier, petite entreprise, grande entreprise, marchand.", start: "o1",
        steps: {
            o1: { title: "Le bloc « Connexion » d'ecobank.com", img: SITE,
                say: "Sur ecobank.com, le bloc « Connexion » propose plusieurs accès selon le profil du client.",
                choices: [{ label: "Particulier", next: "o2", tap: [88.2, 43.4], hint: "Ecobank Online" }, { label: "Petite entreprise, commerçant, association", next: "o3", tap: [88.2, 51.6], hint: "Omni Lite" },
                    { label: "Entreprise (plusieurs signataires, gros volumes)", next: "o4", tap: [88.2, 59.8], hint: "Omni Plus" }, { label: "Marchand qui encaisse des paiements", next: "o5", tap: [88.2, 68.4], hint: "EcobankPay" }] },
            o2: { title: "Ecobank Online", img: SITE, tap: [88.2, 43.4], say: "Pour un particulier : Ecobank Online, la banque par Internet (comptes, virements, factures, cartes, demandes).", done: true },
            o3: { title: "Omni Lite", img: SITE, tap: [88.2, 51.6], say: "Pour une petite entreprise : Omni Lite, la banque en ligne simplifiée des PME (virements, paiements, relevés).",
                tip: "Souscription auprès du conseiller entreprise ou en agence.", done: true },
            o4: { title: "Omni Plus", img: SITE, tap: [88.2, 59.8], say: "Pour une entreprise : Omni Plus — plusieurs utilisateurs, circuits de validation, paiements groupés (salaires, fournisseurs).",
                tip: "Mise en place par le chargé de clientèle entreprise ; dispositif d'authentification (token) remis à l'entreprise.", done: true },
            o5: { title: "EcobankPay", img: SITE, tap: [88.2, 68.4], say: "Pour un marchand : EcobankPay permet d'encaisser les paiements des clients par QR code.", done: true }
        }
    };

    // ───────────── Points Xpress (agents Ecobank) ─────────────

    var AG = "Téléphone de l'agent Xpress", CL = "Téléphone du client", CPT = "Au comptoir";
    function XP(title, rows) { return { title: title, rows: rows }; }

    var xpTrouver = {
        id: "trouver", title: "Trouver un Point Xpress", icon: "bi-geo-alt", category: "Démarrer", source: "généré", duration: "30 s",
        summary: "Points Xpress : agents de quartier Ecobank, ouverts aussi le soir, le week-end et les jours fériés.", start: "f1",
        steps: {
            f1: { title: "Qu'est-ce qu'un Point Xpress ?", who: CPT, mock: XP("Point Xpress", [{ t: "scene", icon: "bi-shop", text: "Un commerce agréé Ecobank près de chez vous",
                sub: "Dépôts, retraits, ouverture de compte Xpress, transferts, factures et crédit selon le point." }]),
                say: "Un Point Xpress est un commerçant agréé par Ecobank : vous y faites vos opérations courantes près de chez vous, souvent après les heures d'agence et le week-end.", next: "f2" },
            f2: { title: "Localiser un point", who: CL, mock: XP("Trouver un point", [{ t: "field", label: "Rechercher un quartier, une ville", value: "Cocody, Abidjan" },
                { t: "option", icon: "bi-shop", label: "Point Xpress — Boutique Les Palmiers", sub: "350 m · ouvert jusqu'à 21 h", hot: true },
                { t: "option", icon: "bi-shop", label: "Point Xpress — Station Riviera", sub: "1,2 km" }, { t: "option", icon: "bi-bank", label: "Agence Ecobank Riviera", sub: "1,8 km" }]),
                say: "Dans Ecobank Mobile (menu « Autres » → localiser une agence ou un point) ou sur ecobank.com (« Trouve une agence, un distributeur automatique près de chez toi »), cherchez le point le plus proche.",
                tip: "Repérer l'enseigne « Ecobank Xpress Point » sur la devanture ; les services disponibles varient selon le point.", done: true }
        }
    };

    var xpDepot = {
        id: "depot", title: "Déposer de l'argent (dépôt)", icon: "bi-box-arrow-in-down", category: "Opérations", source: "généré", duration: "2 min",
        summary: "Créditer un compte Ecobank ou un compte Xpress en espèces chez un agent ; confirmation par SMS et reçu.", start: "d1",
        steps: {
            d1: { title: "Au comptoir", who: CPT, mock: XP("Dépôt", [{ t: "scene", icon: "bi-cash-coin", text: "Le client donne le numéro de compte (ou le numéro de téléphone Xpress), le montant et les espèces",
                sub: "Pièce d'identité demandée selon le montant et la réglementation." }]),
                say: "Donnez à l'agent le numéro du compte à créditer — ou le numéro de téléphone pour un compte Xpress — et le montant en espèces.", next: "d2" },
            d2: { title: "Saisie par l'agent", who: AG, mock: XP("Xpress Point — Dépôt", [{ t: "select", label: "Type de compte", value: "Compte Ecobank / Compte Xpress" },
                { t: "field", label: "Numéro de compte ou de téléphone", value: "" }, { t: "field", label: "Montant", value: "50 000 XOF" }, { t: "button", label: "Vérifier", hot: true }]),
                say: "L'agent saisit le compte et le montant sur son terminal.", next: "d3" },
            d3: { title: "Vérifier le nom du titulaire", who: AG, mock: XP("Confirmation", [{ t: "summary", items: [["Titulaire", "K•••• A•••"], ["Compte", "••••4521"], ["Montant", "50 000 XOF"]] },
                { t: "button", label: "Confirmer le dépôt", hot: true }]),
                say: "L'agent vous lit le nom du titulaire affiché : confirmez que c'est le bon compte avant qu'il valide.",
                warn: "Un dépôt sur un mauvais compte est difficile à récupérer : toujours faire vérifier le nom.", next: "d4" },
            d4: { title: "SMS de confirmation", who: CL, mock: XP("Messages", [{ t: "sms", text: "Dépôt de 50 000 XOF effectué sur votre compte ••••4521. Réf : XP•••••. Ecobank" }]),
                say: "Vous recevez un SMS de confirmation, et l'agent vous remet un reçu.",
                tip: "Garder le reçu (référence de la transaction) jusqu'à voir le dépôt sur le compte ; sans SMS, ne pas partir sans vérifier avec l'agent.", done: true }
        }
    };

    var xpRetrait = {
        id: "retrait", title: "Retirer de l'argent (retrait)", icon: "bi-box-arrow-up", category: "Opérations", source: "généré", duration: "2 min",
        summary: "Retrait depuis son compte Xpress (validé par le client avec son code PIN) ou avec un code Xpress Cash.", start: "w1",
        steps: {
            w1: { title: "Comment retirer ?", who: CPT, mock: XP("Retrait", [{ t: "option", icon: "bi-phone", label: "Depuis mon compte Xpress" }, { t: "option", icon: "bi-123", label: "Avec un code Xpress Cash (8 chiffres)" }]),
                say: "Deux façons de retirer au Point Xpress.",
                choices: [{ label: "Depuis son compte Xpress", next: "w2", hint: "Le client valide lui-même avec son code PIN." }, { label: "Avec un code Xpress Cash", next: "w6", hint: "Code à 8 chiffres généré dans Ecobank Mobile ou par *326#." }] },
            w2: { title: "Demande de retrait", who: AG, mock: XP("Xpress Point — Retrait", [{ t: "field", label: "Numéro de téléphone du client", value: "07 •• •• •• ••" },
                { t: "field", label: "Montant", value: "20 000 XOF" }, { t: "button", label: "Envoyer la demande", hot: true }]),
                say: "Donnez votre numéro de téléphone Xpress et le montant : l'agent envoie la demande de retrait.", next: "w3" },
            w3: { title: "Le client valide sur son téléphone", who: CL, mock: XP("Ecobank — Retrait", [{ t: "summary", items: [["Agent", "Boutique Les Palmiers"], ["Montant", "20 000 XOF"], ["Frais", "selon la grille"]] },
                { t: "otp", label: "Votre code PIN", hot: true }, { t: "button", label: "Valider" }]),
                say: "Vous recevez la demande sur votre téléphone : vérifiez le montant et l'agent, puis validez vous-même avec votre code PIN.",
                warn: "Le client saisit son code PIN lui-même : il ne le donne jamais à l'agent.", next: "w4" },
            w4: { title: "Remise des espèces", who: CPT, mock: XP("Retrait", [{ t: "scene", icon: "bi-cash-stack", text: "L'agent remet les espèces et le reçu" }]),
                say: "L'agent vous remet les espèces et un reçu ; comptez avant de partir.", next: "w5" },
            w5: { title: "SMS de confirmation", who: CL, mock: XP("Messages", [{ t: "sms", text: "Retrait de 20 000 XOF effectué au Point Xpress Boutique Les Palmiers. Réf : XP•••••. Ecobank" }]),
                say: "Un SMS confirme le retrait et le nouveau solde.", done: true },
            w6: { title: "Code Xpress Cash", who: CL, mock: XP("Xpress Cash", [{ t: "sms", from: "Ecobank Mobile", text: "Code Xpress Cash : •••• •••• — Montant : 20 000 XOF. Retrait au GAB Ecobank ou au Point Xpress." }]),
                say: "Présentez le code Xpress Cash à 8 chiffres reçu (généré par vous ou envoyé par un proche) et annoncez le montant exact.",
                tip: "Le code se génère dans Ecobank Mobile → « Xpress Cash » ou par *326# ; il est valable un temps limité, pour un montant précis.", next: "w7" },
            w7: { title: "Saisie du code par l'agent", who: AG, mock: XP("Xpress Point — Xpress Cash", [{ t: "field", label: "Code Xpress Cash", value: "•••• ••••" }, { t: "field", label: "Montant", value: "20 000 XOF" },
                { t: "button", label: "Valider", hot: true }]),
                say: "L'agent saisit le code et le montant, puis valide.", warn: "Ne communiquer le code qu'à la personne qui retire : quiconque l'a peut retirer l'argent.", next: "w4" }
        }
    };

    var xpOuverture = {
        id: "ouverture-compte", title: "Ouvrir un compte Xpress au Point Xpress", icon: "bi-person-plus", category: "Démarrer", source: "généré", duration: "5 min",
        summary: "Sans passer en agence : pièce d'identité et numéro de téléphone ; le numéro devient le numéro de compte, protégé par un code PIN à 6 chiffres.", start: "o1",
        steps: {
            o1: { title: "Ce qu'il faut apporter", who: CPT, mock: XP("Ouverture de compte", [{ t: "scene", icon: "bi-person-vcard", text: "Pièce d'identité valide + téléphone avec son numéro",
                sub: "Le numéro de téléphone doit être au nom du client et utilisé dans le pays." }]),
                say: "Présentez votre pièce d'identité valide et votre téléphone : votre numéro de téléphone deviendra votre numéro de compte Xpress.", next: "o2" },
            o2: { title: "Enregistrement par l'agent", who: AG, mock: XP("Xpress Point — Nouveau compte", [{ t: "field", label: "Numéro de téléphone", value: "07 •• •• •• ••" },
                { t: "field", label: "Nom et prénoms", value: "" }, { t: "field", label: "Date de naissance", value: "" }, { t: "select", label: "Pièce d'identité", value: "CNI / passeport" },
                { t: "option", icon: "bi-camera", label: "Photo de la pièce et du client", hot: true }]),
                say: "L'agent saisit vos informations et photographie votre pièce d'identité : la vérification (KYC) est faite électroniquement.", next: "o3" },
            o3: { title: "Code PIN du client", who: CL, mock: XP("Ecobank — Compte Xpress", [{ t: "sms", text: "Bienvenue chez Ecobank ! Créez votre code PIN pour activer votre compte Xpress." },
                { t: "otp", label: "Créez votre code PIN (6 chiffres)", hot: true }, { t: "otp", label: "Confirmez" }]),
                say: "Sur votre téléphone, créez vous-même votre code PIN à 6 chiffres et confirmez-le : il protège le compte et valide chaque opération.",
                warn: "L'agent ne doit ni voir ni saisir le code PIN.", next: "o4" },
            o4: { title: "Premier dépôt (facultatif)", who: AG, mock: XP("Xpress Point — Dépôt", [{ t: "field", label: "Compte Xpress", value: "07 •• •• •• ••" }, { t: "field", label: "Montant", value: "" },
                { t: "button", label: "Déposer", hot: true }]),
                say: "Vous pouvez faire tout de suite un premier dépôt sur votre nouveau compte.", next: "o5" },
            o5: { title: "Compte ouvert", who: CL, mock: XP("", [{ t: "success", text: "Compte Xpress ouvert" }, { t: "info", text: "Installez Ecobank Mobile pour gérer votre compte (transferts, factures, Xpress Cash)." }]),
                say: "Votre compte Xpress est ouvert. Installez Ecobank Mobile pour l'utiliser au quotidien (pas à pas « Ecobank Mobile → Télécharger et activer »).",
                tip: "Plafonds du compte Xpress selon la réglementation ; pour les relever, compléter le dossier en agence.", done: true }
        }
    };

    var xpServices = {
        id: "transferts-factures", title: "Transferts, factures et crédit au Point Xpress", icon: "bi-arrow-left-right", category: "Opérations", source: "généré", duration: "2 min",
        summary: "Envoyer de l'argent, payer une facture ou recharger du crédit chez un agent (selon les services du point).", start: "s1",
        steps: {
            s1: { title: "Quel service ?", who: CPT, mock: XP("Services", [{ t: "option", icon: "bi-send", label: "Envoyer de l'argent" }, { t: "option", icon: "bi-receipt", label: "Payer une facture" },
                { t: "option", icon: "bi-phone", label: "Acheter du crédit" }]),
                say: "Indiquez à l'agent le service souhaité.",
                choices: [{ label: "Envoyer de l'argent", next: "s2" }, { label: "Payer une facture", next: "s3" }, { label: "Acheter du crédit", next: "s4" }] },
            s2: { title: "Transfert", who: AG, mock: XP("Xpress Point — Transfert", [{ t: "select", label: "Destination", value: "Compte Ecobank / autre banque / Afrique" }, { t: "field", label: "Bénéficiaire", value: "" },
                { t: "field", label: "Montant", value: "" }, { t: "summary", items: [["Frais", "selon la grille"]] }, { t: "button", label: "Valider", hot: true }]),
                say: "Donnez les coordonnées du bénéficiaire et le montant : l'agent vous annonce les frais avant de valider.", next: "s5" },
            s3: { title: "Facture", who: AG, mock: XP("Xpress Point — Facture", [{ t: "select", label: "Fournisseur", value: "Électricité / eau / TV" }, { t: "field", label: "Référence de la facture", value: "" },
                { t: "button", label: "Rechercher", hot: true }]),
                say: "Donnez la référence de la facture : l'agent affiche le montant dû et le nom de l'abonné ; vérifiez avant de payer.", next: "s5" },
            s4: { title: "Crédit téléphonique", who: AG, mock: XP("Xpress Point — Crédit", [{ t: "select", label: "Opérateur", value: "Orange / MTN / Moov" }, { t: "field", label: "Numéro", value: "" },
                { t: "field", label: "Montant", value: "" }, { t: "button", label: "Valider", hot: true }]),
                say: "Donnez le numéro à recharger et le montant.", next: "s5" },
            s5: { title: "Confirmation", who: CL, mock: XP("Messages", [{ t: "sms", text: "Opération effectuée au Point Xpress. Réf : XP•••••. Ecobank" }]),
                say: "Vous recevez un SMS et un reçu : gardez-les comme preuve.", done: true }
        }
    };

    var xpSecurite = {
        id: "securite-reclamation", title: "Bonnes pratiques et réclamation", icon: "bi-shield-check", category: "Sécurité", source: "généré", duration: "1 min",
        summary: "Ce que l'agent ne doit jamais demander, et que faire si un dépôt n'est pas arrivé ou un retrait est contesté.", start: "q1",
        steps: {
            q1: { title: "Les règles d'or", who: CPT, mock: XP("Sécurité", [{ t: "info", text: "L'agent ne demande JAMAIS votre code PIN ni vos codes SMS." },
                { t: "option", icon: "bi-person-check", label: "Faire vérifier le nom du titulaire" }, { t: "option", icon: "bi-chat-dots", label: "Attendre le SMS de confirmation" },
                { t: "option", icon: "bi-receipt", label: "Garder le reçu" }]),
                say: "Ne donnez jamais votre code PIN ; faites vérifier le nom du titulaire, attendez le SMS et gardez le reçu.",
                choices: [{ label: "Dépôt non arrivé sur le compte", next: "q2" }, { label: "Retrait ou opération contestée", next: "q2" }, { label: "Agent suspect (frais non affichés, demande du code)", next: "q3" }] },
            q2: { title: "Réclamation", who: CL, mock: XP("Réclamation", [{ t: "summary", items: [["Date et heure", "01/10 · 18:42"], ["Point Xpress", "Nom / lieu"], ["Montant", "XOF"], ["Référence", "XP•••••"]] },
                { t: "button", label: "Appeler le centre de relation client", hot: true }]),
                say: "Contactez le centre de relation client avec le reçu : date et heure, nom du point, montant et référence de la transaction. La réclamation est traitée dans le délai prévu.",
                tip: "Côté conseiller : enregistrer la réclamation avec la référence et orienter vers le service Agency Banking.", done: true },
            q3: { title: "Signaler un agent", who: CL, mock: XP("Signalement", [{ t: "scene", icon: "bi-exclamation-triangle", text: "Ne pas poursuivre l'opération", sub: "Signaler le point au centre de relation client (nom, adresse, faits)." }]),
                say: "N'allez pas plus loin et signalez le point au centre de relation client : nom, adresse et ce qui s'est passé.", done: true }
        }
    };

    // ───────────── Guichets automatiques (GAB) ─────────────

    function A(title, rows) { return { title: title, rows: rows }; }
    var MENU_GAB = [{ t: "option", icon: "bi-cash-stack", label: "Retrait" }, { t: "option", icon: "bi-wallet2", label: "Consultation du solde" },
        { t: "option", icon: "bi-list-ul", label: "Mini-relevé" }, { t: "option", icon: "bi-key", label: "Changement de code PIN" }];

    var gabRetrait = {
        id: "retrait-carte", title: "Retirer avec sa carte", icon: "bi-credit-card", category: "Opérations", source: "généré", duration: "1 min",
        summary: "Insérer la carte, code PIN caché, montant, reçu : la carte est rendue avant les billets.", start: "g1",
        steps: {
            g1: { title: "Insérer la carte", hw: "card", mock: A("Bienvenue", [{ t: "scene", icon: "bi-credit-card-2-front", text: "Insérez votre carte", sub: "Puce vers le haut, dans le sens indiqué." }]),
                say: "Insérez votre carte dans la fente, puce vers le haut, dans le sens indiqué par le pictogramme.",
                warn: "Avant d'insérer : vérifier qu'aucun objet suspect n'est collé sur la fente ou le clavier.", next: "g2" },
            g2: { title: "Choisir la langue", mock: A("Langue", [{ t: "option", icon: "bi-translate", label: "Français", hot: true }, { t: "option", icon: "bi-translate", label: "English" }]),
                say: "Choisissez la langue avec la touche à côté de l'écran.", next: "g3" },
            g3: { title: "Code PIN", hw: "keypad", mock: A("Code secret", [{ t: "field", label: "Saisissez votre code PIN", value: "••••" }, { t: "info", text: "Cachez le clavier avec votre main." }]),
                say: "Tapez votre code secret en cachant le clavier avec l'autre main, puis validez (touche verte).",
                warn: "3 codes erronés : la carte est bloquée, voire capturée.", next: "g4" },
            g4: { title: "Menu", mock: A("Que souhaitez-vous faire ?", MENU_GAB.map(function (r, i) { return i === 0 ? Object.assign({ hot: true }, r) : r; })),
                say: "Choisissez « Retrait ».", next: "g5" },
            g5: { title: "Montant", mock: A("Retrait", [{ t: "tiles", items: [["bi-cash", "10 000"], ["bi-cash", "20 000", true], ["bi-cash", "50 000"], ["bi-cash", "100 000"], ["bi-cash", "200 000"], ["bi-pencil", "Autre montant"]] }]),
                say: "Choisissez un montant proposé ou « Autre montant » (multiple des billets disponibles).", tip: "Plafond de retrait selon le type de carte ; frais éventuels affichés selon la grille.", next: "g6" },
            g6: { title: "Reçu", hw: "receipt", mock: A("Souhaitez-vous un reçu ?", [{ t: "option", icon: "bi-receipt", label: "Oui", hot: true }, { t: "option", icon: "bi-x-lg", label: "Non" }]),
                say: "Choisissez si vous voulez un reçu : il est utile en cas de réclamation.", next: "g7" },
            g7: { title: "Reprendre la carte", hw: "card", mock: A("Retrait", [{ t: "scene", icon: "bi-credit-card-2-front", text: "Reprenez votre carte", sub: "La carte sort avant les billets." }]),
                say: "Reprenez d'abord votre carte : elle sort avant les billets.", next: "g8" },
            g8: { title: "Prendre les billets", hw: "cash", mock: A("Retrait", [{ t: "success", text: "Prenez vos billets" }, { t: "info", text: "Billets non retirés : ils sont repris par l'appareil après quelques secondes." }]),
                say: "Prenez vos billets et votre reçu, et rangez-les avant de quitter le GAB.",
                tip: "Billets non sortis mais compte débité : voir le pas à pas « Incident au GAB ».", done: true }
        }
    };

    var gabXpressCash = {
        id: "xpress-cash", title: "Retirer sans carte (Xpress Cash)", icon: "bi-phone", category: "Opérations", source: "généré", duration: "1 min",
        summary: "Code à 8 chiffres généré dans Ecobank Mobile ou par *326#, utilisable par soi ou par un proche, au GAB Ecobank.", start: "x1",
        steps: {
            x1: { title: "Générer le code", mock: A("Avant d'aller au GAB", [{ t: "scene", icon: "bi-phone", text: "Ecobank Mobile → « Xpress Cash »",
                sub: "Choisir le retrait au GAB, le compte et le montant : un code à 8 chiffres est créé (aussi par *326#)." }]),
                say: "Dans Ecobank Mobile, ouvrez « Xpress Cash », choisissez le retrait au GAB, le compte et le montant : vous obtenez un code à 8 chiffres, que vous pouvez envoyer à un proche.",
                tip: "Pas à pas détaillé : « Ecobank Mobile → Retirer sans carte (Xpress Cash) ». Code valable un temps limité, montant exact.", next: "x2" },
            x2: { title: "Écran d'accueil du GAB", mock: A("Bienvenue", [{ t: "scene", icon: "bi-credit-card-2-front", text: "Insérez votre carte" }, { t: "option", icon: "bi-phone", label: "Retrait sans carte (Xpress Cash)", hot: true }]),
                say: "Sans insérer de carte, choisissez « Retrait sans carte / Xpress Cash » sur l'écran d'accueil.", next: "x3" },
            x3: { title: "Saisir le code", hw: "keypad", mock: A("Xpress Cash", [{ t: "field", label: "Code Xpress Cash (8 chiffres)", value: "•••• ••••" }]),
                say: "Tapez le code Xpress Cash à 8 chiffres, puis validez.", next: "x4" },
            x4: { title: "Montant exact", hw: "keypad", mock: A("Xpress Cash", [{ t: "field", label: "Montant", value: "20 000 XOF" }, { t: "info", text: "Le montant doit être exactement celui du code." }]),
                say: "Saisissez exactement le montant choisi à la création du code, puis validez.", next: "x5" },
            x5: { title: "Prendre les billets", hw: "cash", mock: A("Xpress Cash", [{ t: "success", text: "Prenez vos billets" }]),
                say: "Prenez vos billets : le retrait est confirmé par SMS à l'émetteur du code.",
                warn: "Le code donne accès à l'argent : ne le partager qu'avec la personne qui doit retirer.", done: true }
        }
    };

    var gabSolde = {
        id: "solde-releve", title: "Consulter son solde ou un mini-relevé", icon: "bi-wallet2", category: "Consultation", source: "généré", duration: "30 s",
        summary: "Solde disponible à l'écran ou sur ticket, et dernières opérations.", start: "c1",
        steps: {
            c1: { title: "Carte et code PIN", hw: "card", mock: A("Bienvenue", [{ t: "scene", icon: "bi-credit-card-2-front", text: "Insérez votre carte puis tapez votre code PIN" }]),
                say: "Insérez votre carte et tapez votre code PIN en cachant le clavier.", next: "c2" },
            c2: { title: "Menu", mock: A("Que souhaitez-vous faire ?", MENU_GAB.map(function (r, i) { return i === 1 ? Object.assign({ hot: true }, r) : r; })),
                say: "Choisissez « Consultation du solde » ou « Mini-relevé ».",
                choices: [{ label: "Consultation du solde", next: "c3" }, { label: "Mini-relevé", next: "c4" }] },
            c3: { title: "Solde", mock: A("Votre solde", [{ t: "balance", label: "Solde disponible", value: "•••••• XOF" }, { t: "option", icon: "bi-receipt", label: "Imprimer" }, { t: "option", icon: "bi-arrow-left", label: "Autre opération" }]),
                say: "Le solde disponible s'affiche ; vous pouvez l'imprimer ou faire une autre opération.", next: "c5" },
            c4: { title: "Mini-relevé", hw: "receipt", mock: A("Mini-relevé", [{ t: "table", head: ["Date", "Opération", "Montant"], rows: [["28/09", "Virement", "+ ••••"], ["27/09", "Retrait", "- ••••"]] }]),
                say: "Les dernières opérations s'impriment sur un ticket.", next: "c5" },
            c5: { title: "Reprendre la carte", hw: "card", mock: A("Merci", [{ t: "scene", icon: "bi-credit-card-2-front", text: "N'oubliez pas votre carte" }]),
                say: "Reprenez votre carte (et le ticket).", done: true }
        }
    };

    var gabPin = {
        id: "changer-pin", title: "Changer le code PIN de sa carte", icon: "bi-key", category: "Sécurité", source: "généré", duration: "1 min",
        summary: "Remplacer le code reçu par un code personnel, au GAB Ecobank.", start: "p1",
        steps: {
            p1: { title: "Carte et code actuel", hw: "keypad", mock: A("Code secret", [{ t: "field", label: "Code PIN actuel", value: "••••" }]),
                say: "Insérez votre carte et tapez votre code PIN actuel.", next: "p2" },
            p2: { title: "Menu", mock: A("Que souhaitez-vous faire ?", MENU_GAB.map(function (r, i) { return i === 3 ? Object.assign({ hot: true }, r) : r; })),
                say: "Choisissez « Changement de code PIN ».", next: "p3" },
            p3: { title: "Nouveau code", hw: "keypad", mock: A("Nouveau code PIN", [{ t: "field", label: "Nouveau code", value: "••••" }, { t: "field", label: "Confirmez", value: "••••" }]),
                say: "Tapez deux fois votre nouveau code à 4 chiffres.", warn: "Pas de date de naissance ni de 0000 / 1234 ; ne jamais l'écrire sur la carte.", next: "p4" },
            p4: { title: "Code changé", hw: "card", mock: A("", [{ t: "success", text: "Code PIN modifié" }]),
                say: "Le code est changé : il sert dès maintenant dans tous les GAB et terminaux de paiement. Reprenez votre carte.", done: true }
        }
    };

    var gabIncident = {
        id: "incident", title: "Incident au GAB : carte capturée, billets non distribués", icon: "bi-exclamation-triangle", category: "Incidents", source: "généré", duration: "2 min",
        summary: "Quoi faire et quoi noter : carte avalée, billets non sortis mais compte débité, montant incomplet.", start: "i1",
        steps: {
            i1: { title: "Quel incident ?", mock: A("Incident", [{ t: "option", icon: "bi-credit-card-2-front", label: "Carte capturée" }, { t: "option", icon: "bi-cash-stack", label: "Billets non distribués, compte débité" },
                { t: "option", icon: "bi-cash", label: "Montant incomplet" }]),
                say: "Restez calme et identifiez l'incident.",
                choices: [{ label: "Carte capturée par le GAB", next: "i2" }, { label: "Billets non distribués mais compte débité", next: "i3" }, { label: "Montant incomplet", next: "i3" }] },
            i2: { title: "Carte capturée", hw: "card", mock: A("Carte retenue", [{ t: "info", text: "Votre carte a été retenue par l'appareil." }, { t: "scene", icon: "bi-shield-lock", text: "Bloquer la carte si le doute persiste", sub: "Ecobank Mobile → Cartes, ou centre de relation client." }]),
                say: "Si le GAB est dans une agence, présentez-vous à l'agence avec votre pièce d'identité (aux heures d'ouverture). Sinon, contactez le centre de relation client ; en cas de doute (GAB suspect), bloquez la carte.",
                warn: "Refuser l'aide d'un inconnu qui propose de « récupérer » la carte en retapant le code : c'est une fraude connue.", done: true },
            i3: { title: "Noter les informations", hw: "receipt", mock: A("À noter", [{ t: "summary", items: [["Date et heure", "01/10 · 18:42"], ["GAB", "Agence / lieu"], ["Montant demandé", "XOF"], ["Montant reçu", "XOF"]] }]),
                say: "Notez la date et l'heure, le lieu du GAB, le montant demandé et le montant reçu ; gardez le ticket s'il y en a un.", next: "i4" },
            i4: { title: "Réclamation", mock: A("Réclamation", [{ t: "scene", icon: "bi-headset", text: "Centre de relation client ou agence", sub: "Réclamation « retrait GAB non obtenu » : régularisation après contrôle du GAB, dans le délai prévu." }]),
                say: "Faites la réclamation au centre de relation client ou en agence : après contrôle de la caisse du GAB, le montant est recrédité s'il n'a pas été distribué.",
                tip: "Côté conseiller : motif « Réclamation retrait GAB », SLA et circuit du Back-office monétique (voir RAF).", done: true }
        }
    };

    var gabSecurite = {
        id: "securite", title: "Sécurité au GAB", icon: "bi-shield-check", category: "Sécurité", source: "généré", duration: "30 s",
        summary: "Les bons réflexes avant, pendant et après un retrait.", start: "s1",
        steps: {
            s1: { title: "Avant", hw: "card", mock: A("Avant", [{ t: "scene", icon: "bi-search", text: "Inspecter le GAB", sub: "Fente, clavier et façade : rien de collé ni de mobile." }]),
                say: "Avant d'insérer la carte, vérifiez la fente, le clavier et la façade : rien ne doit être collé ou bouger.", next: "s2" },
            s2: { title: "Pendant", hw: "keypad", mock: A("Pendant", [{ t: "scene", icon: "bi-hand-index", text: "Cacher le clavier", sub: "Personne trop près ; ne pas accepter d'aide d'un inconnu." }]),
                say: "Cachez le clavier en tapant le code et n'acceptez pas l'aide d'un inconnu.", next: "s3" },
            s3: { title: "Après", hw: "cash", mock: A("Après", [{ t: "scene", icon: "bi-bell", text: "Ranger l'argent et vérifier le SMS", sub: "Activer les alertes SMS pour voir chaque opération." }]),
                say: "Rangez billets et carte avant de partir, vérifiez l'alerte SMS ; en cas d'opération inconnue, bloquez la carte et appelez le centre de relation client.", done: true }
        }
    };

    return {
        platforms: [
            { id: "ecobank-mobile", name: "Ecobank Mobile", icon: "bi-phone-fill", color: "#0B5ED7", ready: true,
                description: "L'application mobile : enrôlement, code PIN oublié, solde, transferts, factures, crédit, MoMo, Xpress Cash, EcobankPay, cartes, sécurité.",
                guides: [enrolement, reinitPin, solde, transfert, credit, facture, xpressCash, ecobankPay, partager, cartes, securite] },
            { id: "ecobank-online", name: "Ecobank Online (Internet)", icon: "bi-laptop", color: "#5B3CC4", ready: true, device: "browser",
                description: "La banque par Internet sur ecobank.com : connexion, activation, mot de passe oublié, comptes et relevés, virements, bénéficiaires, paiements, cartes, demandes, sécurité.",
                guides: [olAcces, olActivation, olMdp, olComptes, olVirement, olBenef, olPaiements, olCartes, olDemandes, olProfil, olOrientation] },
            { id: "xpress-point", name: "Points Xpress", icon: "bi-shop", color: "#00A651", ready: true,
                description: "Les agents Ecobank de quartier : dépôt, retrait (compte Xpress ou code Xpress Cash), ouverture de compte Xpress, transferts, factures, réclamations.",
                guides: [xpTrouver, xpDepot, xpRetrait, xpOuverture, xpServices, xpSecurite] },
            { id: "gab", name: "Guichets automatiques", icon: "bi-credit-card", color: "#DC8A00", ready: true, device: "atm",
                description: "Les GAB Ecobank : retrait avec carte, retrait sans carte (Xpress Cash), solde et mini-relevé, code PIN, incidents, sécurité.",
                guides: [gabRetrait, gabXpressCash, gabSolde, gabPin, gabIncident, gabSecurite] }
        ]
    };
})();
