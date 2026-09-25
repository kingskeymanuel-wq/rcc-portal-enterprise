package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.*;
import com.ecobank.rccportal.model.KnowledgeArticle;
import com.ecobank.rccportal.model.KnowledgeCategory;
import com.ecobank.rccportal.model.KnowledgeCountry;
import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.repository.KnowledgeArticleRepository;
import com.ecobank.rccportal.repository.KnowledgeCategoryRepository;
import com.ecobank.rccportal.repository.KnowledgeCountryRepository;
import com.ecobank.rccportal.repository.RccNotificationRepository;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgeService {

    private final KnowledgeCategoryRepository categoryRepository;
    private final KnowledgeCountryRepository countryRepository;
    private final KnowledgeArticleRepository articleRepository;
    private final RccNotificationRepository notificationRepository;
    private final ImageStorageService imageStorageService;
    private final com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository;
    private final com.ecobank.rccportal.repository.UserRepository userRepository;
    private final com.ecobank.rccportal.repository.AttachmentRepository attachmentRepository;

    public KnowledgeService(KnowledgeCategoryRepository categoryRepository,
                            KnowledgeCountryRepository countryRepository,
                            KnowledgeArticleRepository articleRepository,
                            RccNotificationRepository notificationRepository,
                            ImageStorageService imageStorageService,
                            com.ecobank.rccportal.repository.RccServiceRepository rccServiceRepository,
                            com.ecobank.rccportal.repository.UserRepository userRepository,
                            com.ecobank.rccportal.repository.AttachmentRepository attachmentRepository) {
        this.categoryRepository = categoryRepository;
        this.countryRepository = countryRepository;
        this.articleRepository = articleRepository;
        this.notificationRepository = notificationRepository;
        this.imageStorageService = imageStorageService;
        this.rccServiceRepository = rccServiceRepository;
        this.userRepository = userRepository;
        this.attachmentRepository = attachmentRepository;
    }

    /**
     * Résout les catégories visibles pour un utilisateur donné : QA/Admin voient TOUTES les
     * équipes (comme avant l'introduction de cette fonctionnalité) ; un agent classique ne
     * voit que les catégories de SA PROPRE équipe (déduite de son ACTIVITY — voir
     * TeamClassifier), pour ne pas mélanger le contenu Inbound/Outbound/CIB.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeCategoryResponse> listCategoriesFor(com.ecobank.rccportal.security.AuthenticatedUser requester) {
        // Base de connaissances UNIQUE : toutes les équipes (Inbound Voix, Mail/Rafiki, CIB,
        // Outbound, agences…) voient exactement les mêmes rubriques — plus de filtrage par équipe.
        return listCategories();
    }

    // ---------- Categories ----------

    @Transactional(readOnly = true)
    public List<KnowledgeCategoryResponse> listCategories() {
        return listCategories(null);
    }

    /** team non nul : ne renvoie que les catégories de cette équipe (+ les catégories
     *  historiques sans équipe, traitées comme INBOUND_VOICE — voir migration). */
    @Transactional(readOnly = true)
    public List<KnowledgeCategoryResponse> listCategories(String team) {
        return categoryRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(c -> team == null || team.isBlank()
                        || team.equalsIgnoreCase(c.getTeam())
                        || (c.getTeam() == null && ("INBOUND_VOICE".equalsIgnoreCase(team) || "INBOUND_MAIL".equalsIgnoreCase(team))))
                .map(c -> new KnowledgeCategoryResponse(c.getCategoryId(), c.getCode(), c.getTitle(), c.getIcon(), c.getImageUrl(), c.getSortOrder(), c.getTeam()))
                .toList();
    }

    @Transactional
    public KnowledgeCategoryResponse createCategory(KnowledgeCategoryRequest request) {
        KnowledgeCategory saved = categoryRepository.save(KnowledgeCategory.builder()
                .code(request.code()).title(request.title()).icon(request.icon())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .team(request.team())
                .build());
        return new KnowledgeCategoryResponse(saved.getCategoryId(), saved.getCode(), saved.getTitle(), saved.getIcon(), saved.getImageUrl(), saved.getSortOrder(), saved.getTeam());
    }

    @Transactional
    public KnowledgeCategoryResponse updateCategory(Integer categoryId, KnowledgeCategoryRequest request) {
        KnowledgeCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> com.ecobank.rccportal.util.ApiException.notFound("Catégorie introuvable."));
        if (request.title() != null && !request.title().isBlank()) category.setTitle(request.title().trim());
        if (request.icon() != null) category.setIcon(request.icon().isBlank() ? null : request.icon().trim());
        if (request.sortOrder() != null) category.setSortOrder(request.sortOrder());
        if (request.team() != null) category.setTeam(request.team().isBlank() ? null : request.team().trim().toUpperCase());
        categoryRepository.save(category);
        return new KnowledgeCategoryResponse(category.getCategoryId(), category.getCode(), category.getTitle(), category.getIcon(), category.getImageUrl(), category.getSortOrder(), category.getTeam());
    }

    @Transactional
    public void deleteCategory(Integer categoryId, boolean force) {
        KnowledgeCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> com.ecobank.rccportal.util.ApiException.notFound("Catégorie introuvable."));
        List<KnowledgeArticle> articles = articleRepository.findByCategory_CategoryIdOrderBySortOrderAsc(categoryId);
        if (!articles.isEmpty() && !force) {
            throw com.ecobank.rccportal.util.ApiException.badRequest(
                    "Impossible de supprimer : " + articles.size() + " article(s) sont encore classés dans cette catégorie. Déplacez-les ou supprimez-les d'abord.");
        }
        if (force) {
            // Suppression explicitement demandée par l'utilisateur (confirmation côté frontend
            // avec le décompte exact) — on nettoie aussi les pièces jointes de chaque article,
            // jamais de ligne orpheline en base après coup.
            for (KnowledgeArticle article : articles) {
                attachmentRepository.deleteAll(
                        attachmentRepository.findByEntityTypeAndEntityId("KnowledgeArticle", article.getArticleId()));
                articleRepository.delete(article);
            }
        }
        categoryRepository.delete(category);
    }

    /** Upload réel — réservé à l'administrateur (voir KnowledgeApiController). */
    @Transactional
    public KnowledgeCategoryResponse updateCategoryImage(Integer categoryId, org.springframework.web.multipart.MultipartFile file) {
        KnowledgeCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> ApiException.notFound("Unknown category."));
        category.setImageUrl(imageStorageService.store(file));
        KnowledgeCategory saved = categoryRepository.save(category);
        return new KnowledgeCategoryResponse(saved.getCategoryId(), saved.getCode(), saved.getTitle(), saved.getIcon(), saved.getImageUrl(), saved.getSortOrder(), saved.getTeam());
    }

    // ---------- Countries ----------

    @Transactional(readOnly = true)
    public List<KnowledgeCountryResponse> listCountries() {
        return countryRepository.findAllByOrderBySortOrderAsc().stream()
                .map(this::toCountryResponse)
                .toList();
    }

    @Transactional
    public KnowledgeCountryResponse createCountry(KnowledgeCountryRequest request) {
        KnowledgeCountry saved = countryRepository.save(KnowledgeCountry.builder()
                .countryCode(request.countryCode()).label(request.label()).flagEmoji(request.flagEmoji())
                .currency(request.currency()).zone(request.zone()).regulator(request.regulator())
                .agencyCount(request.agencyCount()).phone(request.phone())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .build());
        return toCountryResponse(saved);
    }

    private KnowledgeCountryResponse toCountryResponse(KnowledgeCountry c) {
        return new KnowledgeCountryResponse(c.getCountryCode(), c.getLabel(), c.getFlagEmoji(), c.getCurrency(),
                c.getZone(), c.getRegulator(), c.getAgencyCount(), c.getPhone(), c.getSortOrder());
    }

    // ---------- Articles ----------

    /** countryCode null → uniquement les articles génériques de la catégorie.
     *  countryCode renseigné → articles spécifiques au pays + articles génériques (fallback).
     *  serviceCode filtre en plus, en mémoire (peu d'articles par catégorie, pas besoin d'une requête dédiée). */
    @Transactional(readOnly = true)
    public List<KnowledgeArticleResponse> listArticles(Integer categoryId, String countryCode, String serviceCode) {
        KnowledgeCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> ApiException.notFound("Unknown category."));

        // countryCode absent = "toutes les filiales" — doit vraiment tout renvoyer (générique +
        // chaque filiale confondues), pas seulement les articles génériques. Seul un countryCode
        // précis restreint à cette filiale-là uniquement.
        List<KnowledgeArticle> articles = (countryCode == null || countryCode.isBlank())
                ? articleRepository.findByCategory_CategoryIdOrderBySortOrderAsc(categoryId)
                : articleRepository.findByCategoryAndCountry_CountryCodeOrderBySortOrderAsc(category, countryCode);

        if (serviceCode != null && !serviceCode.isBlank()) {
            articles = articles.stream()
                    .filter(a -> a.getService() != null && serviceCode.equalsIgnoreCase(a.getService().getCode()))
                    .toList();
        }

        return articles.stream().map(this::toArticleResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeArticleResponse> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        // Recherche par pertinence (titre > mots-clés > contenu), insensible aux accents, aux
        // pluriels et aux petites fautes de frappe — l'ancien LIKE sur titre/tags ignorait le
        // contenu des articles et exigeait la requête mot pour mot. Voir SearchText.
        List<String> terms = SearchText.queryTerms(query);
        if (terms.isEmpty()) return List.of();
        record Hit(KnowledgeArticle article, double score) {}
        return articleRepository.findAll().stream()
                .map(a -> {
                    var match = SearchText.score(query, terms,
                            SearchText.Field.of(a.getTitle(), 3.0),
                            SearchText.Field.of(a.getTags(), 2.0),
                            SearchText.Field.of(
                                    SearchText.stripHtml(a.getContentHtml()), 1.0));
                    return match.isRelevant(terms.size()) ? new Hit(a, match.score()) : null;
                })
                .filter(java.util.Objects::nonNull)
                .sorted((x, y) -> Double.compare(y.score(), x.score()))
                .limit(50)
                .map(h -> toArticleResponse(h.article()))
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeArticleResponse getArticle(Integer id) {
        return articleRepository.findById(id)
                .map(this::toArticleResponse)
                .orElseThrow(() -> ApiException.notFound("Article not found."));
    }

    @Transactional
    public KnowledgeArticleResponse createArticle(KnowledgeArticleRequest request, Integer createdByUserId) {
        KnowledgeCategory category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> ApiException.badRequest("Unknown category."));
        KnowledgeCountry country = (request.countryCode() != null && !request.countryCode().isBlank())
                ? countryRepository.findById(request.countryCode()).orElseThrow(() -> ApiException.badRequest("Unknown country."))
                : null;
        com.ecobank.rccportal.model.RccService service = resolveService(request.serviceCode());

        KnowledgeArticle saved = articleRepository.save(KnowledgeArticle.builder()
                .category(category).country(country).service(service)
                .title(request.title()).contentHtml(request.contentHtml()).tags(request.tags())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .createdByUserId(createdByUserId)
                .build());
        notifyArticle("Nouvel article publié dans la Base de connaissances : " + saved.getTitle(), saved);
        return toArticleResponse(saved);
    }

    @Transactional
    public KnowledgeArticleResponse updateArticle(Integer id, KnowledgeArticleRequest request) {
        KnowledgeArticle article = articleRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Article not found."));

        if (request.categoryId() != null) {
            article.setCategory(categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown category.")));
        }
        if (request.countryCode() != null) {
            article.setCountry(request.countryCode().isBlank() ? null :
                    countryRepository.findById(request.countryCode()).orElseThrow(() -> ApiException.badRequest("Unknown country.")));
        }
        if (request.serviceCode() != null) {
            article.setService(request.serviceCode().isBlank() ? null : resolveService(request.serviceCode()));
        }
        if (request.title() != null) article.setTitle(request.title());
        if (request.contentHtml() != null) article.setContentHtml(request.contentHtml());
        if (request.tags() != null) article.setTags(request.tags());
        if (request.sortOrder() != null) article.setSortOrder(request.sortOrder());

        articleRepository.save(article);
        notifyArticle("Article mis à jour dans la Base de connaissances : " + article.getTitle(), article);
        return toArticleResponse(article);
    }

    /**
     * Trouve ou crée l'"article conteneur" pour un triplet (catégorie, service, filiale) — utilisé
     * par l'import rapide depuis une vignette de rubrique (même principe que ProcedureService.
     * findOrCreateContainer). Repéré par son titre, généré de façon déterministe à partir du
     * triplet — pas de nouvelle colonne, un second import réutilise le même conteneur.
     */
    @Transactional
    public KnowledgeArticle findOrCreateContainer(Integer categoryId, String serviceCode, String countryCode,
                                                   Integer createdByUserId) {
        KnowledgeCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> ApiException.badRequest("Unknown category."));
        com.ecobank.rccportal.model.RccService service = resolveService(serviceCode);
        KnowledgeCountry country = (countryCode != null && !countryCode.isBlank())
                ? countryRepository.findById(countryCode.trim().toUpperCase())
                    .orElseThrow(() -> ApiException.badRequest("Unknown country."))
                : null;

        String containerTitle = buildContainerTitle(category.getTitle(), service, country);

        List<KnowledgeArticle> candidates = (country == null)
                ? articleRepository.findByCategoryAndCountryIsNullOrderBySortOrderAsc(category)
                : articleRepository.findByCategoryAndCountry_CountryCodeOrderBySortOrderAsc(category, country.getCountryCode());
        for (KnowledgeArticle candidate : candidates) {
            if (containerTitle.equalsIgnoreCase(candidate.getTitle().trim())) {
                return candidate;
            }
        }

        KnowledgeArticle saved = articleRepository.save(KnowledgeArticle.builder()
                .category(category).country(country).service(service)
                .title(containerTitle)
                .contentHtml("<p>Dossier de fichiers — voir les pièces jointes.</p>")
                .sortOrder(0).createdByUserId(createdByUserId)
                .build());
        notifyArticle("Nouveau dossier de fichiers dans la Base de connaissances : " + saved.getTitle(), saved);
        return saved;
    }

    private String buildContainerTitle(String categoryTitle, com.ecobank.rccportal.model.RccService service, KnowledgeCountry country) {
        StringBuilder sb = new StringBuilder("Documents — ").append(categoryTitle);
        if (service != null) sb.append(" (").append(service.getName()).append(")");
        if (country != null) sb.append(" [").append(country.getCountryCode()).append("]");
        return sb.toString();
    }

    private com.ecobank.rccportal.model.RccService resolveService(String serviceCode) {
        if (serviceCode == null || serviceCode.isBlank()) return null;
        return rccServiceRepository.findByCodeIgnoreCase(serviceCode.trim())
                .orElseThrow(() -> ApiException.badRequest("Unknown service: " + serviceCode));
    }

    /** Diffusion globale (TargetUserId=null, visible par tous) — chaque agent voit l'alerte de mise à jour. */
    /** Profils qui consultent la Base de connaissances pour répondre aux clients (pas ceux qui la rédigent). */
    static final String KB_AUDIENCE_ROLES = "AGENT,TEAM_LEADER,SUPERVISOR,FORMATEUR";

    /** Notification ciblée : seulement la filiale (et le service) de l'article, et les profils qui l'utilisent. */
    private void notifyArticle(String content, KnowledgeArticle article) {
        notificationRepository.save(RccNotification.builder()
                .targetUser(null)
                .content(content.length() > 500 ? content.substring(0, 497) + "…" : content)
                .isRead(false)
                .actionType("OPEN_KB_ARTICLE")
                .actionTarget(String.valueOf(article.getArticleId()))
                .audienceCountry(article.getCountry() != null ? article.getCountry().getCountryCode() : null)
                .audienceServiceCode(article.getService() != null ? article.getService().getCode() : null)
                .audienceRoles(KB_AUDIENCE_ROLES)
                .build());
    }

    @Transactional
    public void removeArticle(Integer id) {
        if (!articleRepository.existsById(id)) throw ApiException.notFound("Article not found.");
        articleRepository.deleteById(id);
    }

    private KnowledgeArticleResponse toArticleResponse(KnowledgeArticle a) {
        return new KnowledgeArticleResponse(
                a.getArticleId(), a.getCategory().getCategoryId(), a.getCategory().getTitle(),
                a.getCountry() != null ? a.getCountry().getCountryCode() : null,
                a.getCountry() != null ? a.getCountry().getLabel() : null,
                a.getService() != null ? a.getService().getCode() : null,
                a.getService() != null ? a.getService().getName() : null,
                a.getTitle(), a.getContentHtml(), a.getTags(), a.getSortOrder(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}