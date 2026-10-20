package com.example.hangat.config.mail;

/**
 * 인증 메일 본문 - HTML 과 평문을 같이 만든다.
 *
 * <p>메일 클라이언트는 브라우저가 아니다. 여기서 지키는 것들:
 * <ul>
 *   <li>레이아웃은 table - Gmail·Outlook 에서 flex/grid 가 무너진다</li>
 *   <li>스타일은 전부 인라인 - {@code <style>} 블록은 클라이언트마다 지워진다</li>
 *   <li>원격 이미지·웹폰트 없음 - 대부분 기본값이 이미지 차단이다. 아이콘은 메일에 첨부해 cid 로 쓰고,
 *       그마저 막혀도 글자만으로 성립해야 한다</li>
 *   <li>색은 프론트 tokens.css 와 같은 값을 쓴다</li>
 *   <li>다크 모드 반전을 덜 타도록 배경색을 명시하고 color-scheme 을 light 로 고정한다</li>
 * </ul>
 *
 * <p>평문본은 장식이 아니라 대체본이다. HTML 을 막아둔 클라이언트에는 이쪽이 그대로 보이므로
 * 링크·코드·유효시간이 전부 들어 있어야 한다.
 */
final class AuthMailTemplates {

    /** 발송 한 건 - 제목과 두 본문. */
    record MailContent(String subject, String text, String html) {
    }

    // 프론트 tokens.css 의 라이트 팔레트와 같은 값
    private static final String BRAND = "#1f7a6d";       // --primary
    private static final String BRAND_DARK = "#155e54";  // --primary-dark
    private static final String BRAND_TINT = "#e6f2ee";  // --ac-bg
    private static final String TEXT = "#16242a";
    private static final String SUB = "#5a6972";
    private static final String FAINT = "#8b95a1";
    private static final String PAGE = "#f4f6f8";
    private static final String CARD = "#ffffff";
    private static final String LINE = "#e4eaee";

    /** 한글이 깨지지 않는 순서. 웹폰트를 못 쓰므로 기기 기본 글꼴로만 간다. */
    private static final String FONT =
            "-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',"
                    + "'맑은 고딕',Roboto,'Noto Sans KR','Helvetica Neue',Arial,sans-serif";
    private static final String MONO =
            "ui-monospace,SFMono-Regular,'SF Mono',Menlo,Consolas,'D2Coding',monospace";

    private static final String SITE = "hangatjeju.com";

    /**
     * 대표 아이콘 - 메일에 첨부해 {@code cid:} 로 참조한다.
     *
     * <p>원격 이미지({@code <img src="https://...">})를 쓰지 않는 이유: Gmail·Outlook 은 기본이 이미지 차단이라
     * 첫 화면에 깨진 아이콘이 뜬다. 인라인 첨부는 추적 픽셀이 될 수 없어 대체로 바로 보여준다.
     * 그래도 막히는 클라이언트가 있으므로 옆의 글자 워드마크가 브랜딩을 책임지고,
     * 이미지는 장식이라 {@code alt} 를 비운다.
     */
    static final String BRAND_IMAGE_CID = "hangat-mark";
    static final String BRAND_IMAGE_PATH = "mail/hangat-mark.png";
    static final String WITHDRAWAL_IMAGE_CID = "hangat-withdrawal";

    static MailContent withdrawal(java.time.Instant deleteAt) {
        String deadline = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'KST'")
                .withZone(java.time.ZoneId.of("Asia/Seoul")).format(deleteAt);
        String message = "회원탈퇴가 처리되었습니다. 신청 시각부터 30일간 데이터를 보관합니다. "
                + "삭제 예정 시각은 " + deadline + "입니다. 그 전에 기존 계정으로 로그인하면 탈퇴를 취소할 수 있습니다.";
        String text = "지금까지 이용해주셔서 감사합니다.\n\n" + message;
        String html = shell("회원탈퇴가 처리되었습니다", "신청 시각부터 30일간 데이터를 보관합니다.", """
                <tr><td style="padding:22px 32px 0;">
                  <h1 style="margin:0;font:700 22px/1.45 {{FONT}};color:{{TEXT}};">회원탈퇴가 처리되었습니다</h1>
                </td></tr>
                <tr><td align="center" style="padding:24px 32px;">
                  <img src="cid:hangat-withdrawal" width="360" alt="인사하는 한갓지도 캐릭터들" style="display:block;width:100%;max-width:360px;height:auto;border:0;">
                </td></tr>
                <tr><td style="padding:0 32px;">
                  <p style="margin:0 0 16px;font:700 19px/1.6 {{FONT}};color:{{BRAND}};">지금까지 이용해주셔서 감사합니다.</p>
                  <p style="margin:0;font:400 15px/1.8 {{FONT}};color:{{SUB}};">{{MESSAGE}}</p>
                </td></tr>
                """.replace("{{MESSAGE}}", escape(message)))
                .replace("본인이 요청하지 않았다면 이 메일을 무시해주세요.<br>", "");
        return new MailContent("회원탈퇴가 처리되었습니다", text, html);
    }
    // 원본 166x144 - 워드마크(18px) 옆에 맞춘 표시 크기
    private static final String BRAND_IMAGE_WIDTH = "32";
    private static final String BRAND_IMAGE_HEIGHT = "28";

