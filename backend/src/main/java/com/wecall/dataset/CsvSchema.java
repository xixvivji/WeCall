package com.wecall.dataset;

import java.util.List;

/** Shared source for import headers and downloadable blank templates. */
public final class CsvSchema {
    private CsvSchema() {}
    public record Column(String name,String label,boolean required,String guidance,String example) {}
    public record Template(String type,String field,String label,List<Column> columns) {
        public String header() {return String.join(",",columns.stream().map(Column::name).toList());}
    }
    private static Column c(String name,String label,String guidance,String example) {return new Column(name,label,true,guidance,example);}
    public static final List<Template> TEMPLATES = List.of(
        new Template("products","products","상품",List.of(
            c("product_id","상품 ID","파일 안에서 중복되지 않는 식별자. 다른 파일에서도 같은 값을 사용하세요.","P001"),
            c("name","상품명","원래 상품명을 입력하세요.","합성 크래커"),
            c("manufacturer","제조사","실제 제조사를 입력하세요.","가상식품"),
            c("pack_size","포장 규격","상품을 구분할 수 있는 규격을 입력하세요.","100g"),
            c("unit","수량 단위","EA만 지원합니다. 박스 수량을 그대로 넣지 마세요.","EA"))),
        new Template("receipts","receipts","입고",List.of(
            c("receipt_id","입고 ID","파일 안에서 중복되지 않는 입고 기록 ID입니다.","R001"),
            c("product_id","상품 ID","products.csv에 있는 product_id를 사용하세요.","P001"),
            new Column("lot_number","제조번호",false,"모르면 빈칸으로 두세요. 임의로 추정하거나 UNKNOWN을 넣지 마세요.","A01"),
            new Column("expiry_date","소비기한",false,"YYYY-MM-DD. 모르면 빈칸으로 두세요.","2026-10-31"),
            c("received_quantity","입고 수량","0~1000000000 정수 EA. 재고와 실제 연결 출고의 합계 이상이어야 합니다.","100"),
            c("received_at","입고일","YYYY-MM-DD. 데이터 기준일 이후일 수 없습니다.","2026-09-01"))),
        new Template("inventory","inventory","재고",List.of(
            c("inventory_id","재고 ID","파일 안에서 중복되지 않는 재고 기록 ID입니다.","I001"),
            c("receipt_id","입고 ID","receipts.csv에 있는 receipt_id를 사용하세요.","R001"),
            c("warehouse","창고","재고가 있는 창고 이름 또는 코드입니다.","WH1"),
            c("quantity","재고 수량","0~1000000000 정수 EA입니다.","60"),
            c("hold_status","기존 격리 상태","NONE(미격리) 또는 HELD(격리). 회수 대상 판정과 별개입니다.","NONE"))),
        new Template("shipments","shipments","출고",List.of(
            c("shipment_id","출고 ID","파일 안에서 중복되지 않는 출고 기록 ID입니다.","S001"),
            c("order_id","주문 ID","원래 주문 ID를 입력하세요. 서로 다른 출고에 같은 주문 ID를 쓸 수 있습니다.","O001"),
            c("product_id","상품 ID","products.csv에 있는 product_id를 사용하세요.","P001"),
            c("quantity","출고 수량","0~1000000000 정수 EA. 실제 연결 수량 합계 이상이어야 합니다.","40"),
            c("shipped_at","출고일","YYYY-MM-DD. 기준일 이후이거나 연결한 입고일 이전일 수 없습니다.","2026-09-02"))),
        new Template("shipment_allocations","shipmentAllocations","출고·입고 연결",List.of(
            c("allocation_id","연결 ID","파일 안에서 중복되지 않는 연결 기록 ID입니다.","A001"),
            c("shipment_id","출고 ID","shipments.csv에 실제 기록된 출고 ID입니다.","S001"),
            c("receipt_id","입고 ID","receipts.csv의 입고 ID. 연결하는 출고와 상품이 같아야 합니다.","R001"),
            c("quantity","연결 수량","1~1000000000 정수 EA. 실제 확인한 연결만 입력하세요. 근거가 없으면 연결 행을 만들지 마세요.","40")))
    );
    public static Template find(String type) {
        return TEMPLATES.stream().filter(t->t.type().equals(type)).findFirst().orElseThrow(()->
            new com.wecall.recall.RecallService.Failure(org.springframework.http.HttpStatus.NOT_FOUND,"CSV 양식 종류를 확인하세요"));
    }
}
