package com.example.hangat.user.model.dto;

import com.example.hangat.user.model.User;
import com.example.hangat.user.model.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 회원 정보 DTO 모음
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserDto {

    /** 닉네임 변경 */
    public record NicknameRequest(
            @NotBlank(message = "닉네임을 입력해주세요.")
            @Size(min = 2, max = 50, message = "닉네임은 2~50자 이내로 하셔야합니다.")
            String nickname
    ){
    }
    public record NicknameAvailableResponse(boolean available) {
    }

    /** 선택 생년월일 변경. null이면 기존 값을 삭제한다. */
    public record BirthDateRequest(
            @Past(message = "생년월일을 확인해주세요.")
            LocalDate birthDate
    ) {
    }

    /**
     * 회원 정보 응답
     */
    public record UserResponse(
            Long userId,
            String  email,
            String nickname,
            LocalDate birthDate,
            UserStatus status,
            boolean emailVerified,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            String profileImageUrl,
            boolean demoAccount
    ) {

        public static UserResponse form(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getNickname(),
                    user.getBirthDate(),
                    user.getStatus(),
                    user.isEmailVerified(),
                    user.getLastLoginAt(),
                    user.getCreatedAt(),
                    // 이미 열린 이전 프론트도 읽을 수 있도록 내 정보는 기존 본인 전용 주소를 유지한다.
                    user.isDemoAccount() ? user.publicProfileImageUrl() : user.getProfileImageKey() == null ? null : "/users/me/profile-image/"
                            + user.getProfileImageKey().substring(user.getProfileImageKey().lastIndexOf('/') + 1),
                    user.isDemoAccount()
            );
        }
    }
}
