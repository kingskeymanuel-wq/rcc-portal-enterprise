package com.ecobank.rccportal.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Échanges de l'espace Formation façon EduFun (TrainingJourneyController). */
public final class TrainingJourneyDtos {

    private TrainingJourneyDtos() {}

    public record Badge(String code, String label, String icon, String description, boolean earned, int progress, int target) {}

    public record LeaderboardEntry(int rank, String name, String teamLabel, int xp, String level, boolean me) {}

    public record Eligible(String sourceType, Integer sourceId, String title, Integer score) {}

    public record Certificate(Long id, String certificateNumber, String holderName, String teamLabel, String sourceType,
                              Integer sourceId, String title, Integer score, String status, LocalDateTime requestedAt,
                              LocalDateTime decidedAt, String decidedBy, String decisionNote) {}

    /** Tableau de bord personnel : XP, niveau, compteurs, badges, série, classement d'équipe. */
    public record Summary(String name, String teamLabel, int xp, String level, int levelFloorXp, Integer nextLevelXp,
                          String nextLevel, int lessonsCompleted, int coursesCompleted, int quizzesTaken,
                          int quizzesPassed, int bestScore, int streakDays, int certificatesIssued,
                          List<Badge> badges, List<LeaderboardEntry> teamLeaderboard, Integer myTeamRank,
                          List<Eligible> eligibleCertificates, int gamesPlayed, int evaluationsTaken,
                          int evaluationsPassed, List<GameResult> gameResults) {}

    /** Résultat de l'agent sur un jeu du Centre d'Évaluation (parties + évaluation notée du tour en cours). */
    public record GameResult(String gameKey, int plays, Integer bestPlayScore, Integer evaluationScore,
                             Integer evaluationAttempts, Boolean evaluationPassed, LocalDateTime lastPlayedAt) {}

    public record Activity(LocalDateTime at, String userName, String teamLabel, String type, String title,
                           Integer score, Boolean passed) {}

    /** Vue QA synchronisée : ce que font les agents, ce qui attend une décision QA. */
    public record QaOverview(LocalDateTime syncedAt, int pendingCertificates, int draftCourses, int publishedCourses,
                             int archivedCourses, int activeLearnersToday, int lessonsCompletedWeek,
                             int quizzesPassedWeek, int quizzesFailedWeek, List<Activity> recentActivity,
                             List<LeaderboardEntry> leaderboard) {}

    public record CertificateRequest(String sourceType, Integer sourceId) {}

    public record CertificateDecision(String action, String note) {}

    public record Verification(boolean valid, String status, String certificateNumber, String holderName,
                               String title, LocalDateTime issuedAt) {}
}
