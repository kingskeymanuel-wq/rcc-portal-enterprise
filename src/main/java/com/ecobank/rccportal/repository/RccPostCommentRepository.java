package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.RccPost;
import com.ecobank.rccportal.model.RccPostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RccPostCommentRepository extends JpaRepository<RccPostComment, Integer> {
    List<RccPostComment> findByPostOrderByCreatedAtAsc(RccPost post);
    List<RccPostComment> findByPostInOrderByCreatedAtAsc(Collection<RccPost> posts);
    long countByPost(RccPost post);
}
