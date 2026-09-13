package com.wecall.attachment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.UUID;

@Component
public class LocalAttachmentStorage implements AttachmentStorage {
    private final Path root;
    public LocalAttachmentStorage(@Value("${wecall.attachments.directory:${user.home}/.wecall/attachments}") String directory) {root=Path.of(directory).toAbsolutePath().normalize();}
    private Path path(UUID id,String suffix) throws IOException {
        Files.createDirectories(root,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symbolic storage path");
        return root.resolve(id+suffix);
    }
    public void write(UUID id,byte[] bytes) throws IOException {
        Path temp=path(id,".part"),target=path(id,".blob");
        try {
            try(var channel=FileChannel.open(temp,java.util.Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Duplicate attachment");
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
        } finally {Files.deleteIfExists(temp);}
    }
    public byte[] read(UUID id) throws IOException {
        Path file=path(id,".blob");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(10485761);
            if(bytes.length>10485760)throw new IOException("Oversized stored file");
            return bytes;
        }
    }
    public void delete(UUID id) throws IOException {Files.deleteIfExists(path(id,".blob"));Files.deleteIfExists(path(id,".part"));}
}
