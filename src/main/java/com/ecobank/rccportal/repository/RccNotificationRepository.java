package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccNotification;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RccNotificationRepository extends JpaRepository<RccNotification, Integer> {

    /** Notifications personnelles de l'utilisateur + notifications globales (targetUser = null). */
    @Query("SELECT n FROM RccNotification n WHERE n.targetUser IS NULL OR n.targetUser = :user ORDER BY n.createdAt DESC")
    List<RccNotification> findForUserOrGlobal(User user);
}
