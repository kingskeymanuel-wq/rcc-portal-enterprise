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

    return {
        platforms: [
            { id: "ecobank-mobile", name: "Ecobank Mobile", icon: "bi-phone-fill", color: "#0B5ED7", ready: true,
                description: "L'application mobile : enrôlement, solde, transferts, factures, crédit, MoMo, Xpress Cash, EcobankPay, cartes, sécurité.",
                guides: [enrolement, solde, transfert, credit, facture, xpressCash, ecobankPay, partager, cartes, securite] },
            { id: "ecobank-online", name: "Ecobank Online (Internet)", icon: "bi-laptop", color: "#5B3CC4", ready: false, description: "Banque par Internet — à venir.", guides: [] },
            { id: "xpress-point", name: "Points Xpress", icon: "bi-shop", color: "#00A651", ready: false, description: "Dépôts et retraits chez les agents Xpress — à venir.", guides: [] },
            { id: "gab", name: "Guichets automatiques", icon: "bi-credit-card", color: "#DC8A00", ready: false, description: "Retraits, Xpress Cash au GAB — à venir.", guides: [] }
        ]
    };
})();
