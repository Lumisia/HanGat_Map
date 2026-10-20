package com.example.hangat.course.share;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CourseShareRepository extends JpaRepository<CourseShare, Long> {
    Optional<CourseShare> findByTokenAndActiveTrue(String token);
}
