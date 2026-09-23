package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Course;
import com.ecobank.rccportal.model.CourseAttempt;
import com.ecobank.rccportal.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CourseAttemptRepository extends JpaRepository<CourseAttempt, Integer> {
    Optional<CourseAttempt> findByCourseAndUser(Course course, User user);
    List<CourseAttempt> findByUser(User user);
    List<CourseAttempt> findByCourse(Course course);
}