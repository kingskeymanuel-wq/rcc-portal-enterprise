package com.ecobank.rccportal.repository;

import com.ecobank.rccportal.model.Course;
import com.ecobank.rccportal.model.CourseQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CourseQuestionRepository extends JpaRepository<CourseQuestion, Integer> {
    List<CourseQuestion> findByCourseOrderByQuestionNumberAsc(Course course);
}