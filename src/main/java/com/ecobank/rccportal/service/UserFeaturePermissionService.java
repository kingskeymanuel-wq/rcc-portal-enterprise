package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.FeaturePermissionResponse;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserFeaturePermission;
import com.ecobank.rccportal.repository.UserFeaturePermissionRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Dérogations d'accès aux fonctionnalités par utilisateur — surcouche
 * explicite au comportement par défaut basé sur le rôle. Une ligne présente
 * (vrai ou faux) prime toujours sur le rôle ; l'absence de ligne laisse le
 * rôle décider seul.
 */
@Service
public class UserFeaturePermissionService {

    private final UserFeaturePermissionRepository permissionRepository;
    private final UserRepository userRepository;

    public UserFeaturePermissionService(UserFeaturePermissionRepository permissionRepository,
                                        UserRepository userRepository) {
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<FeaturePermissionResponse> listForUser(Long userId) {
        User user = findUser(userId);
        return permissionRepository.findByUser(user).stream()
                .map(p -> new FeaturePermissionResponse(p.getFeatureCode(), p.getIsAllowed()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeaturePermissionResponse> listForUsername(String username) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
        return permissionRepository.findByUser(user).stream()
                .map(p -> new FeaturePermissionResponse(p.getFeatureCode(), p.getIsAllowed()))
                .toList();
    }

    @Transactional
    public void setPermission(Long userId, String featureCode, boolean isAllowed) {
        User user = findUser(userId);
        String code = normalizeCode(featureCode);

        UserFeaturePermission permission = permissionRepository.findByUserAndFeatureCode(user, code)
                .orElseGet(() -> UserFeaturePermission.builder().user(user).featureCode(code).build());
        permission.setIsAllowed(isAllowed);
        permissionRepository.save(permission);
    }

    @Transactional
    public void clearPermission(Long userId, String featureCode) {
        User user = findUser(userId);
        permissionRepository.deleteByUserAndFeatureCode(user, normalizeCode(featureCode));
    }

    private String normalizeCode(String featureCode) {
        if (featureCode == null || featureCode.isBlank()) {
            throw ApiException.badRequest("featureCode is required.");
        }
        return featureCode.trim().toLowerCase();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Unknown user."));
    }
}
