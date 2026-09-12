package com.wecall.recall;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/recalls/{caseId}/assessments/{runId}")
public class AssessmentExportController {
    private final RecallService recalls;
    public AssessmentExportController(RecallService recalls) {this.recalls=recalls;}
    // Quoting alone does not stop spreadsheet formula evaluation.
    static String safe(String value) {
        if(value==null)return "";
        String stripped=value.stripLeading();
        if(!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 || value.indexOf('\t')>=0 || value.indexOf('\r')>=0 || value.indexOf('\n')>=0)
            return "'"+value;
        return value;
    }
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@PathVariable UUID caseId,@PathVariable UUID runId,@RequestParam String type) throws IOException {
        if(!Set.of("inventory","shipments").contains(type))
            throw new RecallService.Failure(HttpStatus.BAD_REQUEST,"내보내기 유형은 inventory 또는 shipments입니다");
        var run=recalls.getAssessment(caseId,runId);
        var output=new StringWriter();output.write('\ufeff');
        try(var csv=new CSVPrinter(output,CSVFormat.RFC4180)) {
            if(type.equals("inventory")) {
                csv.printRecord("case_id","assessment_id","condition_id","dataset_id","inventory_id","receipt_id","warehouse","quantity_ea","hold_status_at_assessment","decision");
                for(var row:run.inventory())csv.printRecord(caseId,runId,run.conditionId(),run.datasetId(),safe(row.inventoryId()),safe(row.receiptId()),safe(row.warehouse()),row.quantity(),row.holdStatus(),row.decision());
            } else {
                csv.printRecord("case_id","assessment_id","condition_id","dataset_id","shipment_id","order_id","quantity_ea","target_ea","non_target_ea","needs_review_ea","unlinked_ea");
                for(var row:run.shipments())csv.printRecord(caseId,runId,run.conditionId(),run.datasetId(),safe(row.shipmentId()),safe(row.orderId()),row.quantity(),row.target(),row.nonTarget(),row.needsReview(),row.unlinked());
            }
        }
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"assessment-"+runId+"-"+type+".csv\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .body(output.toString().getBytes(StandardCharsets.UTF_8));
    }
}
