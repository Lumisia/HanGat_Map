package com.example.hangat.config.mail;

import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 발송 조립 - multipart 구성과 실패 삼키기. 실제 SMTP 는 타지 않는다. */
class AuthMailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final AuthMailSender sender =
            new AuthMailSender(mailSender, "no-reply@hangatjeju.com", "https://hangatjeju.com");

    @Test
    void 인증메일은_평문과_HTML을_함께_보낸다() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        sender.sendVerification("user@example.com", "TOKEN123");

        MimeMessage sent = captureSent();
        assertThat(sent.getSubject()).isEqualTo("[한갓지도] 이메일 인증을 완료해주세요");
        // HTML 을 막아둔 클라이언트가 평문본으로 떨어질 수 있어야 한다.
        // Spring 기본 모드라 바깥은 mixed > related 로 한 겹 더 싸이고 그 안이 alternative 다.
        assertThat(contentTypes(sent)).anyMatch(type -> type.startsWith("multipart/alternative"));
        assertThat(contentTypes(sent))
                .anyMatch(type -> type.startsWith("text/plain"))
                .anyMatch(type -> type.startsWith("text/html"));

        String body = flatten(sent);
        assertThat(body).contains("https://hangatjeju.com/verify?token=TOKEN123");
        assertThat(body).contains("이메일 인증하기");
    }

    /** 아이콘은 원격 참조가 아니라 메일에 실려 나가야 한다 - 안 실리면 본문에 깨진 그림이 뜬다. */
    @Test
    void 대표_아이콘이_메일에_실려_나간다() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        sender.sendWelcome("user@example.com", "제주러버");

        MimeMessage sent = captureSent();
        assertThat(contentTypes(sent)).anyMatch(type -> type.startsWith("image/png"));
        assertThat(flatten(sent)).contains("cid:" + AuthMailTemplates.BRAND_IMAGE_CID);
    }

    @Test
    void 코드메일도_같은_방식으로_나간다() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        sender.sendResetCode("user@example.com", "482913");

        MimeMessage sent = captureSent();
        assertThat(sent.getSubject()).isEqualTo("[한갓지도] 비밀번호 재설정 코드");
        assertThat(flatten(sent)).contains("482913");
    }

    /**
     * &#64;Async 스레드에서 던지면 호출자가 못 받는다 - 로그만 남기고 삼킨다.
     * 여기서 예외가 새면 가입·재설정 트랜잭션이 메일 서버 장애에 끌려간다.
     */
    @Test
    void 발송_실패는_호출자에게_전파되지_않는다() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() -> sender.sendResetCode("user@example.com", "482913"))
                .doesNotThrowAnyException();
    }

    private MimeMessage captureSent() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        // 실제 전송이면 Transport 가 부른다. send 를 목으로 막았으니 헤더를 여기서 갱신한다.
        sent.saveChanges();
        return sent;
    }

    /** 중첩 파트의 Content-Type 을 전부 모은다. */
    private static List<String> contentTypes(MimeMessage message) throws Exception {
        List<String> types = new ArrayList<>();
        collectTypes((MimeMultipart) message.getContent(), types);
        return types;
    }

    private static void collectTypes(MimeMultipart multipart, List<String> out) throws Exception {
        out.add(multipart.getContentType());
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getContent() instanceof MimeMultipart nested) {
                collectTypes(nested, out);
            } else {
                out.add(part.getContentType());
            }
        }
    }

    /** multipart 안의 파트 본문을 전부 이어붙인다 - 어느 파트에 들어갔는지는 따지지 않는다. */
    private static String flatten(MimeMessage message) throws Exception {
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        StringBuilder joined = new StringBuilder();
        appendParts(multipart, joined);
        return joined.toString();
    }

    private static void appendParts(MimeMultipart multipart, StringBuilder out) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            Object content = multipart.getBodyPart(i).getContent();
            if (content instanceof MimeMultipart nested) {
                appendParts(nested, out);
            } else {
                out.append(content).append('\n');
            }
        }
    }
}
