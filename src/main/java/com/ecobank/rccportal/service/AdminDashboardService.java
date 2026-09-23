package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.AdminDashboardResponse;
import com.ecobank.rccportal.repository.LoginAuditRepository;
import com.ecobank.rccportal.repository.RccServiceRepository;
import com.ecobank.rccportal.repository.RefreshTokenRepository;
import com.ecobank.rccportal.repository.RoleRepository;
import com.ecobank.rccportal.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Agrège les KPI du dashboard admin (GET /admin/dashboard). Chaque chiffre vient
 * d'une vraie requête (User, Role, RccService, RefreshToken, LoginAudit) — aucune
 * valeur n'est inventée.
 */
@Service
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RccServiceRepository rccServiceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAuditRepository loginAuditRepository;

    public AdminDashboardService(UserRepository userRepository,
                                  RoleRepository roleRepository,
                                  RccServiceRepository rccServiceRepository,
                                  RefreshTokenRepository refreshTokenRepository,
                                  LoginAuditRepository loginAuditRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.rccServiceRepository = rccServiceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.loginAuditRepository = loginAuditRepository;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse getAdminDashboard() {
        long users = userRepository.count();
        long roles = roleRepository.count();
        long services = rccServiceRepository.count();
        long activeSessions = refreshTokenRepository.countByExpiresAtAfter(LocalDateTime.now());
        long auditLogs = loginAuditRepository.count();

        return new AdminDashboardResponse(users, roles, services, activeSessions, auditLogs);
    }
}
