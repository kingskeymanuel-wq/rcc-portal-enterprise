package com.ecobank.rccportal.service;


import com.ecobank.rccportal.util.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Notifications e-mail RÉELLES liées aux comptes (SMTP — voir spring.mail.* dans
 * application.yml). L'authentification elle-même (2FA) ne passe plus par e-mail — voir
 * MfaService : le code OTP est produit en local par l'utilisateur via une appli
 * Authenticator déjà configurée en amont, puis vérifié auprès du serveur MFA Ecobank ;
 * plus besoin de le livrer nous-mêmes.
 *
 * Exception : la réinitialisation de mot de passe par e-mail (AuthService.forgotPassword,
 * quand un e-mail est fourni) reste un flux « code envoyé par e-mail » à part, pour les
 * comptes n'ayant pas encore d'application d'authentification enrôlée.
 */
@Slf4j
@Service
public class NotificationService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final boolean configured;

    public NotificationService(JavaMailSender mailSender,
                                @Value("${spring.mail.username:}") String username,
                                @Value("${rcc.mail.from:}") String fromAddress) {
        this.mailSender = mailSender;
        this.configured = username != null && !username.isBlank();
        this.fromAddress = (fromAddress == null || fromAddress.isBlank()) ? username : fromAddress;
    }

    /**
     * Notifie un agent de la décision d'un administrateur sur sa demande d'accès
     * (parcours de connectivité). Contrairement au code 2FA, un échec d'envoi
     * ici ne doit pas bloquer l'action de l'administrateur (le compte est déjà
     * approuvé/refusé en base) — on journalise seulement.
     */
    public void sendAccountStatusEmail(String username, String email, boolean approved) {
        if (!configured || email == null || email.isBlank()) {
            log.warn("Skipping account status e-mail (username={}, approved={}): SMTP not configured or no e-mail on file.",
                    username, approved);
            return;
        }
        String subject = approved ? "Ecobank RCC — Accès autorisé" : "Ecobank RCC — Demande d'accès refusée";
        String body = approved
                ? "Bonjour,\n\nVotre demande d'accès au portail RCC a été approuvée par un administrateur. "
                        + "Vous pouvez désormais vous connecter avec votre username et votre mot de passe.\n\n— Portail RCC Ecobank"
                : "Bonjour,\n\nVotre demande d'accès au portail RCC a été refusée par un administrateur. "
                        + "Pour plus d'informations, contactez votre superviseur ou le support IT.\n\n— Portail RCC Ecobank";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            log.info("Account status e-mail sent (username={}, approved={})", username, approved);
        } catch (Exception e) {
            log.warn("Account status e-mail could not be sent (username={}, approved={}): {}", username, approved, e.getMessage());
        }
    }

    /**
     * Envoie le code de réinitialisation de mot de passe par e-mail. Contrairement à
     * sendAccountStatusEmail, un échec ici DOIT bloquer la demande : sans code livré,
     * l'utilisateur n'a aucun moyen de confirmer la réinitialisation.
     */
    public void sendPasswordResetCodeEmail(String username, String email, String code, int ttlMinutes) {
        if (!configured) {
            log.warn("Cannot send password reset code e-mail (username={}): SMTP not configured.", username);
            throw ApiException.serviceUnavailable(
                    "L'envoi d'e-mail n'est pas configuré sur ce serveur. Contactez un administrateur.");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("Ecobank RCC — Code de réinitialisation de mot de passe");
        message.setText("Bonjour,\n\nVoici votre code de vérification pour réinitialiser votre mot de passe : " + code
                + "\n\nCe code expire dans " + ttlMinutes + " minutes. Si vous n'êtes pas à l'origine de cette demande, "
                + "ignorez cet e-mail et contactez le support IT.\n\n— Portail RCC Ecobank");
        try {
            mailSender.send(message);
            log.info("Password reset code e-mail sent (username={})", username);
        } catch (Exception e) {
            log.warn("Password reset code e-mail could not be sent (username={}): {}", username, e.getMessage());
            throw ApiException.serviceUnavailable(
                    "L'e-mail de réinitialisation n'a pas pu être envoyé. Réessayez plus tard ou contactez un administrateur.");
        }
    }

    /**
     * Diffusion — depuis la page Audit, un même message envoyé à plusieurs destinataires.
     * Contrairement au code de réinitialisation, l'échec d'un destinataire ne doit pas
     * empêcher l'envoi aux autres — chaque tentative est indépendante.
     *
     * @return le nombre d'e-mails effectivement envoyés.
     */
    public int sendBroadcastEmail(java.util.List<String> recipientEmails, String subject, String body) {
        if (!configured) {
            throw ApiException.serviceUnavailable(
                    "L'envoi d'e-mail n'est pas configuré sur ce serveur (spring.mail.* absent). Contactez l'équipe infrastructure.");
        }
        int sent = 0;
        for (String email : recipientEmails) {
            if (email == null || email.isBlank()) continue;
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(email);
            message.setSubject(subject);
            message.setText(body);
            try {
                mailSender.send(message);
                sent++;
            } catch (Exception e) {
                log.warn("Broadcast e-mail could not be sent (to={}): {}", email, e.getMessage());
            }
        }
        log.info("Broadcast e-mail sent to {}/{} recipient(s)", sent, recipientEmails.size());
        return sent;
    }
}
