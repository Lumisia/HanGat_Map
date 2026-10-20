package com.example.hangat.config.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증 메일 본문 - 두 벌(HTML·평문)이 같은 정보를 담는지, 메일 클라이언트에서 깨질 요소가 없는지.
 * 사용자가 링크나 코드를 못 받으면 가입·재설정이 그대로 막히므로 값 누락을 우선 본다.
 */
class AuthMailTemplatesTest {

    private static final String LINK = "https://hangatjeju.com/verify?token=abc123&x=1";

    @Test
    void 인증메일은_링크를_HTML과_평문_양쪽에_담는다() {
        AuthMailTemplates.MailContent mail = AuthMailTemplates.verification(LINK);

        assertThat(mail.subject()).isEqualTo("[한갓지도] 이메일 인증을 완료해주세요");
        // 평문본은 그대로 붙여넣을 수 있어야 하므로 escape 하지 않은 원본이 들어간다
        assertThat(mail.text()).contains(LINK).contains("24시간");
        // HTML 에서는 & 가 &amp; 로 들어간다 - 브라우저가 되돌려 읽는다
        assertThat(mail.html()).contains("abc123&amp;x=1").contains("24시간");
        // 주소는 버튼 href 에만 둔다. 글자로 한 번 더 노출하면 토큰이 붙은 긴 URL 이 본문을 지저분하게 만들고,
        // HTML 을 못 읽는 클라이언트는 어차피 평문 파트에서 원본 주소를 받는다.
        assertThat(countOccurrences(mail.html(), "abc123&amp;x=1")).isEqualTo(1);
    }

    @Test
    void 코드메일은_코드와_제한을_양쪽에_담는다() {
        for (AuthMailTemplates.MailContent mail :
                List.of(AuthMailTemplates.resetCode("482913"), AuthMailTemplates.oauthCode("482913"))) {
            assertThat(mail.text()).contains("482913").contains("10분").contains("5회");
            assertThat(mail.html()).contains("482913").contains("10분").contains("5회");
        }
    }

    @Test
    void 환영메일은_닉네임과_기능목록을_담는다() {
        AuthMailTemplates.MailContent mail = AuthMailTemplates.welcome("제주러버", "https://hangatjeju.com");

        assertThat(mail.subject()).isEqualTo("[한갓지도] 한갓지도에 오신 것을 환영합니다");
        assertThat(mail.html()).contains("제주러버님, 환영합니다");
        assertThat(mail.text()).contains("제주러버님, 환영합니다.");

        // 목록은 HTML 과 평문 어느 쪽으로 읽어도 같은 내용이어야 한다
        for (String feature : List.of("한산한 날을 골라서 갑니다", "AI가 동선까지 짜 줍니다",
                "제주 관광지 7,800여 곳", "날씨를 같이 봅니다", "마음에 든 곳은 찜해 둡니다")) {
            assertThat(mail.html()).contains(feature);
            assertThat(mail.text()).contains(feature);
        }
        // CTA 는 홈이 아니라 지도로 보낸다
        assertThat(mail.html()).contains("한갓지도 둘러보기")
                .contains("href=\"https://hangatjeju.com/map\"");
        assertThat(mail.text()).contains("https://hangatjeju.com/map");
    }

    /** FRONTEND_URL 에 슬래시가 붙어 와도 //map 이 되면 안 된다. */
    @Test
    void 사이트주소_끝_슬래시는_정리한다() {
        assertThat(AuthMailTemplates.welcome("제주러버", "https://hangatjeju.com/").html())
                .contains("href=\"https://hangatjeju.com/map\"");
    }

    /** 소셜 가입은 공급자가 준 값을 그대로 쓰므로 닉네임이 비어 올 수 있다. */
    @Test
    void 닉네임이_없으면_회원으로_부른다() {
        for (String nickname : new String[]{null, "", "   "}) {
            AuthMailTemplates.MailContent mail = AuthMailTemplates.welcome(nickname, "https://hangatjeju.com");
            assertThat(mail.html()).contains("회원님, 환영합니다");
            assertThat(mail.text()).startsWith("회원님, 환영합니다.");
        }
    }

    @Test
    void 닉네임에_섞인_HTML은_escape된다() {
        String html = AuthMailTemplates.welcome("<script>alert(1)</script>", "https://hangatjeju.com").html();

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void 제목은_메일별로_다르다() {
        assertThat(AuthMailTemplates.resetCode("000000").subject())
                .isEqualTo("[한갓지도] 비밀번호 재설정 코드");
        assertThat(AuthMailTemplates.oauthCode("000000").subject())
                .isEqualTo("[한갓지도] 소셜 로그인 인증 코드");
    }

    /**
     * 원격 이미지는 기본 차단이라 깨진 아이콘이 뜬다. 이미지는 메일에 첨부한 cid 하나뿐이어야 한다.
     */
    @Test
    void 이미지는_인라인_첨부_하나뿐이다() {
        for (String html : allHtml()) {
            assertThat(html).doesNotContain("<link").doesNotContain("<script");
            assertThat(html).doesNotContain("@import").doesNotContain("url(");
            // src 로 뭔가를 불러오는 곳은 cid: 밖에 없어야 한다
            assertThat(countOccurrences(html, "<img")).isEqualTo(1);
            assertThat(html).contains("src=\"cid:" + AuthMailTemplates.BRAND_IMAGE_CID + "\"");
            assertThat(html).doesNotContain("src=\"http");
        }
    }

    /** 아이콘이 막혀도 브랜딩은 옆의 글자 워드마크가 책임진다 - 그래서 alt 는 비운다. */
    @Test
    void 아이콘이_막혀도_워드마크가_남는다() {
        for (String html : allHtml()) {
            assertThat(html).contains("alt=\"\"");
            assertThat(html).contains(">한갓지도</span>");
        }
    }

    /** {@code <style>} 블록은 클라이언트마다 통째로 지워진다. 스타일은 인라인만 쓴다. */
    @Test
    void 스타일블록_없이_인라인만_쓴다() {
        for (String html : allHtml()) {
            assertThat(html).doesNotContain("<style");
            assertThat(html).contains("<table role=\"presentation\"");
            assertThat(html).contains("<!DOCTYPE html>").contains("lang=\"ko\"");
        }
    }

    /** 색 플레이스홀더가 하나라도 남으면 본문에 {{...}} 가 그대로 노출된다. */
    @Test
    void 치환되지_않은_플레이스홀더가_없다() {
        for (String html : allHtml()) {
            assertThat(html).doesNotContain("{{").doesNotContain("}}");
        }
        assertThat(AuthMailTemplates.resetCode("482913").html())
                .contains("#155e54")   // BRAND_DARK 가 BRAND 보다 먼저 치환돼야 한다
                .doesNotContain("#1f7a6d_DARK");
    }

    @Test
    void 값에_섞인_HTML은_escape된다() {
        String html = AuthMailTemplates.verification("https://x/verify?token=\"><b>bad</b>").html();

        assertThat(html).doesNotContain("<b>bad</b>");
        assertThat(html).contains("&quot;&gt;&lt;b&gt;bad&lt;/b&gt;");
    }

    private static List<String> allHtml() {
        return List.of(
                AuthMailTemplates.verification(LINK).html(),
                AuthMailTemplates.welcome("제주러버", "https://hangatjeju.com").html(),
                AuthMailTemplates.resetCode("482913").html(),
                AuthMailTemplates.oauthCode("482913").html());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
