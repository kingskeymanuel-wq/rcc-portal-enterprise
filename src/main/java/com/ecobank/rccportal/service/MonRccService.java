package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.*;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MON RCC — fil social interne. Portage du module frontend "MON RCC ENGINE" (localStorage
 * 'eco_rcc') vers le backend réel : posts, commentaires, likes, stories éphémères (24h),
 * suivi de communautés (par clé, pas de follow agent-à-agent — voir RccCommunityFollow) et
 * notifications. La publication de posts/stories est réservée à l'équipe Communication et
 * aux admins (même règle que canPost() côté frontend) ; commenter/aimer est ouvert à tous.
 */
@Service
public class MonRccService {


    private final RccPostRepository postRepository;
    private final RccPostCommentRepository commentRepository;
    private final RccPostLikeRepository likeRepository;
    private final RccStoryRepository storyRepository;
    private final RccCommunityFollowRepository followRepository;
    private final com.ecobank.rccportal.repository.RccCommunityRepository communityRepository;
    private final RccNotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;
    private final com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository;

    public MonRccService(RccPostRepository postRepository, RccPostCommentRepository commentRepository,
                          RccPostLikeRepository likeRepository, RccStoryRepository storyRepository,
                          RccCommunityFollowRepository followRepository,
                          com.ecobank.rccportal.repository.RccCommunityRepository communityRepository,
                          RccNotificationRepository notificationRepository,
                          UserRepository userRepository,
                          com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository,
                          com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.likeRepository = likeRepository;
        this.storyRepository = storyRepository;
        this.followRepository = followRepository;
        this.communityRepository = communityRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.userServiceAssignmentRepository = userServiceAssignmentRepository;
    }

    // ---------- Communautés (propres au portail — jamais dbo.SERVICES) ----------

    @Transactional(readOnly = true)
    public List<com.ecobank.rccportal.dto.RccCommunityResponse> listCommunities() {
        return communityRepository.findAllByOrderBySortOrderAsc().stream()
                .map(c -> new com.ecobank.rccportal.dto.RccCommunityResponse(c.getCommunityId(), c.getCommunityKey(), c.getLabel(), c.getSortOrder()))
                .toList();
    }

    @Transactional
    public com.ecobank.rccportal.dto.RccCommunityResponse createCommunity(com.ecobank.rccportal.dto.RccCommunityRequest request) {
        if (communityRepository.findByCommunityKeyIgnoreCase(request.communityKey()).isPresent()) {
            throw ApiException.badRequest("Cette communauté existe déjà.");
        }
        var saved = communityRepository.save(com.ecobank.rccportal.model.RccCommunity.builder()
                .communityKey(request.communityKey().trim().toUpperCase().replace(" ", "_"))
                .label(request.label().trim())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .build());
        return new com.ecobank.rccportal.dto.RccCommunityResponse(saved.getCommunityId(), saved.getCommunityKey(), saved.getLabel(), saved.getSortOrder());
    }

    @Transactional
    public com.ecobank.rccportal.dto.RccCommunityResponse updateCommunity(Integer id, com.ecobank.rccportal.dto.RccCommunityRequest request) {
        var community = communityRepository.findById(id).orElseThrow(() -> ApiException.notFound("Communauté introuvable."));
        community.setLabel(request.label().trim());
        if (request.sortOrder() != null) community.setSortOrder(request.sortOrder());
        communityRepository.save(community);
        return new com.ecobank.rccportal.dto.RccCommunityResponse(community.getCommunityId(), community.getCommunityKey(), community.getLabel(), community.getSortOrder());
    }

    @Transactional
    public void deleteCommunity(Integer id) {
        var community = communityRepository.findById(id).orElseThrow(() -> ApiException.notFound("Communauté introuvable."));
        followRepository.findAll().stream()
                .filter(f -> f.getCommunityKey().equalsIgnoreCase(community.getCommunityKey()))
                .forEach(followRepository::delete);
        communityRepository.delete(community);
    }

    // ---------- Posts ----------

    private static final int DEFAULT_VISIBILITY_DAYS = 14;