    /**
     * 환영 메일에 싣는 기능 - {제목, 설명}.
     * 전부 지금 화면에 있는 것이다. 새 기능이 붙으면 여기에 더하고, 빠지면 여기서도 뺀다.
     */
    private static final String[][] FEATURES = {
            {"한산한 날을 골라서 갑니다",
                    "한국관광공사 집중률 예보를 매일 받아 앞으로 30일치를 보여줍니다. "
                            + "같은 장소도 언제 가느냐에 따라 붐빔이 다릅니다."},
            {"AI가 동선까지 짜 줍니다",
                    "일정·인원·예산·이동수단을 넣으면 하루 단위 코스를 만들어 줍니다. "
                            + "후보를 고를 때 그날 혼잡도를 함께 봅니다."},
            {"제주 관광지 7,800여 곳",
                    "사진·운영시간·입장료·주차와 화장실 여부까지 한 화면에서 확인합니다."},
            {"날씨를 같이 봅니다",
                    "기상청 단기·중기 예보를 코스에 붙입니다. 예보가 나빠지면 야외 일정을 "
                            + "실내로 바꾸도록 알려 드립니다."},
            {"마음에 든 곳은 찜해 둡니다",
                    "지도에서 누른 하트가 마이페이지에 그대로 모입니다."}
    };

    private AuthMailTemplates() {
    }

    // ────────────────────────── 메일 4종 ──────────────────────────

    /**
     * 가입 완료 환영 메일.
     *
     * <p>여기 적는 것은 실제로 동작하는 기능만이다 - 메일은 지우지도 고치지도 못하므로
     * 화면에 없는 것을 적으면 그대로 거짓말이 된다. 수치도 근거가 있는 것만 쓴다.
     */
    static MailContent welcome(String nickname, String siteUrl) {
        String name = (nickname == null || nickname.isBlank()) ? "회원" : nickname;
        // 첫 화면은 지도다 - 한산한 곳을 찾는 게 이 서비스의 시작점이라 홈이 아니라 /map 으로 보낸다
        String mapUrl = siteUrl.replaceAll("/+$", "") + "/map";

        String html = shell(
                "한갓지도에 오신 것을 환영합니다",
                "혼잡 예보로 한산한 제주를 찾아보세요.",
                """
                        <tr><td style="padding:22px 32px 0;">
                          <h1 style="margin:0 0 10px;font:700 22px/1.45 {{FONT}};color:{{TEXT}};letter-spacing:-0.02em;">{{NAME}}님, 환영합니다</h1>
                          <p style="margin:0;font:400 15px/1.75 {{FONT}};color:{{SUB}};">가입이 끝났습니다. 한갓지도는 <strong style="color:{{TEXT}};font-weight:600;">붐비지 않는 제주</strong>를 찾는 데 필요한 것만 모았습니다.</p>
                        </td></tr>
                        <tr><td style="padding:24px 32px 0;">
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                        {{FEATURES}}
                          </table>
                        </td></tr>
                        <tr><td style="padding:8px 32px 0;">
                          <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                            <td bgcolor="{{BRAND}}" style="border-radius:10px;">
                              <a href="{{MAP_URL}}" style="display:inline-block;padding:14px 30px;font:600 15px/1 {{FONT}};color:#ffffff;text-decoration:none;border-radius:10px;">한갓지도 둘러보기</a>
                            </td>
                          </tr></table>
                        </td></tr>
                        """
                        .replace("{{NAME}}", escape(name))
                        .replace("{{MAP_URL}}", escape(mapUrl))
                        .replace("{{FEATURES}}", featureRows()));

        StringBuilder text = new StringBuilder()
                .append(name).append("님, 환영합니다.\n\n")
                .append("가입이 끝났습니다. 한갓지도는 붐비지 않는 제주를 찾는 데 필요한 것만 모았습니다.\n\n");
        for (int i = 0; i < FEATURES.length; i++) {
            text.append(i + 1).append(". ").append(FEATURES[i][0]).append('\n')
                    .append("   ").append(FEATURES[i][1]).append("\n\n");
        }
        text.append(mapUrl).append("\n\n한갓지도 · ").append(SITE).append('\n');

        return new MailContent("[한갓지도] 한갓지도에 오신 것을 환영합니다", text.toString(), html);
    }

