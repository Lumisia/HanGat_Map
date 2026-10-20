package com.example.hangat.user.repository;


import com.example.hangat.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.example.hangat.user.model.UserStatus;

import java.util.Optional;


public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByIdAndStatus(Long id, UserStatus status);

    /** 동시 사진 교체를 순서대로 처리해 이전 사진 키를 정확히 회수한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);
}
