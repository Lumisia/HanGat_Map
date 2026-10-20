package com.example.hangat.course;

import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.model.entity.CourseItem;
import com.example.hangat.course.model.entity.CourseItemCost;
import com.example.hangat.course.model.enums.CostCategory;
import com.example.hangat.map.model.entity.Place;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Only parses the controlled good-price import format, never arbitrary place descriptions. */
public final class CourseMenuCostResolver {
    private static final Pattern MENU = Pattern.compile(".+ ([0-9]{1,3}(?:,[0-9]{3})*|[0-9]+)원");

    public List<CourseItemCost> resolve(Course course, List<CourseItem> items, List<CourseItemCost> stored) {
        var result = new ArrayList<>(stored);
        for (CourseItem item : items) {
            boolean covered = stored.stream().anyMatch(cost -> cost.getCourseItem() != null
                    && cost.getCourseItem().getId().equals(item.getId()));
            if (!covered) result.add(estimate(course, item));
        }
        return result;
    }

    private CourseItemCost estimate(Course course, CourseItem item) {
        Place place = item.getPlace();
        String text = place.getOverview();
        if (place.isGoodPrice() && text != null && text.startsWith(Place.GOOD_PRICE_MENU_PREFIX)
                && course.getPeople() != null && course.getPeople() > 0) {
            var prices = new ArrayList<Integer>();
            try {
                for (String menu : text.substring(Place.GOOD_PRICE_MENU_PREFIX.length()).trim().split(" · ")) {
                    var match = MENU.matcher(menu);
                    if (!match.matches()) return CourseItemCost.unknown(course, item, CostCategory.FOOD);
                    int unit = Integer.parseInt(match.group(1).replace(",", ""));
                    if (unit <= 0) return CourseItemCost.unknown(course, item, CostCategory.FOOD);
                    prices.add(Math.multiplyExact(unit, course.getPeople().intValue()));
                }
                if (!prices.isEmpty()) {
                    int min = prices.stream().mapToInt(Integer::intValue).min().orElseThrow();
                    int max = prices.stream().mapToInt(Integer::intValue).max().orElseThrow();
                    return CourseItemCost.estimated(course, item, CostCategory.FOOD, min, max,
                            "착한가격 대표메뉴 중 1인 1메뉴 × " + course.getPeople() + "명 (추정, 실제 주문과 다를 수 있음)"
                                    + (place.getGoodPriceBaseDate() == null ? "" : " · 자료 기준 " + place.getGoodPriceBaseDate()));
                }
            } catch (NumberFormatException | ArithmeticException invalidPrice) {
                // Unusable or overflowing source amounts must not become a fabricated price.
            }
        }
        return CourseItemCost.unknown(course, item, place.isGoodPrice() ? CostCategory.FOOD : CostCategory.ACTIVITY);
    }
}
