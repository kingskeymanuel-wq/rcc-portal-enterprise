package com.ecobank.rccportal.repository;

import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {

    // ===========================
    // DASHBOARD AGENT
    // ===========================

    public long countProcedures() {
        return 0;
    }

    public long countKnowledgeArticles() {
        return 0;
    }

    public long countTrainingCourses() {
        return 0;
    }

    public long countNotifications() {
        return 0;
    }

    public long countBadges() {
        return 0;
    }

    public long countQaEvaluations() {
        return 0;
    }

    // ===========================
    // DASHBOARD QA
    // ===========================

    public long countCoachings() {
        return 0;
    }

    public long countActionPlans() {
        return 0;
    }

    public double averageQualityScore() {
        return 0;
    }

    // ===========================
    // DASHBOARD ADMIN
    // ===========================

    public long countUsers() {
        return 0;
    }

    public long countRoles() {
        return 0;
    }

    public long countServices() {
        return 0;
    }

    public long countActiveSessions() {
        return 0;
    }

    public long countAuditLogs() {
        return 0;
    }
// ===========================
// Compatibilité ancien Dashboard
// ===========================

    public int countConnectedUsers() {
        return 0;
    }

    public int countOpenTickets() {
        return 0;
    }

    public int countActiveAgents() {
        return 0;
    }

    public int calculateSatisfaction() {
        return 0;
    }
}