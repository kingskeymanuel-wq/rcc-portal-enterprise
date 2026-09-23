package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.TrainingJourneyDtos.Summary;
import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.util.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Espace Formation (modèle EduFun) : XP, niveaux, badges, classement, certificats contrôlés par QA. */
class TrainingJourneyServiceTest {

    private UserRepository userRepository;
    private CourseAttemptRepository attemptRepository;
    private TrainingProgressRepository progressRepository;
    private TrainingLessonRepository lessonRepository;
    private TrainingCertificateRepository certificateRepository;
    private GameScoreRepository gameScoreRepository;
    private GameEvaluationAttemptRepository gameAttemptRepository;
    private TrainingJourneyService service;

    private User awa, koffi;
    private Course quiz, selfAssessment;
    private TrainingFormation formation;
    private TrainingLesson lesson1, lesson2;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        attemptRepository = mock(CourseAttemptRepository.class);
        progressRepository = mock(TrainingProgressRepository.class);
        lessonRepository = mock(TrainingLessonRepository.class);
        certificateRepository = mock(TrainingCertificateRepository.class);
        gameScoreRepository = mock(GameScoreRepository.class);
        gameAttemptRepository = mock(GameEvaluationAttemptRepository.class);
        service = new TrainingJourneyService(userRepository, teamRepository, courseRepository, attemptRepository,
                progressRepository, lessonRepository, certificateRepository, gameScoreRepository, gameAttemptRepository,
                mock(GameDefinitionRepository.class));

        awa = new User(); awa.setId(1L); awa.setUsername("awa"); awa.setName("Awa Traoré"); awa.setActivity("INBOUND");
        koffi = new User(); koffi.setId(2L); koffi.setUsername("koffi"); koffi.setName("Koffi N'Guessan"); koffi.setActivity("INBOUND");
        when(userRepository.findAll()).thenReturn(List.of(awa, koffi));
        when(userRepository.findFirstByUsernameIgnoreCase("awa")).thenReturn(Optional.of(awa));
        when(teamRepository.findAll()).thenReturn(List.of(Team.builder().code("INBOUND").label("Inbound Voix").build()));

        quiz = Course.builder().courseId(10).title("Cartes bancaires").type("STANDARD").build();
        selfAssessment = Course.builder().courseId(11).title("Posture d'accueil").type("SELF_ASSESSMENT").build();
        formation = TrainingFormation.builder().formationId(5).title("Onboarding Inbound").build();
        lesson1 = TrainingLesson.builder().lessonId(51).title("Accueil").formation(formation).build();
        lesson2 = TrainingLesson.builder().lessonId(52).title("Découverte").formation(formation).build();
        when(lessonRepository.countByFormation_FormationId(5)).thenReturn(2L);
        when(certificateRepository.findAll()).thenReturn(List.of());
        when(certificateRepository.findByUserIdOrderByRequestedAtDesc(any())).thenReturn(List.of());
        when(certificateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private TrainingProgress done(TrainingLesson lesson, User u, LocalDateTime at) {
        return TrainingProgress.builder().lesson(lesson).userId(u.getId()).completed(true).completedAt(at).lastActivityAt(at).build();
    }

    private CourseAttempt attempt(Course c, User u, Integer score, LocalDateTime at) {
        return CourseAttempt.builder().course(c).user(u).status("DONE").score(score).completedAt(at).finalized(true).attemptNumber(1).build();
    }

    @Test
    void summary_computesXpBadgesLevelAndTeamRanking() {
        LocalDateTime now = LocalDateTime.now();
        when(progressRepository.findAll()).thenReturn(List.of(
                done(lesson1, awa, now), done(lesson2, awa, now.minusDays(1))));
        when(attemptRepository.findAll()).thenReturn(List.of(
                attempt(quiz, awa, 100, now.minusDays(2)),          // 50 + 20 bonus
                attempt(selfAssessment, awa, null, now),            // 30
                attempt(quiz, koffi, 40, now)));                    // 10

        Summary s = service.summary("awa");

        assertEquals(2 * 20 + 70 + 30, s.xp());
        assertEquals("Apprenti", s.level());
        assertEquals(300, s.nextLevelXp());
        assertEquals(2, s.lessonsCompleted());
        assertEquals(1, s.quizzesPassed());
        assertEquals(3, s.streakDays());
        assertTrue(s.badges().stream().anyMatch(b -> b.code().equals("PERFECT") && b.earned()));
        assertTrue(s.badges().stream().anyMatch(b -> b.code().equals("STREAK") && b.earned()));
        assertFalse(s.badges().stream().anyMatch(b -> b.code().equals("GRADUATE") && b.earned()));
        assertEquals(1, s.myTeamRank());
        assertEquals(2, s.teamLeaderboard().size());
        assertEquals("Inbound Voix", s.teamLabel());
        // parcours terminé à 100 % + quiz réussi → 2 certificats à demander
        assertEquals(2, s.eligibleCertificates().size());
    }

    @Test
    void evaluationCenterGamesFeedXpBadgesAndResults() {
        LocalDateTime now = LocalDateTime.now();
        when(progressRepository.findAll()).thenReturn(List.of());
        when(attemptRepository.findAll()).thenReturn(List.of());
        List<GameScore> plays = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) plays.add(GameScore.builder().gameKey("quiz-eclair").userId(1L).score(40 + i).playedAt(now).build());
        when(gameScoreRepository.findAll()).thenReturn(plays);
        when(gameAttemptRepository.findAll()).thenReturn(List.of(
                GameEvaluationAttempt.builder().gameKey("quiz-eclair").userId(1L).attemptNumber(1).evaluationRound(1).score(55).correctCount(11).totalCount(20).createdAt(now).build(),
                GameEvaluationAttempt.builder().gameKey("quiz-eclair").userId(1L).attemptNumber(2).evaluationRound(1).score(85).correctCount(17).totalCount(20).createdAt(now).build(),
                GameEvaluationAttempt.builder().gameKey("duel-chrono").userId(1L).attemptNumber(1).evaluationRound(1).score(40).correctCount(4).totalCount(10).createdAt(now).build()));

        Summary s = service.summary("awa");

        // 7 parties le même jour → plafond 25 ; quiz-eclair réussi +40 ; duel tenté +10
        assertEquals(25 + 40 + 10, s.xp());
        assertEquals(7, s.gamesPlayed());
        assertEquals(2, s.evaluationsTaken());
        assertEquals(1, s.evaluationsPassed());
        var quiz = s.gameResults().stream().filter(g -> g.gameKey().equals("quiz-eclair")).findFirst().orElseThrow();
        assertEquals(7, quiz.plays());
        assertEquals(46, quiz.bestPlayScore());
        assertEquals(85, quiz.evaluationScore());
        assertTrue(quiz.evaluationPassed());
    }

