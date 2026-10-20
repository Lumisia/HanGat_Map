package com.example.hangat.domain.weather;

import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import com.example.hangat.domain.weather.model.KmaResponse;
import com.example.hangat.domain.weather.model.MidLandItem;
import com.example.hangat.domain.weather.model.MidTaItem;
import com.example.hangat.domain.weather.model.ShortTermItem;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.List;
import java.util.function.Function;

@Component
public class WeatherClient {

    private static final int JEJU_NX = 52;                          // 제주도 격자 x
    private static final int JEJU_NY = 38;                          // 제주도 격자 Y
    private static final String JEJU_TA_REG_ID = "11G00201";        // 중기기온 - 제주
    private static final String JEJU_LAND_REG_ID = "11G00000";      // 중기육상 - 제주도 권역

    private final RestClient restClient;
    private final WeatherProperties properties;

    public WeatherClient(RestClient weatherRestClient, WeatherProperties properties) {
        this.restClient = weatherRestClient;
        this.properties = properties;
    }

    /* 단기 예보 - 시간별 TMP/TMN/TMX/SKY/PTY 행 목록 */
    public List<ShortTermItem> fetchShortTerm(String baseDate, String baseTime) {
        return fetchShortTerm(baseDate, baseTime, JEJU_NX, JEJU_NY);
    }

    public List<ShortTermItem> fetchShortTerm(String baseDate, String baseTime, int nx, int ny) {
        KmaResponse<ShortTermItem> response = call(
                uri -> uri.path("/VilageFcstInfoService_2.0/getVilageFcst")
                        .queryParam("base_date", baseDate)
                        .queryParam("base_time", baseTime)
                        .queryParam("nx", nx)
                        .queryParam("ny", ny)
                        .queryParam("numOfRows", 1000),
                new ParameterizedTypeReference<>() {
                });
        return items(response);
    }

    /* 증기기온 - 발표일 기준 3~7일 후 최저/최고 */
    public MidTaItem fetchMidTemperature(String tmFc) {
        KmaResponse<MidTaItem> response = call(
                uri -> uri.path("/MidFcstInfoService/getMidTa")
                        .queryParam("regId", JEJU_TA_REG_ID)
                        .queryParam("tmFc", tmFc),
                new ParameterizedTypeReference<>() {
                });
        return firstItem(response);
    }

    /* 중기육상 - 발표일 기준 3~7일 후 하늘상태 및 강수확률 */
    public MidLandItem fetchMidLand(String tmFc) {
        KmaResponse<MidLandItem> response = call(
                uri -> uri.path("/MidFcstInfoService/getMidLandFcst")
                        .queryParam("regId", JEJU_LAND_REG_ID)
                        .queryParam("tmFc", tmFc),
                new ParameterizedTypeReference<>() {
                });
        return firstItem(response);
    }

    /* 공통 호출부 - 공통 파라미터 부착, 호출, 검증, 예외 반환 */
    private <T> KmaResponse<T> call(
            Function<UriBuilder, UriBuilder> params,
            ParameterizedTypeReference<KmaResponse<T>> type
    ) {
        return call(params, type, 1);
    }

    private <T> KmaResponse<T> call(
            Function<UriBuilder, UriBuilder> params,
            ParameterizedTypeReference<KmaResponse<T>> type,
            int page
    ) {
        try {
            KmaResponse<T> response = restClient.get()
                    .uri(uriBuilder -> params.apply(uriBuilder)
                            .queryParam("serviceKey", "{serviceKey}")
                            .queryParam("dataType", "JSON")
                            .queryParam("pageNo", page)
                            .build(properties.serviceKey()))
                    .retrieve()
                    .body(type);

            validate(response);
            return response;

        } catch (BaseException e) {
            throw e;

        } catch (Exception e) {
            throw new BaseException(
                    BaseResponseStatus.EXTERNAL_API_ERROR,
                    "기상청 예보를 조회하지 못했습니다."
            );
        }
    }

    /* 헤더 검증 - 기상청 resultCode 00만 정상 */
    private void validate(KmaResponse<?> response) {
        if (response == null || response.response() == null || response.response().header() == null) {
            throw new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "기상청 응답 형식이 올바르지 않습니다.");
        }

        KmaResponse.Header header = response.response().header();
        if(!"00".equals(header.resultCode())) {
            throw new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, header
                    .resultCode() + " " + header.resultMsg());
        }
    }

    private <T> List<T> items(KmaResponse<T> response) {
        KmaResponse.Body<T> body = response.response().body();
        if(body == null || body.items() == null || body.items().item() == null) {
            throw new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "기상청 응답에 데이터가 없습니다.");
        }

        return body.items().item();
    }

    private <T> T firstItem(KmaResponse<T> response) {
        List<T> list = items(response);
        if(list.isEmpty()) {
            throw new BaseException(BaseResponseStatus.EXTERNAL_API_ERROR, "기상청 응답에 데이터가 없습니다.");
        }

         return list.get(0);
    }

    /** 알림 비교용 단기예보 전체 페이지 조회. */
    public List<ShortTermItem> fetchShortTermForNotifications(
            String baseDate,
            String baseTime,
            int nx,
            int ny
    ) {
        List<ShortTermItem> result = new java.util.ArrayList<>();

        for (int page = 1; page <= 20; page++) {
            KmaResponse<ShortTermItem> response = call(
                    uri -> uri.path("/VilageFcstInfoService_2.0/getVilageFcst")
                            .queryParam("base_date", baseDate)
                            .queryParam("base_time", baseTime)
                            .queryParam("nx", nx)
                            .queryParam("ny", ny)
                            .queryParam("numOfRows", 1000),
                    new ParameterizedTypeReference<KmaResponse<ShortTermItem>>() {},
                    page
            );

            List<ShortTermItem> rows = items(response);
            Integer total = response.response().body().totalCount();

            if (total == null || total < 1 || rows.isEmpty()) {
                throw new IllegalStateException("기상청 예보 페이지가 불완전합니다.");
            }

            result.addAll(rows);

            if (result.size() >= total) {
                return List.copyOf(result);
            }
        }

        throw new IllegalStateException("기상청 예보 페이지 제한을 초과했습니다.");
    }
}
