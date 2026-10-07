"use strict";

/**
 * Pas à pas du portail CIB : Ecobank Omni Lite et Omni Plus (banque en ligne des entreprises).
 * Rédigés d'après les manuels officiels (Omni 4.1 : Première connexion, Solde et relevé, Enregistrer un bénéficiaire,
 * Transactions de paiement ; Guide d'utilisation Omni Plus). Captures des manuels (comptes de démonstration).
 * Ces plateformes ne s'affichent que dans la base CIB (audience « CIB ») ; les autres équipes gardent leurs pas à pas.
 */
(function () {
    if (!window.RCC_GUIDES) return;
    window.RCC_GUIDES.platforms.push(
        { id: "omni-lite", name: "Omni Lite", icon: "bi-building", color: "#0F7A8A", ready: true, device: "browser", audience: "CIB",
            url: "omni.ecobank.com",
            description: "La banque en ligne des entreprises (Omni) : première connexion, accueil et paramètres, soldes et relevés, bénéficiaires, paiements (SICA, RTGS, compte à compte, chèques), autorisation et envoi.",
            guides: [
 {
  "id": "premiere-connexion",
  "title": "Première connexion à Omni",
  "icon": "bi-box-arrow-in-right",
  "category": "Démarrer",
  "source": "guide",
  "duration": "3 à 5 min",
  "summary": "Mail de bienvenue, première connexion, questions de sécurité et changement du mot de passe provisoire.",
  "start": "mail",
  "steps": {
   "mail": {
    "title": "Retrouver le mail des identifiants",
    "img": "/images/pas-a-pas/omni/1-p02-3.jpg",
    "say": "Vérifiez votre boîte mail : vous avez reçu un message de ecobankomni@ecobank.com, objet « ECOBANK OMNI GENERATED PASSWORD ». Il contient votre nom d'utilisateur, votre premier mot de passe et le lien https://omni.ecobank.com.",
    "tip": "Mail introuvable : vérifier les courriers indésirables, puis l'adresse e-mail déclarée dans le formulaire Omni.",
    "next": "login"
   },
   "login": {
    "title": "Se connecter",
    "img": "/images/pas-a-pas/omni/1-p02-3.jpg",
    "say": "Ouvrez https://omni.ecobank.com, saisissez votre nom d'utilisateur et le mot de passe reçu par mail, puis cliquez sur « Log In ».",
    "tap": [
     77.9,
     47.8
    ],
    "warn": "Ne demandez jamais au client de vous communiquer son mot de passe : il le saisit lui-même.",
    "next": "questions"
   },
   "questions": {
    "title": "Répondre aux questions de sécurité",
    "img": "/images/pas-a-pas/omni/1-p05-7.jpg",
    "say": "Comme c'est votre première connexion, Omni vous pose des questions de sécurité : répondez à au moins 5 questions et retenez bien vos réponses.",
    "tap": [
     28.2,
     12.8
    ],
    "tip": "Ces réponses servent à réinitialiser le mot de passe ou à confirmer un changement de paramètres plus tard.",
    "next": "mdp"
   },
   "mdp": {
    "title": "Changer le mot de passe provisoire",
    "img": "/images/pas-a-pas/omni/1-p06-5.jpg",
    "say": "Saisissez l'ancien mot de passe (celui du mail), puis le nouveau mot de passe deux fois, et cliquez sur « Changer ».",
    "tap": [
     61.7,
     68.4
    ],
    "warn": "Le nouveau mot de passe est personnel : il ne doit être communiqué à personne, pas même au conseiller.",
    "next": "relogin"
   },
   "relogin": {
    "title": "Se reconnecter avec le nouveau mot de passe",
    "img": "/images/pas-a-pas/omni/1-p03-4.jpg",
    "say": "L'écran de connexion réapparaît : reconnectez-vous avec votre nom d'utilisateur et votre nouveau mot de passe. Vous arrivez sur la page d'accueil d'Omni.",
    "done": true
   }
  }
 },
 {
  "id": "accueil-parametres",
  "title": "Découvrir l'accueil et les paramètres",
  "icon": "bi-grid-1x2",
  "category": "Démarrer",
  "source": "guide",
  "duration": "2 min",
  "summary": "Barre des menus, comptes du tableau de bord, questions de sécurité, mot de passe, langue et liens rapides.",
  "start": "accueil",
  "steps": {
   "accueil": {
    "title": "La page d'accueil",
    "img": "/images/pas-a-pas/omni/1-p03-4.jpg",
    "say": "En haut à droite : votre nom, le code Omni de l'entreprise, la date de dernière connexion et les liens Déconnexion et Aide. La barre verte donne accès aux modules : Compte, Admin, Collection, Liquidité, Paiements, Rapports.",
    "tip": "Module Compte : transactions et relevés · Paiements : SICA, RTGS, SWIFT, mise à disposition, chèques · Rapports : rapports d'activité.",
    "next": "comptes"
   },
   "comptes": {
    "title": "Choisir les comptes du tableau de bord",
    "img": "/images/pas-a-pas/omni/1-p05-3.jpg",
    "say": "Cliquez sur l'icône « Modification des paramètres », onglet « Comptes de Dashboard » : sélectionnez jusqu'à 3 comptes et ajoutez-les avec la flèche, puis « Enregistrer ».",
    "tap": [
     29.4,
     36.0
    ],
    "next": "securite"
   },
   "securite": {
    "title": "Modifier les questions de sécurité",
    "img": "/images/pas-a-pas/omni/1-p05-7.jpg",
    "say": "Onglet « Questionnaire Mot de passe » : choisissez les questions, saisissez les nouvelles réponses, puis « Enregistrer ».",
    "tap": [
     28.2,
     12.8
    ],
    "next": "graph"
   },
   "graph": {
    "title": "Suivre l'évolution des soldes",
    "img": "/images/pas-a-pas/omni/1-p08-3.jpg",
    "say": "La zone « Soldes de comptes » affiche l'évolution du solde en graphique : choisissez le compte dans la liste, le graphique se met à jour.",
    "tip": "Langue : bouton Français / Anglais en haut à droite. Liens rapides : ajoutez la page affichée en favori avec l'icône « + ».",
    "done": true
   }
  }
 },
 {
  "id": "solde-releve",
  "title": "Consulter un solde et télécharger un relevé",
  "icon": "bi-wallet2",
  "category": "Comptes",
  "source": "guide",
  "duration": "1 à 2 min",
  "summary": "Sommaire des comptes, rafraîchissement du solde, historique des transactions et export PDF / Excel.",
  "start": "menu",
  "steps": {
   "menu": {
    "title": "Ouvrir le sommaire des comptes",
    "img": "/images/pas-a-pas/omni/2-p02-3.jpg",
    "say": "Cliquez sur « Compte », puis sur « Solde du Compte » et « Sommaire des Comptes » : vos comptes et leurs soldes s'affichent, regroupés par type (courant, épargne, prêt…).",
    "tap": [
     18.4,
     29.8
    ],
    "next": "date"
   },
   "date": {
    "title": "Vérifier la date du solde",
    "img": "/images/pas-a-pas/omni/2-p02-4.jpg",
    "say": "Le solde n'est rafraîchi qu'à la demande : placez la souris sur le nom de la banque (ex. « Ecobank Côte d'Ivoire ») ; une bulle jaune indique la date et l'heure du dernier rafraîchissement.",
    "tap": [
     7.5,
     25.5
    ],
    "next": "current"
   },
   "current": {
    "title": "Rafraîchir le solde maintenant",
    "img": "/images/pas-a-pas/omni/2-p03-4.jpg",
    "say": "Cochez « Current », choisissez la devise dans « Équivalent en devises », cochez « Tout afficher » puis cliquez sur « Exécuter ».",
    "tap": [
     83.5,
     20.3
    ],
    "next": "historique"
   },
   "historique": {
    "title": "Voir l'historique des transactions",
    "img": "/images/pas-a-pas/omni/2-p03-6.jpg",
    "say": "Cliquez sur le nom de la banque (ou double-cliquez sur le numéro de compte), choisissez la période avec « Date », puis « Exécuter ».",
    "tap": [
     42.2,
     10.5
    ],
    "tip": "Pour exporter le relevé : icône XLS (Excel) ou PDF en haut du tableau.",
    "next": "echelle"
   },
   "echelle": {
    "title": "Historique des soldes (échelle d'intérêt)",
    "img": "/images/pas-a-pas/omni/2-p04-6.jpg",
    "say": "Depuis le sommaire, bouton « Historique » (au survol du nom de la banque) : choisissez la période et le compte, puis « Exécuter ».",
    "tap": [
     59.7,
     9.8
    ],
    "done": true
   }
  }
 },
 {
  "id": "beneficiaire",
  "title": "Enregistrer un bénéficiaire",
  "icon": "bi-person-plus",
  "category": "Paiements",
  "source": "guide",
  "duration": "2 à 3 min",
  "summary": "Bénéficiaire récurrent ou ponctuel, banque du bénéficiaire, compte (16 ou 24 positions / IBAN) et validation par l'autorisateur.",
  "start": "liste",
  "steps": {
   "liste": {
    "title": "Ouvrir la liste des bénéficiaires",
    "img": "/images/pas-a-pas/omni/3-p02-6.jpg",
    "say": "Cliquez sur « Paiements », puis à gauche sur « Maîtres » et « Liquidité Bénéficiaires ». Cliquez sur « Ajouter le Nouveau Bénéficiaire ».",
    "tap": [
     17.2,
     28.8
    ],
    "tip": "Pendant un paiement, on peut aussi créer le bénéficiaire avec « Adhoc Beneficiary » : la validation du paiement vaut alors validation du bénéficiaire.",
    "next": "perso"
   },
   "perso": {
    "title": "Détails personnels",
    "img": "/images/pas-a-pas/omni/3-p03-4.jpg",
    "say": "Cochez « Save Beneficiary » pour un bénéficiaire récurrent et saisissez son code (il servira à le retrouver), puis le nom et les autres champs utiles.",
    "tap": [
     33.0,
     7.5
    ],
    "next": "banque"
   },
   "banque": {
    "title": "Choisir la banque du bénéficiaire",
    "img": "/images/pas-a-pas/omni/3-p04-4.jpg",
    "say": "Dans « Détails de la Banque Bénéficiaire », cliquez sur la loupe de « Recherche de l'Agence de la Banque ».",
    "tap": [
     90.0,
     13.1
    ],
    "next": "filtre"
   },
   "filtre": {
    "title": "Rechercher une banque de Côte d'Ivoire",
    "img": "/images/pas-a-pas/omni/3-p04-3.jpg",
    "say": "Saisissez « CI » dans « Code de la banque », cliquez sur « Filtre » puis sur la banque choisie : les autres champs se remplissent seuls.",
    "tap": [
     6.9,
     14.7
    ],
    "warn": "Ne modifiez pas les champs remplis automatiquement.",
    "next": "compte"
   },
   "compte": {
    "title": "Compte du bénéficiaire",
    "img": "/images/pas-a-pas/omni/3-p05-3.jpg",
    "say": "Saisissez le numéro de compte, la devise (XOF pour le FCFA) et l'IBAN.",
    "tap": [
     56.8,
     8.5
    ],
    "warn": "Compte Ecobank : 16 positions. Autre banque : 24 positions ou l'IBAN.",
    "next": "contact"
   },
   "contact": {
    "title": "Détails du contact puis enregistrer",
    "img": "/images/pas-a-pas/omni/3-p05-4.jpg",
    "say": "L'onglet « Détails du Contact » complète l'adresse et les coordonnées. Cliquez sur « Enregistrer ».",
    "tap": [
     17.0,
     85.4
    ],
    "next": "valider"
   },
   "valider": {
    "title": "Faire valider le bénéficiaire",
    "img": "/images/pas-a-pas/omni/3-p06-5.jpg",
    "say": "Un profil autorisateur ouvre « Paiements » > « Maîtres » > « Auth de Bénéficiaires », passe la souris sur la case à cocher et choisit « Accepter » (ou « Rejeter »).",
    "tap": [
     18.2,
     35.7
    ],
    "tip": "Un bénéficiaire créé hors paiement n'est utilisable qu'après cette validation.",
    "done": true
   }
  }
 },
 {
  "id": "paiement",
  "title": "Initier un paiement",
  "icon": "bi-send",
  "category": "Paiements",
  "source": "guide",
  "duration": "3 min",
  "summary": "Virement UEMOA (SICA / RTGS), virement compte à compte, mise à disposition, chèques : saisie et envoi à l'autorisation.",
  "start": "nouveau",
  "steps": {
   "nouveau": {
    "title": "Ouvrir les paiements",
    "img": "/images/pas-a-pas/omni/4-p02-5.jpg",
    "say": "Cliquez sur « Paiement », puis « Paiement » > « Paiement ». Onglet « Lot » : paiements groupés (salaires…) ; onglet « Instruments » : paiements individuels. Cliquez sur « Nouveau ».",
    "tap": [
     98.1,
     5.9
    ],
    "tip": "Tout paiement est initié par un profil initiateur puis autorisé par un profil autorisateur.",
    "next": "type"
   },
   "type": {
    "title": "Choisir le type de paiement",
    "img": "/images/pas-a-pas/omni/4-p02-6.jpg",
    "say": "Choisissez le produit : virement zone UEMOA (Third Party Transfer), virement compte à compte (Ecobank Book Transfer), mise à disposition (Cash Payment), chèque standard ou certifié, salaires, virements internationaux.",
    "tap": [
     16.0,
     38.6
    ],
    "choices": [
     {
      "label": "Virement zone UEMOA (SICA / RTGS)",
      "next": "uemoa"
     },
     {
      "label": "Virement compte à compte, mise à disposition ou chèque",
      "next": "autre"
     }
    ]
   },
   "uemoa": {
    "title": "Virement UEMOA : SICA ou RTGS",
    "img": "/images/pas-a-pas/omni/4-p03-5.jpg",
    "say": "Choisissez le produit : SICA UEMOA pour un montant inférieur à 50 000 000 FCFA, STAR UEMOA (RTGS) au-delà. Saisissez une référence et choisissez le compte de débit.",
    "tap": [
     30.1,
     6.9
    ],
    "tip": "SICA : envoi avant 9h30 → traité le jour même (J), après → J+1 ; bénéficiaire crédité au plus tard à J+2. RTGS : avant 16h00 → J, après → J+1.",
    "next": "detail"
   },
   "autre": {
    "title": "Produit, référence et compte de débit",
    "img": "/images/pas-a-pas/omni/4-p04-4.jpg",
    "say": "Sélectionnez le produit de paiement selon la devise, saisissez une référence (celle du système de l'entreprise par exemple) et choisissez le compte de débit.",
    "tap": [
     26.9,
     6.9
    ],
    "next": "detail"
   },
   "detail": {
    "title": "Bénéficiaire, date, devise et montant",
    "img": "/images/pas-a-pas/omni/4-p06-5.jpg",
    "say": "Choisissez le code du bénéficiaire (déjà créé et validé) ou créez-le, saisissez la date d'activation (aujourd'hui ou plus tard), la devise et le montant, puis « Enregistrer et Soumettre ».",
    "tap": [
     23.3,
     23.6
    ],
    "tip": "La transaction part alors chez l'autorisateur.",
    "done": true
   }
  }
 },
 {
  "id": "autoriser-envoyer",
  "title": "Autoriser, envoyer ou rejeter un paiement",
  "icon": "bi-check2-circle",
  "category": "Paiements",
  "source": "guide",
  "duration": "2 min",
  "summary": "Profil autorisateur : vérifier, autoriser puis envoyer à la banque ; rejeter ou abandonner une transaction.",
  "start": "liste",
  "steps": {
   "liste": {
    "title": "Retrouver les transactions en attente",
    "img": "/images/pas-a-pas/omni/4-p02-5.jpg",
    "say": "Avec le profil autorisateur : « Paiement » > « Paiement » > « Paiement ». Onglet « Lot » ou « Instruments » selon le paiement. Statut « Pour Mon Autorisation » : à vous d'autoriser.",
    "tip": "Double-cliquez sur une transaction pour la vérifier avant de l'autoriser.",
    "next": "autoriser"
   },
   "autoriser": {
    "title": "Autoriser",
    "img": "/images/pas-a-pas/omni/4-p02-5.jpg",
    "say": "Cochez la transaction (case à gauche) : le bouton « Autoriser » passe en gras. Cliquez dessus. Le statut devient « Pour envoi ».",
    "next": "envoyer"
   },
   "envoyer": {
    "title": "Envoyer à la banque",
    "img": "/images/pas-a-pas/omni/4-p02-5.jpg",
    "say": "Cochez de nouveau la transaction avec le profil approprié et cliquez sur « Envoyer ». Statut : « Envoyé à la banque » — c'est seulement à ce stade que la banque traite le paiement.",
    "warn": "Une transaction autorisée mais non envoyée n'est pas traitée : vérifier ce statut quand un client signale un virement non reçu.",
    "next": "rejeter"
   },
   "rejeter": {
    "title": "Rejeter ou abandonner",
    "img": "/images/pas-a-pas/omni/4-p02-5.jpg",
    "say": "Pour une transaction non autorisée : cochez-la, cliquez sur « More », puis « Rejeter » ou « Abandonner » (suppression).",
    "done": true
   }
  }
 }
] },
        { id: "omni-plus", name: "Omni Plus", icon: "bi-buildings-fill", color: "#1B3A8A", ready: true, device: "browser", audience: "CIB",
            url: "omni.ecobank.com",
            description: "Omni Plus pour les grandes entreprises : trésorerie, relevés MT940, recherche de transactions, bénéficiaires, paiements uniques et groupés, modèles, créances et mandats, financement de la chaîne d'approvisionnement.",
            guides: [
 {
  "id": "tresorerie",
  "title": "Situation de trésorerie et soldes du jour",
  "icon": "bi-graph-up-arrow",
  "category": "Comptes",
  "source": "guide",
  "duration": "1 à 2 min",
  "summary": "Soldes et activités du jour (Intraday), historique des soldes et informations du compte.",
  "start": "menu",
  "steps": {
   "menu": {
    "title": "Ouvrir les comptes",
    "img": "/images/pas-a-pas/omni-plus/plus-p03-1.jpg",
    "say": "Dans le menu, cliquez sur « Accounts » (Comptes).",
    "next": "intraday"
   },
   "intraday": {
    "title": "Récapitulatif du compte · Intraday",
    "img": "/images/pas-a-pas/omni-plus/plus-p03-2.jpg",
    "say": "Cliquez sur « Account Summary » (Récapitulatif du compte) puis sur « Intraday » (Activités du jour).",
    "tap": [
     17.8,
     14.9
    ],
    "next": "select"
   },
   "select": {
    "title": "Actions du compte",
    "img": "/images/pas-a-pas/omni-plus/plus-p04-1.jpg",
    "say": "Sur la ligne du compte, cliquez sur le bouton « Select » (Sélectionner) puis sur « Balance History » (Historique des soldes).",
    "tap": [
     15.5,
     17.8
    ],
    "next": "historique"
   },
   "historique": {
    "title": "Historique des soldes",
    "img": "/images/pas-a-pas/omni-plus/plus-p04-2.jpg",
    "say": "Tous les détails du solde du compte s'affichent.",
    "tap": [
     54.5,
     29.3
    ],
    "next": "info"
   },
   "info": {
    "title": "Informations sur le compte",
    "img": "/images/pas-a-pas/omni-plus/plus-p04-3.jpg",
    "say": "Via « Select », cliquez sur « Account Information » : les détails du compte s'ouvrent dans une fenêtre.",
    "tap": [
     17.1,
     28.8
    ],
    "tip": "Personnalisez l'affichage avec « Column Settings », « View », « Customize table » et les filtres.",
    "done": true
   }
  }
 },
 {
  "id": "releve",
  "title": "Télécharger un relevé (PDF, Excel, MT940…)",
  "icon": "bi-file-earmark-arrow-down",
  "category": "Comptes",
  "source": "guide",
  "duration": "1 min",
  "summary": "Historique des transactions et téléchargement du relevé : XLS, CSV, TSV, MT940, MT950, AFB120 ou PDF.",
  "start": "menu",
  "steps": {
   "menu": {
    "title": "Historique des transactions",
    "img": "/images/pas-a-pas/omni-plus/plus-p07-2.jpg",
    "say": "Pointez sur « Accounts », puis « Account Summary », et cliquez sur « Transaction History ».",
    "tap": [
     27.3,
     20.9
    ],
    "next": "activites"
   },
   "activites": {
    "title": "Activités du compte",
    "img": "/images/pas-a-pas/omni-plus/plus-p08-1.jpg",
    "say": "Sur la ligne du compte, cliquez sur « Select » puis « Activities » (Activités).",
    "tap": [
     21.9,
     18.7
    ],
    "next": "format"
   },
   "format": {
    "title": "Choisir le format",
    "img": "/images/pas-a-pas/omni-plus/plus-p08-2.jpg",
    "say": "Cliquez sur le bouton de téléchargement : relevés XLS, CSV, TSV, MT940, MT950 et AFB120.",
    "tap": [
     98.0,
     5.0
    ],
    "tip": "MT940 / MT950 / AFB120 : formats bancaires que le logiciel comptable de l'entreprise sait importer.",
    "next": "pdf"
   },
   "pdf": {
    "title": "Relevé PDF",
    "img": "/images/pas-a-pas/omni-plus/plus-p08-3.jpg",
    "say": "Cliquez sur l'icône PDF pour télécharger le relevé en PDF.",
    "tap": [
     95.5,
     5.5
    ],
    "done": true
   }
  }
 },
 {
  "id": "cash-position",
  "title": "Récapitulatif et recherche de transactions",
  "icon": "bi-search",
  "category": "Comptes",
  "source": "guide",
  "duration": "2 min",
  "summary": "Situation de trésorerie par catégorie, recherche avancée par date comptable et jeux de codes types.",
  "start": "menu",
  "steps": {
   "menu": {
    "title": "Récapitulatif de la situation de trésorerie",
    "img": "/images/pas-a-pas/omni-plus/plus-p09-1.jpg",
    "say": "Pointez sur « Accounts », puis cliquez sur « Cash Position Summary ».",
    "tap": [
     13.7,
     12.0
    ],
    "next": "filtre"
   },
   "filtre": {
    "title": "Afficher la situation",
    "img": "/images/pas-a-pas/omni-plus/plus-p09-2.jpg",
    "say": "Cliquez sur « Filter » : la situation s'affiche ; « Account » et « Transaction » donnent le détail du compte ou des transactions.",
    "tap": [
     24.4,
     42.3
    ],
    "next": "recherche"
   },
   "recherche": {
    "title": "Recherche de transaction",
    "img": "/images/pas-a-pas/omni-plus/plus-p10-1.jpg",
    "say": "Pointez sur « Accounts », puis cliquez sur « Transaction Search ».",
    "tap": [
     13.3,
     22.7
    ],
    "next": "avance"
   },
   "avance": {
    "title": "Filtre avancé",
    "img": "/images/pas-a-pas/omni-plus/plus-p10-2.jpg",
    "say": "Dans « Advanced Filter », indiquez la « Posting Date » (date comptable), puis cliquez sur « Search ».",
    "tap": [
     79.9,
     61.5
    ],
    "next": "codes"
   },
   "codes": {
    "title": "Gérer un jeu de codes types",
    "img": "/images/pas-a-pas/omni-plus/plus-p11-1.jpg",
    "say": "Cliquez sur « Manage Type Code » pour regrouper les codes types que vous recherchez souvent.",
    "tap": [
     76.5,
     19.3
    ],
    "next": "enregistrer"
   },
   "enregistrer": {
    "title": "Enregistrer le jeu de codes",
    "img": "/images/pas-a-pas/omni-plus/plus-p11-2.jpg",
    "say": "Sélectionnez les codes, saisissez le « Type Code Set Name », puis « Save ».",
    "tap": [
     73.7,
     63.2
    ],
    "done": true
   }
  }
 },
 {
  "id": "beneficiaires",
  "title": "Créer un bénéficiaire",
  "icon": "bi-person-plus",
  "category": "Paiements",
  "source": "guide",
  "duration": "2 min",
  "summary": "Bénéficiaire enregistré pour les paiements suivants : détails, banque, vérification et soumission.",
  "start": "menu",
  "steps": {
   "menu": {
    "title": "Bénéficiaires",
    "img": "/images/pas-a-pas/omni-plus/plus-p12-1.jpg",
    "say": "Pointez sur « Payments », puis cliquez sur « Beneficiaries ».",
    "tap": [
     21.6,
     31.2
    ],
    "next": "creer"
   },
   "creer": {
    "title": "Créer",
    "img": "/images/pas-a-pas/omni-plus/plus-p12-2.jpg",
    "say": "Cliquez sur « Create Beneficiaries ».",
    "tap": [
     94.7,
     17.3
    ],
    "next": "details"
   },
   "details": {
    "title": "Détails du bénéficiaire",
    "img": "/images/pas-a-pas/omni-plus/plus-p13-1.jpg",
    "say": "Saisissez les détails : nom de l'entreprise ou du bénéficiaire, type de paiement, numéro de compte, banque…",
    "next": "suivant"
   },
   "suivant": {
    "title": "Vérifier puis soumettre",
    "img": "/images/pas-a-pas/omni-plus/plus-p13-3.jpg",
    "say": "Cliquez sur « Next » pour vérifier, puis soumettez.",
    "tap": [
     96.8,
     22.5
    ],
    "tip": "Le bénéficiaire suit ensuite le circuit d'approbation de l'entreprise.",
    "done": true
   }
  }
 },
 {
  "id": "paiement-unique",
  "title": "Paiement unique : créer, approuver, envoyer",
  "icon": "bi-send",
  "category": "Paiements",
  "source": "guide",
  "duration": "3 à 4 min",
  "summary": "Du Centre de paiement à « Sent to Bank » : initiateur, vérificateur puis envoi à la banque.",
  "start": "centre",
  "steps": {
   "centre": {
    "title": "Centre de paiement",
    "img": "/images/pas-a-pas/omni-plus/plus-p14-1.jpg",
    "say": "Pointez sur « Payment », puis cliquez sur « Payment Center ».",
    "tap": [
     15.9,
     12.6
    ],
    "next": "unique"
   },
   "unique": {
    "title": "Paiement unique",
    "img": "/images/pas-a-pas/omni-plus/plus-p14-2.jpg",
    "say": "Cliquez sur « Single Payment ».",
    "tap": [
     86.9,
     18.7
    ],
    "next": "produit"
   },
   "produit": {
    "title": "Type de produit de paiement",
    "img": "/images/pas-a-pas/omni-plus/plus-p15-1.jpg",
    "say": "Créez le paiement en choisissant le type de produit et son paquet.",
    "tap": [
     34.3,
     46.4
    ],
    "next": "verifier"
   },
   "verifier": {
    "title": "Vérifier le paiement",
    "img": "/images/pas-a-pas/omni-plus/plus-p15-2.jpg",
    "say": "Complétez les détails puis cliquez sur « Verify Payment ».",
    "tap": [
     95.8,
     13.0
    ],
    "next": "soumettre"
   },
   "soumettre": {
    "title": "Soumettre",
    "img": "/images/pas-a-pas/omni-plus/plus-p16-1.jpg",
    "say": "Cliquez sur « Submit » : la transaction passe de la file de l'initiateur à celle du contrôleur.",
    "tap": [
     94.9,
     58.1
    ],
    "next": "approuver"
   },
   "approuver": {
    "title": "Approbation par le vérificateur",
    "img": "/images/pas-a-pas/omni-plus/plus-p16-2.jpg",
    "say": "Le vérificateur clique sur « Select » puis « Approve »…",
    "tap": [
     7.9,
     45.0
    ],
    "next": "confirmer"
   },
   "confirmer": {
    "title": "Confirmer l'approbation",
    "img": "/images/pas-a-pas/omni-plus/plus-p16-3.jpg",
    "say": "… et confirme avec « Approve » dans la fenêtre.",
    "tap": [
     74.8,
     29.6
    ],
    "next": "envoyer"
   },
   "envoyer": {
    "title": "Envoyer à la banque",
    "img": "/images/pas-a-pas/omni-plus/plus-p17-1.jpg",
    "say": "Cliquez sur le bouton d'action « Send ».",
    "tap": [
     10.5,
     65.2
    ],
    "next": "statut"
   },
   "statut": {
    "title": "Statut « Sent to Bank »",
    "img": "/images/pas-a-pas/omni-plus/plus-p17-2.jpg",
    "say": "Le statut « Sent to Bank » confirme que la banque a reçu la transaction.",
    "tap": [
     44.3,
     43.9
    ],
    "warn": "Tant que le statut n'est pas « Sent to Bank », la banque ne traite pas le paiement.",
    "done": true
   }
  }
 },
 {
  "id": "paiement-groupe",
  "title": "Paiement groupé (salaires, fournisseurs)",
  "icon": "bi-people",
  "category": "Paiements",
  "source": "guide",
  "duration": "3 min",
  "summary": "Plusieurs paiements dans un même lot, saisis ou téléchargés par fichier.",
  "start": "centre",
  "steps": {
   "centre": {
    "title": "Centre de paiement",
    "img": "/images/pas-a-pas/omni-plus/plus-p18-1.jpg",
    "say": "Pointez sur « Payment », puis cliquez sur « Payment Center ».",
    "tap": [
     15.9,
     12.5
    ],
    "next": "groupe"
   },
   "groupe": {
    "title": "Paiement groupé",
    "img": "/images/pas-a-pas/omni-plus/plus-p18-2.jpg",
    "say": "Cliquez sur « Batch Payment ».",
    "tap": [
     95.4,
     17.6
    ],
    "next": "produit"
   },
   "produit": {
    "title": "Produit et paquet",
    "img": "/images/pas-a-pas/omni-plus/plus-p19-1.jpg",
    "say": "Choisissez le type de produit de paiement et son paquet.",
    "tap": [
     49.6,
     44.1
    ],
    "next": "details"
   },
   "details": {
    "title": "Détails des instruments",
    "img": "/images/pas-a-pas/omni-plus/plus-p19-2.jpg",
    "say": "Saisissez tous les paiements du lot, puis « Verify ».",
    "tap": [
     93.7,
     48.6
    ],
    "next": "soumettre"
   },
   "soumettre": {
    "title": "Soumettre",
    "img": "/images/pas-a-pas/omni-plus/plus-p19-3.jpg",
    "say": "Cliquez sur « Submit » : le lot part en approbation ; après approbation, il est envoyé à la banque.",
    "tap": [
     95.5,
     30.2
    ],
    "done": true
   }
  }
 },
 {
  "id": "modeles",
  "title": "Modèles de paiement (unique ou groupé)",
  "icon": "bi-files",
  "category": "Paiements",
  "source": "guide",
  "duration": "3 min",
  "summary": "Enregistrer les détails d'un paiement pour le refaire plus vite.",
  "start": "menu",
  "steps": {
   "menu": {
    "title": "Modèles",
    "img": "/images/pas-a-pas/omni-plus/plus-p20-1.jpg",
    "say": "Pointez sur « Payment », puis cliquez sur « Template ».",
    "tap": [
     20.5,
     18.6
    ],
    "next": "type"
   },
   "type": {
    "title": "Type de modèle",
    "img": "/images/pas-a-pas/omni-plus/plus-p20-2.jpg",
    "say": "Cliquez sur « Single Payment Template » (paiement unique) ; pour un lot : « Batch Payment Template ».",
    "tap": [
     80.5,
     19.8
    ],
    "choices": [
     {
      "label": "Modèle de paiement unique",
      "next": "produit"
     },
     {
      "label": "Modèle de paiement groupé",
      "next": "gproduit"
     }
    ]
   },
   "produit": {
    "title": "Produit et paquet",
    "img": "/images/pas-a-pas/omni-plus/plus-p21-1.jpg",
    "say": "Choisissez le « Payment Product Type » et son paquet, puis « Next ».",
    "tap": [
     95.5,
     56.4
    ],
    "next": "verifier"
   },
   "verifier": {
    "title": "Vérifier et soumettre",
    "img": "/images/pas-a-pas/omni-plus/plus-p22-1.jpg",
    "say": "Saisissez les détails, « Verify », puis « Submit » : le modèle part chez le vérificateur.",
    "tap": [
     90.1,
     53.6
    ],
    "done": true
   },
   "gproduit": {
    "title": "Modèle groupé : produit et paquet",
    "img": "/images/pas-a-pas/omni-plus/plus-p24-1.jpg",
    "say": "Choisissez le « Payment Product Type » et son « Package », puis « Next », saisissez les détails et « Verify ».",
    "tap": [
     50.4,
     45.1
    ],
    "next": "assistant"
   },
   "assistant": {
    "title": "Assistant de transaction",
    "img": "/images/pas-a-pas/omni-plus/plus-p25-1.jpg",
    "say": "Cliquez sur « Use Transaction Wizard » pour détailler chaque instrument du lot, en respectant le total de contrôle, puis soumettez.",
    "tap": [
     8.3,
     50.5
    ],
    "done": true
   }
  }
 },
 {
  "id": "creances",
  "title": "Créances : donneurs d'ordre, mandats, encaissements",
  "icon": "bi-box-arrow-in-down",
  "category": "Créances",
  "source": "guide",
  "duration": "4 min",
  "summary": "Créer un donneur d'ordre, un mandat de prélèvement, puis lancer une créance simple ou groupée.",
  "start": "payer",
  "steps": {
   "payer": {
    "title": "Donneurs d'ordre",
    "img": "/images/pas-a-pas/omni-plus/plus-p26-1.jpg",
    "say": "Pointez sur « Receivables », puis cliquez sur « Payer ».",
    "tap": [
     33.9,
     19.0
    ],
    "next": "creer"
   },
   "creer": {
    "title": "Créer un donneur d'ordre",
    "img": "/images/pas-a-pas/omni-plus/plus-p26-2.jpg",
    "say": "Cliquez sur « Create Payer », saisissez les détails, « Next » puis « Submit ».",
    "tap": [
     95.6,
     17.8
    ],
    "next": "mandat"
   },
   "mandat": {
    "title": "Mandats",
    "img": "/images/pas-a-pas/omni-plus/plus-p28-1.jpg",
    "say": "Pointez sur « Receivables », puis cliquez sur « Mandates ».",
    "tap": [
     34.6,
     22.0
    ],
    "next": "cmandat"
   },
   "cmandat": {
    "title": "Créer un mandat",
    "img": "/images/pas-a-pas/omni-plus/plus-p28-2.jpg",
    "say": "Cliquez sur « Create Mandate », saisissez les détails, « Next », vérifiez puis « Submit » : la banque pourra effectuer les débits.",
    "tap": [
     95.0,
     18.3
    ],
    "next": "creance"
   },
   "creance": {
    "title": "Lancer une créance",
    "img": "/images/pas-a-pas/omni-plus/plus-p31-1.jpg",
    "say": "« Receivable » > « Receivable Center » > « Create Receivable » ; choisissez le produit et son paquet, « Next », saisissez les détails et « Save ».",
    "tap": [
     50.5,
     43.4
    ],
    "next": "verifier"
   },
   "verifier": {
    "title": "Vérifier",
    "img": "/images/pas-a-pas/omni-plus/plus-p32-1.jpg",
    "say": "Cliquez sur « Verify » ; pour un lot, « Use Transaction Wizard » permet de saisir chaque instrument (total de contrôle).",
    "tap": [
     7.1,
     51.5
    ],
    "next": "approuver"
   },
   "approuver": {
    "title": "Soumettre, approuver, envoyer",
    "img": "/images/pas-a-pas/omni-plus/plus-p33-2.jpg",
    "say": "« Submit » envoie à l'autorisateur, qui choisit « Select » > « Approve » ; puis « Select » > « Send » transmet à la banque.",
    "tap": [
     10.2,
     38.7
    ],
    "done": true
   }
  }
 },
 {
  "id": "scf-commande",
  "title": "Chaîne d'approvisionnement : bon de commande",
  "icon": "bi-cart-check",
  "category": "Financement",
  "source": "guide",
  "duration": "4 min",
  "summary": "Relation client principal – fournisseur : création, approbation, envoi et acceptation du bon de commande.",
  "start": "menu",
  "steps": {
   "menu": {
    "title": "Centre de commande",
    "img": "/images/pas-a-pas/omni-plus/plus-p34-1.jpg",
    "say": "Pointez sur « SCF », puis « PO Center », et cliquez sur « Purchase Order ».",
    "tap": [
     51.0,
     14.2
    ],
    "next": "creer"
   },
   "creer": {
    "title": "Créer un bon de commande",
    "img": "/images/pas-a-pas/omni-plus/plus-p34-2.jpg",
    "say": "Cliquez sur « Create Purchase Order ».",
    "tap": [
     93.0,
     11.4
    ],
    "next": "produit"
   },
   "produit": {
    "title": "Produit et paquet",
    "img": "/images/pas-a-pas/omni-plus/plus-p35-1.jpg",
    "say": "Choisissez le type de produit du bon de commande et son paquet, puis « Next ».",
    "tap": [
     50.7,
     27.6
    ],
    "next": "details"
   },
   "details": {
    "title": "Détails du bon",
    "img": "/images/pas-a-pas/omni-plus/plus-p35-2.jpg",
    "say": "Saisissez les détails, « Verify », puis « Submit ».",
    "tap": [
     95.7,
     56.4
    ],
    "next": "approuver"
   },
   "approuver": {
    "title": "Approbation",
    "img": "/images/pas-a-pas/omni-plus/plus-p36-1.jpg",
    "say": "L'autorisateur approuve le bon de commande.",
    "tap": [
     10.6,
     42.9
    ],
    "next": "envoyer"
   },
   "envoyer": {
    "title": "Envoi au fournisseur",
    "img": "/images/pas-a-pas/omni-plus/plus-p36-2.jpg",
    "say": "L'initiateur l'envoie au fournisseur avec « Send ».",
    "tap": [
     10.9,
     43.3
    ],
    "next": "accepter"
   },
   "accepter": {
    "title": "Acceptation par le fournisseur",
    "img": "/images/pas-a-pas/omni-plus/plus-p37-1.jpg",
    "say": "Le fournisseur ouvre « SCF » > « PO Center » > « Acceptance Approval », puis « Accept » ; il vérifie, soumet, fait approuver et renvoie l'approbation au client principal avec « Send ».",
    "tap": [
     46.3,
     10.8
    ],
    "done": true
   }
  }
 },
 {
  "id": "scf-facture",
  "title": "Factures, paiement, financement et remboursement",
  "icon": "bi-receipt",
  "category": "Financement",
  "source": "guide",
  "duration": "4 min",
  "summary": "Facture du fournisseur, paiement immédiat (Pay Now), demande de financement et remboursement de prêt.",
  "start": "menu",
  "steps": {
   "menu": {
    "title": "Centre de facturation",
    "img": "/images/pas-a-pas/omni-plus/plus-p39-1.jpg",
    "say": "Pointez sur « SCF », puis « Invoice Center », et cliquez sur « Invoice ».",
    "tap": [
     46.6,
     12.4
    ],
    "next": "creer"
   },
   "creer": {
    "title": "Créer une facture",
    "img": "/images/pas-a-pas/omni-plus/plus-p39-2.jpg",
    "say": "Cliquez sur « Create Invoice », choisissez le produit et son paquet, « Next ».",
    "tap": [
     95.1,
     5.1
    ],
    "next": "details"
   },
   "details": {
    "title": "Détails de la facture",
    "img": "/images/pas-a-pas/omni-plus/plus-p40-1.jpg",
    "say": "Saisissez les détails, « Verify », « Submit » ; l'autorisateur approuve puis l'initiateur envoie la facture au client principal (« Send »).",
    "tap": [
     94.6,
     56.2
    ],
    "next": "choix"
   },
   "choix": {
    "title": "Le client principal règle la facture",
    "img": "/images/pas-a-pas/omni-plus/plus-p42-1.jpg",
    "say": "Depuis la facture, menu « Select » :",
    "tap": [
     12.3,
     37.6
    ],
    "choices": [
     {
      "label": "Payer maintenant (Pay Now)",
      "next": "paynow"
     },
     {
      "label": "Demander un financement",
      "next": "finance"
     },
     {
      "label": "Rembourser un prêt",
      "next": "rembourser"
     }
    ]
   },
   "paynow": {
    "title": "Payer maintenant",
    "img": "/images/pas-a-pas/omni-plus/plus-p42-2.jpg",
    "say": "« Pay Now » : saisissez les détails, « Verify » puis « Submit » pour passer en approbation.",
    "tap": [
     95.5,
     47.4
    ],
    "done": true
   },
   "finance": {
    "title": "Demande de financement",
    "img": "/images/pas-a-pas/omni-plus/plus-p43-2.jpg",
    "say": "« Request Finance » : saisissez les détails, « Verify », « Submit » ; l'autorisateur approuve et « Send » transmet la demande à la banque. Le statut change quand la banque accorde la facilité.",
    "tap": [
     12.4,
     47.5
    ],
    "done": true
   },
   "rembourser": {
    "title": "Remboursement de prêt",
    "img": "/images/pas-a-pas/omni-plus/plus-p47-2.jpg",
    "say": "« Pay Now » sur la facture financée : saisissez les détails du prêt, « Next », « Submit » ; l'autorisateur approuve en indiquant le montant, puis l'initiateur envoie (« Send »). Suivez l'état dans « Payment ».",
    "tap": [
     13.2,
     35.4
    ],
    "done": true
   }
  }
 }
] }
    );
})();
