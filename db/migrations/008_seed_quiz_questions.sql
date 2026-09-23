-- Seed de questions génériques (savoir-faire relation client, terminologie bancaire
-- universelle) pour que les jeux basés sur la banque QuizQuestion (quiz-eclair,
-- millions-ecobank, duel-chrono, chrono-challenge, roue-fortune) aient du contenu
-- dès l'installation. Volontairement générique — aucun chiffre/délai/frais propre
-- à Ecobank non vérifié n'est inventé ici ; QA complète ensuite avec du contenu
-- métier réel depuis l'onglet Questions.
--
-- A executer une seule fois, apres 007_add_games.sql.

SET QUOTED_IDENTIFIER ON;
GO

IF NOT EXISTS (SELECT 1 FROM dbo.QuizQuestions WHERE QuestionText = N'Avant de communiquer une information confidentielle (solde, coordonnées...), que doit toujours faire l''agent ?')
INSERT INTO dbo.QuizQuestions (QuestionText, Type, Difficulty, Category, OptionsJson, CorrectOptionIndex, Explanation, Points) VALUES
(N'Avant de communiquer une information confidentielle (solde, coordonnées...), que doit toujours faire l''agent ?', 'MCQ', 'EASY', 'AUTRE',
 N'["Authentifier le client (identité vérifiée)","Lui demander son numéro de téléphone uniquement","Rien, on peut répondre directement","Transférer l''appel sans vérification"]', 0,
 N'L''authentification du client est une étape obligatoire avant toute divulgation d''information sur un compte.', 10),

(N'Que signifie l''acronyme RIB ?', 'MCQ', 'EASY', 'COMPTE',
 N'["Relevé d''Identité Bancaire","Registre Interne Bancaire","Rapport d''Information Bancaire","Réserve Interbancaire"]', 0,
 N'Le RIB identifie un compte bancaire précis (banque, guichet, numéro, clé).', 10),

(N'Que signifie OTP dans le contexte d''une transaction sécurisée ?', 'MCQ', 'EASY', 'CONNEXION PRODUITS DIGITAUX',
 N'["One Time Password (code à usage unique)","Online Transfer Protocol","Official Transaction Paper","Open Terminal Payment"]', 0,
 N'L''OTP est un code envoyé une seule fois pour valider une opération sensible.', 10),

(N'Un client signale la perte de sa carte bancaire. Quelle est la toute première action à effectuer ?', 'MCQ', 'MEDIUM', 'CARTE ATM',
 N'["Faire opposition / bloquer immédiatement la carte","Lui demander de rappeler plus tard","Attendre la confirmation par écrit","Ne rien faire, la carte se bloque automatiquement"]', 0,
 N'Le blocage immédiat limite le risque d''utilisation frauduleuse pendant que le dossier est traité.', 15),

(N'Que désigne un "compte dormant" ?', 'MCQ', 'MEDIUM', 'COMPTE',
 N'["Un compte resté inactif pendant une longue période","Un compte tout juste ouvert","Un compte réservé aux mineurs","Un compte en devise étrangère"]', 0,
 N'Un compte dormant n''a enregistré aucune opération depuis longtemps ; il est généralement soumis à des restrictions.', 15),

(N'Quel est le rôle du code SWIFT/BIC dans un virement international ?', 'MCQ', 'HARD', 'TRANSFERT',
 N'["Identifier la banque destinataire dans le réseau interbancaire international","Identifier uniquement le client","Servir de mot de passe pour la banque en ligne","Remplacer le RIB"]', 0,
 N'Le code SWIFT/BIC identifie une banque précise pour acheminer correctement un virement international.', 20),

(N'Un client conteste un prélèvement qu''il ne reconnaît pas. Quelle est la bonne attitude ?', 'MCQ', 'MEDIUM', 'COMPTE',
 N'["Enregistrer la contestation et l''orienter vers le circuit de traitement dédié","Lui dire que rien ne peut être fait","Rembourser immédiatement sans vérification","Ignorer la demande si le montant est faible"]', 0,
 N'Toute contestation doit être tracée et suivre le circuit de vérification prévu, quel que soit le montant.', 15),

(N'Que permet le service B2W (Bank to Wallet) ?', 'MCQ', 'MEDIUM', 'MMH',
 N'["Transférer de l''argent d''un compte bancaire vers un portefeuille mobile money","Payer une facture d''électricité","Ouvrir un compte bancaire","Commander une carte bancaire"]', 0,
 N'B2W déplace des fonds du compte bancaire vers un portefeuille mobile money.', 15),

