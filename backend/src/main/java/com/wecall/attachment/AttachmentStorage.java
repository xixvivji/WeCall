package com.wecall.attachment;
import java.io.IOException;
import java.util.UUID;
public interface AttachmentStorage {
    void write(UUID id,byte[] bytes) throws IOException;
    byte[] read(UUID id) throws IOException;
    void delete(UUID id) throws IOException;
}
