package com.example.hangat.user.repository;

import com.example.hangat.user.model.auth.AccountRecoveryToken;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface AccountRecoveryTokenRepository extends JpaRepository<AccountRecoveryToken, Long> {
    Optional<AccountRecoveryToken> findByTokenHash(String tokenHash);
    @Modifying @Query("delete from AccountRecoveryToken t where t.userId = :userId")
    void deleteAllByUserId(Long userId);
}