    @Transactional(readOnly = true)
    public List<RccPostResponse> listPosts(AuthenticatedUser requester) {
        LocalDateTime now = LocalDateTime.now();
        List<RccPost> posts = postRepository.findAllByOrderByPublishedAtDesc().stream()
                .filter(p -> !p.getPublishedAt().isAfter(now)) // pas encore programmé
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now)) // pas encore expiré
                .toList();
        return buildPostResponses(posts, requester);
    }

    /**
     * Historique — tout le monde y a accès, mais un simple conseiller ne voit que les 7
     * derniers jours (au-delà, ça disparaît de son écran) ; QA et Admin gardent l'historique
     * complet, sans limite, et peuvent supprimer n'importe quelle publication depuis là
     * (même bouton de modération que le fil principal, voir MonRccController.deletePost).
     */
    @Transactional(readOnly = true)
    public List<RccPostResponse> listHistory(AuthenticatedUser requester) {
        LocalDateTime now = LocalDateTime.now();
        boolean fullHistory = "admin".equalsIgnoreCase(requester.role()) || isQualityAssurance(requester);
        LocalDateTime cutoff = fullHistory ? null : now.minusDays(7);

        List<RccPost> posts = postRepository.findAllByOrderByPublishedAtDesc().stream()
                .filter(p -> !p.getPublishedAt().isAfter(now))
                .filter(p -> cutoff == null || p.getPublishedAt().isAfter(cutoff))
                .toList();
        return buildPostResponses(posts, requester);
    }

    /** Mes publications programmées (pas encore visibles) — pour que l'auteur puisse les retrouver/annuler. */
    @Transactional(readOnly = true)
    public List<RccPostResponse> listMyScheduled(AuthenticatedUser requester) {
        LocalDateTime now = LocalDateTime.now();
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);
        if (me == null) return List.of();
        List<RccPost> posts = postRepository.findAllByOrderByPublishedAtDesc().stream()
                .filter(p -> p.getPublishedAt().isAfter(now))
                .filter(p -> p.getAuthor() != null && p.getAuthor().getId().equals(me.getId()))
                .toList();
        return buildPostResponses(posts, requester);
    }

    private List<RccPostResponse> buildPostResponses(List<RccPost> posts, AuthenticatedUser requester) {
        if (posts.isEmpty()) return List.of();
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);

        Map<Integer, Long> commentCounts = commentRepository.findByPostInOrderByCreatedAtAsc(posts).stream()
                .collect(Collectors.groupingBy(c -> c.getPost().getPostId(), Collectors.counting()));
        List<RccPostLike> likes = likeRepository.findByPostIn(posts);
        Map<Integer, Long> likeCounts = likes.stream()
                .collect(Collectors.groupingBy(l -> l.getPost().getPostId(), Collectors.counting()));
        Set<Integer> likedByMePostIds = me == null ? Set.of() : likes.stream()
                .filter(l -> l.getUser().getId().equals(me.getId()))
                .map(l -> l.getPost().getPostId())
                .collect(Collectors.toSet());

        return posts.stream()
                .map(p -> toResponse(p,
                        commentCounts.getOrDefault(p.getPostId(), 0L).intValue(),
                        likeCounts.getOrDefault(p.getPostId(), 0L).intValue(),
                        likedByMePostIds.contains(p.getPostId())))
                .toList();
    }

    @Transactional
    public RccPostResponse createPost(RccPostRequest request, AuthenticatedUser requester) {
        assertCanPost(requester);
        User author = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));

        String content = request.content() != null ? request.content() : "";
        boolean hasImage = request.imageUrl() != null && !request.imageUrl().isBlank();
        if (content.isBlank() && !hasImage) {
            throw ApiException.badRequest("Ajoutez du texte ou une image avant de publier.");
        }

        LocalDateTime publishedAt = request.scheduledFor() != null ? request.scheduledFor() : LocalDateTime.now();
        int visibilityDays = request.visibilityDays() != null && request.visibilityDays() > 0
                ? request.visibilityDays() : DEFAULT_VISIBILITY_DAYS;

        RccPost post = RccPost.builder()
                .author(author).authorLabel(author.getName())
                .content(content).imageUrl(request.imageUrl())
                .viewCount(0).publishedAt(publishedAt)
                .expiresAt(publishedAt.plusDays(visibilityDays))
                .build();
        postRepository.save(post);
        return toResponseSingle(post, author);
    }

    @Transactional
    public RccPostResponse updatePost(Integer id, RccPostRequest request, AuthenticatedUser requester) {
        RccPost post = postRepository.findById(id).orElseThrow(() -> ApiException.notFound("Post not found."));
        assertAuthorOrAdmin(post.getAuthor(), requester);
        if (request.content() != null) post.setContent(request.content());
        if (request.imageUrl() != null) post.setImageUrl(request.imageUrl());
        postRepository.save(post);
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username()).orElse(null);
        return toResponseSingle(post, me);
    }

    @Transactional
    public void deletePost(Integer id, AuthenticatedUser requester) {
        RccPost post = postRepository.findById(id).orElseThrow(() -> ApiException.notFound("Post not found."));
        assertAuthorOrAdmin(post.getAuthor(), requester);
        postRepository.delete(post);
    }

    @Transactional
    public void recordView(Integer id) {
        RccPost post = postRepository.findById(id).orElseThrow(() -> ApiException.notFound("Post not found."));
        post.setViewCount(post.getViewCount() + 1);
        postRepository.save(post);
    }

    @Transactional
    public RccPostResponse toggleLike(Integer postId, AuthenticatedUser requester) {
        RccPost post = postRepository.findById(postId).orElseThrow(() -> ApiException.notFound("Post not found."));
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        var existing = likeRepository.findByPostAndUser(post, me);
        if (existing.isPresent()) {
            likeRepository.delete(existing.get());
        } else {
            likeRepository.save(RccPostLike.builder().post(post).user(me).build());
        }
        return toResponseSingle(post, me);
    }

    // ---------- Commentaires ----------

    @Transactional(readOnly = true)
    public List<RccPostCommentResponse> listComments(Integer postId) {
        RccPost post = postRepository.findById(postId).orElseThrow(() -> ApiException.notFound("Post not found."));
        return commentRepository.findByPostOrderByCreatedAtAsc(post).stream().map(this::toResponse).toList();
    }

    @Transactional
    public RccPostCommentResponse addComment(Integer postId, RccPostCommentRequest request, AuthenticatedUser requester) {
        RccPost post = postRepository.findById(postId).orElseThrow(() -> ApiException.notFound("Post not found."));
        User author = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        RccPostComment comment = RccPostComment.builder()
                .post(post).author(author).authorLabel(author.getName()).content(request.content())
                .build();
        commentRepository.save(comment);

        if (post.getAuthor() != null && !post.getAuthor().getId().equals(author.getId())) {
            notificationRepository.save(RccNotification.builder()
                    .targetUser(post.getAuthor())
                    .content(author.getName() + " a commenté votre publication.")
                    .isRead(false)
                    .build());
        }
        return toResponse(comment);
    }

    @Transactional
    public void deleteComment(Integer commentId, AuthenticatedUser requester) {
        RccPostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> ApiException.notFound("Comment not found."));
        assertAuthorOrAdmin(comment.getAuthor(), requester);
        commentRepository.delete(comment);
    }

    // ---------- Stories ----------

    @Transactional(readOnly = true)
    public List<RccStoryResponse> listActiveStories() {
        return storyRepository.findActive(LocalDateTime.now()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public RccStoryResponse createStory(RccStoryRequest request, AuthenticatedUser requester) {
        assertCanPost(requester);
        if ((request.content() == null || request.content().isBlank())
                && (request.imageUrl() == null || request.imageUrl().isBlank())) {
            throw ApiException.badRequest("Un texte ou un fichier est requis.");
        }
        RccStory story = RccStory.builder()
                .authorLabel(requester.name() != null ? requester.name() : requester.username())
                .content(request.content() != null ? request.content() : "").imageUrl(request.imageUrl())
                .publishedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
        storyRepository.save(story);
        return toResponse(story);
    }

    @Transactional
    public void deleteStory(Integer id, AuthenticatedUser requester) {
        assertCanPost(requester);
        if (!storyRepository.existsById(id)) throw ApiException.notFound("Story not found.");
        storyRepository.deleteById(id);
    }

    // ---------- Suivi de communautés ----------

    @Transactional(readOnly = true)
    public List<String> myFollowedCommunities(AuthenticatedUser requester) {
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        return followRepository.findByUser(me).stream().map(RccCommunityFollow::getCommunityKey).toList();
    }

    @Transactional
    public void follow(String communityKey, AuthenticatedUser requester) {
        if (communityKey == null || communityKey.isBlank()) throw ApiException.badRequest("communityKey is required.");
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        if (followRepository.findByUserAndCommunityKey(me, communityKey).isPresent()) return;
        followRepository.save(RccCommunityFollow.builder().user(me).communityKey(communityKey).build());
    }

    @Transactional
    public void unfollow(String communityKey, AuthenticatedUser requester) {
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        followRepository.findByUserAndCommunityKey(me, communityKey).ifPresent(followRepository::delete);
    }

    // ---------- Notifications ----------

    /** Rôles autorisés à voir une notification GLOBALE (targetUser=null) selon son actionType —
     *  "chaque portail sa notification". Un actionType absent de cette table (ex. OPEN_KB_ARTICLE,
     *  ou une annonce sans actionType) reste visible par tous, comme avant — seuls les 3 types à
     *  destination claire d'un rôle précis sont désormais restreints. Les notifications
     *  PERSONNELLES (targetUser déjà posé sur un utilisateur précis) ne passent jamais par ce
     *  filtre — elles sont réservées à leur destinataire de toute façon, quel que soit son rôle. */
    private static final Map<String, Set<String>> GLOBAL_NOTIFICATION_ROLES = Map.of(
            "OPEN_WORKFLOW_REQUEST", Set.of("ADMIN", "RH"), // "affectation initiale à vérifier" — IT/admin, voir WorkflowService
            "QA_EVALUATION_REVIEW", Set.of("TEAM_LEADER"),
            "COMPETITION_TEAM_SELECTION", Set.of("TEAM_LEADER")
    );

    @Transactional(readOnly = true)
    public List<RccNotificationResponse> myNotifications(AuthenticatedUser requester) {
        User me = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        String myRole = requester.role() != null ? requester.role().toUpperCase() : "";

        return notificationRepository.findForUserOrGlobal(me).stream()
                .filter(n -> {
                    if (n.getTargetUser() != null) return true; // notification personnelle — jamais filtrée par rôle
                    // Map.of(...) lève toujours NPE sur une clé null (contrairement à HashMap) — or
                    // actionType reste null par défaut sur toute notification créée sans .actionType(...)
                    // explicite (ex. MonRccService.createGlobalNotification, annonces "signaler" sans
                    // ciblage). On sécurise l'appel plutôt que d'imposer actionType partout.
                    String actionType = n.getActionType();
                    if (actionType == null) return true; // pas de restriction de rôle définie — visible par tous, comme avant
                    Set<String> allowedRoles = GLOBAL_NOTIFICATION_ROLES.get(actionType);
                    return allowedRoles == null || allowedRoles.contains(myRole);
                })
                .map(this::toResponse).toList();
    }

    @Transactional
    public void markNotificationRead(Integer id) {
        RccNotification notification = notificationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Notification not found."));
        notification.setIsRead(true);
        notificationRepository.save(notification);
    }

    /** Diffuse une notification globale (targetUser = null) — ex. "signaler" une publication à tous. */
    @Transactional
    public RccNotificationResponse createGlobalNotification(RccNotificationRequest request, AuthenticatedUser requester) {
        assertCanBroadcast(requester);

        boolean hasFilter = (request.countryCode() != null && !request.countryCode().isBlank())
                || (request.serviceCode() != null && !request.serviceCode().isBlank())
                || (request.activity() != null && !request.activity().isBlank());

        if (!hasFilter) {
            RccNotification notification = RccNotification.builder()
                    .targetUser(null).content(request.content()).isRead(false)
                    .build();
            return toResponse(notificationRepository.save(notification));
        }

        // Ciblage réel — un exemplaire par destinataire correspondant à la filiale/service/équipe choisis.
        List<User> allUsers = userRepository.findAll();
        List<User> matching = allUsers.stream()
                .filter(u -> request.countryCode() == null || request.countryCode().isBlank()
                        || request.countryCode().equalsIgnoreCase(u.getAffiliateBranch()))
                .filter(u -> {
                    if (request.activity() == null || request.activity().isBlank()) return true;
                    // Comparaison exacte impossible : User.activity est un champ libre très
                    // hétérogène ("INBOUND VOICE", "INBOUND MAIL/CIS", "CMB CIB"...), jamais une
                    // des 4 valeurs canoniques telles quelles — d'où "Aucun agent ne correspond"
                    // même quand des agents de cette équipe existent bel et bien. On classifie
                    // avec la même logique que le reste du portail (Team Leader/Reporting), voir
                    // TeamClassifier.
                    try {
                        return com.ecobank.rccportal.util.TeamClassifier.classify(u.getActivity()).name()
                                .equalsIgnoreCase(request.activity());
                    } catch (Exception e) {
                        return false;
                    }
                })
                .filter(u -> {
                    if (request.serviceCode() == null || request.serviceCode().isBlank()) return true;
                    var assignments = userServiceAssignmentRepository.findServicesByUserId(u.getId());
                    return assignments.stream().anyMatch(a -> a.getService() != null
                            && request.serviceCode().equalsIgnoreCase(a.getService().getCode()));
                })
                .toList();

        RccNotification last = null;
        for (User target : matching) {
            last = notificationRepository.save(RccNotification.builder()
                    .targetUser(target).content(request.content()).isRead(false).build());
        }
        if (last == null) {
            throw ApiException.badRequest("Aucun agent ne correspond à cette filiale/service/équipe.");
        }
        return toResponse(last);
    }

    // ---------- Permissions ----------

    /** Diffusion depuis la page Audit — reste admin (et QA), pas concerné par la restriction "pas de contenu". */
    private void assertCanBroadcast(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isQualityAssurance(requester)) {
            throw ApiException.forbidden("Only an administrator or Quality Assurance can broadcast a notification.");
        }
    }

    /** MON RCC est ouvert à tout agent connecté — publications comme stories (décision prise plus tôt dans le projet, l'ancienne restriction QA-only ci-dessous a été retirée). */
    /** Publications/stories réservées à QA et Admin — l'agent conseiller ne peut plus publier sur MON RCC (retiré à sa demande explicite). */
    private void assertCanPost(AuthenticatedUser requester) {
        boolean isAdmin = "admin".equalsIgnoreCase(requester.role());
        if (!isAdmin && !isQualityAssurance(requester)) {
            throw ApiException.forbidden("Only Quality Assurance or an administrator can publish to MON RCC.");
        }
    }

    /**
     * Le service est stocké en base sous forme de code (ex. "QUALITY_ASSURANCE"),
     * pas sous forme de libellé d'affichage — il faut donc normaliser avant de
     * comparer (même règle que UserController/WorkflowController).
     */
    private boolean isQualityAssurance(AuthenticatedUser requester) {
        return requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
    }

    private void assertAuthorOrAdmin(User author, AuthenticatedUser requester) {
        if ("admin".equalsIgnoreCase(requester.role()) || isQualityAssurance(requester)) return;
        String authorusername = author != null ? author.getUsername() : null;
        if (authorusername == null || !requester.username().equalsIgnoreCase(authorusername)) {
            throw ApiException.forbidden("You can only modify your own content.");
        }
    }

    // ---------- Mapping ----------

    private RccPostResponse toResponse(RccPost p, int commentCount, int likeCount, boolean likedByMe) {
        return new RccPostResponse(p.getPostId(),
                safeAuthorUsername(p.getAuthor()), p.getAuthorLabel(), safeAuthorPhotoUrl(p.getAuthor()),
                p.getContent(), p.getImageUrl(), p.getViewCount(),
                likeCount, likedByMe, commentCount,
                p.getPublishedAt(), p.getUpdatedAt(),
                p.getExpiresAt(), p.getPublishedAt().isAfter(LocalDateTime.now()));
    }

    /** Même principe de robustesse que safeAuthorUsername — jamais planter le fil pour une photo manquante. */
    private String safeAuthorPhotoUrl(User author) {
        if (author == null) return null;
        try {
            return userProfileRepository.findByUser(author).map(UserProfile::getPhotoUrl).orElse(null);
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }

    /**
     * L'auteur est chargé paresseusement — un simple "!= null" ne détecte pas une ligne
     * supprimée en base (le proxy Hibernate existe encore, seul l'accès à un champ échoue).
     * authorLabel (déjà stocké sur la publication) sert de repli dans l'affichage — une
     * publication ne doit jamais faire planter tout le fil à cause d'un auteur disparu.
     */
    private String safeAuthorUsername(User author) {
        if (author == null) return null;
        try {
            return author.getUsername();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return null;
        }
    }

    private RccPostResponse toResponseSingle(RccPost p, User me) {
        int likeCount = (int) likeRepository.countByPost(p);
        boolean likedByMe = me != null && likeRepository.findByPostAndUser(p, me).isPresent();
        int commentCount = (int) commentRepository.countByPost(p);
        return toResponse(p, commentCount, likeCount, likedByMe);
    }

    private RccPostCommentResponse toResponse(RccPostComment c) {
        return new RccPostCommentResponse(c.getCommentId(), c.getPost().getPostId(),
                safeAuthorUsername(c.getAuthor()), c.getAuthorLabel(), safeAuthorPhotoUrl(c.getAuthor()),
                c.getContent(), c.getCreatedAt());
    }

    private RccStoryResponse toResponse(RccStory s) {
        return new RccStoryResponse(s.getStoryId(), s.getAuthorLabel(), s.getContent(), s.getImageUrl(),
                s.getPublishedAt(), s.getExpiresAt());
    }

    private RccNotificationResponse toResponse(RccNotification n) {
        return new RccNotificationResponse(n.getNotificationId(), n.getContent(),
                Boolean.TRUE.equals(n.getIsRead()), n.getCreatedAt(), n.getActionType(), n.getActionTarget());
    }
}
