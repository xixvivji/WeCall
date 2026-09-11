package com.wecall.recall;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="wecall.ai.worker-enabled",havingValue="true",matchIfMissing=true)
public class ExtractionWorker {
    private final ExtractionService jobs;private final ExtractionClient client;
    public ExtractionWorker(ExtractionService jobs,ExtractionClient client){this.jobs=jobs;this.client=client;}
    @Scheduled(fixedDelayString="${wecall.ai.poll-ms:1000}")
    public void poll() {
        jobs.recoverInterrupted();
        jobs.claim().ifPresent(work->{
            long start=System.nanoTime();
            ExtractionClient.Response result=null;String error=null,raw=null;
            try {result=client.extract(work.id(),work.source(),work.sha());}
            catch(ExtractionClient.Failed e){error=e.code;raw=e.raw;}
            catch(RuntimeException e){error="AI_CLIENT_ERROR";}
            jobs.finish(work,result,error,raw,(System.nanoTime()-start)/1_000_000);
        });
    }
}
