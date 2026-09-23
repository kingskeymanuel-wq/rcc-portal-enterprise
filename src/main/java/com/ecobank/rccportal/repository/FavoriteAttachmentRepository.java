package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Attachment;
import com.ecobank.rccportal.model.FavoriteAttachment;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteAttachmentRepository extends JpaRepository<FavoriteAttachment, Integer> {
    List<FavoriteAttachment> findByUser(User user);
    Optional<FavoriteAttachment> findByUserAndAttachment(User user, Attachment attachment);
    boolean existsByUserAndAttachment(User user, Attachment attachment);
    void deleteByUserAndAttachment(User user, Attachment attachment);
}
