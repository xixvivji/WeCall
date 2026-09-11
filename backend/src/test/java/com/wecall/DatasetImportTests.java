package com.wecall;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WithMockUser(username="test-reviewer",roles="REVIEWER")
@SpringBootTest @AutoConfigureMockMvc @Import(PostgresTestConfiguration.class)
class DatasetImportTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    static final Map<String,String> FILES = Map.of("products","products", "receipts","receipts", "inventory","inventory", "shipments","shipments", "shipmentAllocations","shipment_allocations");
    @BeforeEach void clean() { jdbc.execute("TRUNCATE dataset CASCADE"); }
    MockMultipartHttpServletRequestBuilder request(String file, String oldText, String newText) throws Exception {
        var req = multipart("/api/v1/datasets");
        req.with(csrf());
        req.param("asOf","2026-09-09T18:00:00+09:00");
        for (var entry : FILES.entrySet()) {
            String text = Files.readString(Path.of("../samples/recall-001/"+entry.getValue()+".csv"));
            if (entry.getKey().equals(file)) text = text.replace(oldText,newText);
            req.file(new MockMultipartFile(entry.getKey(),entry.getValue()+".csv","text/csv",text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return req;
    }
    void empty() { assertThat(jdbc.queryForObject("SELECT count(*) FROM dataset",Long.class)).isZero(); }
    @Test void storesSampleAndPreservesUnknowns() throws Exception {
        mvc.perform(request("","","")).andExpect(status().isCreated()).andExpect(jsonPath("$.counts.products").value(3))
            .andExpect(jsonPath("$.counts.receipts").value(7)).andExpect(jsonPath("$.counts.shipments").value(4))
            .andExpect(jsonPath("$.unlinkedShipmentQuantity").value(10));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory",Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory",Long.class)).isEqualTo(250);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM shipment_allocation",Long.class)).isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT lot_number FROM receipt WHERE id='R4'",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipment_allocation a JOIN shipment s ON a.dataset_id=s.dataset_id AND a.shipment_id=s.id JOIN receipt r ON a.dataset_id=r.dataset_id AND a.receipt_id=r.id WHERE s.product_id=r.product_id",Long.class)).isEqualTo(4);
        mvc.perform(request("","","")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dataset",Long.class)).isEqualTo(2);
    }
    @Test void rejectsMissingReferenceWithoutWrites() throws Exception {
        mvc.perform(request("inventory","I1,R1","I1,R99")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].file").value("inventory.csv")).andExpect(jsonPath("$.errors[0].row").value(2)); empty();
    }
    @Test void rejectsDuplicateId() throws Exception {
        mvc.perform(request("products","P2,","P1,")).andExpect(status().isBadRequest()); empty();
    }
    @Test void rejectsInvalidDate() throws Exception {
        mvc.perform(request("receipts","2026-10-31","2026-02-30")).andExpect(status().isBadRequest()); empty();
    }
    @Test void rejectsNegativeQuantity() throws Exception {
        mvc.perform(request("inventory",",60,",",-1,")).andExpect(status().isBadRequest()); empty();
    }
    @Test void rejectsWrongProductAllocation() throws Exception {
        mvc.perform(request("shipmentAllocations","A1,S1,R1,40","A1,S1,R6,1")).andExpect(status().isBadRequest()); empty();
    }
    @Test void rejectsOverallocatedShipment() throws Exception {
        mvc.perform(request("shipmentAllocations","A2,S2,R2,20","A2,S2,R2,31")).andExpect(status().isBadRequest()); empty();
    }
    @Test void rejectsStockAboveReceiptQuantity() throws Exception {
        mvc.perform(request("inventory",",60,",",101,")).andExpect(status().isBadRequest()); empty();
    }
    @Test void rollsBackEarlierInsertsOnDatabaseFailure() throws Exception {
        jdbc.execute("ALTER TABLE shipment_allocation ADD CONSTRAINT reject_test CHECK (quantity < 40)");
        try {
            Assertions.assertThrows(Exception.class, () -> mvc.perform(request("","","")));
            empty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product",Long.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE shipment_allocation DROP CONSTRAINT reject_test"); }
    }
}
