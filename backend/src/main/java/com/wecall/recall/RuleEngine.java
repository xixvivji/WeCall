package com.wecall.recall;

import java.time.*;
import java.util.*;
import static com.wecall.recall.RecallModels.*;

/** Three-valued logic: missing inputs never silently become a negative match. */
public final class RuleEngine {
    private RuleEngine() {}
    public enum Truth { TRUE, FALSE, UNKNOWN }
    public static void validate(Rule rule) { validate(rule, 0, new int[]{0}); }
    private static void validate(Rule r, int depth, int[] count) {
        require(r != null && depth <= 8 && ++count[0] <= 100, "조건은 깊이 8, 노드 100개 이내여야 합니다");
        require(r.op() != null, "조건 op가 필요합니다");
        if (Set.of("AND", "OR").contains(r.op())) {
            require(r.field() == null && r.values() == null && r.children() != null
                && r.children().size() >= 2 && r.children().size() <= 20, "AND/OR에는 2~20개 children만 지정합니다");
            for (Rule child : r.children()) validate(child, depth+1, count);
            return;
        }
        require(Set.of("EQ", "IN", "BETWEEN").contains(r.op()), "지원하지 않는 조건 op입니다");
        require(r.field() != null && Set.of("LOT_NUMBER", "EXPIRY_DATE").contains(r.field()), "지원하지 않는 조건 field입니다");
        require(r.children() == null && r.values() != null && !r.values().isEmpty() && r.values().size() <= 100, "리프에는 1~100개 values만 지정합니다");
        require(r.values().stream().allMatch(v->v != null && !v.isBlank() && v.length() <= 500 && v.equals(v.trim())), "조건 값이 비었거나 형식이 잘못되었습니다");
        require(!r.op().equals("EQ") || r.values().size()==1, "EQ에는 값 1개가 필요합니다");
        if (r.op().equals("BETWEEN")) {
            require(r.field().equals("EXPIRY_DATE") && r.values().size()==2, "BETWEEN은 날짜 경계 2개가 필요합니다");
        }
        if (r.field().equals("EXPIRY_DATE")) {
            try {
                for (String value : r.values()) {
                    require(value.matches("\\d{4}-\\d{2}-\\d{2}"), "날짜는 YYYY-MM-DD여야 합니다");
                    LocalDate.parse(value);
                }
                if (r.op().equals("BETWEEN")) require(!LocalDate.parse(r.values().get(0)).isAfter(LocalDate.parse(r.values().get(1))), "날짜 범위가 역전되었습니다");
            } catch (DateTimeException e) { throw new IllegalArgumentException("유효하지 않은 날짜입니다"); }
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    public static Truth evaluate(Rule r, String lot, LocalDate expiry) {
        if (r.op().equals("AND") || r.op().equals("OR")) {
            boolean unknown = false;
            for (Rule child : r.children()) {
                Truth value = evaluate(child,lot,expiry);
                if (r.op().equals("AND") && value==Truth.FALSE) return Truth.FALSE;
                if (r.op().equals("OR") && value==Truth.TRUE) return Truth.TRUE;
                unknown |= value==Truth.UNKNOWN;
            }
            return unknown ? Truth.UNKNOWN : r.op().equals("AND") ? Truth.TRUE : Truth.FALSE;
        }
        String value = r.field().equals("LOT_NUMBER") ? lot : expiry==null ? null : expiry.toString();
        if (value==null || value.isBlank()) return Truth.UNKNOWN;
        boolean match = r.op().equals("BETWEEN")
            ? !expiry.isBefore(LocalDate.parse(r.values().get(0))) && !expiry.isAfter(LocalDate.parse(r.values().get(1)))
            : r.values().contains(value);
        return match ? Truth.TRUE : Truth.FALSE;
    }
}
