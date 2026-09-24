package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.MailTemplateRequest;
import com.ecobank.rccportal.dto.MailTemplateResponse;
import com.ecobank.rccportal.dto.MailTemplatesOverviewResponse;
import com.ecobank.rccportal.model.MailTemplate;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portage direct de src/services/mail-template.service.js, étendu pour permettre à
 * chaque agent de créer ses propres masques personnels (voir create()) selon le type
 * de mail (catégorie existante) qu'il doit envoyer — auparavant réservé à la QA.
 * Règles : un modèle "service" doit avoir une boîte destinataire ; un modèle personnel
 * peut être modifié/supprimé par son auteur ou la QA ; un modèle système, uniquement
 * par la QA ; la création/le classement des catégories elles-mêmes reste réservé à la QA.
 */
@Service
public class MailTemplateService {

    private static final java.util.regex.Pattern PLACEHOLDER_PATTERN = java.util.regex.Pattern.compile("\\[([A-Za-zÀ-ÿ_]+)\\]");

    private final MailTemplateRepository mailTemplateRepository;
    private final MailTemplateCategoryRepository categoryRepository;
    private final MailRecipientGroupRepository recipientGroupRepository;
    private final UserRepository userRepository;

    public MailTemplateService(MailTemplateRepository mailTemplateRepository,
                                MailTemplateCategoryRepository categoryRepository,
                                MailRecipientGroupRepository recipientGroupRepository,
                                UserRepository userRepository) {
        this.mailTemplateRepository = mailTemplateRepository;
        this.categoryRepository = categoryRepository;
        this.recipientGroupRepository = recipientGroupRepository;
        this.userRepository = userRepository;
    }

    /**
     * Masque PERSONNEL (créé par un agent, non système) : visible uniquement par son auteur.
     * Les masques système (QA / escalades) restent communs à tous.
     */
    static boolean visibleTo(MailTemplate t, AuthenticatedUser requester) {
        if (Boolean.TRUE.equals(t.getIsSystemTemplate())) return true;
        String author = t.getCreatedBy() != null ? t.getCreatedBy().getUsername() : null;
        if (author == null) return true; // ancien masque sans auteur connu : reste commun
        return requester != null && requester.username() != null && requester.username().equalsIgnoreCase(author);
    }

    private MailTemplate findVisible(Integer id, AuthenticatedUser requester) {
        MailTemplate template = mailTemplateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Mail template not found."));
        if (!visibleTo(template, requester)) throw ApiException.notFound("Mail template not found.");
        return template;
    }

    @Transactional(readOnly = true)
    public MailTemplatesOverviewResponse listAll() {
        return listAll(null);
    }

