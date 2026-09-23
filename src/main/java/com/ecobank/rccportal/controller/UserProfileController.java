package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.UserProfileRequest;
import com.ecobank.rccportal.dto.UserProfileResponse;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.ImageStorageService;
import com.ecobank.rccportal.service.UserProfileService;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/user-profiles")
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final ImageStorageService imageStorageService;

    public UserProfileController(UserProfileService userProfileService, ImageStorageService imageStorageService) {
        this.userProfileService = userProfileService;
        this.imageStorageService = imageStorageService;
    }

    @GetMapping("/me")
    public UserProfileResponse mine(@AuthenticationPrincipal AuthenticatedUser requester) {
        return userProfileService.get(requester.username());
    }

    @PutMapping("/me")
    public UserProfileResponse updateMine(@RequestBody UserProfileRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        return userProfileService.update(requester.username(), request);
    }

    /** Upload direct d'un fichier image — remplace la photo de profil et retourne le profil à jour. */
    @PostMapping("/me/photo")
    public UserProfileResponse uploadPhoto(@RequestParam("file") MultipartFile file,
                                            @AuthenticationPrincipal AuthenticatedUser requester) {
        String photoUrl = imageStorageService.store(file);
        return userProfileService.update(requester.username(), new UserProfileRequest(photoUrl, null, null, null, null));
    }

    /** Upload direct d'un fichier image — fond d'écran personnel des conversations MON RCC. */
    @PostMapping("/me/chat-background")
    public UserProfileResponse uploadChatBackground(@RequestParam("file") MultipartFile file,
                                                      @AuthenticationPrincipal AuthenticatedUser requester) {
        String url = imageStorageService.store(file);
        return userProfileService.update(requester.username(), new UserProfileRequest(null, url, null, null, null));
    }

    /** Retire le fond d'écran personnalisé — retour au fond par défaut. */
    @DeleteMapping("/me/chat-background")
    public UserProfileResponse removeChatBackground(@AuthenticationPrincipal AuthenticatedUser requester) {
        return userProfileService.clearChatBackground(requester.username());
    }

    /** QA/admin uniquement : consulter le profil d'un autre agent (ex. fiche agent). */
    @GetMapping("/{matricule}")
    public UserProfileResponse get(@PathVariable String matricule, @AuthenticationPrincipal AuthenticatedUser requester) {
        boolean isSelf = requester.username().equals(matricule);
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        boolean isQa = requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isSelf && !isAdmin && !isQa) {
            throw ApiException.forbidden("You can only view your own profile.");
        }
        return userProfileService.get(matricule);
    }
}
