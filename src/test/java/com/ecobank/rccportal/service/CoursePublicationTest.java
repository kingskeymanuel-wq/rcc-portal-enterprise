package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.Course;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cours créés avant le contrôle de publication QA : considérés comme publiés. */
class CoursePublicationTest {

    @Test
    void legacyCourseWithoutStatusIsPublished() {
        Course legacy = Course.builder().title("Ancien cours").build();
        legacy.setPublicationStatus(null);
        assertEquals("PUBLISHED", CourseService.publicationStatusOf(legacy));
        legacy.setPublicationStatus("DRAFT");
        assertEquals("DRAFT", CourseService.publicationStatusOf(legacy));
    }
}
