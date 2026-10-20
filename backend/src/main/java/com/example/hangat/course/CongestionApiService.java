package com.example.hangat.course;

import com.example.hangat.course.model.CongestionApiResponseDto;
import com.example.hangat.course.model.CongestionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Service
public class CongestionApiService {

    @Value("${congestion-api.base-url}")
    private String baseUrl;

    @Value("${congestion-api.service-key}")
    private String serviceKey;

    private final RestClient restClient;
    private final KtoRequests requests;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CongestionApiService() {
        this(Duration.ofSeconds(3), Duration.ofSeconds(10));
    }

    @Autowired
    public CongestionApiService(
            @Value("${congestion-api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${congestion-api.read-timeout:10s}") Duration readTimeout) {
        restClient = KtoRequests.client(connectTimeout, readTimeout);
        requests = new KtoRequests(connectTimeout.plus(readTimeout), Thread::sleep);
    }

    public List<CongestionDto> getCongestionData(
            String signguCd,
            String touristAttractionName
    ) {
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl)
                .queryParam("serviceKey", "{serviceKey}")
                .queryParam("pageNo", 1)
                .queryParam("numOfRows", 30)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "Hangat")
                .queryParam("areaCd", "50")
                .queryParam("signguCd", "{signguCd}")
                .queryParam("tAtsNm", "{touristAttractionName}")
                .queryParam("_type", "json")
                .encode()
                .buildAndExpand(
                        serviceKey,
                        signguCd,
                        touristAttractionName
                )
                .toUri();

        // 본문 읽기까지 제한하고, 작업 만료/종료로 인터럽트되면 대기 중인 외부 요청도 취소한다.
        String response = requests.execute(1, () -> restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class));

        try {
            if (response == null || response.isBlank()) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode bodyNode = root
                    .path("response")
                    .path("body");
            JsonNode itemsNode = bodyNode.path("items");

            if (itemsNode.isMissingNode() || itemsNode.isNull()) {
                return Collections.emptyList();
            }

            if (itemsNode.isTextual() && itemsNode.asText().isBlank()) {
                return Collections.emptyList();
            }

            CongestionApiResponseDto result = objectMapper.readValue(
                    response,
                    CongestionApiResponseDto.class
            );

            if (result.getResponse() == null
                    || result.getResponse().getBody() == null
                    || result.getResponse().getBody().getItems() == null
                    || result.getResponse().getBody().getItems().getItem() == null) {
                return Collections.emptyList();
            }

            return result.getResponse()
                    .getBody()
                    .getItems()
                    .getItem();
        } catch (Exception e) {
            throw new RuntimeException(
                    "혼잡도 API 응답 처리 중 오류가 발생했습니다.",
                    e
            );
        }
    }
}
