package com.example.hangat.course;

import com.example.hangat.course.model.TourApiResponseDto;
import com.example.hangat.course.model.TourPlaceDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TourApiService {

    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 3;
    static final int MAX_RAW_CANDIDATES = PAGE_SIZE * MAX_PAGES;

    private final String baseUrl;
    private final String serviceKey;
    private final RestClient restClient;
    private final KtoRequests requests;

    protected TourApiService() {
        this(RestClient.create(), null, null);
    }

    @Autowired
    public TourApiService(
            @Value("${tour-api.base-url}") String baseUrl,
            @Value("${tour-api.service-key}") String serviceKey,
            @Value("${tour-api.connect-timeout:3s}") java.time.Duration connectTimeout,
            @Value("${tour-api.read-timeout:10s}") java.time.Duration readTimeout
    ) {
        this(KtoRequests.client(connectTimeout, readTimeout), baseUrl, serviceKey,
                new KtoRequests(connectTimeout.plus(readTimeout), Thread::sleep));
    }

    TourApiService(RestClient restClient, String baseUrl, String serviceKey) {
        this(restClient, baseUrl, serviceKey, new KtoRequests(java.time.Duration.ofSeconds(13), Thread::sleep));
    }

    TourApiService(RestClient restClient, String baseUrl, String serviceKey, KtoRequests requests) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.requests = requests;
    }

    public List<TourPlaceDto> getTourPlaces() {
        if (serviceKey == null || serviceKey.isBlank() || baseUrl == null || baseUrl.isBlank()) throw new KtoApiException(false);
        Map<String, TourPlaceDto> uniquePlaces = new LinkedHashMap<>();
        int fetchedCount = 0;

        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            TourApiResponseDto.Body body = fetchPage(pageNo);
            List<TourPlaceDto> pageItems = pageItems(body);
            if (pageItems.isEmpty()) {
                break;
            }

            fetchedCount += pageItems.size();
            for (TourPlaceDto place : pageItems) {
                if (place == null || place.getContentId() == null || place.getContentId().isBlank()) {
                    continue;
                }
                uniquePlaces.putIfAbsent(place.getContentId(), place);
                if (uniquePlaces.size() >= MAX_RAW_CANDIDATES) {
                    return List.copyOf(uniquePlaces.values());
                }
            }

            Integer totalCount = body.getTotalCount();
            if (totalCount != null && fetchedCount >= totalCount) {
                break;
            }
        }

        return List.copyOf(uniquePlaces.values());
    }

    private TourApiResponseDto.Body fetchPage(int pageNo) {
        return requests.execute(pageNo, () -> fetchOnce(pageNo));
    }

    private TourApiResponseDto.Body fetchOnce(int pageNo) {
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl)
                .queryParam("serviceKey", "{serviceKey}")
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "Hangat")
                .queryParam("_type", "json")
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", PAGE_SIZE)
                .queryParam("arrange", "C")
                .queryParam("areaCode", 39)
                .encode()
                .buildAndExpand(serviceKey)
                .toUri();

        TourApiResponseDto result = restClient.get()
                .uri(uri)
                .retrieve()
                .body(TourApiResponseDto.class);

        if (result == null || result.getResponse() == null || result.getResponse().getHeader() == null
                || !"0000".equals(result.getResponse().getHeader().getResultCode()) || result.getResponse().getBody() == null) {
            throw new IllegalArgumentException("KTO response contract");
        }
        return result.getResponse().getBody();
    }

    private List<TourPlaceDto> pageItems(TourApiResponseDto.Body body) {
        if (body == null
                || body.getItems() == null
                || body.getItems().getItem() == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(body.getItems().getItem());
    }
}
