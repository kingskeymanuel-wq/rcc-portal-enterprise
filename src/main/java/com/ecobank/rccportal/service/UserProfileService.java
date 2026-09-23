package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.UserProfileRequest;
import com.ecobank.rccportal.dto.UserProfileResponse;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.model.UserProfile;
import com.ecobank.rccportal.repository.UserProfileRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profil étendu (photo, bio, téléphone, date de naissance) — n'existait auparavant que
 * comme table SQL sans aucune route pour la lire ou l'écrire. */
@Service
public class UserProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserRepository userRepository;

    public UserProfileService(UserProfileRepository userProfileRepository, UserRepository userRepository) {
        this.userProfileRepository = userProfileRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse get(String username) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
        return userProfileRepository.findByUser(user)
                .map(p -> toResponse(user, p))
                .orElseGet(() -> new UserProfileResponse(user.getUsername(), user.getName(), null, null, null, null, null));
    }

    @Transactional
    public UserProfileResponse update(String username, UserProfileRequest request) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
        UserProfile profile = userProfileRepository.findByUser(user)
                .orElseGet(() -> UserProfile.builder().user(user).build());
        if (request.photoUrl() != null) profile.setPhotoUrl(request.photoUrl());
        if (request.chatBackgroundUrl() != null) profile.setChatBackgroundUrl(request.chatBackgroundUrl());
        if (request.birthdate() != null) profile.setBirthdate(request.birthdate());
        if (request.phone() != null) profile.setPhone(request.phone());
        if (request.bio() != null) profile.setBio(request.bio());
        return toResponse(user, userProfileRepository.save(profile));
    }

    /** Remise à zéro explicite — la sémantique "non-null gagne" de update() ne peut pas effacer un champ. */
    @Transactional
    public UserProfileResponse clearChatBackground(String username) {
        User user = userRepository.findFirstByUsernameIgnoreCase(username).orElseThrow(() -> ApiException.notFound("Unknown user."));
        UserProfile profile = userProfileRepository.findByUser(user)
                .orElseGet(() -> UserProfile.builder().user(user).build());
        profile.setChatBackgroundUrl(null);
        return toResponse(user, userProfileRepository.save(profile));
    }

    private UserProfileResponse toResponse(User user, UserProfile p) {
        return new UserProfileResponse(user.getUsername(), user.getName(), p.getPhotoUrl(), p.getChatBackgroundUrl(),
                p.getBirthdate(), p.getPhone(), p.getBio());
    }
}
