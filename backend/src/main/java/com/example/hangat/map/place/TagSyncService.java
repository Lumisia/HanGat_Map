package com.example.hangat.map.place;

import com.example.hangat.map.client.PublicApiClient;
import com.example.hangat.map.model.dto.LclsSystmItem;
import com.example.hangat.map.model.dto.TourApiResponse;
import com.example.hangat.map.model.entity.Tag;
import com.example.hangat.map.model.enums.TagType;
import com.example.hangat.map.repository.TagRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * KTO 분류체계를 {@code tags} 테이블로 동기화한다 - 커밋 A
 *
 * <p>화면의 "모든 종류의 관광지" 드롭다운에 들어갈 값이다. 우리가 임의로 만든 분류가 아니라
 * <b>한국관광공사 표준 분류</b> 246개를 그대로 쓴다 - 심사에서 근거를 댈 수 있고,
 * 장소마다 어느 분류인지는 목록 API가 이미 알려주므로 우리가 판정할 일이 없다.
 *
 * <p><b>호출 비용은 1콜</b>. {@code numOfRows=1000} 한 번에 246건이 다 온다(2026-08-24 실측).
 *
 * <p>{@link PlaceIngestService}가 시작할 때 부른다. 별도 트랜잭션이라 장소 적재가 태그를
 * 참조하기 전에 커밋이 끝나 있다.
 */
@Service
public class TagSyncService {

    private static final Logger log = LoggerFactory.getLogger(TagSyncService.class);

    private static final String PATH = "/KorService2/lclsSystmCode2";

    /** 동기화 결과. */
    public record SyncResult(int fetched, int inserted, int updated, int unchanged) {
    }

    private final PublicApiClient client;
    private final TagRepository tagRepository;

    public TagSyncService(PublicApiClient client, TagRepository tagRepository) {
        this.client = client;
        this.tagRepository = tagRepository;
    }

    @Transactional
    public SyncResult sync() {
        TourApiResponse<LclsSystmItem> res = client.get(PATH,
                Map.of("lclsSystmListYn", "Y", "numOfRows", PublicApiClient.MAX_PAGE_SIZE, "pageNo", 1),
                new TypeReference<TourApiResponse<LclsSystmItem>>() {
                });
        List<LclsSystmItem> items = res.items();
        log.info("KTO 분류체계 수신: {}건 (totalCount={})", items.size(), res.totalCount());

        // 246행뿐이라 통째로 읽어 코드→엔티티 맵으로 쓴다
        Map<String, Tag> existing = tagRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Tag::getCode, t -> t, (a, b) -> a));

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;

        for (LclsSystmItem item : items) {
            String code = item.lclsSystm3Cd();
            String name = item.lclsSystm3Nm();
            if (isBlank(code) || isBlank(name)) {
                continue;
            }
            String description = item.hierarchyText();
            Tag tag = existing.get(code.trim());

            if (tag == null) {
                tagRepository.save(Tag.builder()
                        .code(code.trim())
                        .name(name.trim())
                        .tagType(TagType.PLACE)
                        .description(description)
                        .isActive(true)
                        .build());
                inserted++;
            } else if (!name.trim().equals(tag.getName())
                    || !java.util.Objects.equals(description, tag.getDescription())) {
                tag.updateFromSource(name.trim(), description);
                updated++;
            } else {
                unchanged++;
            }
        }

        SyncResult result = new SyncResult(items.size(), inserted, updated, unchanged);
        log.info("분류체계 동기화 완료 {}", result);
        return result;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
