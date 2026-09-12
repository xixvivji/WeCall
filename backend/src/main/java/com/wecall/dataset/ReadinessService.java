package com.wecall.dataset;

import com.wecall.recall.RecallService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReadinessService {
    private final JdbcTemplate jdbc;
    public ReadinessService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private Map<String,Object> dataset(UUID id) {
        var rows=jdbc.queryForList("SELECT id,as_of AS \"asOf\",created_at AS \"createdAt\" FROM dataset WHERE id=?",id);
        if(rows.isEmpty())throw new RecallService.Failure(HttpStatus.NOT_FOUND,"데이터 버전이 없습니다");
        return rows.getFirst();
    }
    private static final String SHIPMENTS="""
        SELECT s.id,s.product_id,s.order_id,s.quantity,coalesce(a.linked,0) AS linked,
            s.quantity-coalesce(a.linked,0) AS unlinked
        FROM shipment s LEFT JOIN
            (SELECT shipment_id,sum(quantity) AS linked FROM shipment_allocation WHERE dataset_id=? GROUP BY shipment_id) a
            ON a.shipment_id=s.id WHERE s.dataset_id=?
        """;
    public Map<String,Object> summary(UUID id) {
        var result=new LinkedHashMap<String,Object>(dataset(id));
        for(String table:List.of("product","receipt","inventory","shipment","shipment_allocation"))
            result.put(table+"Count",jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE dataset_id=?",Long.class,id));
        result.put("receipts",jdbc.queryForMap("""
            SELECT count(*) FILTER(WHERE lot_number IS NULL) AS "missingLot",
                count(*) FILTER(WHERE expiry_date IS NULL) AS "missingExpiry",
                count(*) FILTER(WHERE lot_number IS NULL OR expiry_date IS NULL) AS "missingEither"
            FROM receipt WHERE dataset_id=?
            """,id));
        result.put("shipments",jdbc.queryForMap("SELECT count(*) FILTER(WHERE quantity>0 AND linked=0) AS \"unlinkedCount\", count(*) FILTER(WHERE linked>0 AND unlinked>0) AS \"partialCount\", count(*) FILTER(WHERE quantity>0 AND unlinked=0) AS \"fullyLinkedCount\",count(*) FILTER(WHERE quantity=0) AS \"zeroQuantityCount\",coalesce(sum(unlinked),0) AS \"unlinkedQuantity\" FROM ("+SHIPMENTS+") s",id,id));
        return result;
    }
    public Map<String,Object> issues(UUID id,String type,int page,int size) {
        if(!Set.of("receipts","shipments").contains(type) || page<0 || size<1 || size>100)
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"type은 receipts/shipments, page는 0 이상, size는 1~100이어야 합니다");
        dataset(id);
        String query;List<Object> args=new ArrayList<>();args.add(id);
        if(type.equals("receipts"))query="SELECT id,product_id AS \"productId\",lot_number AS \"lotNumber\",expiry_date AS \"expiryDate\",received_quantity AS quantity,received_at AS \"receivedAt\" FROM receipt WHERE dataset_id=? AND (lot_number IS NULL OR expiry_date IS NULL)";
        else {query="SELECT id,product_id AS \"productId\",order_id AS \"orderId\",quantity,linked,unlinked FROM ("+SHIPMENTS+") s WHERE unlinked>0";args.add(id);}
        long total=jdbc.queryForObject("SELECT count(*) FROM ("+query+") issues",Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        return Map.of("items",jdbc.queryForList(query+" ORDER BY id LIMIT ? OFFSET ?",args.toArray()),"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size);
    }
}
