/* Cartographie des cas RCC — table de classification CRM (produit → incident → type → catégorie → sous-catégorie → option FCR).
 * Source : tableau de cartographie transmis par la QA. FCR : R = résolu au 1er contact, A = autorisation requise,
 * O = autorisation requise (RCC Outbound), G = selon profil GTP, L = selon le linkage. */
window.RCC_CASE_MAP = [
 {
  "key": "agences",
  "label": "Agences",
  "icon": "bi-bank2",
  "color": "#0057B8",
  "cases": [
   {
    "id": "agences-1",
    "t": "DEMANDE CONTACT/LOCALISATION DE L'AGENCE",
    "type": "Demande",
    "cat": "Service bancaire de l'agence",
    "sub": "Information sur l'agent",
    "fcr": "R"
   },
   {
    "id": "agences-2",
    "t": "DEMANDE HORAIRE DES AGENCES",
    "type": "Demande",
    "cat": "Service bancaire de l'agence",
    "sub": "Information sur l'agent",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "appels",
  "label": "Appels (interrompu, inaudible, rappel, CIB)",
  "icon": "bi-telephone-x-fill",
  "color": "#7B3FE4",
  "cases": [
   {
    "id": "appels-1",
    "t": "APPEL INTERROMPU",
    "type": "Enquête",
    "cat": "Compte",
    "sub": "Suivi",
    "fcr": "R"
   },
   {
    "id": "appels-2",
    "t": "APPEL INAUDIBLE",
    "type": "Enquête",
    "cat": "Compte",
    "sub": "Suivi",
    "fcr": "R"
   },
   {
    "id": "appels-3",
    "t": "RAPPEL CLIENT",
    "type": "Demande",
    "cat": "Compte",
    "sub": "Suivi",
    "fcr": "O"
   },
   {
    "id": "appels-4",
    "t": "CIB AU INBOUND",
    "type": "Enquête",
    "cat": "Compte",
    "sub": "Suivi",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "bourse",
  "label": "Bourses Sénégal | Togo | Bénin",
  "icon": "bi-graph-up-arrow",
  "color": "#00897B",
  "cases": [
   {
    "id": "bourse-1",
    "t": "INFORMATION BOURSE (SENEGAL, TOGO, BENIN)",
    "type": "Enquête",
    "cat": "Compte",
    "sub": "Information sur la bourse",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "autres",
  "label": "Autres",
  "icon": "bi-three-dots",
  "color": "#5C6BC0",
  "cases": [
   {
    "id": "autres-1",
    "t": "DOCUMENTS EGARES (CNI EGAREE)",
    "type": "Enquête",
    "cat": "Compte",
    "sub": "Document égaré",
    "fcr": "R"
   },
   {
    "id": "autres-2",
    "t": "PAIEMENT DE TIMBRE DE PASSEPORT",
    "type": "Demande",
    "cat": "Compte",
    "sub": "Paiement de timbre de passeport",
    "fcr": "R"
   },
   {
    "id": "autres-3",
    "t": "HOMMAGE",
    "type": "Commentaires",
    "cat": "Retours positifs",
    "sub": "Choisir la catégorie pour laquelle le client nous remercie",
    "fcr": "R"
   },
   {
    "id": "autres-4",
    "t": "INFORMATION SMS/MAILS RECUS",
    "type": "Enquête",
    "cat": "Compte",
    "sub": "Types de compte",
    "fcr": "R"
   },
   {
    "id": "autres-5",
    "t": "INFORMATION CONTACT GESTIONNAIRE",
    "type": "Enquête",
    "cat": "Services bancaires d'agence",
    "sub": "Information sur l'Area Manager",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "carte",
  "label": "Carte",
  "icon": "bi-credit-card-2-front-fill",
  "color": "#E0435B",
  "cases": [
   {
    "id": "carte-1",
    "t": "CARTE CAPTUREE (GUICHET ECOBANK/CONFRERE)",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Utilisation de la carte",
    "fcr": "R"
   },
   {
    "id": "carte-2",
    "t": "CODE PIN NON DELIVRE",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Émission du NIP",
    "fcr": "R"
   },
   {
    "id": "carte-3",
    "t": "CARTE EXPIREE/COMMANDE (VISA, MASTERCARD, CASHXPRESS, ETC.)",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Renouvellement de carte",
    "fcr": "R"
   },
   {
    "id": "carte-4",
    "t": "DEBLOCAGE VBV",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Activation",
    "fcr": "A"
   },
   {
    "id": "carte-5",
    "t": "DEMANDE DE TRAVEL NOTICE",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Emplacement accepté (spécifique à un pays)",
    "fcr": "R"
   },
   {
    "id": "carte-6",
    "t": "INFORMATION SUR LE CVV",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Utilisation de la carte",
    "fcr": "R"
   },
   {
    "id": "carte-7",
    "t": "FRAIS DE CARTE MAGNETIQUE (LIEE AU COMPTE ET PREPAYEE)",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Frais et redevance",
    "fcr": "R"
   },
   {
    "id": "carte-8",
    "t": "DEMANDE INFORMATION EMISSION DE CARTE",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Demande de renseignement sur la durée d'émission de la carte",
    "fcr": "R"
   },
   {
    "id": "carte-9",
    "t": "DEMANDE INFORMATION RECHARGEMENT DE LA CARTE CASHXPRESS",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Chargement de la carte prépayée",
    "fcr": "R"
   },
   {
    "id": "carte-10",
    "t": "DEMANDE INFORMATION LIMITE DES TRANSACTIONS",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Limite de transaction",
    "fcr": "R"
   },
   {
    "id": "carte-11",
    "t": "DEMANDE D'INFORMATION TRANSFERT P2P CASHXPRESS",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Transfert",
    "fcr": "R"
   },
   {
    "id": "carte-12",
    "t": "DEMANDE DE SOLDE CARTE CASHXPRESS",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Solde de carte",
    "fcr": "R"
   },
   {
    "id": "carte-13",
    "t": "DEMANDE INFORMATION SUR LES TYPES DE CARTES",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Types de cartes",
    "fcr": "R"
   },
   {
    "id": "carte-14",
    "t": "REEDITION CODE PIN",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Réédition du NIP",
    "fcr": "R"
   },
   {
    "id": "carte-15",
    "t": "CARTE MAGNETIQUE EGAREE",
    "type": "Demande",
    "cat": "Cartes",
    "sub": "Blocage de la carte",
    "fcr": "G"
   },
   {
    "id": "carte-16",
    "t": "AUGMENTATION DE LIMITE (INF A 3000 USD)",
    "type": "Demande",
    "cat": "Cartes",
    "sub": "Modification de la limite de la carte",
    "fcr": "A"
   },
   {
    "id": "carte-17",
    "t": "CARTE SUPPLEMENTAIRE",
    "type": "Demande",
    "cat": "Cartes",
    "sub": "Demande de carte supplémentaire",
    "fcr": "R"
   },
   {
    "id": "carte-18",
    "t": "DEMANDE DE LINKAGE",
    "type": "Demande",
    "cat": "Cartes",
    "sub": "Couplage de compte",
    "fcr": "R"
   },
   {
    "id": "carte-19",
    "t": "CARTE COMPLAINT",
    "type": "Demande",
    "cat": "Cartes",
    "sub": "Réactivation",
    "fcr": "A"
   },
   {
    "id": "carte-20",
    "t": "REINITIALISATION PASS OU WEB CODE CASH XPRESS",
    "type": "Demande",
    "cat": "GTP",
    "sub": "Réinitialisation du code PIN",
    "fcr": "G"
   },
   {
    "id": "carte-21",
    "t": "BLOCAGE/DESACTIVATION CASHXPRESS",
    "type": "Demande",
    "cat": "GTP",
    "sub": "Bloquer/Désactiver",
    "fcr": "G"
   }
  ]
 },
 {
  "key": "carte-virtuelle",
  "label": "Carte virtuelle",
  "icon": "bi-phone-vibrate-fill",
  "color": "#AD1457",
  "cases": [
   {
    "id": "carte-virtuelle-1",
    "t": "ASSISTANCE CREATION CARTE VIRTUELLE",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Création de carte virtuelle",
    "fcr": "R"
   },
   {
    "id": "carte-virtuelle-2",
    "t": "ASSISTANCE RECHARGEMENT CARTE VIRTUELLE",
    "type": "Enquête",
    "cat": "Cartes",
    "sub": "Chargement de carte virtuelle",
    "fcr": "R"
   },
   {
    "id": "carte-virtuelle-3",
    "t": "INFORMATION CARTE VIRTUELLE (COUT, SUPPRESSION, DELAI DE VALIDITE)",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Carte virtuelle",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "gab",
  "label": "ATM / GAB",
  "icon": "bi-cash-stack",
  "color": "#2E7D32",
  "cases": [
   {
    "id": "gab-1",
    "t": "LOCALISATION/DISPONIBILITE DU GAB",
    "type": "Enquête",
    "cat": "Guichets automatiques",
    "sub": "Emplacement du guichet automatique",
    "fcr": "R"
   },
   {
    "id": "gab-2",
    "t": "ASSISTANCE UTILISATION DU GAB",
    "type": "Enquête",
    "cat": "Guichets automatiques",
    "sub": "Utilisation du GAB",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "prets",
  "label": "Prêts",
  "icon": "bi-piggy-bank-fill",
  "color": "#F57C00",
  "cases": [
   {
    "id": "prets-1",
    "t": "DEMANDE INFORMATION PRET (PRET ET RACHAT DE PRET)",
    "type": "Demande",
    "cat": "Compte",
    "sub": "Prêt personnel",
    "fcr": "R"
   },
   {
    "id": "prets-2",
    "t": "DEMANDE INFORMATION PRET",
    "type": "Enquête",
    "cat": "Services bancaires d'agence",
    "sub": "Demande de prêt",
    "fcr": "R"
   },
   {
    "id": "prets-3",
    "t": "INFORMATION CASH COLL",
    "type": "Enquête",
    "cat": "Compte",
    "sub": "Information sur le compte",
    "fcr": "R"
   },
   {
    "id": "prets-4",
    "t": "INFORMATION AVANCE SUR SALAIRE",
    "type": "Demande",
    "cat": "Compte",
    "sub": "Découvert",
    "fcr": "R"
   },
   {
    "id": "prets-5",
    "t": "DEMANDE TABLEAU D'AMORTISSEMENT",
    "type": "Enquête",
    "cat": "Compte",
    "sub": "Calendrier de remboursement du prêt",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "bancassurance",
  "label": "Bancassurance",
  "icon": "bi-shield-fill-check",
  "color": "#00838F",
  "cases": [
   {
    "id": "bancassurance-1",
    "t": "INFORMATION BANCASSURANCE",
    "type": "Enquête",
    "cat": "Bancassurance",
    "sub": "Politiques de bancassurance",
    "fcr": "R"
   },
   {
    "id": "bancassurance-2",
    "t": "INFORMATION RACHAT ASSURANCE",
    "type": "Enquête",
    "cat": "Bancassurance",
    "sub": "Politiques de bancassurance",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "transfert",
  "label": "Transfert : appel de fonds, Rapid Transfer, virement",
  "icon": "bi-arrow-left-right",
  "color": "#1565C0",
  "cases": [
   {
    "id": "transfert-1",
    "t": "DEMANDE INFORMATION FRAIS DE TRANSFERT",
    "type": "Enquête",
    "cat": "Paiements",
    "sub": "Frais et redevance",
    "fcr": "R"
   },
   {
    "id": "transfert-2",
    "t": "INFORMATION BANQUE CORRESPONDANTE",
    "type": "Enquête",
    "cat": "Paiements",
    "sub": "Transfert international",
    "fcr": "R"
   },
   {
    "id": "transfert-3",
    "t": "INFORMATION SUR VIREMENT SALAIRE/PENSION",
    "type": "Enquête",
    "cat": "Paiements",
    "sub": "Salaire",
    "fcr": "R"
   },
   {
    "id": "transfert-4",
    "t": "DEMANDE DE SWIFT DE TRANSACTION",
    "type": "Enquête",
    "cat": "Paiements",
    "sub": "Swift transfer enquiry",
    "fcr": "R"
   },
   {
    "id": "transfert-5",
    "t": "DEMANDE INFORMATION DELAI DE TRAITEMENT",
    "type": "Enquête",
    "cat": "Transfert rapide",
    "sub": "Durée du transfert",
    "fcr": "R"
   },
   {
    "id": "transfert-6",
    "t": "INFORMATION APPEL DE FONDS",
    "type": "Demande",
    "cat": "Paiements",
    "sub": "Transfert de fonds",
    "fcr": "R"
   },
   {
    "id": "transfert-7",
    "t": "DEMANDE CODE SWIFT",
    "type": "Enquête",
    "cat": "Paiements",
    "sub": "Code swift",
    "fcr": "R"
   },
   {
    "id": "transfert-8",
    "t": "DEMANDE INFORMATION TAUX DE CHANGE",
    "type": "Enquête",
    "cat": "Paiements",
    "sub": "Taux de change",
    "fcr": "R"
   },
   {
    "id": "transfert-9",
    "t": "DEMANDE INFORMATION TRANSFERT/VIREMENT",
    "type": "Enquête",
    "cat": "Paiements",
    "sub": "Transfert interbancaire",
    "fcr": "R"
   },
   {
    "id": "transfert-10",
    "t": "DEMANDE INFORMATION SUR LES LIMITES DE TRANSFERT",
    "type": "Enquête",
    "cat": "Transfert rapide",
    "sub": "Limite de transfert rapide",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "eol",
  "label": "Ecobank Online",
  "icon": "bi-globe2",
  "color": "#0277BD",
  "cases": [
   {
    "id": "eol-1",
    "t": "INFORMATION ECOBANK ONLINE",
    "type": "Enquête",
    "cat": "ECOBANK en ligne",
    "sub": "Inscription création d'utilisateurs",
    "fcr": "R"
   },
   {
    "id": "eol-2",
    "t": "ASSISTANCE CREATION EOL",
    "type": "Enquête",
    "cat": "ECOBANK en ligne",
    "sub": "Inscription/Création d'utilisateurs",
    "fcr": "R"
   },
   {
    "id": "eol-3",
    "t": "DEMANDE INFORMATION FRAIS DE TRANSFERT",
    "type": "Enquête",
    "cat": "ECOBANK en ligne",
    "sub": "Frais et redevance",
    "fcr": "R"
   },
   {
    "id": "eol-4",
    "t": "ASSISTANCE ACHAT DE CREDIT",
    "type": "Demande",
    "cat": "ECOBANK en ligne",
    "sub": "Option de paiement disponible",
    "fcr": "R"
   },
   {
    "id": "eol-5",
    "t": "ASSISTANCE CHANGEMENT MOT DE PASSE",
    "type": "Demande",
    "cat": "ECOBANK en ligne",
    "sub": "Nom d'utilisateur/mot de passe",
    "fcr": "R"
   },
   {
    "id": "eol-6",
    "t": "ASSISTANCE ETABLISSEMENT DES QUESTIONS DE SECURITE",
    "type": "Demande",
    "cat": "ECOBANK en ligne",
    "sub": "Réinitialisation des questions et réponses de sécurité",
    "fcr": "R"
   },
   {
    "id": "eol-7",
    "t": "ASSISTANCE TRANSFERT DE FONDS",
    "type": "Enquête",
    "cat": "ECOBANK en ligne",
    "sub": "Transfert rapide",
    "fcr": "R"
   },
   {
    "id": "eol-8",
    "t": "DEMANDE DE RESET EOL",
    "type": "Demande",
    "cat": "ECOBANK en ligne",
    "sub": "Réinitialisation du mot de passe",
    "fcr": "A"
   },
   {
    "id": "eol-9",
    "t": "ASSISTANCE VERIFICATION DU PRET",
    "type": "Enquête",
    "cat": "ECOBANK en ligne",
    "sub": "Statut du prêt",
    "fcr": "R"
   },
   {
    "id": "eol-10",
    "t": "DEMANDE DE DEVERROUILLAGE EOL",
    "type": "Demande",
    "cat": "ECOBANK en ligne",
    "sub": "Déverrouillage",
    "fcr": "A"
   },
   {
    "id": "eol-11",
    "t": "DEMANDE INFORMATION OPTION VIREMENT",
    "type": "Demande",
    "cat": "ECOBANK en ligne",
    "sub": "Activation de l'option paiement",
    "fcr": "R"
   },
   {
    "id": "eol-12",
    "t": "DEMANDE INFORMATION LIMITE DE TRANSACTION",
    "type": "Demande",
    "cat": "ECOBANK en ligne",
    "sub": "Examen de la limite",
    "fcr": "R"
   },
   {
    "id": "eol-13",
    "t": "ASSISTANCE RELEVE DE COMPTE",
    "type": "Enquête",
    "cat": "ECOBANK en ligne",
    "sub": "Relevé de compte",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "mobile",
  "label": "Mobile App",
  "icon": "bi-phone-fill",
  "color": "#6A1B9A",
  "cases": [
   {
    "id": "mobile-1",
    "t": "INFORMATION ECOBANK APPLICATION MOBILE",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Ouvrir",
    "fcr": "R"
   },
   {
    "id": "mobile-2",
    "t": "DEMANDE CREATION DE PROFIL APPLICATION MOBILE",
    "type": "Demande",
    "cat": "Application mobile",
    "sub": "Activation des services bancaires mobiles",
    "fcr": "A"
   },
   {
    "id": "mobile-3",
    "t": "DEVERROUILLAGE MOBILE APP",
    "type": "Demande",
    "cat": "Application mobile",
    "sub": "Déverrouillez l'appli mobile",
    "fcr": "A"
   },
   {
    "id": "mobile-4",
    "t": "ASSISTANCE PAIEMENT DE FACTURE",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Paiement de factures",
    "fcr": "R"
   },
   {
    "id": "mobile-5",
    "t": "ASSISTANCE ACHAT DE CREDIT",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Recharge du temps d'antenne",
    "fcr": "R"
   },
   {
    "id": "mobile-6",
    "t": "ASSISTANCE TRAVEL NOTICE",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Services de cartes",
    "fcr": "R"
   },
   {
    "id": "mobile-7",
    "t": "TRANSFERT B2W",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Transfert B2W",
    "fcr": "R"
   },
   {
    "id": "mobile-8",
    "t": "TRANSFERT COMPTE A COMPTE",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Transfert compte à compte",
    "fcr": "R"
   },
   {
    "id": "mobile-9",
    "t": "TRANSFERT NATIONAL",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Transfert national",
    "fcr": "R"
   },
   {
    "id": "mobile-10",
    "t": "TRANSFERT INTERNATIONAL",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Transfert international",
    "fcr": "R"
   },
   {
    "id": "mobile-11",
    "t": "TRANSFERT DE FONDS",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Transfert de fonds",
    "fcr": "R"
   },
   {
    "id": "mobile-12",
    "t": "DEMANDE DE RESET MOBILE APP",
    "type": "Demande",
    "cat": "Application mobile",
    "sub": "Réinitialisation du code PIN",
    "fcr": "A"
   },
   {
    "id": "mobile-13",
    "t": "DEMANDE AJOUT DE COMPTE",
    "type": "Demande",
    "cat": "Application mobile",
    "sub": "Ajouter un compte",
    "fcr": "A"
   },
   {
    "id": "mobile-14",
    "t": "DEMANDE MISE A JOUR DU CONTACT TELEPHONIQUE",
    "type": "Demande",
    "cat": "Application mobile",
    "sub": "Mise à jour du nom",
    "fcr": "A"
   },
   {
    "id": "mobile-15",
    "t": "DEMANDE DE DESACTIVATION DE PROFIL",
    "type": "Demande",
    "cat": "Application mobile",
    "sub": "Désactivation de l'application mobile",
    "fcr": "A"
   },
   {
    "id": "mobile-16",
    "t": "ASSISTANCE RECHARGEMENT DE LA CARTE",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Services de cartes",
    "fcr": "R"
   },
   {
    "id": "mobile-17",
    "t": "ASSISTANCE RELEVE DE COMPTE",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Relevé de compte",
    "fcr": "R"
   },
   {
    "id": "mobile-18",
    "t": "DEMANDE D'INFORMATION DEPLAFONNEMENT DE COMPTE",
    "type": "Enquête",
    "cat": "Application mobile",
    "sub": "Changement de limite",
    "fcr": "R"
   },
   {
    "id": "mobile-19",
    "t": "ASSISTANCE AUTO REINITIALISATION",
    "type": "Enquête",
    "cat": "Compte Xpress",
    "sub": "Auto-réinitialisation du code PIN",
    "fcr": "R"
   },
   {
    "id": "mobile-20",
    "t": "ASSISTANCE XPRESSCASH",
    "type": "Enquête",
    "cat": "Compte Xpress",
    "sub": "Génération de jetons de trésorerie Xpress (Xpress espèces)",
    "fcr": "R"
   },
   {
    "id": "mobile-21",
    "t": "DEMANDE INFORMATION ECOBANK PAY",
    "type": "Enquête",
    "cat": "ECOBANK Pay",
    "sub": "ECOBANK Pay",
    "fcr": "R"
   },
   {
    "id": "mobile-22",
    "t": "ASSISTANCE ECOBANK PAY",
    "type": "Enquête",
    "cat": "ECOBANK Pay",
    "sub": "Codes QR",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "b2w",
  "label": "Bank to Wallet",
  "icon": "bi-wallet2",
  "color": "#C62828",
  "cases": [
   {
    "id": "b2w-1",
    "t": "DEMANDE DE LINKAGE",
    "type": "Demande",
    "cat": "USSD",
    "sub": "Activation",
    "fcr": "L"
   },
   {
    "id": "b2w-2",
    "t": "DEMANDE DE DESACTIVATION BANK TO WALLET",
    "type": "Demande",
    "cat": "USSD",
    "sub": "Supprimer le profil",
    "fcr": "A"
   },
   {
    "id": "b2w-3",
    "t": "DEMANDE INFORMATION FRAIS DE TRANSFERT",
    "type": "Enquête",
    "cat": "USSD",
    "sub": "Transfert de fonds (ECOBANK)",
    "fcr": "R"
   },
   {
    "id": "b2w-4",
    "t": "ASSISTANCE RELEVE DE COMPTE",
    "type": "Enquête",
    "cat": "USSD",
    "sub": "Relevé de compte",
    "fcr": "R"
   },
   {
    "id": "b2w-5",
    "t": "DEMANDE DE TRANSFERT B2W",
    "type": "Enquête",
    "cat": "USSD",
    "sub": "Transfert B2W",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "omnilite",
  "label": "Omni Lite",
  "icon": "bi-building",
  "color": "#4527A0",
  "cases": [
   {
    "id": "omnilite-1",
    "t": "DEMANDE D'INFORMATION GENERALE SUR OMNILITE",
    "type": "Enquête",
    "cat": "OMNI LITE",
    "sub": "Omnilite",
    "fcr": "R"
   },
   {
    "id": "omnilite-2",
    "t": "DEMANDE DE REINITIALISATION DE MOT DE PASSE",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Réinitialiser le mot de passe",
    "fcr": "A"
   },
   {
    "id": "omnilite-3",
    "t": "DEMANDE DE DEVERROUILLAGE",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Déverrouillage de profil",
    "fcr": "A"
   },
   {
    "id": "omnilite-4",
    "t": "DEMANDE DE VERROUILLAGE",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Verrouillage de profil",
    "fcr": "A"
   },
   {
    "id": "omnilite-5",
    "t": "ASSISTANCE ETABLISSEMENT DES QUESTIONS DE SECURITE",
    "type": "Enquête",
    "cat": "OMNI LITE",
    "sub": "Configuration de la question de sécurité",
    "fcr": "R"
   },
   {
    "id": "omnilite-6",
    "t": "ASSISTANCE MODIFICATION D'UN UTILISATEUR",
    "type": "Enquête",
    "cat": "OMNI LITE",
    "sub": "Modification d'un utilisateur",
    "fcr": "R"
   },
   {
    "id": "omnilite-7",
    "t": "DEMANDE DE SUPPRESSION D'UN UTILISATEUR",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Supprimer l'utilisateur",
    "fcr": "A"
   },
   {
    "id": "omnilite-8",
    "t": "ASSISTANCE TRANSFERT/PAIEMENT DE SALAIRE",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Omnilite guide de l'utilisateur",
    "fcr": "R"
   },
   {
    "id": "omnilite-9",
    "t": "ACTIVATION DE JETON",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Demande de jeton matériel",
    "fcr": "A"
   },
   {
    "id": "omnilite-10",
    "t": "ASSISTANCE D'AVIS DE CREDIT",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Générer un relevé / avis de paiement",
    "fcr": "A"
   },
   {
    "id": "omnilite-11",
    "t": "DEMANDE DE RELEVE DE COMPTE",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Générer un relevé / avis de paiement",
    "fcr": "R"
   },
   {
    "id": "omnilite-12",
    "t": "DEMANDE INFORMATION LIMITE DE TRANSACTION",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Examen de la limite",
    "fcr": "A"
   },
   {
    "id": "omnilite-13",
    "t": "DEMANDE D'INFORMATION SUR LE NOM D'UTILISATEUR",
    "type": "Demande",
    "cat": "OMNI LITE",
    "sub": "Demande de nom d'utilisateur",
    "fcr": "R"
   }
  ]
 },
 {
  "key": "omniplus",
  "label": "Omni Plus",
  "icon": "bi-buildings-fill",
  "color": "#283593",
  "cases": [
   {
    "id": "omniplus-1",
    "t": "DEMANDE D'INFORMATION GENERALE SUR OMNIPLUS",
    "type": "Enquête",
    "cat": "Omniplus",
    "sub": "Omniplus",
    "fcr": "R"
   },
   {
    "id": "omniplus-2",
    "t": "DEMANDE DE REINITIALISATION DE MOT DE PASSE",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Réinitialisation du mot de passe",
    "fcr": "A"
   },
   {
    "id": "omniplus-3",
    "t": "DEMANDE DE DEVERROUILLAGE",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Déverrouillage de profil",
    "fcr": "A"
   },
   {
    "id": "omniplus-4",
    "t": "ASSISTANCE ETABLISSEMENT DES QUESTIONS DE SECURITE",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Guide de l'utilisation Omniplus",
    "fcr": "R"
   },
   {
    "id": "omniplus-5",
    "t": "ASSISTANCE MODIFICATION D'UN UTILISATEUR",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Modification de l'utilisateur",
    "fcr": "A"
   },
   {
    "id": "omniplus-6",
    "t": "DEMANDE DE SUPPRESSION D'UN UTILISATEUR",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Supprimer l'utilisateur",
    "fcr": "A"
   },
   {
    "id": "omniplus-7",
    "t": "ASSISTANCE TRANSFERT/PAIEMENT DE SALAIRE",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Omniplus guide de l'utilisateur",
    "fcr": "R"
   },
   {
    "id": "omniplus-8",
    "t": "ACTIVATION DE JETON",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Activation du jeton",
    "fcr": "A"
   },
   {
    "id": "omniplus-9",
    "t": "ASSISTANCE MODIFICATION DE LA LANGUE",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Changer la langue du profil",
    "fcr": "R"
   },
   {
    "id": "omniplus-10",
    "t": "VERIFICATION DE TRANSACTIONS",
    "type": "Enquête",
    "cat": "Omniplus",
    "sub": "Confirmation de paiement",
    "fcr": "R"
   },
   {
    "id": "omniplus-11",
    "t": "ASSISTANCE D'AVIS DE CREDIT",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Générer un relevé / avis de paiement",
    "fcr": "A"
   },
   {
    "id": "omniplus-12",
    "t": "DEMANDE DE RELEVE DE COMPTE",
    "type": "Demande",
    "cat": "Omniplus",
    "sub": "Générer un relevé / avis de paiement",
    "fcr": "R"
   },
   {
    "id": "omniplus-13",
    "t": "DEMANDE D'INFORMATION SUR LE NOM D'UTILISATEUR",
    "type": "Enquête",
    "cat": "Omniplus",
    "sub": "Demande de nom d'utilisateur",
    "fcr": "R"
   },
   {
    "id": "omniplus-14",
    "t": "DEMANDE INFORMATION LIMITE DE TRANSACTION",
    "type": "Enquête",
    "cat": "Omniplus",
    "sub": "Examen de la limite",
    "fcr": "R"
   }
  ]
 }
];
