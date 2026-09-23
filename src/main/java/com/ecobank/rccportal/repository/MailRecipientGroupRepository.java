package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.MailRecipientGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MailRecipientGroupRepository extends JpaRepository<MailRecipientGroup, Integer> {
}
