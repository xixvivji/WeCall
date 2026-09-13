package com.wecall.dataset;

import org.apache.commons.csv.CSVFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.time.*;
import java.util.*;
import java.security.*;

@Service
public class DatasetService {
    private final JdbcTemplate jdbc;
    public DatasetService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private void validPage(int page,int size) {
        if(page<0 || size<1 || size>100) throw new com.wecall.recall.RecallService.Failure(org.springframework.http.HttpStatus.BAD_REQUEST,"page는 0 이상, size는 1~100이어야 합니다");
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> list(int page,int size) {
        validPage(page,size);
        long total=jdbc.queryForObject("SELECT count(*) FROM dataset",Long.class);
        var rows=jdbc.queryForList("SELECT id,as_of AS \"asOf\",created_at AS \"createdAt\" FROM dataset ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",size,(long)page*size);
        return Map.of("items",rows,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size);
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> products(UUID id,String query,int page,int size) {
        validPage(page,size);
        if(query.length()>200) throw new com.wecall.recall.RecallService.Failure(org.springframework.http.HttpStatus.BAD_REQUEST,"검색어는 200자 이하여야 합니다");
        if(jdbc.queryForObject("SELECT count(*) FROM dataset WHERE id=?",Long.class,id)==0)
            throw new com.wecall.recall.RecallService.Failure(org.springframework.http.HttpStatus.NOT_FOUND,"데이터 버전이 없습니다");
        String where=" WHERE dataset_id=? AND (strpos(lower(name),lower(?))>0 OR strpos(lower(id),lower(?))>0)";
        long total=jdbc.queryForObject("SELECT count(*) FROM product"+where,Long.class,id,query.strip(),query.strip());
        var rows=jdbc.queryForList("SELECT id,name,manufacturer,pack_size AS \"packSize\",unit FROM product"+where+" ORDER BY id LIMIT ? OFFSET ?",id,query.strip(),query.strip(),size,(long)page*size);
        return Map.of("items",rows,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> provenance(UUID id) {
        var rows=jdbc.queryForList("SELECT id,as_of AS \"asOf\",created_at AS \"createdAt\",uploaded_by AS \"uploadedBy\" FROM dataset WHERE id=?",id);
        if(rows.isEmpty())throw new com.wecall.recall.RecallService.Failure(org.springframework.http.HttpStatus.NOT_FOUND,"데이터 버전이 없습니다");
        var result=new LinkedHashMap<String,Object>(rows.getFirst());
        var files=jdbc.queryForList("SELECT file_type AS \"type\",filename,sha256,byte_size AS \"byteSize\",row_count AS \"rowCount\" FROM dataset_source_file WHERE dataset_id=? ORDER BY file_type",id);
        var derived=jdbc.queryForList("SELECT id AS \"evidenceId\",case_id AS \"caseId\",base_dataset_id AS \"parentDatasetId\",reviewed_by AS \"reviewedBy\",reviewed_at AS \"reviewedAt\" FROM receipt_evidence WHERE result_dataset_id=? AND status='APPROVED'",id);
        result.put("source",!derived.isEmpty()?"EVIDENCE":!files.isEmpty()?"CSV":"LEGACY");
        result.put("files",files);result.put("derivation",derived.isEmpty()?null:derived.getFirst());
        return result;
    }
    private static final Map<String, String> HEADERS = Map.of(
        "products", "product_id,name,manufacturer,pack_size,unit",
        "receipts", "receipt_id,product_id,lot_number,expiry_date,received_quantity,received_at",
        "inventory", "inventory_id,receipt_id,warehouse,quantity,hold_status",
        "shipments", "shipment_id,order_id,product_id,quantity,shipped_at",
        "shipment_allocations", "allocation_id,shipment_id,receipt_id,quantity");
    private static final List<String> FILES = List.of("products", "receipts", "inventory", "shipments", "shipment_allocations");
    public record ImportError(String file, long row, String field, String message) {}
    public static class InvalidDataset extends RuntimeException {
        public final List<ImportError> errors;
        public InvalidDataset(List<ImportError> errors) { super("CSV validation failed"); this.errors = errors; }
    }
    private record Row(String file, long line, Map<String, String> values) {
        String get(String key) { return values.get(key); }
        long quantity(String key) { return Long.parseLong(get(key)); }
        String id() { return values.values().iterator().next(); }
    }
    private void error(List<ImportError> errors, Row r, String field, String message) {
        errors.add(new ImportError(r.file + ".csv", r.line, field, message));
    }
    private Map<String, Row> parse(String file, MultipartFile upload, List<ImportError> errors) {
        Map<String, Row> rows = new LinkedHashMap<>();
        var headers = List.of(HEADERS.get(file).split(","));
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(upload.getInputStream(), decoder))) {
            reader.mark(1);
            if (reader.read() != '\ufeff') reader.reset();
            try (var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(false).get().parse(reader)) {
                if (!parser.getHeaderNames().equals(headers)) {
                    errors.add(new ImportError(file + ".csv", 1, "header", "헤더와 순서가 지정 양식과 다릅니다"));
                    return rows;
                }
                for (var record : parser) {
                    if (record.getRecordNumber() > 10000) {
                        errors.add(new ImportError(file + ".csv", record.getRecordNumber()+1, "", "파일별 10000행 제한"));
                        break;
                    }
                    if (!record.isConsistent()) {
                        errors.add(new ImportError(file + ".csv", record.getRecordNumber()+1, "", "열 개수가 다릅니다"));
                        continue;
                    }
                    Map<String, String> values = new LinkedHashMap<>();
                    for (String h : headers) values.put(h, record.get(h).trim());
                    Row r = new Row(file, record.getRecordNumber()+1, values);
                    for (String h : headers) {
                        String value = r.get(h);
                        if (Set.of("lot_number", "expiry_date").contains(h) && value.isEmpty()) continue;
                        if (value.isEmpty()) { error(errors, r, h, "필수값 누락"); continue; }
                        if (value.length() > 500) error(errors, r, h, "500자 제한");
                        if (h.equals("quantity") || h.equals("received_quantity")) {
                            try {
                                long n = Long.parseLong(value);
                                if (n < (file.equals("shipment_allocations") ? 1 : 0) || n > 1_000_000_000L)
                                    throw new NumberFormatException();
                            } catch (NumberFormatException e) { error(errors, r, h, "허용 범위 내 정수 수량 필요 (최대 10억)"); }
                        }
                        if (Set.of("expiry_date", "received_at", "shipped_at").contains(h)) {
                            try {
                                if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeException("format");
                                LocalDate.parse(value);
                            } catch (DateTimeException e) { error(errors, r, h, "유효한 YYYY-MM-DD 날짜 필요"); }
                        }
                    }
                    if (file.equals("products") && !r.get("unit").equals("EA")) error(errors, r, "unit", "현재 EA만 지원");
                    if (file.equals("inventory") && !Set.of("NONE", "HELD").contains(r.get("hold_status"))) error(errors,r,"hold_status","NONE 또는 HELD 필요");
                    if (rows.putIfAbsent(r.id(), r) != null) error(errors, r, headers.getFirst(), "중복 ID");
                }
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            errors.add(new ImportError(file + ".csv", 0, "", "UTF-8 CSV 파싱 실패"));
        }
        return rows;
    }
    private void references(Map<String, Map<String, Row>> data, String from, String field, String to, List<ImportError> errors) {
        for (Row r : data.get(from).values())
            if (!data.get(to).containsKey(r.get(field))) error(errors,r,field,"참조 ID가 없습니다");
    }
    private void reject(List<ImportError> errors) { if (!errors.isEmpty()) throw new InvalidDataset(errors); }

    @Transactional
    public Map<String, Object> importFiles(OffsetDateTime asOf, Map<String, MultipartFile> uploads) {
        return importFiles(asOf,uploads,null);
    }
    @Transactional
    public Map<String,Object> importFiles(OffsetDateTime asOf,Map<String,MultipartFile> uploads,String actor) {

        if(uploads.values().stream().anyMatch(f->f.getSize()>5L*1024*1024)||uploads.values().stream().mapToLong(MultipartFile::getSize).sum()>26L*1024*1024)
            throw new com.wecall.recall.RecallService.Failure(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"CSV 파일당 5 MiB, 전체 26 MiB 제한입니다");
        List<ImportError> errors = new ArrayList<>();
        Map<String, Map<String, Row>> data = new LinkedHashMap<>();
        for (String file : FILES) data.put(file, parse(file, uploads.get(file), errors));
        reject(errors);
        references(data,"receipts","product_id","products",errors);
        references(data,"shipments","product_id","products",errors);
        references(data,"inventory","receipt_id","receipts",errors);
        references(data,"shipment_allocations","shipment_id","shipments",errors);
        references(data,"shipment_allocations","receipt_id","receipts",errors);
        reject(errors);
        Map<String,Long> shipped = new HashMap<>(), used = new HashMap<>();
        for (Row r : data.get("inventory").values()) used.merge(r.get("receipt_id"),r.quantity("quantity"),Long::sum);
        for (Row a : data.get("shipment_allocations").values()) {
            Row s = data.get("shipments").get(a.get("shipment_id"));
            Row r = data.get("receipts").get(a.get("receipt_id"));
            if (!s.get("product_id").equals(r.get("product_id"))) error(errors,a,"receipt_id","출고와 입고 상품이 다릅니다");
            if (LocalDate.parse(s.get("shipped_at")).isBefore(LocalDate.parse(r.get("received_at")))) error(errors,a,"receipt_id","입고 이전 출고입니다");
            shipped.merge(s.id(),a.quantity("quantity"),Long::sum);
            used.merge(r.id(),a.quantity("quantity"),Long::sum);
        }
        for (Row r : data.get("receipts").values()) {
            if (used.getOrDefault(r.id(),0L) > r.quantity("received_quantity")) error(errors,r,"received_quantity","재고와 연결 출고 합계가 입고량을 초과합니다");
            if (LocalDate.parse(r.get("received_at")).isAfter(asOf.toLocalDate())) error(errors,r,"received_at","데이터 기준일 이후입니다");
        }
        for (Row s : data.get("shipments").values()) {
            if (shipped.getOrDefault(s.id(),0L) > s.quantity("quantity")) error(errors,s,"quantity","연결 수량이 출고량을 초과합니다");
            if (LocalDate.parse(s.get("shipped_at")).isAfter(asOf.toLocalDate())) error(errors,s,"shipped_at","데이터 기준일 이후입니다");
        }
        reject(errors);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dataset(id,as_of,uploaded_by) VALUES (?,?,?)",id,asOf,actor);
        for(String file:FILES) {
            MultipartFile upload=uploads.get(file);
            String filename=upload.getOriginalFilename();
            if(filename!=null){filename=filename.replace('\\','/');filename=filename.substring(filename.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","_");if(filename.isBlank())filename=null;}
            try {
                var digest=MessageDigest.getInstance("SHA-256");long size;
                try(var input=new DigestInputStream(upload.getInputStream(),digest)){size=input.transferTo(OutputStream.nullOutputStream());}
                jdbc.update("INSERT INTO dataset_source_file(dataset_id,file_type,filename,sha256,byte_size,row_count) VALUES (?,?,?,?,?,?)",id,file,filename,HexFormat.of().formatHex(digest.digest()),size,data.get(file).size());
            } catch(IOException e){throw new InvalidDataset(List.of(new ImportError(file+".csv",0,"file","원본 파일 해시를 읽을 수 없습니다")));}
            catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
        for (Row r : data.get("products").values()) jdbc.update("INSERT INTO product VALUES (?,?,?,?,?,?)",id,r.id(),r.get("name"),r.get("manufacturer"),r.get("pack_size"),r.get("unit"));
        for (Row r : data.get("receipts").values()) jdbc.update("INSERT INTO receipt VALUES (?,?,?,?,?,?,?)",id,r.id(),r.get("product_id"),r.get("lot_number").isEmpty()?null:r.get("lot_number"),r.get("expiry_date").isEmpty()?null:LocalDate.parse(r.get("expiry_date")),r.quantity("received_quantity"),LocalDate.parse(r.get("received_at")));
        for (Row r : data.get("inventory").values()) jdbc.update("INSERT INTO inventory VALUES (?,?,?,?,?,?)",id,r.id(),r.get("receipt_id"),r.get("warehouse"),r.quantity("quantity"),r.get("hold_status"));
        for (Row r : data.get("shipments").values()) jdbc.update("INSERT INTO shipment VALUES (?,?,?,?,?,?)",id,r.id(),r.get("order_id"),r.get("product_id"),r.quantity("quantity"),LocalDate.parse(r.get("shipped_at")));
        for (Row r : data.get("shipment_allocations").values()) jdbc.update("INSERT INTO shipment_allocation VALUES (?,?,?,?,?,?)",id,r.id(),r.get("shipment_id"),r.get("receipt_id"),data.get("shipments").get(r.get("shipment_id")).get("product_id"),r.quantity("quantity"));
        Map<String,Integer> counts = new LinkedHashMap<>();
        data.forEach((k,v)->counts.put(k,v.size()));
        long unlinked = data.get("shipments").values().stream().mapToLong(r->r.quantity("quantity")-shipped.getOrDefault(r.id(),0L)).sum();
        return Map.of("datasetId",id,"asOf",asOf,"counts",counts,"unlinkedShipmentQuantity",unlinked);
    }
}
