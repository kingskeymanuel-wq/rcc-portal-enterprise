package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.CardAvailabilityDtos.CardRequest;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.CardResponse;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.CellRequest;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.CellResponse;
import com.ecobank.rccportal.dto.CardAvailabilityDtos.MatrixResponse;
import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.model.CardAvailability;
import com.ecobank.rccportal.model.CardProduct;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.ecobank.rccportal.repository.CardAvailabilityRepository;
import com.ecobank.rccportal.repository.CardProductRepository;
import com.ecobank.rccportal.util.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Onglet « Disponibilité des cartes » de la Base de connaissances : pour la filiale choisie,
 * un tableau cartes × villes que QA coche (disponible / non) avec une précision par case,
 * et que les agents consultent pour répondre au client.
 *
 * Les villes proposées sont celles des agences de la filiale (onglet « Agences / Carte »),
 * plus toute ville déjà renseignée ici (QA peut en ajouter une qui n'a pas encore d'agence
 * saisie). Une case jamais cochée = « non renseigné », distinct de « indisponible ».
 */
@Service
public class CardAvailabilityService {

    /** Modèle proposé à QA pour démarrer une filiale vide (créé seulement sur demande explicite). */
    static final List<String[]> STANDARD_CARDS = List.of(
            new String[]{"Visa Classic", "Débit", "Carte de débit internationale liée au compte courant."},
            new String[]{"Visa Gold", "Débit", "Plafonds de retrait et de paiement plus élevés que la Classic."},
            new String[]{"Visa Platinum", "Débit", "Carte premium — plafonds élevés, services associés."},
            new String[]{"Mastercard", "Débit", "Carte de débit internationale Mastercard."},
            new String[]{"Carte prépayée Visa", "Prépayée", "Sans compte bancaire, rechargeable (agence, Mobile app, Xpress)."},
            new String[]{"Carte GIM-UEMOA", "Débit", "Réseau régional UEMOA (filiales concernées uniquement)."}
    );

    private final CardProductRepository cardRepository;
    private final CardAvailabilityRepository availabilityRepository;
    private final BankBranchRepository branchRepository;
    private final BankBranchService branchService;

    public CardAvailabilityService(CardProductRepository cardRepository,
                                   CardAvailabilityRepository availabilityRepository,
                                   BankBranchRepository branchRepository,
                                   BankBranchService branchService) {
        this.cardRepository = cardRepository;
        this.availabilityRepository = availabilityRepository;
        this.branchRepository = branchRepository;
        this.branchService = branchService;
    }

    @Transactional(readOnly = true)
    public MatrixResponse matrix(String countryCode) {
        String country = normalizeCountry(countryCode);
        List<CardProduct> cards = cardRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderBySortOrderAscNameAsc(country);
        List<CardAvailability> cells = cards.isEmpty() ? List.of()
                : availabilityRepository.findByCardProductIdIn(cards.stream().map(CardProduct::getId).toList());

        // Villes : agences de la filiale + villes déjà renseignées, sans doublon (casse/espaces).
        Map<String, String> cities = new LinkedHashMap<>();
        for (BankBranch b : branchRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc(country)) {
            addCity(cities, b.getCity());
        }
        cells.forEach(c -> addCity(cities, c.getCity()));
        Collator collator = Collator.getInstance(Locale.FRENCH);
        collator.setStrength(Collator.PRIMARY);
        List<String> sortedCities = new ArrayList<>(cities.values());
        sortedCities.sort(collator);

        CardAvailability latest = cells.stream()
                .filter(c -> c.getUpdatedAt() != null)
                .max(Comparator.comparing(CardAvailability::getUpdatedAt))
                .orElse(null);

        return new MatrixResponse(country, sortedCities,
                cards.stream().map(CardAvailabilityService::toResponse).toList(),
                cells.stream().map(CardAvailabilityService::toResponse).toList(),
                latest != null ? latest.getUpdatedAt() : null,
                latest != null ? latest.getUpdatedBy() : null);
    }

