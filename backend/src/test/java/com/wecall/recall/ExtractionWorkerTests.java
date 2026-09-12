package com.wecall.recall;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExtractionWorkerTests {
    @Test void workerAcceptsReplacementWithoutHttpOrModelDependencies() {
        var jobs=mock(ExtractionService.class);
        var work=new ExtractionService.Work(UUID.randomUUID(),UUID.randomUUID(),"합성 원문","snapshot-hash");
        when(jobs.claim()).thenReturn(Optional.of(work));
        var result=new ExtractionModels.Result(work.id(),work.sha(),"v1","MOCK","test","test-v1","test-v1",
            new RecallModels.Rule("EQ","LOT_NUMBER",List.of("A01"),null),"합성 원문",List.of());
        var response=new ExtractionClient.Response(result,"synthetic-test-response");
        ExtractionClient replacement=(id,source,sha)->{
            assertThat(id).isEqualTo(work.id());assertThat(source).isEqualTo(work.source());assertThat(sha).isEqualTo(work.sha());
            return response;
        };
        new ExtractionWorker(jobs,replacement).poll();
        verify(jobs).recoverInterrupted();
        verify(jobs).finish(eq(work),same(response),isNull(),isNull(),anyLong());
    }
    @Test void replacementFailureUsesSamePersistedFailureContract() {
        var jobs=mock(ExtractionService.class);
        var work=new ExtractionService.Work(UUID.randomUUID(),UUID.randomUUID(),"합성 원문","hash");
        when(jobs.claim()).thenReturn(Optional.of(work));
        ExtractionClient replacement=(id,source,sha)->{throw new ExtractionClient.Failed("AI_UNAVAILABLE",null);};
        new ExtractionWorker(jobs,replacement).poll();
        verify(jobs).finish(eq(work),isNull(),eq("AI_UNAVAILABLE"),isNull(),anyLong());
    }
}