    static MailContent verification(String link) {
        String html = shell(
                "이메일 인증",
                "24시간 안에 인증을 완료해주세요.",
                """
                        <tr><td style="padding:22px 32px 0;">
                          <h1 style="margin:0 0 10px;font:700 22px/1.45 {{FONT}};color:{{TEXT}};letter-spacing:-0.02em;">이메일 인증을 완료해주세요</h1>
                          <p style="margin:0;font:400 15px/1.75 {{FONT}};color:{{SUB}};">아래 버튼을 눌러 가입을 마무리해주세요.<br>링크는 <strong style="color:{{TEXT}};font-weight:600;">24시간</strong> 동안 유효합니다.</p>
                        </td></tr>
                        <tr><td style="padding:26px 32px 0;">
                          <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                            <td bgcolor="{{BRAND}}" style="border-radius:10px;">
                              <a href="{{LINK}}" style="display:inline-block;padding:14px 30px;font:600 15px/1 {{FONT}};color:#ffffff;text-decoration:none;border-radius:10px;">이메일 인증하기</a>
                            </td>
                          </tr></table>
                        </td></tr>
                        """.replace("{{LINK}}", escape(link)));

        String text = """
                이메일 인증을 완료해주세요.

                아래 링크를 눌러 인증을 완료해주세요. 24시간 동안 유효합니다.
                %s

                본인이 요청하지 않았다면 이 메일을 무시해주세요.

                한갓지도 · %s
                """.formatted(link, SITE);

        return new MailContent("[한갓지도] 이메일 인증을 완료해주세요", text, html);
    }

    static MailContent resetCode(String code) {
        return codeMail("[한갓지도] 비밀번호 재설정 코드", "비밀번호 재설정 코드",
                "아래 코드를 비밀번호 재설정 화면에 입력해주세요.", code);
    }

    static MailContent oauthCode(String code) {
        return codeMail("[한갓지도] 소셜 로그인 인증 코드", "소셜 로그인 인증 코드",
                "아래 코드를 인증 화면에 입력해주세요.", code);
    }

    // ────────────────────────── 공통 조립 ──────────────────────────

    /** 코드형 메일 - 재설정·소셜 인증이 형태가 같아 하나로 묶는다. */
    private static MailContent codeMail(String subject, String heading, String lead, String code) {
        String html = shell(
                heading,
                "인증 코드 " + code + " · 10분 안에 입력해주세요.",
                """
                        <tr><td style="padding:22px 32px 0;">
                          <h1 style="margin:0 0 10px;font:700 22px/1.45 {{FONT}};color:{{TEXT}};letter-spacing:-0.02em;">{{HEADING}}</h1>
                          <p style="margin:0;font:400 15px/1.75 {{FONT}};color:{{SUB}};">{{LEAD}}</p>
                        </td></tr>
                        <tr><td style="padding:22px 32px 0;">
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>
                            <td align="center" bgcolor="{{TINT}}" style="border-radius:12px;padding:24px 16px;">
                              <!-- letter-spacing 은 마지막 글자 뒤에도 붙는다. text-indent 로 되밀어 가운데를 맞춘다 -->
                              <div style="font:700 30px/1.2 {{MONO}};color:{{BRAND_DARK}};letter-spacing:0.32em;text-indent:0.32em;">{{CODE}}</div>
                            </td>
                          </tr></table>
                        </td></tr>
                        <tr><td style="padding:20px 32px 0;">
                          <p style="margin:0;font:400 13px/1.75 {{FONT}};color:{{SUB}};"><strong style="color:{{TEXT}};font-weight:600;">10분</strong> 안에 입력해주세요. 5회 틀리면 코드가 폐기됩니다.</p>
                        </td></tr>
                        """
                        .replace("{{HEADING}}", escape(heading))
                        .replace("{{LEAD}}", escape(lead))
                        .replace("{{CODE}}", escape(code)));

        String text = """
                %s

                %s

                인증 코드: %s

                10분 안에 입력해주세요. 5회 틀리면 코드가 폐기됩니다.
                본인이 요청하지 않았다면 이 메일을 무시해주세요.

                한갓지도 · %s
                """.formatted(heading, lead, code, SITE);

        return new MailContent(subject, text, html);
    }