    @Transactional
    public CardResponse createCard(CardRequest request) {
        CardProduct card = CardProduct.builder()
                .countryCode(normalizeCountry(request.countryCode()))
                .name(request.name().trim())
                .category(blankToNull(request.category()))
                .details(blankToNull(request.details()))
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .active(true)
                .build();
        return toResponse(cardRepository.save(card));
    }

    @Transactional
    public CardResponse updateCard(Long id, CardRequest request) {
        CardProduct card = cardRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Carte introuvable."));
        card.setName(request.name().trim());
        card.setCategory(blankToNull(request.category()));
        card.setDetails(blankToNull(request.details()));
        if (request.sortOrder() != null) card.setSortOrder(request.sortOrder());
        return toResponse(cardRepository.save(card));
    }

    @Transactional
    public void deleteCard(Long id) {
        if (!cardRepository.existsById(id)) throw ApiException.notFound("Carte introuvable.");
        availabilityRepository.deleteByCardProductId(id);
        cardRepository.deleteById(id);
    }

    /** Ajoute les cartes standard absentes (par nom) — n'écrase rien de ce que QA a saisi. */
    @Transactional
    public List<CardResponse> addStandardCards(String countryCode) {
        String country = normalizeCountry(countryCode);
        List<CardProduct> existing = cardRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderBySortOrderAscNameAsc(country);
        List<CardResponse> created = new ArrayList<>();
        int order = existing.size();
        for (String[] s : STANDARD_CARDS) {
            boolean present = existing.stream().anyMatch(c -> c.getName().equalsIgnoreCase(s[0]));
            if (present) continue;
            created.add(toResponse(cardRepository.save(CardProduct.builder()
                    .countryCode(country).name(s[0]).category(s[1]).details(s[2])
                    .sortOrder(order++).active(true).build())));
        }
        return created;
    }

    /** Coche / décoche une case (et sa précision). Crée la ligne au premier cochage. */
    @Transactional
    public CellResponse setCell(CellRequest request, String updatedBy) {
        CardProduct card = cardRepository.findById(request.cardId())
                .orElseThrow(() -> ApiException.notFound("Carte introuvable."));
        String city = cleanCity(request.city());
        if (city.isEmpty()) throw ApiException.badRequest("Ville obligatoire.");
        CardAvailability cell = availabilityRepository.findByCardProductIdAndCityIgnoreCase(card.getId(), city)
                .orElseGet(() -> CardAvailability.builder().cardProductId(card.getId()).city(city).build());
        cell.setAvailable(Boolean.TRUE.equals(request.available()));
        cell.setNote(blankToNull(request.note()));
        cell.setUpdatedBy(updatedBy);
        CardAvailability saved = availabilityRepository.save(cell);
        if (saved.getUpdatedAt() == null) saved.setUpdatedAt(LocalDateTime.now());
        return toResponse(saved);
    }

    private static void addCity(Map<String, String> cities, String city) {
        String clean = cleanCity(city);
        if (!clean.isEmpty()) cities.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
    }

    static String cleanCity(String city) {
        return city == null ? "" : city.trim().replaceAll("\\s+", " ");
    }

    /** Même normalisation que l'onglet « Agences / Carte » (ISO2, ISO3 ou libellé → code à 2 lettres). */
    private String normalizeCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) throw ApiException.badRequest("Filiale obligatoire.");
        return branchService.normalizeCountryCode(countryCode);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static CardResponse toResponse(CardProduct c) {
        return new CardResponse(c.getId(), c.getCountryCode(), c.getName(), c.getCategory(), c.getDetails(), c.getSortOrder());
    }

    private static CellResponse toResponse(CardAvailability c) {
        return new CellResponse(c.getCardProductId(), c.getCity(), Boolean.TRUE.equals(c.getAvailable()),
                c.getNote(), c.getUpdatedBy(), c.getUpdatedAt());
    }
}
