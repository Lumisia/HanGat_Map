package com.example.hangat.map.goodprice;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoLocalClientTest {
    @Test
    void routeSearchUsesBoundedKeywordAndParkingRequests() {
        Fixture f=fixture();
        f.server.expect(requestTo(containsString("/v2/local/search/keyword.json")))
                .andExpect(requestTo(containsString("radius=1000")))
                .andRespond(withSuccess("{\"documents\":[]}",MediaType.APPLICATION_JSON));
        f.server.expect(requestTo(containsString("category_group_code=PK6")))
                .andRespond(withSuccess("{\"documents\":[]}",MediaType.APPLICATION_JSON));
        assertThat(f.client.searchRouteAccessPoints("어승생 입구",new BigDecimal("126.5"),new BigDecimal("33.4"),1000)).isEmpty();
        assertThat(f.client.searchRouteAccessPoints(null,new BigDecimal("126.5"),new BigDecimal("33.4"),1000)).isEmpty();
        f.server.verify();
    }


    @Test
    void 기존_주소_좌표_계약을_유지한다() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(containsString("/v2/local/search/address.json?query=")))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess("""
                        {"documents":[{"x":"126.5500000","y":"33.4500000"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client.geocode("제주 주소")).contains(
                new KakaoLocalClient.GeoPoint(
                        new BigDecimal("33.4500000"), new BigDecimal("126.5500000")));
        fixture.server.verify();
    }

    @Test
    void AD5_숙박_document_identity와_공식_region을_파싱한다() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(containsString("category_group_code=AD5")))
                .andExpect(requestTo(containsString("radius=20000")))
                .andRespond(withSuccess("""
                        {"documents":[{
                          "id":"12345","place_name":"Kakao 호텔",
                          "address_name":"제주특별자치도 제주시 구좌읍",
                          "road_address_name":"제주특별자치도 제주시 숙소로 1",
                          "x":"126.5500000","y":"33.4500000",
                          "category_group_code":"AD5","category_name":"여행 > 숙박",
                          "phone":"064-000-0000","place_url":"https://place.map.kakao.com/12345",
                          "distance":"1200"
                        }]}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(containsString("/v2/local/geo/coord2regioncode.json")))
                .andRespond(withSuccess("""
                        {"documents":[{
                          "region_type":"H","code":"5011025926",
                          "region_1depth_name":"제주특별자치도",
                          "region_2depth_name":"제주시","region_3depth_name":"구좌읍"
                        }]}
                        """, MediaType.APPLICATION_JSON));

        var places = fixture.client.searchLodgings(
                new BigDecimal("126.55"), new BigDecimal("33.45"), 20_000);
        assertThat(places).singleElement().satisfies(place -> {
            assertThat(place.id()).isEqualTo("12345");
            assertThat(place.categoryGroupCode()).isEqualTo("AD5");
            assertThat(place.distanceMeters()).isEqualTo(1200);
        });

        assertThat(fixture.client.resolveAdministrativeRegion(
                new BigDecimal("126.55"), new BigDecimal("33.45")))
                .get().extracting(KakaoLocalClient.KakaoAdministrativeRegion::region3DepthName)
                .isEqualTo("구좌읍");
        fixture.server.verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new KakaoLocalClient(builder, "test-key", "http://kakao.test"), server);
    }

    private record Fixture(KakaoLocalClient client, MockRestServiceServer server) {
    }
}