    /**
     * 기능 목록 한 줄씩.
     * {@code <ul>} 은 클라이언트마다 들여쓰기가 제각각이라 번호 배지 + 본문 2열 table 로 그린다.
     */
    private static String featureRows() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < FEATURES.length; i++) {
            boolean last = i == FEATURES.length - 1;
            rows.append("""
                        <tr>
                          <td width="26" valign="top" style="padding:0 12px {{GAP}}px 0;">
                            <div style="width:26px;height:26px;background:{{TINT}};border-radius:8px;font:700 12px/26px {{FONT}};color:{{BRAND_DARK}};text-align:center;">{{N}}</div>
                          </td>
                          <td valign="top" style="padding:0 0 {{GAP}}px;">
                            <div style="font:600 15px/1.55 {{FONT}};color:{{TEXT}};">{{TITLE}}</div>
                            <div style="margin-top:2px;font:400 13px/1.7 {{FONT}};color:{{SUB}};">{{DESC}}</div>
                          </td>
                        </tr>
                    """
                    .replace("{{N}}", String.valueOf(i + 1))
                    .replace("{{TITLE}}", escape(FEATURES[i][0]))
                    .replace("{{DESC}}", escape(FEATURES[i][1]))
                    .replace("{{GAP}}", last ? "22" : "18"));
        }
        return rows.toString();
    }

    /**
     * 카드 한 장짜리 껍데기.
     * preheader 는 받은편지함 목록에서 제목 옆에 붙는 미리보기 문장이다 - 본문에는 보이지 않게 숨긴다.
     */
    private static String shell(String title, String preheader, String body) {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="light">
                <meta name="supported-color-schemes" content="light">
                <title>{{TITLE}}</title>
                </head>
                <body style="margin:0;padding:0;background:{{PAGE}};">
                <div style="display:none;max-height:0;max-width:0;opacity:0;overflow:hidden;font-size:1px;line-height:1px;color:{{PAGE}};">{{PREHEADER}}</div>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:{{PAGE}};">
                  <tr><td align="center" style="padding:32px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:560px;background:{{CARD}};border:1px solid {{LINE}};border-radius:14px;">
                      <tr><td style="padding:26px 32px 0;">
                        <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                          <td valign="middle" style="padding-right:9px;">
                            <img src="cid:{{BRAND_CID}}" width="{{BRAND_W}}" height="{{BRAND_H}}" alt="" style="display:block;border:0;outline:none;text-decoration:none;">
                          </td>
                          <td valign="middle">
                            <span style="font:700 18px/1.2 {{FONT}};color:{{BRAND}};letter-spacing:-0.02em;">한갓지도</span>
                            <span style="font:400 12px/1.2 {{FONT}};color:{{FAINT}};">&nbsp;&nbsp;한적한 제주를 찾는 지도</span>
                          </td>
                        </tr></table>
                      </td></tr>
                      {{BODY}}
                      <tr><td style="padding:24px 32px 28px;">
                        <div style="border-top:1px solid #eef2f4;padding-top:16px;font:400 12px/1.75 {{FONT}};color:{{FAINT}};">
                          본인이 요청하지 않았다면 이 메일을 무시해주세요.<br>
                          발신 전용 메일입니다 · <a href="https://{{SITE}}" style="color:{{BRAND}};text-decoration:none;">{{SITE}}</a>
                        </div>
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body>
                </html>
                """
                .replace("{{TITLE}}", escape(title))
                .replace("{{PREHEADER}}", escape(preheader))
                .replace("{{BODY}}", body)
                .replace("{{BRAND_CID}}", BRAND_IMAGE_CID)
                .replace("{{BRAND_W}}", BRAND_IMAGE_WIDTH)
                .replace("{{BRAND_H}}", BRAND_IMAGE_HEIGHT)
                .replace("{{FONT}}", FONT)
                .replace("{{MONO}}", MONO)
                .replace("{{BRAND_DARK}}", BRAND_DARK)
                .replace("{{BRAND}}", BRAND)
                .replace("{{TINT}}", BRAND_TINT)
                .replace("{{TEXT}}", TEXT)
                .replace("{{SUB}}", SUB)
                .replace("{{FAINT}}", FAINT)
                .replace("{{PAGE}}", PAGE)
                .replace("{{CARD}}", CARD)
                .replace("{{LINE}}", LINE)
                .replace("{{SITE}}", SITE);
    }

    /** 토큰·코드는 우리가 만든 값이지만, 본문에 그대로 박히므로 escape 해서 넣는다. */
    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
