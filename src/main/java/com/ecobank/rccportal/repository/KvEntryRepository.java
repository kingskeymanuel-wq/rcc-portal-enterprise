package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.KvEntry;
import com.ecobank.rccportal.model.KvEntryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KvEntryRepository extends JpaRepository<KvEntry, KvEntryId> {

    List<KvEntry> findById_Scope(String scope);
}
