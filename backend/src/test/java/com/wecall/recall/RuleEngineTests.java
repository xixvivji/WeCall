package com.wecall.recall;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static com.wecall.recall.RecallModels.*;
import static com.wecall.recall.RuleEngine.Truth.*;
import static org.assertj.core.api.Assertions.*;

class RuleEngineTests {
    Rule lot=new Rule("IN","LOT_NUMBER",List.of("A01","A02"),null);
    Rule expiry=new Rule("EQ","EXPIRY_DATE",List.of("2026-10-31"),null);
    @Test void appliesThreeValuedLogic() {
        var and=new Rule("AND",null,null,List.of(lot,expiry));
        var or=new Rule("OR",null,null,List.of(lot,expiry));
        assertThat(RuleEngine.evaluate(and,null,LocalDate.parse("2026-10-31"))).isEqualTo(UNKNOWN);
        assertThat(RuleEngine.evaluate(and,null,LocalDate.parse("2026-11-30"))).isEqualTo(FALSE);
        assertThat(RuleEngine.evaluate(or,null,LocalDate.parse("2026-10-31"))).isEqualTo(TRUE);
        assertThat(RuleEngine.evaluate(or,null,LocalDate.parse("2026-11-30"))).isEqualTo(UNKNOWN);
    }
    @Test void dateOnlyConditionDoesNotRequireLot() {
        assertThat(RuleEngine.evaluate(expiry,null,LocalDate.parse("2026-10-31"))).isEqualTo(TRUE);
    }
    @Test void rangeIncludesBothBoundaries() {
        var range=new Rule("BETWEEN","EXPIRY_DATE",List.of("2026-10-01","2026-10-31"),null);
        RuleEngine.validate(range);
        assertThat(RuleEngine.evaluate(range,null,LocalDate.parse("2026-10-01"))).isEqualTo(TRUE);
        assertThat(RuleEngine.evaluate(range,null,LocalDate.parse("2026-10-31"))).isEqualTo(TRUE);
        assertThat(RuleEngine.evaluate(range,null,LocalDate.parse("2026-11-01"))).isEqualTo(FALSE);
        assertThat(RuleEngine.evaluate(range,null,null)).isEqualTo(UNKNOWN);
    }
    @Test void preservesRowRelationships() {
        var a=new Rule("AND",null,null,List.of(new Rule("EQ","LOT_NUMBER",List.of("A01"),null),expiry));
        var b=new Rule("AND",null,null,List.of(new Rule("EQ","LOT_NUMBER",List.of("A02"),null),new Rule("EQ","EXPIRY_DATE",List.of("2026-11-30"),null)));
        var rows=new Rule("OR",null,null,List.of(a,b));
        RuleEngine.validate(rows);
        assertThat(RuleEngine.evaluate(rows,"A01",LocalDate.parse("2026-11-30"))).isEqualTo(FALSE);
        assertThat(RuleEngine.evaluate(rows,"A02",LocalDate.parse("2026-11-30"))).isEqualTo(TRUE);
    }
    @Test void rejectsMalformedTreesAndDates() {
        assertThatThrownBy(()->RuleEngine.validate(new Rule("AND",null,null,List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RuleEngine.validate(new Rule("BETWEEN","EXPIRY_DATE",List.of("2026-11-30","2026-10-01"),null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RuleEngine.validate(new Rule("EQ","EXPIRY_DATE",List.of("2026-02-30"),null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RuleEngine.validate(new Rule("EQ","LOT_NUMBER",List.of("A01"),List.of(lot)))).isInstanceOf(IllegalArgumentException.class);
    }
}