    @Test
    void requestCertificate_refusesWhenParcoursNotFinished() {
        when(progressRepository.findAll()).thenReturn(List.of(done(lesson1, awa, LocalDateTime.now())));
        when(attemptRepository.findAll()).thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.requestCertificate("awa", "FORMATION", 5));
        verify(certificateRepository, never()).save(any());
    }

    @Test
    void requestCertificate_createsPendingRequestForFinishedParcours() {
        LocalDateTime now = LocalDateTime.now();
        when(progressRepository.findAll()).thenReturn(List.of(done(lesson1, awa, now), done(lesson2, awa, now)));
        when(attemptRepository.findAll()).thenReturn(List.of());

        var cert = service.requestCertificate("awa", "formation", 5);

        assertEquals("PENDING", cert.status());
        assertEquals("Onboarding Inbound", cert.title());
        assertNull(cert.certificateNumber());
    }

    @Test
    void decide_issueGivesNumber_rejectNeedsReason_revokeOnlyIssued() {
        TrainingCertificate pending = TrainingCertificate.builder().id(7L).userId(1L).sourceType("COURSE").sourceId(10)
                .title("Cartes bancaires").status("PENDING").requestedAt(LocalDateTime.now()).build();
        when(certificateRepository.findById(7L)).thenReturn(Optional.of(pending));
        when(userRepository.findById(1L)).thenReturn(Optional.of(awa));
        when(certificateRepository.existsByCertificateNumber(any())).thenReturn(false);

        assertThrows(ApiException.class, () -> service.decide(7L, "REJECT", " ", "QA"));
        assertThrows(ApiException.class, () -> service.decide(7L, "REVOKE", "fraude", "QA"));

        var issued = service.decide(7L, "ISSUE", null, "Chef QA");
        assertEquals("ISSUED", issued.status());
        assertTrue(issued.certificateNumber().matches("RCC-" + LocalDate.now().getYear() + "-[A-Z0-9]{6}"));
        assertEquals("Chef QA", issued.decidedBy());

        assertThrows(ApiException.class, () -> service.decide(7L, "ISSUE", null, "QA"));
        assertEquals("REVOKED", service.decide(7L, "REVOKE", "Erreur de saisie", "QA").status());
    }

    @Test
    void verify_unknownNumberIsInvalid() {
        when(certificateRepository.findByCertificateNumberIgnoreCase("RCC-0000-XXXXXX")).thenReturn(Optional.empty());
        assertFalse(service.verify("RCC-0000-XXXXXX").valid());
    }

    @Test
    void streakAndLevels() {
        LocalDate today = LocalDate.now();
        assertEquals(0, TrainingJourneyService.streak(Set.of(today.minusDays(3))));
        assertEquals(2, TrainingJourneyService.streak(Set.of(today.minusDays(1), today.minusDays(2))));
        assertEquals(0, TrainingJourneyService.levelIndex(99));
        assertEquals(1, TrainingJourneyService.levelIndex(100));
        assertEquals(5, TrainingJourneyService.levelIndex(5000));
    }
}
