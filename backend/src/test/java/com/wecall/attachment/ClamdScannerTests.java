package com.wecall.attachment;
import com.wecall.recall.RecallService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class ClamdScannerTests {
    @Test void disabledIsExplicitlyUnscanned() {
        var scanner=new ClamdScanner("disabled","invalid.invalid",1,100);
        try {var receipt=scanner.scan(new byte[]{1});assertThat(receipt.status()).isEqualTo("NOT_SCANNED");assertThat(receipt.scannedAt()).isNull();}finally{scanner.close();}
    }
    // A protocol peer only, not a virus detection engine.
    void exchange(byte[] payload,String reply,HttpStatus failure) throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var received=executor.submit(()->{
                try(var socket=server.accept()) {
                    var input=new DataInputStream(socket.getInputStream());
                    assertThat(new String(input.readNBytes(10),StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                    var data=new ByteArrayOutputStream();int length;
                    while((length=input.readInt())!=0){assertThat(length).isBetween(1,8192);data.write(input.readNBytes(length));}
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));return data.toByteArray();
                }
            });
            var scanner=new ClamdScanner("clamd","127.0.0.1",server.getLocalPort(),1000);
            try {
                if(failure==null){var r=scanner.scan(payload);assertThat(r.status()).isEqualTo("CLEAN");assertThat(r.scannedAt()).isNotNull();}
                else assertThatThrownBy(()->scanner.scan(payload)).isInstanceOfSatisfying(RecallService.Failure.class,e->assertThat(e.status).isEqualTo(failure));
                assertThat(received.get(2,TimeUnit.SECONDS)).isEqualTo(payload);
            }finally{scanner.close();}
        }
    }
    @Test void streamsExactBytesInBoundedChunks() throws Exception {exchange(new byte[20000],"stream: OK\0",null);}
    @Test void rejectsDetectedThreat() throws Exception {exchange(new byte[]{1},"stream: Test-Signature FOUND\0",HttpStatus.UNPROCESSABLE_ENTITY);}
    @Test void badAndOversizedResponsesFailClosed() throws Exception {
        for(String reply:new String[]{"stream: limit ERROR\0","garbage\0","stream: OK","x".repeat(5000)})exchange(new byte[]{1},reply,HttpStatus.SERVICE_UNAVAILABLE);
    }
    @Test void refusedConnectionAndDeadlineFailClosed() throws Exception {
        int port;try(var server=new ServerSocket(0)){port=server.getLocalPort();}
        var refused=new ClamdScanner("clamd","127.0.0.1",port,100);
        try{assertThatThrownBy(()->refused.scan(new byte[]{1})).isInstanceOfSatisfying(RecallService.Failure.class,e->assertThat(e.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));}finally{refused.close();}
        try(var server=new ServerSocket(0);var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var peer=executor.submit(()->{try(var socket=server.accept()){socket.getInputStream().readAllBytes();}return null;});
            var scanner=new ClamdScanner("clamd","127.0.0.1",server.getLocalPort(),100);
            long start=System.nanoTime();
            try{assertThatThrownBy(()->scanner.scan(new byte[]{1})).isInstanceOf(RecallService.Failure.class);assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(2000);peer.get(2,TimeUnit.SECONDS);}finally{scanner.close();}
        }
    }
    @Test void invalidModeCannotDisableScanning(){assertThatThrownBy(()->new ClamdScanner("clmad","127.0.0.1",3310,1000)).isInstanceOf(IllegalArgumentException.class);}
}
