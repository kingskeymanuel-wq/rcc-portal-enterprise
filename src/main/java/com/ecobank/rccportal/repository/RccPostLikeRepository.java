package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccPost;
import com.ecobank.rccportal.model.RccPostLike;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RccPostLikeRepository extends JpaRepository<RccPostLike, Integer> {

    Optional<RccPostLike> findByPostAndUser(RccPost post, User user);

    List<RccPostLike> findByPost(RccPost post);

    long countByPost(RccPost post);

    List<RccPostLike> findByPostIn(Collection<RccPost> posts);
}
