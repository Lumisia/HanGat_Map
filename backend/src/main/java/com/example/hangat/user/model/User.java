package com.example.hangat.user.model;

import com.example.hangat.common.util.DateTimes;
import com.example.hangat.common.util.EmailNormalizer;
import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 회원 모델 - 로그인·가입·프로필의 기준 데이터.
 * 소셜 계정은 비밀번호가 없어서 password를 null 허용으로 뒀고, 연동 정보는 user_social_accounts가 따로 들고 있음.
 * USER_001~003, MY_009~011에서 씀.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_users_nickname", columnNames = "nickname")
        },
        indexes = {
                @Index(name = "idx_users_status", columnList = "status"),
                @Index(name = "idx_users_last_login_at", columnList = "last_login_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Getter
// 닉네임 등 다른 프로필 정보의 동시 수정이 새 사진 키를 덮어쓰지 않도록 변경 컬럼만 갱신한다.
@org.hibernate.annotations.DynamicUpdate
public class User {
    // 이미지 교체 시 새 파일명으로 바꿔 기존 브라우저 캐시와 구분한다.
    public static final String DEMO_PROFILE_FILENAME = "d3e00001-2026-4000-8000-000000000001.png";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 ID. EmailNormalizer 통과한 값만 넣음 */
    @Column(nullable = false, length = 255)
    private String email;

    /** BCrypt 해시만 저장. 소셜 전용 계정은 null */
    @Column(length = 255)
    private String password;

    /** 표시 이름. 실명 컬럼은 안 씀 */
    @Setter
    @Column(nullable = false, length = 50)
    private String nickname;

    /** 프로필 선택 정보 (MY_009) */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** 비공개 저장소 내부 키. 응답에는 MinIO 주소나 키 대신 백엔드 조회 경로를 내보낸다. */
    @Column(name = "profile_image_key", length = 200)
    private String profileImageKey;

    /** 검증·저장이 끝난 사진으로 교체한다. 이전 파일 정리는 서비스가 커밋 후 처리한다. */
    public void updateProfileImage(String key) {
        if (isDemoAccount()) throw new BaseException(BaseResponseStatus.DEMO_PROFILE_IMAGE_LOCKED);
        this.profileImageKey = key;
    }

    /** 리뷰와 내 정보에서 공통으로 쓰는 공개 사진 주소. 비활성 계정·미등록 사진은 노출하지 않는다. */
    public String publicProfileImageUrl() {
        if (id == null || status != UserStatus.ACTIVE) return null;
        if (isDemoAccount()) return "/users/" + id + "/profile-image/" + DEMO_PROFILE_FILENAME;
        if (profileImageKey == null) return null;
        return "/users/" + id + "/profile-image/"
                + profileImageKey.substring(profileImageKey.lastIndexOf('/') + 1);
    }

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.PENDING;

    /** 인증 메일 누른 시각. null이면 미인증 (USER_002) */
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /** 재설정·직접 변경 완료 시각 */
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    /** 로그인 성공 시 갱신 (USER_001) */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /** status=WITHDRAWN일 때만 값이 있어야 함 (DB CHECK) */
    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ────────────────────────── 가입 ──────────────────────────

    /**
     * 이메일 가입 (USER_002).
     * 인증 메일 누르기 전까지 PENDING이라 로그인 안 됨.
     * encodedPassword는 BCrypt 돌린 값만 넘길 것.
     */
    public static User signUpWithEmail(String email, String encodedPassword,
                                       String nickname) {
        return User.builder()
                .email(EmailNormalizer.normalize(email))
                .password(encodedPassword)
                .nickname(nickname)
                .birthDate(null)
                .status(UserStatus.PENDING)
                .build();
    }

    /**
     * 소셜 가입.
     * 공급자가 이메일을 검증했거나 한갓 인증 코드를 통과한 뒤에만 호출한다.
     * 이메일 소유권이 확인됐으므로 PENDING을 건너뛰고 바로 ACTIVE로 만든다.
     */
    public static User signUpWithSocial(String email, String nickname) {
        return User.builder()
                .email(EmailNormalizer.normalize(email))
                .password(null)
                .nickname(nickname)
                .birthDate(null)
                .status(UserStatus.ACTIVE)
                .emailVerifiedAt(DateTimes.nowUtc())
                .build();
    }

    // ────────────────────────── 상태 변경 ──────────────────────────

    /**
     * 인증 메일 클릭 처리 (USER_002).
     * PENDING일 때만 ACTIVE로 올림 - 정지된 회원이 링크 눌러서 풀리면 안 되니까.
     */
    public void verifyEmail() {
        if (this.emailVerifiedAt != null) {
            return; // 이미 인증됨. 링크를 두 번 눌러도 오류 없이 넘어간다
        }
        this.emailVerifiedAt = DateTimes.nowUtc();
        if (this.status == UserStatus.PENDING) {
            this.status = UserStatus.ACTIVE;
        }
    }

    /** 비밀번호 변경. 이미 인코딩된 값을 받음. 재설정도 사용자가 직접 정하므로 이거 하나로 끝 */
    public void changePassword(String encodedPassword) {
        if (isDemoAccount()) {
            throw new BaseException(BaseResponseStatus.DEMO_PASSWORD_LOCKED);
        }
        this.password = encodedPassword;
        this.passwordChangedAt = DateTimes.nowUtc();
    }

    /** 소셜 전용 계정이 비밀번호를 처음 설정하는 경우 */
    public void registerPassword(String encodedPassword) {
        changePassword(encodedPassword);
    }

    /** 로그인 성공 시 갱신 (USER_001) */
    public void recordLogin() {
        this.lastLoginAt = DateTimes.nowUtc();
    }

    /** 선택 프로필 생년월일 변경. null이면 값을 삭제한다. */
    public void updateBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    /**
     * 탈퇴.
     * status와 withdrawnAt이 같이 움직여야 DB CHECK를 안 어김.
     */
    public void withdraw() {
        withdrawAt(DateTimes.nowUtc());
    }

    public void withdrawAt(LocalDateTime at) {
        if (this.status == UserStatus.WITHDRAWN) {
            return; // 멱등
        }
        this.status = UserStatus.WITHDRAWN;
        this.withdrawnAt = at;
        this.authVersion++;
    }

    public void cancelWithdrawal() {
        if (this.status != UserStatus.WITHDRAWN) throw new BaseException(BaseResponseStatus.REQUEST_ERROR);
        this.status = UserStatus.ACTIVE;
        this.withdrawnAt = null;
    }

    // ────────────────────────── 조회 ──────────────────────────

    /** 미인증·이용제한·탈퇴는 로그인 못 함 (USER_001) */
    public boolean canLogin() {
        return this.status.isLoginAllowed();
    }

    /** 소셜 전용 계정 여부. 비번 로그인 시도 구분용 */
    public boolean hasPassword() {
        return this.password != null;
    }

    /** 심사용 단일 계정 정책. 닉네임이나 클라이언트 입력 플래그로 권한을 결정하지 않는다. */
    public boolean isDemoAccount() {
        return "demo@hangatjeju.com".equals(this.email);
    }

    /** 이메일 인증 완료 여부 (USER_002) */
    public boolean isEmailVerified() {
        return this.emailVerifiedAt != null;
    }

    // ────────────────────────── 시간 관련 ──────────────────────────

    @PrePersist
    void onCreate() {
        LocalDateTime now = DateTimes.nowUtc();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = DateTimes.nowUtc();
    }

    // ────────────────────────── 기타 ──────────────────────────

    /**
     * password는 절대 안 넣음.
     * @ToString이나 @Data 붙이면 비밀번호 해시가 로그에 그대로 찍힘.
     */
    @Override
    public String toString() {
        return "User(id=" + id + ", email=" + email + ", nickname=" + nickname
                + ", status=" + status + ")";
    }
}
