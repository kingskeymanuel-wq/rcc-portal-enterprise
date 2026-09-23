package com.ecobank.rccportal;

import com.ecobank.rccportal.dto.MailTemplateRequest;
import com.ecobank.rccportal.model.MailTemplate;
import com.ecobank.rccportal.model.MailTemplateCategory;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.MailRecipientGroupRepository;
import com.ecobank.rccportal.repository.MailTemplateCategoryRepository;
import com.ecobank.rccportal.repository.MailTemplateRepository;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.service.MailTemplateService;
import com.ecobank.rccportal.util.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailTemplateServiceTest {

    @Mock
    private MailTemplateRepository mailTemplateRepository;

    @Mock
    private MailTemplateCategoryRepository categoryRepository;

    @Mock
    private MailRecipientGroupRepository recipientGroupRepository;

    @Mock
    private UserRepository userRepository;

    private MailTemplateService service;

    @BeforeEach
    void setUp() {

        MockitoAnnotations.openMocks(this);

        service = new MailTemplateService(
                mailTemplateRepository,
                categoryRepository,
                recipientGroupRepository,
                userRepository
        );

        when(categoryRepository.findAll())
                .thenReturn(List.of());

        when(recipientGroupRepository.findAll())
                .thenReturn(List.of());

        when(mailTemplateRepository.findAll())
                .thenReturn(List.of());
    }

    /**
     * AuthenticatedUser conserve le terme "matricule".
     *
     * Cela n'a pas d'impact sur le nouveau modèle User :
     * la valeur est ensuite recherchée dans USERS.USERNAME.
     */
    private AuthenticatedUser agent(String matricule) {

        return new AuthenticatedUser(
                matricule,
                "agent",
                "Inbound",
                "Test"
        );
    }

    @Test
    void createRejectsAServiceTemplateWithoutARecipientGroup() {

        MailTemplateRequest request =
                new MailTemplateRequest(
                        1,
                        "x",
                        "y",
                        "com/ecobank/rccportal/dashboard/service",
                        null
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(
                                request,
                                agent("kone.aissatou")
                        )
                );

        assertTrue(
                ex.getMessage()
                        .contains("recipientGroupId")
        );
    }

    @Test
    void createRejectsAnEmptySubject() {

        MailTemplateRequest request =
                new MailTemplateRequest(
                        1,
                        "",
                        "y",
                        "person",
                        null
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(
                                request,
                                agent("kone.aissatou")
                        )
                );

        assertTrue(
                ex.getMessage()
                        .contains("Subject")
        );
    }

    @Test
    void aNonAuthorAgentCannotUpdateSomeoneElsesTemplate() {

        User author =
                User.builder()
                        .id(1L)
                        .username("kone.aissatou")
                        .name("Koné Aïssatou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        MailTemplateCategory category =
                MailTemplateCategory.builder()
                        .categoryId(1)
                        .code("x")
                        .label("X")
                        .sortOrder(0)
                        .build();

        MailTemplate existing =
                MailTemplate.builder()
                        .templateId(1)
                        .category(category)
                        .subject("s")
                        .body("b")
                        .recipientType("person")
                        .createdBy(author)
                        .isSystemTemplate(false)
                        .build();

        when(
                mailTemplateRepository.findById(1)
        ).thenReturn(
                Optional.of(existing)
        );

        MailTemplateRequest request =
                new MailTemplateRequest(
                        null,
                        "nouveau",
                        null,
                        null,
                        null
                );

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.update(
                                1,
                                request,
                                agent("diallo.thierno")
                        )
                );

        assertEquals(
                403,
                ex.getStatus().value()
        );
    }

    @Test
    void anAdminCanDeleteAnyTemplate() {

        User author =
                User.builder()
                        .id(1L)
                        .username("kone.aissatou")
                        .name("Koné Aïssatou")
                        .status("APPROVED")
                        .failedAttempts(0)
                        .loginCount(0)
                        .build();

        MailTemplateCategory category =
                MailTemplateCategory.builder()
                        .categoryId(1)
                        .code("x")
                        .label("X")
                        .sortOrder(0)
                        .build();

        MailTemplate existing =
                MailTemplate.builder()
                        .templateId(1)
                        .category(category)
                        .subject("s")
                        .body("b")
                        .recipientType("person")
                        .createdBy(author)
                        .isSystemTemplate(false)
                        .build();

        when(
                mailTemplateRepository.findById(1)
        ).thenReturn(
                Optional.of(existing)
        );

        AuthenticatedUser admin =
                new AuthenticatedUser(
                        "admin",
                        "admin",
                        "Administration",
                        "Admin"
                );

        service.remove(
                1,
                admin
        );

        verify(
                mailTemplateRepository
        ).delete(existing);
    }
}