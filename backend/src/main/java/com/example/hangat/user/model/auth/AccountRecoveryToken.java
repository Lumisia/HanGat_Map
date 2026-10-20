package com.example.hangat.user.model.auth;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name = "account_recovery_tokens") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountRecoveryToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    public AccountRecoveryToken(Long userId, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId; this.tokenHash = tokenHash; this.expiresAt = expiresAt;
    }
}