    @Transactional(readOnly = true)
    public MailTemplatesOverviewResponse listAll(AuthenticatedUser requester) {
        var categories = categoryRepository.findAll().stream()
                .sorted((a, b) -> a.getSortOrder().compareTo(b.getSortOrder()))
                .map(c -> new MailTemplatesOverviewResponse.CategoryDto(c.getCategoryId(), c.getCode(), c.getLabel(),
                        c.getAccentColor(), c.getSortOrder(), c.getTeam()))
                .toList();
        var groups = recipientGroupRepository.findAll().stream()
                .sorted((a, b) -> a.getLabel().compareTo(b.getLabel()))
                .map(g -> new MailTemplatesOverviewResponse.RecipientGroupDto(g.getGroupId(), g.getLabel(), g.getEmail()))
                .toList();
        var templates = mailTemplateRepository.findAll().stream()
                .filter(t -> visibleTo(t, requester))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toResponse)
                .toList();
        return new MailTemplatesOverviewResponse(categories, groups, templates);
    }

    private void assertValid(Integer categoryId, String subject, String body, String recipientType, Integer recipientGroupId) {
        if (categoryId == null) throw ApiException.badRequest("categoryId is required.");
        if (subject == null || subject.isBlank()) throw ApiException.badRequest("Subject is required.");
        if (body == null || body.isBlank()) throw ApiException.badRequest("Body is required.");
        if (!"person".equals(recipientType) && !"service".equals(recipientType)) {
            throw ApiException.badRequest("recipientType must be one of: person, service.");
        }
        if ("service".equals(recipientType) && recipientGroupId == null) {
            throw ApiException.badRequest("recipientGroupId is required when recipientType is \"service\".");
        }
    }

    private static final java.util.Set<String> ALLOWED_TEAMS = java.util.Set.of("EMAIL", "RAFIKI", "SOCIAL", "OUTBOUND");

    /** Crée une nouvelle catégorie de masques, directement classée dans une équipe — QA uniquement. */
    @Transactional
    public MailTemplatesOverviewResponse.CategoryDto createCategory(String label, String team, String iconGlyph,
                                                                      String accentColor, AuthenticatedUser requester) {
        requireQaOnly(requester);
        if (label == null || label.isBlank()) throw ApiException.badRequest("label is required.");
        if (team == null || !ALLOWED_TEAMS.contains(team.toUpperCase())) {
            throw ApiException.badRequest("team must be one of: " + ALLOWED_TEAMS);
        }
        String code = label.trim().toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (categoryRepository.findByCodeIgnoreCase(code).isPresent()) {
            throw ApiException.badRequest("A category with a similar name already exists.");
        }
        int nextOrder = (int) categoryRepository.count();
        var category = categoryRepository.save(com.ecobank.rccportal.model.MailTemplateCategory.builder()
                .code(code)
                .label(label.trim())
                .iconGlyph(iconGlyph)
                .accentColor(accentColor)
                .sortOrder(nextOrder)
                .team(team.toUpperCase())
                .build());
        return new MailTemplatesOverviewResponse.CategoryDto(category.getCategoryId(), category.getCode(),
                category.getLabel(), category.getAccentColor(), category.getSortOrder(), category.getTeam());
    }

    /** Classe une catégorie de masques dans une des 3 équipes (EMAIL/RAFIKI/SOCIAL) — QA uniquement. */
    @Transactional
    public void assignCategoryTeam(Integer categoryId, String team, AuthenticatedUser requester) {
        requireQaOnly(requester);
        if (team == null || !ALLOWED_TEAMS.contains(team.toUpperCase())) {
            throw ApiException.badRequest("team must be one of: " + ALLOWED_TEAMS);
        }
        var category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> ApiException.notFound("Unknown category."));
        category.setTeam(team.toUpperCase());
        categoryRepository.save(category);
    }

    /**
     * Crée un masque personnel. Ouvert à tout utilisateur authentifié (agent inclus) — c'est
     * la façon dont un agent se crée son propre mail-type selon le type de mail (catégorie
     * existante) qu'il doit envoyer. Seule la gestion des catégories elles-mêmes (création,
     * classement par équipe) reste réservée à la QA — voir requireQaOnly() ci-dessous.
     */
    @Transactional
    public MailTemplateResponse create(MailTemplateRequest request, AuthenticatedUser requester) {
        if (requester == null || requester.username() == null) {
            throw ApiException.unauthorized("Authentication required.");
        }
        assertValid(request.categoryId(), request.subject(), request.body(), request.recipientType(), request.recipientGroupId());

        User author = userRepository.findFirstByUsernameIgnoreCase(requester.username())
                .orElseThrow(() -> ApiException.unauthorized("Unknown user."));
        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> ApiException.badRequest("Unknown category."));

        MailTemplate template = MailTemplate.builder()
                .category(category)
                .subject(request.subject().trim())
                .body(request.body())
                .recipientType(request.recipientType())
                .recipientGroup("service".equals(request.recipientType())
                        ? recipientGroupRepository.findById(request.recipientGroupId())
                            .orElseThrow(() -> ApiException.badRequest("Unknown recipient group."))
                        : null)
                .createdBy(author)
                .isSystemTemplate(false)
                .build();

        return toResponse(mailTemplateRepository.save(template));
    }

    @Transactional
    public MailTemplateResponse update(Integer id, MailTemplateRequest request, AuthenticatedUser requester) {
        MailTemplate existing = mailTemplateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Mail template not found."));
        assertAuthorOrAdmin(existing, requester);

        // N'applique que les champs réellement fournis (mise à jour partielle) — voir le
        // bug corrigé côté Node (mail-template.service.js) : ne jamais écraser un champ
        // non envoyé avec une valeur null.
        if (request.categoryId() != null) {
            existing.setCategory(categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown category.")));
        }
        if (request.subject() != null) existing.setSubject(request.subject().trim());
        if (request.body() != null) existing.setBody(request.body());
        if (request.recipientType() != null) existing.setRecipientType(request.recipientType());
        if (request.recipientGroupId() != null) {
            existing.setRecipientGroup(recipientGroupRepository.findById(request.recipientGroupId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown recipient group.")));
        }

        assertValid(existing.getCategory().getCategoryId(), existing.getSubject(), existing.getBody(),
                existing.getRecipientType(), existing.getRecipientGroup() != null ? existing.getRecipientGroup().getGroupId() : null);

        return toResponse(mailTemplateRepository.save(existing));
    }

    @Transactional
    public void remove(Integer id, AuthenticatedUser requester) {
        MailTemplate existing = mailTemplateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Mail template not found."));
        assertAuthorOrAdmin(existing, requester);
        mailTemplateRepository.delete(existing);
    }

    /** ⚠️ Réservé à la Quality Assurance — gestion des catégories (types de mail) uniquement.
     *  La création d'un masque personnel (create()) est ouverte à tout utilisateur authentifié. */
    private void requireQaOnly(AuthenticatedUser requester) {
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));
        if (!isQa) {
            throw ApiException.forbidden("Only Quality Assurance can manage mail templates.");
        }
    }

    /**
     * Un modèle système ne peut être modifié/supprimé que par la QA ; un modèle personnel
     * (créé par un agent ou quiconque via create()) peut être modifié/supprimé par son
     * auteur, ou par la QA (droit de regard).
     */
    private void assertAuthorOrAdmin(MailTemplate template, AuthenticatedUser requester) {
        boolean isQa = requester != null && requester.service() != null
                && "quality assurance".equals(requester.service().toLowerCase().replace('_', ' '));

        if (Boolean.TRUE.equals(template.getIsSystemTemplate())) {
            if (!isQa) throw ApiException.forbidden("Only Quality Assurance can modify a system mail template.");
            return;
        }

        String authorUsername = template.getCreatedBy() != null ? template.getCreatedBy().getUsername() : null;
        boolean isAuthor = requester != null && requester.username() != null && requester.username().equalsIgnoreCase(authorUsername);
        if (!isAuthor && !isQa) {
            throw ApiException.forbidden("You can only modify your own mail templates.");
        }
    }

    /** Balises [XXX] présentes dans le sujet + le corps — sert à générer le formulaire de saisie côté écran. */
    @Transactional(readOnly = true)
    public java.util.List<String> listPlaceholders(Integer id) {
        return listPlaceholders(id, null);
    }

    @Transactional(readOnly = true)
    public java.util.List<String> listPlaceholders(Integer id, AuthenticatedUser requester) {
        MailTemplate template = requester == null
                ? mailTemplateRepository.findById(id).orElseThrow(() -> ApiException.notFound("Mail template not found."))
                : findVisible(id, requester);
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        extractPlaceholders(template.getSubject(), found);
        extractPlaceholders(template.getBody(), found);
        return new java.util.ArrayList<>(found);
    }

    /**
     * Remplissage automatique — chaque balise [XXX] du sujet/corps est remplacée par la
     * valeur correspondante fournie. Une balise sans valeur fournie (ou valeur vide) reste
     * telle quelle, pour que l'agent voie clairement ce qu'il lui reste à compléter.
     */
    @Transactional(readOnly = true)
    public com.ecobank.rccportal.dto.MailTemplateFillResponse fill(Integer id, java.util.Map<String, String> values) {
        return fill(id, values, null);
    }

    @Transactional(readOnly = true)
    public com.ecobank.rccportal.dto.MailTemplateFillResponse fill(Integer id, java.util.Map<String, String> values, AuthenticatedUser requester) {
        MailTemplate template = requester == null
                ? mailTemplateRepository.findById(id).orElseThrow(() -> ApiException.notFound("Mail template not found."))
                : findVisible(id, requester);

        java.util.Map<String, String> normalizedValues = new java.util.HashMap<>();
        if (values != null) {
            values.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) normalizedValues.put(k.trim().toUpperCase(), v.trim());
            });
        }

        String filledSubject = applyPlaceholders(template.getSubject(), normalizedValues);
        String filledBody = applyPlaceholders(template.getBody(), normalizedValues);

        return new com.ecobank.rccportal.dto.MailTemplateFillResponse(filledSubject, filledBody, listPlaceholders(id, requester));
    }

    private void extractPlaceholders(String text, java.util.Set<String> target) {
        if (text == null) return;
        var matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) target.add(matcher.group(1).toUpperCase());
    }

    private String applyPlaceholders(String text, java.util.Map<String, String> values) {
        if (text == null) return null;
        var matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).toUpperCase();
            String replacement = values.get(key);
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private MailTemplateResponse toResponse(MailTemplate t) {
        return new MailTemplateResponse(
                t.getTemplateId(),
                t.getCategory().getCategoryId(),
                t.getSubject(),
                t.getBody(),
                t.getRecipientType(),
                t.getRecipientGroup() != null ? t.getRecipientGroup().getGroupId() : null,
                t.getCreatedBy() != null ? t.getCreatedBy().getUsername() : null,
                t.getIsSystemTemplate(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
