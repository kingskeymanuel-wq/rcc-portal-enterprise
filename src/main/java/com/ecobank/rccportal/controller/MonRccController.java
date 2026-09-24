package com.ecobank.rccportal.controller;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.MonRccService;
import com.ecobank.rccportal.util.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Portage du module "MON RCC ENGINE" (fil social interne, ex localStorage 'eco_rcc'). */
@RestController
@RequestMapping("/api/mon-rcc")
public class MonRccController {

    private final MonRccService monRccService;
    private final com.ecobank.rccportal.service.ImageStorageService imageStorageService;

    public MonRccController(MonRccService monRccService, com.ecobank.rccportal.service.ImageStorageService imageStorageService) {
        this.monRccService = monRccService;
        this.imageStorageService = imageStorageService;
    }

    /** Upload direct depuis l'ordinateur (photo ou courte vidéo) — alternative au collage d'URL. */
    @PostMapping(value = "/media/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public java.util.Map<String, String> uploadMedia(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return java.util.Map.of("url", imageStorageService.store(file));
    }

    @GetMapping("/posts")
    public List<RccPostResponse> listPosts(@AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.listPosts(requester);
    }

    /** Historique complet — ouvert à tout le monde, y compris les publications sorties du fil principal. */
    @GetMapping("/posts/history")
    public List<RccPostResponse> history(@AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.listHistory(requester);
    }

    /** Mes publications programmées, pas encore publiées. */
    @GetMapping("/posts/scheduled")
    public List<RccPostResponse> scheduled(@AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.listMyScheduled(requester);
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public RccPostResponse createPost(@Valid @RequestBody RccPostRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.createPost(request, requester);
    }

    @PutMapping("/posts/{id}")
    public RccPostResponse updatePost(@PathVariable Integer id, @RequestBody RccPostRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.updatePost(id, request, requester);
    }

    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePost(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        monRccService.deletePost(id, requester);
    }

    @PostMapping("/posts/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordView(@PathVariable Integer id) {
        monRccService.recordView(id);
    }

    @PostMapping("/posts/{id}/like")
    public RccPostResponse toggleLike(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.toggleLike(id, requester);
    }

    @GetMapping("/posts/{id}/comments")
    public List<RccPostCommentResponse> listComments(@PathVariable Integer id) {
        return monRccService.listComments(id);
    }

    @PostMapping("/posts/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public RccPostCommentResponse addComment(@PathVariable Integer id, @Valid @RequestBody RccPostCommentRequest request,
                                              @AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.addComment(id, request, requester);
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable Integer commentId, @AuthenticationPrincipal AuthenticatedUser requester) {
        monRccService.deleteComment(commentId, requester);
    }

    @GetMapping("/stories")
    public List<RccStoryResponse> listStories() {
        return monRccService.listActiveStories();
    }

    @PostMapping("/stories")
    @ResponseStatus(HttpStatus.CREATED)
    public RccStoryResponse createStory(@Valid @RequestBody RccStoryRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.createStory(request, requester);
    }

    @DeleteMapping("/stories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStory(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        monRccService.deleteStory(id, requester);
    }

    @GetMapping("/community-follows/me")
    public List<String> myFollowedCommunities(@AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.myFollowedCommunities(requester);
    }

    @GetMapping("/communities")
    public List<com.ecobank.rccportal.dto.RccCommunityResponse> listCommunities() {
        return monRccService.listCommunities();
    }

    @PostMapping("/communities")
    @ResponseStatus(HttpStatus.CREATED)
    public com.ecobank.rccportal.dto.RccCommunityResponse createCommunity(
            @Valid @RequestBody com.ecobank.rccportal.dto.RccCommunityRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return monRccService.createCommunity(request);
    }

    @PutMapping("/communities/{id}")
    public com.ecobank.rccportal.dto.RccCommunityResponse updateCommunity(
            @PathVariable Integer id,
            @Valid @RequestBody com.ecobank.rccportal.dto.RccCommunityRequest request,
            @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        return monRccService.updateCommunity(id, request);
    }

    @DeleteMapping("/communities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCommunity(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        requireAdmin(requester);
        monRccService.deleteCommunity(id);
    }

    private void requireAdmin(AuthenticatedUser requester) {
        if (requester == null || !"admin".equalsIgnoreCase(requester.role())) {
            throw ApiException.forbidden("Only an administrator can manage communities.");
        }
    }

    @PostMapping("/community-follows")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void follow(@Valid @RequestBody RccCommunityFollowRequest request, @AuthenticationPrincipal AuthenticatedUser requester) {
        monRccService.follow(request.communityKey(), requester);
    }

    @DeleteMapping("/community-follows/{communityKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfollow(@PathVariable String communityKey, @AuthenticationPrincipal AuthenticatedUser requester) {
        monRccService.unfollow(communityKey, requester);
    }

    @GetMapping("/notifications/me")
    public List<RccNotificationResponse> myNotifications(@AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.myNotifications(requester);
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markNotificationRead(@PathVariable Integer id, @AuthenticationPrincipal AuthenticatedUser requester) {
        monRccService.markNotificationRead(id, requester);
    }

    @PostMapping("/notifications/read-all")
    public java.util.Map<String, Integer> markAllNotificationsRead(@AuthenticationPrincipal AuthenticatedUser requester) {
        return java.util.Map.of("marked", monRccService.markAllRead(requester));
    }

    @PostMapping("/notifications")
    @ResponseStatus(HttpStatus.CREATED)
    public RccNotificationResponse broadcastNotification(@Valid @RequestBody RccNotificationRequest request,
                                                           @AuthenticationPrincipal AuthenticatedUser requester) {
        return monRccService.createGlobalNotification(request, requester);
    }
}
