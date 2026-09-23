package com.ecobank.rccportal.service;

import com.ecobank.rccportal.dto.BankBranchCityResponse;
import com.ecobank.rccportal.dto.BankBranchRequest;
import com.ecobank.rccportal.dto.BankBranchResponse;
import com.ecobank.rccportal.model.BankBranch;
import com.ecobank.rccportal.model.KnowledgeCountry;
import com.ecobank.rccportal.repository.BankBranchRepository;
import com.ecobank.rccportal.repository.KnowledgeCountryRepository;
import com.ecobank.rccportal.util.ApiException;
import com.ecobank.rccportal.util.SearchText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Carte des banques" — onglet de la Base de connaissance (voir knowledge.html
 * / bank-map.js) : pour une filiale (countryCode), liste les villes ayant au
 * moins une agence, puis les agences de chaque ville avec position réelle et
 * contacts. Lecture ouverte à tout agent authentifié (même règle que
 * KnowledgeApiController) ; écriture (ajout/modif/suppression d'agence)
 * réservée à QA/ADMIN — voir BankBranchController.requireQaOrAdmin.
 */
@Service
public class BankBranchService {

    /** Codes ISO 3166-1 alpha-3 des filiales → alpha-2, seul format stocké (voir
     *  knowledge-countries.json / bank-branches.json) — au cas où un appelant enverrait "CIV". */
    private static final Map<String, String> ISO3_TO_ISO2 = Map.ofEntries(
            Map.entry("CIV", "CI"), Map.entry("BFA", "BF"), Map.entry("BEN", "BJ"), Map.entry("BDI", "BI"),
            Map.entry("COD", "CD"), Map.entry("CAF", "CF"), Map.entry("COG", "CG"), Map.entry("CMR", "CM"),
            Map.entry("CPV", "CV"), Map.entry("GAB", "GA"), Map.entry("GIN", "GN"), Map.entry("GNQ", "GQ"),
            Map.entry("GNB", "GW"), Map.entry("MLI", "ML"), Map.entry("MOZ", "MZ"), Map.entry("NER", "NE"),
            Map.entry("SEN", "SN"), Map.entry("STP", "ST"), Map.entry("TCD", "TD"), Map.entry("TGO", "TG"));

    private final BankBranchRepository branchRepository;
    private final KnowledgeCountryRepository countryRepository;

    public BankBranchService(BankBranchRepository branchRepository, KnowledgeCountryRepository countryRepository) {
        this.branchRepository = branchRepository;
        this.countryRepository = countryRepository;
    }

    /**
     * Ramène la filiale demandée au code à 2 lettres stocké en base (BankBranch.countryCode =
     * KnowledgeCountry.countryCode) : " ci " → "CI", "CIV" → "CI", "Côte d'Ivoire" → "CI"
     * (libellé d'une filiale de la Base de connaissance, sans tenir compte des accents/casse).
     * Valeur inconnue : renvoyée en majuscules telle quelle (la liste sera simplement vide).
     */
    public String normalizeCountryCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Filiale (country) obligatoire.");
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (code.length() == 2) return code;
        String iso2 = ISO3_TO_ISO2.get(code);
        if (iso2 != null) return iso2;
        String label = SearchText.normalize(raw);
        for (KnowledgeCountry c : countryRepository.findAll()) {
            if (c.getCountryCode() != null && label.equals(SearchText.normalize(c.getLabel()))) {
                return c.getCountryCode().trim().toUpperCase(Locale.ROOT);
            }
        }
        return code;
    }

    @Transactional(readOnly = true)
    public List<BankBranchResponse> listByCountry(String rawCountryCode, boolean includeInactive) {
        String countryCode = normalizeCountryCode(rawCountryCode);
        List<BankBranch> branches = includeInactive
                ? branchRepository.findByCountryCodeIgnoreCaseOrderByCityAscNameAsc(countryCode)
                : branchRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc(countryCode);
        return branches.stream().map(BankBranchResponse::from).toList();
    }

    /** Regroupe par ville — sert à afficher d'abord les villes du pays (façon "choisissez une ville"),
     *  chacune avec un centre approximatif (moyenne des agences) pour cadrer la carte avant de zoomer. */
    @Transactional(readOnly = true)
    public List<BankBranchCityResponse> listCitiesByCountry(String rawCountryCode) {
        String countryCode = normalizeCountryCode(rawCountryCode);
        List<BankBranch> branches = branchRepository.findByCountryCodeIgnoreCaseAndActiveTrueOrderByCityAscNameAsc(countryCode);
        Map<String, List<BankBranch>> byCity = new LinkedHashMap<>();
        for (BankBranch b : branches) {
            byCity.computeIfAbsent(b.getCity(), k -> new java.util.ArrayList<>()).add(b);
        }
        return byCity.entrySet().stream().map(e -> {
            List<BankBranch> list = e.getValue();
            double latSum = 0;
            double lonSum = 0;
            int withCoords = 0;
            for (BankBranch b : list) {
                if (b.getLatitude() != null && b.getLongitude() != null) {
                    latSum += b.getLatitude();
                    lonSum += b.getLongitude();
                    withCoords++;
                }
            }
            Double centerLat = withCoords > 0 ? latSum / withCoords : null;
            Double centerLon = withCoords > 0 ? lonSum / withCoords : null;
            return new BankBranchCityResponse(e.getKey(), list.size(), centerLat, centerLon);
        }).toList();
    }

    @Transactional
    public BankBranchResponse create(BankBranchRequest request) {
        BankBranch branch = BankBranch.builder()
                .countryCode(normalizeCountryCode(request.countryCode()))
                .city(request.city().trim())
                .name(request.name().trim())
                .address(request.address())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .phone(request.phone())
                .email(request.email())
                .openingHours(request.openingHours())
                .managerName(request.managerName())
                .branchType(request.branchType())
                .active(request.active() == null || request.active())
                .build();
        return BankBranchResponse.from(branchRepository.save(branch));
    }

    @Transactional
    public BankBranchResponse update(Long id, BankBranchRequest request) {
        BankBranch branch = branchRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Agence introuvable."));
        branch.setCountryCode(normalizeCountryCode(request.countryCode()));
        branch.setCity(request.city().trim());
        branch.setName(request.name().trim());
        branch.setAddress(request.address());
        branch.setLatitude(request.latitude());
        branch.setLongitude(request.longitude());
        branch.setPhone(request.phone());
        branch.setEmail(request.email());
        branch.setOpeningHours(request.openingHours());
        branch.setManagerName(request.managerName());
        branch.setBranchType(request.branchType());
        if (request.active() != null) {
            branch.setActive(request.active());
        }
        return BankBranchResponse.from(branchRepository.save(branch));
    }

    @Transactional
    public void delete(Long id) {
        if (!branchRepository.existsById(id)) {
            throw ApiException.notFound("Agence introuvable.");
        }
        branchRepository.deleteById(id);
    }
}
