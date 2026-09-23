package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.KvEntry;
import com.ecobank.rccportal.model.KvEntryId;
import com.ecobank.rccportal.repository.KvEntryRepository;
import com.ecobank.rccportal.security.AuthenticatedUser;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Source de vérité LMS côté backend.
 *
 * ⚠️ Corrigé : la clé de stockage inclut désormais le matricule de l'utilisateur
 * (KvEntryId("lms", "state:" + username)) — avant, une clé fixe "lms"/"state"
 * faisait partager le même état de formation par TOUS les utilisateurs.
 */
@Slf4j
@Service
public class LmsStateService {

    private final KvEntryRepository kvEntryRepository;
    private final ObjectMapper objectMapper;

    public LmsStateService(KvEntryRepository kvEntryRepository, ObjectMapper objectMapper) {
        this.kvEntryRepository = kvEntryRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public JsonNode readState(String username) {
        return kvEntryRepository.findById(stateId(username))
                .map(KvEntry::getValue)
                .map(this::parseOrEmptyObject)
                .orElseGet(objectMapper::createObjectNode);
    }

    @Transactional
    public JsonNode saveState(JsonNode state, AuthenticatedUser requester) {
        if (requester == null) {
            throw ApiException.unauthorized("Authentication required.");
        }
        if (state != null && !state.isObject()) {
            throw ApiException.badRequest("LMS state must be a JSON object.");
        }
        ObjectNode normalized = state == null ? objectMapper.createObjectNode() : (ObjectNode) state.deepCopy();
        KvEntryId id = stateId(requester.username());
        KvEntry entry = kvEntryRepository.findById(id)
                .orElseGet(() -> KvEntry.builder().id(id).build());
        entry.setValue(normalized.toString());
        kvEntryRepository.save(entry);
        log.info("LMS state saved by {} ({}) at {}", requester.name(), requester.username(), LocalDateTime.now());
        return normalized;
    }

    private KvEntryId stateId(String username) {
        return new KvEntryId("lms", "state:" + username);
    }

    private ObjectNode parseOrEmptyObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
            return objectMapper.createObjectNode();
        } catch (Exception ex) {
            log.warn("Unable to parse LMS state JSON from KvEntries, returning empty object.", ex);
            return objectMapper.createObjectNode();
        }
    }
}