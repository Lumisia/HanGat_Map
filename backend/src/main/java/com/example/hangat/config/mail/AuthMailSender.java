package com.example.hangat.config.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 인증 메일 발송 - 비밀번호 찾기, 카카오 소셜 로그인 + 가입할 때 이용함.
 * &#64;Async를 이용해서 비동기 처리
 *  이유 - 메일 발송은 느리기 때문에 유저가 기다려야함.
 *          + 메일 발송 처리는 다른 스레드에서 실행되는데
 *              실패할 경우 사용하는 서비스들이 트랜잭션이라 안 닿을 수 있음.
 *
 * <p>본문은 {@link AuthMailTemplates} 가 HTML 과 평문 두 벌로 만들고 multipart/alternative 로 함께 보낸다.
 * 클라이언트가 HTML 을 막아두면 평문본이 그대로 보이므로 링크·코드는 양쪽에 다 들어 있다.
 */
@Slf4j
@Component
public class AuthMailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final String frontendUrl;

    public AuthMailSender(
            JavaMailSender mailSender,
            @Value("${app.mail.from:${spring.mail.username}}") String from,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.frontendUrl = frontendUrl;
    }

    // 가입 인증 링크
    @Async
    public void sendWithdrawal(String to, java.time.Instant deleteAt) {
        var content = AuthMailTemplates.withdrawal(deleteAt);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
            attachBrandImage(helper);
            helper.addInline(AuthMailTemplates.WITHDRAWAL_IMAGE_CID,
                    new ClassPathResource("mail/withdrawal.png"), "image/png");
            mailSender.send(message);
        } catch (Exception ignored) {
            log.warn("Withdrawal mail delivery failed");
        }
    }

    @Async
    public void sendVerification(String to, String token) {
        String link = frontendUrl + "/verify?token=" + token;
        send(to, AuthMailTemplates.verification(link));
    }

    /**
     * 가입 완료 환영 메일.
     * 계정이 실제로 쓸 수 있게 된 순간에만 부른다 - 인증 링크를 다시 눌러도 두 번 가면 안 된다.
     */
    @Async
    public void sendWelcome(String to, String nickname) {
        send(to, AuthMailTemplates.welcome(nickname, frontendUrl));
    }

    /** 비밀번호 재설정 6자리 코드 */
    @Async
    public void sendResetCode(String to, String code) {
        send(to, AuthMailTemplates.resetCode(code));
    }

    /** 소셜 가입 또는 기존 계정 연결에 사용할 이메일 인증 코드 */
    @Async
    public void sendOAuthCode(String to, String code) {
        send(to, AuthMailTemplates.oauthCode(code));
    }

    private void send(String to, AuthMailTemplates.MailContent content) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true + setText(평문, HTML) => multipart/alternative
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
            attachBrandImage(helper);
            mailSender.send(message);
        } catch (Exception e) {
            // 던지면 @Async 스레드에서 죽어서 호출자가 못 받음. 로그만 남기고 재발송으로 구제
            log.error("인증 메일 발송 실패 subject={}", content.subject(), e);
        }
    }

    /**
     * 대표 아이콘을 인라인 첨부한다. setText 뒤에 불러야 한다(Spring 요구사항).
     *
     * <p>아이콘은 장식이다. 리소스가 빠져도 메일 자체는 나가야 하므로 여기서만 따로 삼킨다 -
     * 이걸 바깥 catch 에 맡기면 그림 하나 때문에 인증 링크가 통째로 안 나간다.
     */
    private void attachBrandImage(MimeMessageHelper helper) {
        try {
            helper.addInline(AuthMailTemplates.BRAND_IMAGE_CID,
                    new ClassPathResource(AuthMailTemplates.BRAND_IMAGE_PATH), "image/png");
        } catch (Exception e) {
            log.warn("메일 아이콘 첨부 실패 - 글자 워드마크로만 나간다 path={}",
                    AuthMailTemplates.BRAND_IMAGE_PATH, e);
        }
    }
}
