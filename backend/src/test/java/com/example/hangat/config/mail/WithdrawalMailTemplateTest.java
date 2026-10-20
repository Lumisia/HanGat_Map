package com.example.hangat.config.mail;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalMailTemplateTest {
    @Test void usesActualKstDeadlineAndThanksAfterInlineImage() {
        var mail = AuthMailTemplates.withdrawal(Instant.parse("2026-10-19T07:58:24Z"));
        assertThat(mail.subject()).isEqualTo("회원탈퇴가 처리되었습니다");
        assertThat(mail.text()).startsWith("지금까지 이용해주셔서 감사합니다.")
                .contains("2026-10-19 16:58:24 KST", "30일간", "탈퇴를 취소할 수 있습니다");
        assertThat(mail.html()).contains("cid:hangat-withdrawal", "font:700 19px", "2026-10-19 16:58:24 KST")
                .doesNotContain("7433MG", "{{", "이 메일을 무시");
        assertThat(mail.html().indexOf("cid:hangat-withdrawal"))
                .isLessThan(mail.html().indexOf("지금까지 이용해주셔서 감사합니다."));
        assertThat(getClass().getClassLoader().getResource("mail/withdrawal.png")).isNotNull();
    }
}
