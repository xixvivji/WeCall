package com.wecall.attachment;

import com.wecall.auth.CurrentActor;
import com.wecall.recall.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;
import javax.imageio.ImageIO;
import java.io.*;
import java.security.*;
import java.util.*;

@Service
public class AttachmentService {
    private final JdbcTemplate jdbc;private final AttachmentStorage storage;
    public AttachmentService(JdbcTemplate jdbc,AttachmentStorage storage){this.jdbc=jdbc;this.storage=storage;}
    public record ValidFile(String filename,String mediaType,byte[] bytes,String sha256) {}
    private RecallService.Failure fail(HttpStatus status,String message){return new RecallService.Failure(status,message);}
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public List<ValidFile> validate(List<MultipartFile> files) {
        if(files==null||files.isEmpty()||files.size()>5)throw fail(HttpStatus.BAD_REQUEST,"첨부는 1~5개여야 합니다");
        List<ValidFile> result=new ArrayList<>();
        for(var file:files) {
            String name=file.getOriginalFilename();
            if(name==null||name.isBlank()||name.length()>200||name.contains("/")||name.contains("\\")||name.chars().anyMatch(c->Character.isISOControl(c)))throw fail(HttpStatus.BAD_REQUEST,"파일명은 경로 없이 200자 이내로 지정하세요");
            if(file.getSize()>10485760)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"파일당 10 MiB 제한입니다");
            try {
                byte[] bytes;try(var input=file.getInputStream()){bytes=input.readNBytes(10485761);}
                if(bytes.length==0)throw fail(HttpStatus.BAD_REQUEST,"빈 파일은 첨부할 수 없습니다");
                if(bytes.length>10485760)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"파일당 10 MiB 제한입니다");
                String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT),media;
                if(ext.equals("pdf")) {
                    if(!new String(bytes,0,Math.min(5,bytes.length),java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new IOException("Invalid PDF header");
                    try(var doc=Loader.loadPDF(bytes)){if(doc.isEncrypted()||doc.getNumberOfPages()<1||doc.getNumberOfPages()>500)throw new IOException("Unsupported PDF");}
                    media="application/pdf";
                } else if(Set.of("png","jpg","jpeg").contains(ext)) {
                    try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                        var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException("Invalid image");
                        var reader=readers.next();try {
                            reader.setInput(stream);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                            if(!(ext.equals("png")?format.equals("png"):format.equals("jpeg")))throw new IOException("Mismatched image");
                            int w=reader.getWidth(0),h=reader.getHeight(0);
                            if(w<1||h<1||(long)w*h>20000000)throw new IOException("Image dimensions");
                            if(reader.read(0)==null)throw new IOException("Invalid image data");
                        }finally{reader.dispose();}
                    }
                    media=ext.equals("png")?"image/png":"image/jpeg";
                } else throw fail(HttpStatus.BAD_REQUEST,"PDF·PNG·JPEG만 첨부할 수 있습니다");
                result.add(new ValidFile(name,media,bytes,hash(bytes)));
            }catch(IOException|IllegalArgumentException e){throw fail(HttpStatus.BAD_REQUEST,"파일 형식·내용을 확인하세요. 암호화 PDF, 500쪽 초과 PDF, 2천만 픽셀 초과 이미지는 지원하지 않습니다");}
        }
        return result;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void save(UUID caseId,UUID evidenceId,UUID taskId,UUID proofId,List<ValidFile> files) {
        for(var file:files) {
            UUID id=UUID.randomUUID();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{storage.delete(id);}catch(IOException e){org.slf4j.LoggerFactory.getLogger(AttachmentService.class).error("Attachment rollback cleanup failed for {}",id);}}});
            try{storage.write(id,file.bytes());}catch(IOException e){throw fail(HttpStatus.SERVICE_UNAVAILABLE,"파일 저장에 실패했습니다. 다시 시도하세요");}
            jdbc.update("INSERT INTO evidence_attachment(id,case_id,evidence_id,task_id,proof_id,filename,media_type,byte_size,sha256,uploaded_by) VALUES (?,?,?,?,?,?,?,?,?,?)",id,caseId,evidenceId,taskId,proofId,file.filename(),file.mediaType(),file.bytes().length,file.sha256(),CurrentActor.username());
            event(id,"UPLOADED");
        }
    }
    private void event(UUID id,String type){jdbc.update("INSERT INTO attachment_event(id,attachment_id,event_type,actor) VALUES (?,?,?,?)",UUID.randomUUID(),id,type,CurrentActor.username());}
    public List<Map<String,Object>> list(UUID caseId,UUID evidenceId,UUID proofId) {
        if((evidenceId==null)==(proofId==null))throw fail(HttpStatus.BAD_REQUEST,"입고 증거 또는 작업 증빙 ID 하나를 지정하세요");
        return jdbc.queryForList("SELECT id,filename,media_type AS \"mediaType\",byte_size AS \"byteSize\",sha256,uploaded_by AS \"uploadedBy\",created_at AS \"createdAt\" FROM evidence_attachment WHERE case_id=? AND "+(evidenceId!=null?"evidence_id":"proof_id")+"=? ORDER BY created_at,id",caseId,evidenceId!=null?evidenceId:proofId);
    }
    public record Download(String filename,String mediaType,byte[] bytes) {}
    @Transactional
    public Download download(UUID caseId,UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM evidence_attachment WHERE case_id=? AND id=?",caseId,id);
        if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"첨부 파일이 없습니다");
        var row=rows.getFirst();byte[] bytes;
        try{bytes=storage.read(id);}catch(IOException e){throw fail(HttpStatus.SERVICE_UNAVAILABLE,"첨부 파일을 읽을 수 없습니다. 관리자에게 확인하세요");}
        if(bytes.length!=((Number)row.get("byte_size")).longValue()||!hash(bytes).equals(row.get("sha256")))throw fail(HttpStatus.SERVICE_UNAVAILABLE,"첨부 파일 무결성 확인에 실패했습니다");
        event(id,"DOWNLOAD_REQUESTED");
        return new Download((String)row.get("filename"),(String)row.get("media_type"),bytes);
    }
}
