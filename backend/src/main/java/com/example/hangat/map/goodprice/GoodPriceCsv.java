package com.example.hangat.map.goodprice;

import com.example.hangat.map.model.entity.Place;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 착한가격업소 CSV 파싱 (행안부, 제주 필터본 393행).
 * 헤더: 시도,시군,업종,업소명,연락처,주소,메뉴1,가격1,...메뉴4,가격4
 * 주소에 "하귀9길 2, 1층" 처럼 따옴표+콤마가 있어 표준 CSV 규칙으로 읽는다.
 */
public final class GoodPriceCsv {

    /** 외식이 아닌 업종 - 여행 지도에 미용실·세탁소를 올리지 않는다 (W0 분석 분류) */
    private static final List<String> NONFOOD = List.of(
            "이미용", "미용", "이용", "세탁", "목욕", "숙박", "기타", "서비스", "안경", "사진", "인쇄");

    public record Row(String sigun, String category, String name, String phone,
                      String address, List<String> menuPrices) {

        /** "대표메뉴: 갈치국 9,000원 · 해물뚝배기 9,000원" - overview 에 넣는 실데이터 문구 */
        public String menuText() {
            if (menuPrices.isEmpty()) {
                return null;
            }
            return Place.GOOD_PRICE_MENU_PREFIX + " " + String.join(" · ", menuPrices);
        }
    }

    private GoodPriceCsv() {
    }

    public static List<Row> parse(InputStream in) {
        List<List<String>> records = readCsv(in);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {   // 0행은 헤더
            List<String> f = records.get(i);
            if (f.size() < 6) {
                continue;
            }
            List<String> menus = new ArrayList<>();
            for (int m = 6; m + 1 < f.size() && menus.size() < 2; m += 2) {
                String menu = f.get(m).trim();
                String price = f.get(m + 1).replaceAll("[^0-9]", "");
                if (!menu.isEmpty() && !price.isEmpty()) {
                    menus.add(menu + " " + String.format("%,d", Long.parseLong(price)) + "원");
                }
            }
            rows.add(new Row(f.get(1).trim(), f.get(2).trim(), f.get(3).trim(),
                    blankToNull(f.get(4)), f.get(5).trim(), menus));
        }
        return rows;
    }

    /** 외식업만 (제주 393곳 중 284곳) */
    public static boolean isFood(String category) {
        String c = category == null ? "" : category.trim();
        if (c.isEmpty() || c.contains("비요식")) {
            return false;
        }
        if (c.contains("요식")) {
            return true;
        }
        return NONFOOD.stream().noneMatch(c::contains);
    }

    private static String blankToNull(String s) {
        String t = s == null ? null : s.trim();
        return (t == null || t.isEmpty()) ? null : t;
    }

    /** 따옴표·필드 내 콤마를 처리하는 최소 CSV 리더 (UTF-8 BOM 허용) */
    private static List<List<String>> readCsv(InputStream in) {
        List<List<String>> records = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> fields = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            int ch;
            while ((ch = r.read()) != -1) {
                char c = (char) ch;
                if (c == '﻿') {
                    continue;
                }
                if (quoted) {
                    if (c == '"') {
                        int next = r.read();
                        if (next == '"') {
                            cur.append('"');
                        } else {
                            quoted = false;
                            if (next == ',') {
                                fields.add(cur.toString());
                                cur.setLength(0);
                            } else if (next == '\n' || next == -1) {
                                fields.add(cur.toString());
                                records.add(fields);
                                fields = new ArrayList<>();
                                cur.setLength(0);
                            } else if (next != '\r') {
                                cur.append((char) next);
                            }
                        }
                    } else {
                        cur.append(c);
                    }
                } else if (c == '"' && cur.isEmpty()) {
                    quoted = true;
                } else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '\n') {
                    fields.add(cur.toString());
                    records.add(fields);
                    fields = new ArrayList<>();
                    cur.setLength(0);
                } else if (c != '\r') {
                    cur.append(c);
                }
            }
            if (!cur.isEmpty() || !fields.isEmpty()) {
                fields.add(cur.toString());
                records.add(fields);
            }
        } catch (Exception e) {
            throw new IllegalStateException("착한가격 CSV 읽기 실패", e);
        }
        return records;
    }
}
