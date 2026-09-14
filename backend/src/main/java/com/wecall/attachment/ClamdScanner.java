package com.wecall.attachment;

import com.wecall.recall.RecallService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.*;

@Component
public class ClamdScanner implements MalwareScanner {
    private final boolean enabled;
    private final InetSocketAddress address;
    private final int timeoutMs;
    private final Semaphore slots=new Semaphore(4);
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon().name("attachment-scan-deadline").factory());
    public ClamdScanner(@Value("${wecall.attachments.scan.mode:disabled}") String mode,
        @Value("${wecall.attachments.scan.host:127.0.0.1}") String host,
        @Value("${wecall.attachments.scan.port:3310}") int port,
        @Value("${wecall.attachments.scan.timeout-ms:10000}") int timeoutMs) {
        if(!mode.equals("disabled")&&!mode.equals("clamd"))throw new IllegalArgumentException("scan.mode must be disabled or clamd");
        if(timeoutMs<100||timeoutMs>60000||port<1||port>65535)throw new IllegalArgumentException("Invalid scanner timeout or port");
        this.enabled=mode.equals("clamd");this.timeoutMs=timeoutMs;
        // Resolve configuration once; never resolve a user supplied address during a scan.
        address=enabled?new InetSocketAddress(host,port):null;
        if(enabled&&address.isUnresolved())throw new IllegalArgumentException("Scanner host cannot be resolved");
        deadlines.setRemoveOnCancelPolicy(true);
    }
    public Receipt scan(byte[] bytes) {
        if(!enabled)return new Receipt("NOT_SCANNED",null,null);
        if(!slots.tryAcquire())throw unavailable();
        try(var socket=new Socket()) {
            var deadline=deadlines.schedule(()->{try{socket.close();}catch(IOException ignored){}},timeoutMs,TimeUnit.MILLISECONDS);
            try {
                socket.connect(address,timeoutMs);socket.setSoTimeout(timeoutMs);
                var out=new DataOutputStream(socket.getOutputStream());
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                for(int offset=0;offset<bytes.length;offset+=8192) {
                    int length=Math.min(8192,bytes.length-offset);out.writeInt(length);out.write(bytes,offset,length);
                }
                out.writeInt(0);out.flush();
                var response=new ByteArrayOutputStream();var in=socket.getInputStream();
                boolean terminated=false;
                for(int i=0;i<4096;i++) {int b=in.read();if(b<0)break;if(b==0){terminated=true;break;}response.write(b);}
                if(!terminated)throw unavailable();
                String result=response.toString(StandardCharsets.UTF_8);
                if(result.equals("stream: OK"))return new Receipt("CLEAN","clamd",OffsetDateTime.now());
                if(result.startsWith("stream: ")&&result.endsWith(" FOUND"))
                    throw new RecallService.Failure(HttpStatus.UNPROCESSABLE_ENTITY,"파일 검사에서 위협이 감지되어 처리를 차단했습니다");
                throw unavailable();
            } finally {deadline.cancel(false);}
        }catch(IOException e){throw unavailable();}
        finally {slots.release();}
    }
    private RecallService.Failure unavailable(){return new RecallService.Failure(HttpStatus.SERVICE_UNAVAILABLE,"파일 검사를 완료할 수 없습니다. 잠시 후 다시 시도하세요");}
    @PreDestroy public void close(){deadlines.shutdownNow();}
}
