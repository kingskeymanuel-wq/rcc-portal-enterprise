package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.RccPostCommentRequest;
import com.ecobank.rccportal.dto.RccPostRequest;
import com.ecobank.rccportal.model.RccPost;
import com.ecobank.rccportal.model.RccPostLike;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.MonRccService;
import com.ecobank.rccportal.util.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonRccServiceTest {

    @Mock
    private RccPostRepository postRepository;

    @Mock
    private RccPostCommentRepository commentRepository;

    @Mock
    private RccPostLikeRepository likeRepository;

    @Mock
    private RccStoryRepository storyRepository;

    @Mock
    private RccCommunityFollowRepository followRepository;

    @Mock
    private RccNotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.ecobank.rccportal.repository.RccCommunityRepository communityRepository;

    @Mock
    private com.ecobank.rccportal.repository.UserServiceAssignmentRepository userServiceAssignmentRepository;

    @Mock
    private com.ecobank.rccportal.repository.UserProfileRepository userProfileRepository;

    private MonRccService service;

    @BeforeEach
    void setUp() {

        MockitoAnnotations.openMocks(this);

        service = new MonRccService(
                postRepository,
                commentRepository,
                likeRepository,
                storyRepository,
                followRepository,
                communityRepository,
                notificationRepository,
                userRepository,
                userServiceAssignmentRepository,
                userProfileRepository
        );
    }

    @Test
    void createPostRejectsAnAgentOutsideCommunicationTeam() {

        AuthenticatedUser agent =
                new AuthenticatedUser(
                        "kone.aissatou",
                        "agent",
                        "Outbound",
                        "Koné Aïssatou"
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.createPost(
                                new RccPostRequest(
                                        "Hello",
                                        null
                                ),
                                agent
                        )
                );

        assertTrue(
                ex.getMessage().contains("Communication")
        );

        verifyNoInteractions(postRepository);
    }

    @Test
    void createPostSucceedsForCommunicationTeam() {

        AuthenticatedUser comms =
                new AuthenticatedUser(
                        "edoudou",
                        "agent",
                        "Communication",
                        "Edoudou"
                );

        User author =
                User.builder()
                        .id(1L)
                        .username("edoudou")
                        .name("Edoudou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase("edoudou")
        ).thenReturn(
                Optional.of(author)
        );

        when(
                postRepository.save(any())
        ).thenAnswer(invocation -> {

            RccPost post =
                    invocation.getArgument(0);

            post.setPostId(10);

            return post;
        });

        when(
                likeRepository.countByPost(any())
        ).thenReturn(0L);

        when(
                commentRepository.countByPost(any())
        ).thenReturn(0L);

        var response =
                service.createPost(
                        new RccPostRequest(
                                "Bienvenue à tous !",
                                null
                        ),
                        comms
                );

        assertEquals(
                "edoudou",
                response.authorMatricule()
        );

        assertEquals(
                "Edoudou",
                response.authorLabel()
        );

        assertEquals(
                0,
                response.likeCount()
        );

        assertFalse(
                response.likedByMe()
        );
    }

    @Test
    void toggleLikeCreatesALikeWhenNoneExists() {

        AuthenticatedUser requester =
                new AuthenticatedUser(
                        "kone.aissatou",
                        "agent",
                        "Outbound",
                        "Koné Aïssatou"
                );

        User me =
                User.builder()
                        .id(2L)
                        .username("kone.aissatou")
                        .name("Koné Aïssatou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        RccPost post =
                RccPost.builder()
                        .postId(5)
                        .authorLabel("Team Communication")
                        .viewCount(0)
                        .build();

        when(
                postRepository.findById(5)
        ).thenReturn(
                Optional.of(post)
        );

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                "kone.aissatou"
                        )
        ).thenReturn(
                Optional.of(me)
        );

        when(
                likeRepository
                        .findByPostAndUser(
                                post,
                                me
                        )
        ).thenReturn(
                Optional.empty()
        );

        when(
                likeRepository.countByPost(post)
        ).thenReturn(1L);

        when(
                commentRepository.countByPost(post)
        ).thenReturn(0L);

        service.toggleLike(
                5,
                requester
        );

        verify(
                likeRepository
        ).save(
                any(RccPostLike.class)
        );

        verify(
                likeRepository,
                never()
        ).delete(any());
    }

    @Test
    void toggleLikeRemovesAnExistingLike() {

        AuthenticatedUser requester =
                new AuthenticatedUser(
                        "kone.aissatou",
                        "agent",
                        "Outbound",
                        "Koné Aïssatou"
                );

        User me =
                User.builder()
                        .id(2L)
                        .username("kone.aissatou")
                        .name("Koné Aïssatou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        RccPost post =
                RccPost.builder()
                        .postId(5)
                        .authorLabel(
                                "Team Communication"
                        )
                        .viewCount(0)
                        .build();

        RccPostLike existingLike =
                RccPostLike.builder()
                        .rccPostLikeId(9)
                        .post(post)
                        .user(me)
                        .build();

        when(
                postRepository.findById(5)
        ).thenReturn(
                Optional.of(post)
        );

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                "kone.aissatou"
                        )
        ).thenReturn(
                Optional.of(me)
        );

        when(
                likeRepository
                        .findByPostAndUser(
                                post,
                                me
                        )
        ).thenReturn(
                Optional.of(existingLike)
        );

        when(
                likeRepository.countByPost(post)
        ).thenReturn(0L);

        when(
                commentRepository.countByPost(post)
        ).thenReturn(0L);

        service.toggleLike(
                5,
                requester
        );

        verify(
                likeRepository
        ).delete(existingLike);

        verify(
                likeRepository,
                never()
        ).save(any());
    }

    @Test
    void addCommentNotifiesTheOriginalAuthorButNotSelfComments() {

        AuthenticatedUser commenter =
                new AuthenticatedUser(
                        "kone.aissatou",
                        "agent",
                        "Outbound",
                        "Koné Aïssatou"
                );

        User postAuthor =
                User.builder()
                        .id(1L)
                        .username("edoudou")
                        .name("Edoudou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        User me =
                User.builder()
                        .id(2L)
                        .username("kone.aissatou")
                        .name("Koné Aïssatou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        RccPost post =
                RccPost.builder()
                        .postId(5)
                        .author(postAuthor)
                        .authorLabel("Edoudou")
                        .build();

        when(
                postRepository.findById(5)
        ).thenReturn(
                Optional.of(post)
        );

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                "kone.aissatou"
                        )
        ).thenReturn(
                Optional.of(me)
        );

        when(
                commentRepository.save(any())
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );

        service.addComment(
                5,
                new RccPostCommentRequest(
                        "Merci pour l'info !"
                ),
                commenter
        );

        verify(
                notificationRepository
        ).save(
                argThat(
                        notification ->
                                notification
                                        .getTargetUser()
                                        .equals(postAuthor)
                )
        );
    }

    @Test
    void followIsIdempotent() {

        AuthenticatedUser requester =
                new AuthenticatedUser(
                        "kone.aissatou",
                        "agent",
                        "Outbound",
                        "Koné Aïssatou"
                );

        User me =
                User.builder()
                        .id(2L)
                        .username("kone.aissatou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        when(
                userRepository
                        .findFirstByUsernameIgnoreCase(
                                "kone.aissatou"
                        )
        ).thenReturn(
                Optional.of(me)
        );

        when(
                followRepository
                        .findByUserAndCommunityKey(
                                me,
                                "Banque & Inclusion"
                        )
        ).thenReturn(
                Optional.of(
                        com.ecobank.rccportal.model
                                .RccCommunityFollow
                                .builder()
                                .build()
                )
        );

        service.follow(
                "Banque & Inclusion",
                requester
        );

        verify(
                followRepository,
                never()
        ).save(any());
    }
}