(N'Qu''est-ce qu''une carte CASHXPRESS ?', 'MCQ', 'EASY', 'CASHXPRESS',
 N'["Une carte prépayée utilisable sans compte bancaire classique","Une carte réservée aux entreprises","Un chéquier électronique","Un service de virement uniquement"]', 0,
 N'CASHXPRESS est une carte prépayée, indépendante d''un compte bancaire traditionnel.', 10),

(N'Quel est l''objectif principal du KYC ("Know Your Customer") ?', 'MCQ', 'MEDIUM', 'AUTRE',
 N'["Vérifier l''identité du client avant certaines opérations","Calculer les intérêts d''un compte","Envoyer les relevés de compte","Gérer le planning des agents"]', 0,
 N'Le KYC est un processus de vérification d''identité, pilier de la conformité bancaire.', 15),

(N'Un client insiste pour obtenir une information sur le compte d''un tiers. Que doit faire l''agent ?', 'MCQ', 'EASY', 'AUTRE',
 N'["Refuser poliment — le secret bancaire protège les informations d''un tiers","Donner l''information si le client insiste","Transférer l''appel sans explication","Donner une partie de l''information seulement"]', 0,
 N'Le secret bancaire interdit de communiquer des informations sur le compte d''une personne à un tiers non autorisé.', 10),

(N'Que signifie l''acronyme TPE ?', 'MCQ', 'EASY', 'CARTE ATM',
 N'["Terminal de Paiement Électronique","Traitement Prioritaire Express","Transfert Postal Électronique","Ticket de Paiement Externe"]', 0,
 N'Le TPE est l''appareil utilisé en magasin pour régler un achat par carte.', 10),

(N'Vrai ou faux : un virement et un prélèvement désignent la même opération.', 'TRUE_FALSE', 'EASY', 'TRANSFERT',
 N'["Vrai","Faux"]', 1,
 N'Un virement est initié par le titulaire du compte débité ; un prélèvement est autorisé à l''avance au profit d''un tiers qui déclenche l''opération.', 10),

(N'Vrai ou faux : il faut toujours vérifier l''identité d''un client avant de traiter une demande sensible, même s''il semble pressé.', 'TRUE_FALSE', 'EASY', 'AUTRE',
 N'["Vrai","Faux"]', 0,
 N'L''urgence ressentie par le client ne dispense jamais de la vérification d''identité.', 10),

(N'Vrai ou faux : une procuration permet à une autre personne d''effectuer des opérations sur le compte du titulaire.', 'TRUE_FALSE', 'MEDIUM', 'COMPTE',
 N'["Vrai","Faux"]', 0,
 N'La procuration autorise une personne désignée à agir sur le compte au nom du titulaire.', 10),

(N'Vrai ou faux : un Dépôt à Terme (DAT) peut être retiré à tout moment sans aucune condition.', 'TRUE_FALSE', 'MEDIUM', 'COMPTE',
 N'["Vrai","Faux"]', 1,
 N'Un DAT est bloqué pendant une durée fixée à l''avance ; un retrait anticipé implique généralement des conditions particulières.', 15),

(N'Vrai ou faux : le code VBV (Verified by Visa) sert à sécuriser un paiement en ligne.', 'TRUE_FALSE', 'MEDIUM', 'CARTE ATM',
 N'["Vrai","Faux"]', 0,
 N'VBV ajoute une étape de vérification (code/OTP) lors d''un paiement en ligne par carte Visa.', 10),

(N'Que faire en priorité si un client déclare une transaction qu''il ne reconnaît pas sur son compte ?', 'MCQ', 'HARD', 'CARTE ATM',
 N'["Sécuriser le moyen de paiement concerné puis ouvrir une contestation tracée","Lui conseiller d''appeler sa banque plus tard","Ignorer si le montant est faible","Fermer le compte immédiatement"]', 0,
 N'La priorité est d''éviter tout dommage supplémentaire (blocage si nécessaire) puis de tracer la contestation pour investigation.', 20),

(N'Qu''est-ce qu''un e-Statement ?', 'MCQ', 'EASY', 'CONNEXION PRODUITS DIGITAUX',
 N'["Un relevé de compte transmis électroniquement","Un extrait de casier judiciaire","Une attestation de domicile","Un formulaire de procuration"]', 0,
 N'L''e-Statement remplace l''envoi papier du relevé de compte par une version électronique.', 10),

(N'Quelle attitude adopter face à un client mécontent qui hausse le ton ?', 'MCQ', 'MEDIUM', 'AUTRE',
 N'["Rester calme, reformuler sa demande et se concentrer sur la solution","Hausser le ton également pour se faire entendre","Raccrocher immédiatement","Le faire attendre longtemps sans explication"]', 0,
 N'Le calme et l''écoute active permettent de désamorcer la tension et d''avancer vers une solution.', 15);
GO
