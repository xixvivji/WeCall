package com.wecall.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.wecall.recall.ExtractionModels.*;

@Component
public class FastApiExtractionClient implements ExtractionClient {
    private final RestClient client;
    private final ObjectMapper json;
    private final String base,token;
    private final boolean allowMock;
    public FastApiExtractionClient(ObjectMapper json,@Value("${wecall.ai.base-url:http://127.0.0.1:8000}") String base,
        @Value("${wecall.ai.service-token:}") String token,@Value("${wecall.ai.allow-mock:false}") boolean allowMock,
        @Value("${wecall.ai.connect-timeout-ms:2000}") int connectTimeout,@Value("${wecall.ai.read-timeout-ms:10000}") int readTimeout) {
        this.json=json;this.base=base;this.token=token;this.allowMock=allowMock;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(connectTimeout);factory.setReadTimeout(readTimeout);
        client=RestClient.builder().requestFactory(factory).build();
    }
    @Override
    public Response extract(UUID id,String source,String sha) {
        if(token.isBlank()) throw new Failed("SERVICE_NOT_CONFIGURED",null);
        try {
            return client.post().uri(base+"/v1/extractions").header("X-Service-Token",token)
                .body(Map.of("requestId",id,"sourceText",source)).exchange((request,response)->{
                    byte[] bytes=response.getBody().readNBytes(262145);
                    if(bytes.length>262144) throw new Failed("AI_RESPONSE_TOO_LARGE",null);
                    String raw=new String(bytes,StandardCharsets.UTF_8);
                    if(!response.getStatusCode().is2xxSuccessful()) throw new Failed(response.getStatusCode().value()==422?"AI_REJECTED_INPUT":"AI_UNAVAILABLE",null);
                    try {
                        Result value=json.readValue(raw,Result.class);
                        if(value==null || !id.equals(value.requestId()) || !sha.equals(value.sourceSha256()) || !"v1".equals(value.schemaVersion())
                            || !Set.of("MOCK","LIVE").contains(Objects.toString(value.mode(),""))) throw new IllegalArgumentException();
                        for(String field:Arrays.asList(value.provider(),value.model(),value.promptVersion()))
                            if(field==null || field.isBlank() || field.length()>100) throw new IllegalArgumentException();
                        if(value.sourceQuote()==null || value.sourceQuote().isBlank() || value.sourceQuote().length()>10000 || !source.contains(value.sourceQuote())) throw new IllegalArgumentException();
                        if(value.warnings()==null || value.warnings().size()>20 || value.warnings().stream().anyMatch(w->w==null || w.length()>1000)) throw new IllegalArgumentException();
                        RuleEngine.validate(value.rule());
                        if(value.mode().equals("MOCK") && !allowMock) throw new Failed("MOCK_RESPONSE_DISABLED",raw);
                        return new Response(value,raw);
                    } catch(Failed e) {throw e;} catch(Exception e) {throw new Failed("INVALID_AI_RESPONSE",raw);}
                });
        } catch(ResourceAccessException e) {
            Throwable cause=e;
            while(cause!=null) {if(cause instanceof java.net.SocketTimeoutException) throw new Failed("AI_TIMEOUT",null);cause=cause.getCause();}
            throw new Failed("AI_UNAVAILABLE",null);
        }
    }
}
