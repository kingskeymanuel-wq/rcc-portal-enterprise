package com.ecobank.rccportal.scheduler;

import com.ecobank.rccportal.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Purge définitive des messages éphémères expirés — ils sont déjà masqués à l'affichage, ceci les supprime réellement. */
@Slf4j
@Component
public class EphemeralMessageCleanupJob {

    private final ChatMessageRepository chatMessageRepository;

    public EphemeralMessageCleanupJob(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    @Scheduled(cron = "0 */10 * * * *") // toutes les 10 minutes
    @Transactional
    public void purgeExpiredMessages() {
        var expired = chatMessageRepository.findByExpiresAtBefore(LocalDateTime.now());
        if (!expired.isEmpty()) {
            chatMessageRepository.deleteAll(expired);
            log.info("Purged {} expired ephemeral chat message(s)", expired.size());
        }
    }
}
