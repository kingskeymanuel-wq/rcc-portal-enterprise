package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, Integer> {
    List<Attachment> findByEntityTypeAndEntityId(String entityType, Integer entityId);
}
