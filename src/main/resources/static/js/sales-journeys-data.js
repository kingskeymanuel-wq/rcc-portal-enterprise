"use strict";

/**
 * Parcours de vente interactifs — un par produit digital Ecobank, pensés pour un conseiller
 * Outbound débutant : assimilation rapide + accès rapide à l'argumentaire pendant l'appel.
 * Méthode commune à tous les parcours (vente par les besoins, standard et éprouvée) :
 *   1. Découverte — questions à poser avant de présenter quoi que ce soit
 *   2. Présentation — bénéfices concrets pour le client, pas juste les fonctionnalités
 *   3. Objections — les plus fréquentes, avec une réponse prête à l'emploi
 *   4. Closing — comment conclure sans être insistant
 *
 * ⚠ Base de démarrage à affiner par QA/le Team Leader Outbound au fil de l'expérience
 * terrain — les arguments ci-dessous sont des méthodes de vente générales appliquées à
 * chaque produit, pas des scripts validés par le marketing Ecobank.
 */
window.RCC_SALES_JOURNEYS = [
    {
        key: "ecobank-mobile",
        title: "Ecobank Mobile App",
        icon: "bi-phone-fill",
        colorFrom: "#0057B8", colorTo: "#00A651",
        pitch: "L'app pour gérer son compte sans se déplacer en agence.",
        steps: [
            {
                title: "1. Découverte",
                content: [
                    "Combien de fois par mois vous déplacez-vous en agence pour consulter votre solde ou faire un virement ?",
                    "Avez-vous déjà eu besoin de faire une opération un dimanche ou un jour férié ?",
                    "Utilisez-vous déjà une application bancaire, chez nous ou ailleurs ?"
                ]
            },
            {
                title: "2. Présentation — bénéfices, pas fonctionnalités",
                content: [
                    "Consultez votre solde et vos dernières opérations en 10 secondes, 24h/24 — plus besoin de faire la queue pour un simple contrôle.",
                    "Faites vos virements et payez vos factures depuis votre canapé — gain de temps réel, surtout en fin de mois.",
                    "Recevez une alerte immédiate à chaque mouvement sur le compte — sécurité et tranquillité d'esprit.",
                    "Gratuite, disponible sur Android et iOS, activable en quelques minutes avec votre numéro de compte."
                ]
            },
            {
                title: "3. Objections fréquentes",
                content: [
                    "« Je n'ai pas de smartphone performant » → L'appli est légère, elle fonctionne sur tous les smartphones récents, même d'entrée de gamme.",
                    "« J'ai peur de la sécurité en ligne » → Chaque connexion est protégée par un code confidentiel + une vérification supplémentaire (OTP) à chaque opération sensible ; vos données ne transitent jamais en clair.",
                    "« Je préfère aller en agence, c'est plus sûr » → L'appli ne remplace pas l'agence, elle vous évite les déplacements pour les opérations simples — vous gardez le choix à chaque fois."
                ]
            },
            {
                title: "4. Closing",
                content: [
                    "« Je peux vous accompagner maintenant pour l'activer, ça prend 3 minutes — vous voulez qu'on le fasse ensemble tout de suite ? »",
                    "Si hésitation : proposer d'envoyer le lien de téléchargement par SMS et reprendre contact dans 2-3 jours."
                ]
            }
        ]
    },
    {
        key: "cashxpress",
        title: "CASHXPRESS",
        icon: "bi-cash-coin",
        colorFrom: "#F5A623", colorTo: "#F72585",
        pitch: "Retrait et transfert d'argent sans carte, avec un simple code.",
        steps: [
            {
                title: "1. Découverte",
                content: [
                    "Vous arrive-t-il d'envoyer de l'argent à un proche qui n'a pas de compte bancaire ?",
                    "Avez-vous déjà été bloqué parce que vous n'aviez pas votre carte sur vous ?",
                    "Connaissez-vous quelqu'un dans votre entourage qui a besoin de recevoir de l'argent rapidement, sans compte ?"
                ]
            },
            {
                title: "2. Présentation — bénéfices, pas fonctionnalités",
                content: [
                    "Envoyez de l'argent à n'importe qui, même sans compte bancaire — la personne le retire avec juste un code, dans n'importe quelle agence ou DAB compatible.",
                    "Utile en dépannage : plus besoin d'avoir sa carte sur soi pour retirer, le code suffit.",
                    "Rapide à mettre en place depuis l'appli ou en agence, disponible immédiatement pour le bénéficiaire."
                ]
            },
            {
                title: "3. Objections fréquentes",
                content: [
                    "« C'est compliqué à utiliser » → Trois étapes seulement : montant, numéro du bénéficiaire, code généré automatiquement à lui transmettre.",
                    "« Les frais sont trop élevés » → Comparer avec le coût d'un déplacement ou d'un envoi par un tiers informel — la sécurité et la traçabilité ont une valeur.",
                    "« Le code peut être volé » → Le code est à usage unique et expire après un délai donné ; en cas de doute, il peut être annulé avant retrait."
                ]
            },
            {
                title: "4. Closing",
                content: [
                    "« La prochaine fois que vous devez dépanner quelqu'un rapidement, pensez-y — je peux vous montrer comment ça marche en 2 minutes maintenant si vous voulez. »"
                ]
            }
        ]
    },
    {
        key: "mobile-money",
        title: "Mobile Money (MMH)",
        icon: "bi-wallet2",
        colorFrom: "#00A651", colorTo: "#0057B8",
        pitch: "Le porte-monnaie mobile relié directement au compte bancaire.",
        steps: [
            {
                title: "1. Découverte",
                content: [
                    "Utilisez-vous déjà un service de mobile money pour vos achats du quotidien ?",
                    "Savez-vous que vous pouvez relier votre compte bancaire directement à votre mobile money ?",
                    "Combien de temps passez-vous à faire la queue pour recharger votre compte mobile money ?"
                ]
            },
            {
                title: "2. Présentation — bénéfices, pas fonctionnalités",
                content: [
                    "Transférez de l'argent entre votre compte bancaire et votre portefeuille mobile en quelques secondes, sans passer par un point de recharge.",
                    "Payez vos achats du quotidien directement depuis votre compte, sans retirer d'espèces au préalable.",
                    "Une seule interface pour gérer votre argent, que ce soit sur votre compte ou dans votre mobile money."
                ]
            },
            {
                title: "3. Objections fréquentes",
                content: [
                    "« J'ai déjà un mobile money qui marche bien » → C'est justement l'intérêt : on le connecte à votre compte, vous ne changez rien à vos habitudes, vous gagnez juste en flexibilité.",
                    "« Il y a des frais de transfert » → Comparer avec le coût cumulé des déplacements chez un agent pour recharger manuellement.",
                    "« Je ne fais pas confiance aux transferts numériques » → Chaque transfert est confirmé par un code de sécurité envoyé sur votre téléphone, rien ne part sans votre validation explicite."
                ]
            },
            {
                title: "4. Closing",
                content: [
                    "« On peut l'activer ensemble maintenant, ça ne prend que quelques minutes et ça change votre quotidien immédiatement. »"
                ]
            }
        ]
    },
    {
        key: "carte-prepayee",
        title: "Carte prépayée / Carte ATM",
        icon: "bi-credit-card-fill",
        colorFrom: "#7B2FF7", colorTo: "#F72585",
        pitch: "Une carte simple, sans compte courant obligatoire, pour payer et retirer partout.",
        steps: [
            {
                title: "1. Découverte",
                content: [
                    "Avez-vous déjà une carte pour payer vos achats ou retirer de l'argent ?",
                    "Voyagez-vous parfois, ou avez-vous besoin de payer en ligne ?",
                    "Avez-vous un compte courant, ou cherchez-vous une solution plus simple pour gérer votre budget ?"
                ]
            },
            {
                title: "2. Présentation — bénéfices, pas fonctionnalités",
                content: [
                    "Payez partout, en magasin comme en ligne, sans avoir besoin d'un compte courant complet.",
                    "Rechargez uniquement le montant que vous voulez dépenser — un excellent outil pour maîtriser son budget.",
                    "Acceptée dans le réseau international — utile pour les achats en ligne ou les voyages.",
                    "Émise rapidement, activable dès réception."
                ]
            },
            {
                title: "3. Objections fréquentes",
                content: [
                    "« Je n'ai pas besoin d'une carte, je paie en espèces » → Pratique pour les paiements en ligne, ou en cas d'urgence quand on n'a pas d'espèces sur soi.",
                    "« J'ai peur de perdre le contrôle de mes dépenses » → C'est l'inverse : vous ne pouvez dépenser que ce que vous avez rechargé, contrôle total garanti.",
                    "« Les cartes coûtent cher » → Rappeler qu'il n'y a pas besoin d'un compte courant associé — coût d'entrée réduit."
                ]
            },
            {
                title: "4. Closing",
                content: [
                    "« Je peux lancer votre demande maintenant, la carte vous sera livrée sous quelques jours — on démarre ? »"
                ]
            }
        ]
    },
    {
        key: "e-statement",
        title: "e-Statement",
        icon: "bi-file-earmark-text-fill",
        colorFrom: "#0057B8", colorTo: "#7B2FF7",
        pitch: "Le relevé de compte envoyé automatiquement par email, sans passage en agence.",
        steps: [
            {
                title: "1. Découverte",
                content: [
                    "Comment récupérez-vous actuellement vos relevés de compte ?",
                    "Avez-vous déjà eu besoin d'un relevé en urgence (visa, dossier de prêt...) sans l'avoir sous la main ?",
                    "Avez-vous une adresse email que vous consultez régulièrement ?"
                ]
            },
            {
                title: "2. Présentation — bénéfices, pas fonctionnalités",
                content: [
                    "Recevez votre relevé automatiquement par email chaque mois — plus besoin de vous déplacer ni d'y penser.",
                    "Retrouvez facilement un ancien relevé dans votre boîte mail pour un dossier administratif ou une demande de visa.",
                    "Gratuit et activable immédiatement avec l'adresse email déjà enregistrée sur votre compte."
                ]
            },
            {
                title: "3. Objections fréquentes",
                content: [
                    "« Je préfère le papier » → Le e-Statement peut être imprimé à tout moment ; c'est juste un mode de réception plus rapide et plus fiable.",
                    "« Est-ce que c'est sécurisé par email ? » → Le document est protégé, seul un code lié à votre compte permet de l'ouvrir.",
                    "« Je n'ai pas d'email » → C'est le moment idéal pour en créer un — proposer d'aider à la création si besoin."
                ]
            },
            {
                title: "4. Closing",
                content: [
                    "« C'est gratuit et ça prend 30 secondes à activer maintenant, avec l'email que vous avez déjà chez nous — on active ? »"
                ]
            }
        ]
    },
    {
        key: "pret-credit",
        title: "Prêts & Crédits",
        icon: "bi-piggy-bank-fill",
        colorFrom: "#F72585", colorTo: "#0057B8",
        pitch: "Financer un projet personnel, un véhicule, ou des besoins ponctuels.",
        steps: [
            {
                title: "1. Découverte — la plus importante de tout l'appel",
                content: [
                    "Avez-vous un projet en cours (véhicule, travaux, événement familial...) que vous financez déjà ou envisagez de financer ?",
                    "Comment financez-vous habituellement vos besoins ponctuels importants ?",
                    "Quel serait le montant qui vous faciliterait la vie sur ce projet ?",
                    "Sur quelle durée seriez-vous à l'aise pour rembourser ?"
                ]
            },
            {
                title: "2. Présentation — adaptée à ce qui a été découvert",
                content: [
                    "Ne jamais présenter un prêt générique — reformuler le besoin exprimé puis présenter la solution qui y répond précisément.",
                    "Mettre en avant la simplicité du dossier (pièces déjà disponibles en agence via le compte existant).",
                    "Insister sur la mensualité plutôt que sur le taux — c'est ce qui parle concrètement au client.",
                    "Toujours mentionner : réponse rapide, pas de garantie compliquée pour les petits montants."
                ]
            },
            {
                title: "3. Objections fréquentes",
                content: [
                    "« Je ne veux pas m'endetter » → Recentrer sur le projet précis évoqué en découverte, pas sur l'endettement en général ; proposer une mensualité confortable, pas le montant maximum possible.",
                    "« Le taux est trop élevé » → Comparer au coût de reporter le projet ou de le financer autrement (informel, famille) ; rappeler la transparence totale sur le coût total du crédit dès le départ.",
                    "« Je dois réfléchir » → Ne jamais insister lourdement — proposer un rendez-vous de suivi précis (jour + heure), pas un vague rappel plus tard."
                ]
            },
            {
                title: "4. Closing",
                content: [
                    "« Sur la base de ce que vous m'avez dit, voici ce que je peux vous proposer... » (reformuler le besoin avant de conclure).",
                    "Si accord : enclencher immédiatement la prise de rendez-vous en agence pour la signature — ne jamais laisser un accord verbal sans étape concrète programmée.",
                    "Si hésitation : programmer un rendez-vous de rappel plutôt que de laisser filer le contact."
                ]
            }
        ]
    }
];
