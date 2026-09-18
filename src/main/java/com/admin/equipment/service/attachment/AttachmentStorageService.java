package com.admin.equipment.service.attachment;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 附件文件系统层：只负责字节落盘与读取。
 * 最终文件位于 {dir}/files/{storageKey}，上传中的临时分片位于 {dir}/tmp/{uploadId}/。
 * 所有对外路径解析都经过归一化校验，防止路径穿越。
 */
@Service
public class AttachmentStorageService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStorageService.class);

    /** 存储键只能是 32 位小写十六进制（服务端生成的 UUID），任何其他字符一律拒绝 */
    private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern TMP_DIR_NAME = Pattern.compile("[0-9]{1,19}");

    private final AttachmentProperties props;

    private Path rootDir;
    private Path filesDir;
    private Path tmpDir;

    public AttachmentStorageService(AttachmentProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() throws IOException {
        this.rootDir = Paths.get(props.getDir()).toAbsolutePath().normalize();
        this.filesDir = rootDir.resolve("files");
        this.tmpDir = rootDir.resolve("tmp");
        Files.createDirectories(filesDir);
        Files.createDirectories(tmpDir);
        log.info("附件存储目录初始化完成: {}", rootDir);
    }

    public Path getRootDir() { return rootDir; }
    public Path getFilesDir() { return filesDir; }

    /** 解析最终文件路径并校验不越出 files 目录，非法存储键直接拒绝。 */
    public Path resolveFinal(String storageKey) {
        if (storageKey == null || !STORAGE_KEY_PATTERN.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("非法的存储键");
        }
        Path p = filesDir.resolve(storageKey).normalize();
        if (!p.startsWith(filesDir)) {
            throw new IllegalArgumentException("非法的存储路径");
        }
        return p;
    }

    /** 上传会话的临时分片目录（uploadId 为数据库主键，天然安全）。 */
    public Path tempDirOf(Long uploadId) {
        Path p = tmpDir.resolve(String.valueOf(uploadId)).normalize();
        if (!p.startsWith(tmpDir)) {
            throw new IllegalArgumentException("非法的临时目录");
        }
        return p;
    }

    /** 写入一个分片：先写临时文件再原子改名，重复上传同一序号直接覆盖（断点续传）。 */
    public long writeChunk(Long uploadId, int index, InputStream in, long maxBytes) throws IOException {
        Path dir = tempDirOf(uploadId);
        Files.createDirectories(dir);
        Path tmp = dir.resolve(index + ".part.tmp");
        Path dst = dir.resolve(index + ".part");
        long written = 0;
        try (OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                written += r;
                if (written > maxBytes) {
                    throw new IOException("分片大小超出限制 " + maxBytes + " 字节");
                }
                out.write(buf, 0, r);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        moveReplacing(tmp, dst);
        return written;
    }

    /** 已上传的分片序号列表（升序），用于断点续传状态查询。 */
    public List<Integer> listChunkIndexes(Long uploadId) {
        Path dir = tempDirOf(uploadId);
        List<Integer> indexes = new ArrayList<>();
        if (!Files.isDirectory(dir)) return indexes;
        try (Stream<Path> s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".part"))
                    .map(n -> n.substring(0, n.length() - ".part".length()))
                    .filter(n -> n.matches("[0-9]+"))
                    .map(Integer::parseInt)
                    .sorted()
                    .forEach(indexes::add);
        } catch (IOException e) {
            log.warn("列举分片失败 uploadId={}: {}", uploadId, e.getMessage());
        }
        return indexes;
    }

    public record AssembleResult(long totalSize, String sha256Hex, Path assembledFile) {}

    /**
     * 按序号升序拼接全部分片，边拼接边计算 SHA-256。
     * 要求分片序号从 0 开始连续，否则抛出 IOException。
     */
    public AssembleResult assemble(Long uploadId) throws IOException {
        List<Integer> indexes = listChunkIndexes(uploadId);
        if (indexes.isEmpty()) {
            throw new IOException("没有任何已上传的分片");
        }
        for (int i = 0; i < indexes.size(); i++) {
            if (indexes.get(i) != i) {
                throw new IOException("分片不连续，缺少序号 " + i);
            }
        }
        Path dir = tempDirOf(uploadId);
        Path assembled = dir.resolve("assemble.tmp");
        MessageDigest md = newSha256();
        long total = 0;
        byte[] buf = new byte[8192];
        try (OutputStream out = Files.newOutputStream(assembled)) {
            for (int idx : indexes) {
                Path part = dir.resolve(idx + ".part");
                try (InputStream in = Files.newInputStream(part)) {
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        md.update(buf, 0, r);
                        out.write(buf, 0, r);
                        total += r;
                    }
                }
            }
        }
        return new AssembleResult(total, toHex(md.digest()), assembled);
    }

    /** 将拼装完成的文件移入最终目录。 */
    public Path moveToFinal(Path assembled, String storageKey) throws IOException {
        Path dst = resolveFinal(storageKey);
        Files.createDirectories(filesDir);
        moveReplacing(assembled, dst);
        return dst;
    }

    public boolean finalFileExists(String storageKey) {
        try {
            return Files.isRegularFile(resolveFinal(storageKey));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 计算最终文件的 SHA-256，用于绑定前核验与按需完整性校验。 */
    public String sha256OfFinal(String storageKey) throws IOException {
        Path p = resolveFinal(storageKey);
        MessageDigest md = newSha256();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(p)) {
            int r;
            while ((r = in.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
        }
        return toHex(md.digest());
    }

    public long sizeOfFinal(String storageKey) throws IOException {
        return Files.size(resolveFinal(storageKey));
    }

    public void deleteFinalQuietly(String storageKey) {
        try {
            Files.deleteIfExists(resolveFinal(storageKey));
        } catch (Exception e) {
            log.warn("删除附件文件失败 key={}: {}", storageKey, e.getMessage());
        }
    }

    public void deleteTempDirQuietly(Long uploadId) {
        deleteRecursivelyQuietly(tempDirOf(uploadId));
    }

    public void deleteAssembledTmpQuietly(Long uploadId) {
        try {
            Files.deleteIfExists(tempDirOf(uploadId).resolve("assemble.tmp"));
        } catch (Exception e) {
            log.warn("删除拼装临时文件失败 uploadId={}: {}", uploadId, e.getMessage());
        }
    }

    /**
     * 清理临时分片目录：遍历 tmp 下的会话目录，删除满足条件的目录。
     * @return 删除的目录数量
     */
    public int cleanTempDirs(LongPredicate removable) {
        if (!Files.isDirectory(tmpDir)) return 0;
        int removed = 0;
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(tmpDir)) {
            s.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            log.warn("扫描临时目录失败: {}", e.getMessage());
            return 0;
        }
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            if (!TMP_DIR_NAME.matcher(name).matches()) continue;
            long uploadId;
            try {
                uploadId = Long.parseLong(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (removable.test(uploadId)) {
                deleteRecursivelyQuietly(dir);
                removed++;
            }
        }
        return removed;
    }

    private void moveReplacing(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursivelyQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除失败 {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("递归删除目录失败 {}: {}", dir, e.getMessage());
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
