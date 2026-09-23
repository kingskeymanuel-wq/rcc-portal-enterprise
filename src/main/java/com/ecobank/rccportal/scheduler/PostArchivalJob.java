package com.ecobank.rccportal.scheduler;

import com.ecobank.rccportal.model.RccPost;
import com.ecobank.rccportal.repository.RccPostCommentRepository;
import com.ecobank.rccportal.repository.RccPostLikeRepository;
import com.ecobank.rccportal.repository.RccPostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Suppression définitive des publications MON RCC 2 mois après leur
 * publication (voir RccPost.publishedAt). Entre 2 semaines et 2 mois, une
 * publication sort du fil principal mais reste consultable dans
 * l'historique (voir MonRccService.listPosts vs listHistory) — cette tâche
 * ne s'occupe que de la suppression finale, pas de la bascule vers
 * l'historique qui est purement une question de filtrage à la lecture.
 */
@Slf4j
@Component
public class PostArchivalJob {

    private static final int DELETE_AFTER_MONTHS = 2;

    private final RccPostRepository postRepository;
    private final RccPostCommentRepository commentRepository;
    private final RccPostLikeRepository likeRepository;

    public PostArchivalJob(RccPostRepository postRepository, RccPostCommentRepository commentRepository,
                           RccPostLikeRepository likeRepository) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.likeRepository = likeRepository;
    }

    @Scheduled(cron = "0 30 3 * * *") // tous les jours à 03h30
    @Transactional
    public void deleteExpiredPosts() {
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(DELETE_AFTER_MONTHS);
        List<RccPost> expired = postRepository.findAll().stream()
                .filter(p -> p.getPublishedAt().isBefore(cutoff))
                .toList();

        for (RccPost post : expired) {
            commentRepository.deleteAll(commentRepository.findByPostOrderByCreatedAtAsc(post));
            likeRepository.deleteAll(likeRepository.findByPost(post));
            postRepository.delete(post);
        }

        if (!expired.isEmpty()) {
            log.info("Deleted {} RCC post(s) older than {} months", expired.size(), DELETE_AFTER_MONTHS);
        }
    }
}
