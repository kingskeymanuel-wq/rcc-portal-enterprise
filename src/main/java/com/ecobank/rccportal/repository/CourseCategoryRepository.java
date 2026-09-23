package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.CourseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CourseCategoryRepository extends JpaRepository<CourseCategory, Integer> {
    List<CourseCategory> findAllByOrderBySortOrderAsc();
    Optional<CourseCategory> findByTitleIgnoreCase(String title);
}
