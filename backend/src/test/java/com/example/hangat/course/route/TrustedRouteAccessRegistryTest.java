package com.example.hangat.course.route;

import com.example.hangat.map.goodprice.KakaoLocalClient.KakaoPlace;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class TrustedRouteAccessRegistryTest {
    @Test void onlyMountainNamesReceiveExpandedQueries() {
        assertThat(TrustedRouteAccessRegistry.mountainQueries("어승생 (제주)", true))
                .containsExactly("어승생악", "어승생악 탐방로", "어승생악 입구", "어승생악 주차장");
        assertThat(TrustedRouteAccessRegistry.mountainQueries("어승생 카페", false))
                .containsExactly("어승생카페 입구", "어승생카페 주차장");
        assertThat(TrustedRouteAccessRegistry.mountainQueries("열안지오름(오라동)", true))
                .containsExactly("열안지오름 오라동 입구", "열안지오름 오라동 주차장", "열안지오름 주차장");
    }
    @Test void officialRelationshipRequiresExactProviderIdentityAndFacilityAddress() {
        var r = TrustedRouteAccessRegistry.find("KTO", "127202").orElseThrow();
        assertThat(r.maxDistanceMeters()).isEqualTo(2000);
        assertThat(r.evidence()).isNotEmpty();
        assertThat(TrustedRouteAccessRegistry.find("KAKAO_LOCAL", "127202")).isEmpty();
        assertThat(TrustedRouteAccessRegistry.find("KTO", "other")).isEmpty();
        var correct = new KakaoPlace("20588448", "어리목주차장", "해안동", r.roadAddress(),
                BigDecimal.valueOf(33.3920786624679), BigDecimal.valueOf(126.49461170461), "PK6", "주차장",null,null,1335);
        assertThat(r.matches(correct)).isTrue();
        assertThat(r.matches(new KakaoPlace("other",correct.name(),correct.address(),correct.roadAddress(),
                correct.latitude(),correct.longitude(),correct.categoryGroupCode(),correct.categoryName(),null,null,1335))).isFalse();
        assertThat(RouteAccessPointResolver.associated(correct,"어승생",33.3856593715,126.5067521811)).isFalse();
        assertThat(RouteAccessPointResolver.MAX_DISTANCE_METERS).isEqualTo(1000);
    }
}
