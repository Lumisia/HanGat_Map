package com.example.hangat.config.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 환영 메일을 <b>커밋 이후</b>에 보낸다.
 *
 * <p>인증·재설정 메일은 트랜잭션 안에서 바로 던진다 - 실패해도 사용자가 재발송을 누르면 된다.
 * 환영 메일은 다르다. 회수할 수 없고 재발송 버튼도 없어서, 롤백된 가입에 한 번 나가면 그걸로 끝이다.
 * 그래서 커밋이 확정된 뒤에만 보낸다.
 *
 * <p>{@link AuthMailSender} 를 주입받아 부르는 이유: 같은 빈 안에서 자기 메서드를 부르면
 * 프록시를 타지 않아 {@code @Async} 가 풀린다. 그러면 커밋한 스레드가 SMTP 를 기다리게 된다.
 */
@Component
@RequiredArgsConstructor
public class WelcomeMailDispatcher {

    private final AuthMailSender mailSender;

    /** 트랜잭션이 없으면(테스트·배치 등) 지금 보낸다 - 기다릴 커밋이 없다. */
    public void sendAfterCommit(String email, String nickname) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mailSender.sendWelcome(email, nickname);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mailSender.sendWelcome(email, nickname);
            }
        });
    }
}
