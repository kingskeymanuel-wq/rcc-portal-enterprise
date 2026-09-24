package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.Course;
import com.ecobank.rccportal.repository.*;
import com.ecobank.rccportal.util.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Studio de création : la vidéo téléversée devient la vidéo du cours ; un autre fichier est refusé. */
class CourseVideoUploadTest {

    private final CourseRepository courses = mock(CourseRepository.class);
    private final ImageStorageService storage = mock(ImageStorageService.class);
    private final CourseService service = new CourseService(courses, mock(CourseQuestionRepository.class),
            mock(CourseAttemptRepository.class), mock(UserRepository.class), mock(TeamRepository.class),
            mock(CourseCategoryRepository.class), storage, mock(DocumentStorageService.class), new ObjectMapper());

    @Test
    void uploadedVideoReplacesTheCourseVideo() {
        Course course = Course.builder().title("GAB").build();
        course.setCourseId(7);
        when(courses.findById(7)).thenReturn(Optional.of(course));
        when(courses.save(any(Course.class))).thenAnswer(i -> i.getArgument(0));
        when(storage.store(any())).thenReturn("/uploaded-photos/abc.mp4");

        var response = service.updateCourseVideo(7, new MockMultipartFile("file", "cours.mp4", "video/mp4", new byte[]{1, 2, 3}));
        assertEquals("/uploaded-photos/abc.mp4", response.videoUrl());
    }

    @Test
    void nonVideoIsRefusedBeforeStorage() {
        Course course = Course.builder().title("GAB").build();
        when(courses.findById(7)).thenReturn(Optional.of(course));
        assertThrows(ApiException.class, () -> service.updateCourseVideo(7,
                new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1})));
        verify(storage, never()).store(any());
    }
}
