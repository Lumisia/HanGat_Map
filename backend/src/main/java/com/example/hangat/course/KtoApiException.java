package com.example.hangat.course;

/** Never retain the provider exception: its URI/body can contain credentials. */
public final class KtoApiException extends RuntimeException {
    public static final String USER_MESSAGE = "관광지 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    private final boolean temporary;
    KtoApiException(boolean temporary) { super(USER_MESSAGE); this.temporary = temporary; }
    public boolean isTemporary() { return temporary; }
}
