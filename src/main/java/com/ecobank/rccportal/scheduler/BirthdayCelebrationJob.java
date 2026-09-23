package com.ecobank.rccportal.scheduler;

import com.ecobank.rccportal.model.RccPost;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserProfile;
import com.ecobank.rccportal.repository.RccPostRepository;
import com.ecobank.rccportal.repository.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.List;

/**
 * Célébration automatique des anniversaires — chaque jour, vérifie les
 * profils dont le jour/mois de naissance correspond à aujourd'hui et publie
 * un post de célébration sur MON RCC (comme le rappel d'anniversaire
 * Facebook). Un seul post par personne et par jour (vérifié via le contenu
 * pour éviter les doublons si l'application redémarre le même jour).
 */
@Slf4j
@Component
public class BirthdayCelebrationJob {

    private static final String SYSTEM_AUTHOR_LABEL = "MON RCC";

    private final UserProfileRepository userProfileRepository;
    private final RccPostRepository rccPostRepository;

    public BirthdayCelebrationJob(UserProfileRepository userProfileRepository, RccPostRepository rccPostRepository) {
        this.userProfileRepository = userProfileRepository;
        this.rccPostRepository = rccPostRepository;
    }

    @Scheduled(cron = "0 0 7 * * *") // tous les jours à 07h00
    @Transactional
    public void celebrateTodaysBirthdays() {
        MonthDay today = MonthDay.from(LocalDate.now());
        List<UserProfile> profiles = userProfileRepository.findAll();

        int celebrated = 0;
        for (UserProfile profile : profiles) {
            if (profile.getBirthdate() == null) continue;
            if (!MonthDay.from(profile.getBirthdate()).equals(today)) continue;

            User user = profile.getUser();
            String name = user.getName() != null ? user.getName() : user.getUsername();
            String content = "🎉 Joyeux anniversaire à " + name + " ! Toute l'équipe RCC te souhaite une excellente journée.";

            boolean alreadyPostedToday = rccPostRepository.existsByContentAndPublishedAtAfter(
                    content, LocalDate.now().atStartOfDay());
            if (alreadyPostedToday) continue;

            RccPost post = RccPost.builder()
                    .author(null)
                    .authorLabel(SYSTEM_AUTHOR_LABEL)
                    .content(content)
                    .imageUrl(null)
                    .viewCount(0)
                    .publishedAt(LocalDateTime.now())
                    .build();
            rccPostRepository.save(post);
            celebrated++;
        }

        if (celebrated > 0) {
            log.info("Published {} birthday celebration post(s)", celebrated);
        }
    }
}
