package com.example.hangat.review.model;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 후기 작성 요청 - 별점, 혼잡 제보, 한줄평과 업로드된 사진 URL을 전달한다.
 * 별점 또는 제보 중 하나는 필수이며 교차 필드·사진 소유권 검증은 서비스가 담당한다.
 */
@Getter
@NoArgsConstructor
public class ReviewCreateRequest {

    /** 별점 1~5. 생략 가능 */
    private Byte rating;

    /** QUIET / NORMAL / CROWDED. 생략 가능 */
    private String congestionReport;

    /** 한줄평 최대 60자 */
    private String content;

    /** 업로드 API 가 돌려준 URL, 최대 5장 */
    private List<String> imageUrls;
}
