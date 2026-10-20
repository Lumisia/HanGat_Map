/**
 * 지도 도메인(MAP_001~009) - 장소·혼잡·사진·검색의 데이터 계층과 적재 파이프라인.
 *
 * <p>패키지 규칙 (2026-09-07 정리, course 패키지와 같은 원리):
 * <ul>
 *   <li>{@code controller/} - HTTP 진입점. 적재 엔드포인트는 {@code PlaceIngestController} 한 곳에 모은다(dev 전용)</li>
 *   <li>{@code service/} - <b>화면 조회</b>({@code PlaceService}, {@code CrowdForecastService})와
 *       <b>공용 유틸</b>({@code RegionResolver}, {@code PlaceNameNormalizer} - course·domain 패키지도 import),
 *       부팅 초기화({@code MapMasterDataInitializer})만 둔다</li>
 *   <li>{@code model/ repository/ client/} - 팀 전체가 쓰는 공용 데이터 계층. 다른 패키지 48곳이 import하므로 옮기지 않는다</li>
 *   <li><b>적재 파이프라인은 데이터 종류별 하위 패키지</b> - {@code place/}(KTO 장소 목록·태그),
 *       {@code congestion/}(집중률 + 스케줄러), {@code detail/}(상세·메뉴), {@code image/}(사진),
 *       {@code goodprice/}(착한가격), {@code store/}(소상공인 상가). 각 패키지는 Service(호출·변환) + Writer(청크 트랜잭션) 쌍</li>
 * </ul>
 */
package com.example.hangat.map;
