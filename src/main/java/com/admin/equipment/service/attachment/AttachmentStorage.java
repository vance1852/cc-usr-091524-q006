package com.admin.equipment.service.attachment;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 本地文件存储：所有路径均由服务端生成，相对路径解析时做归一化越界校验，
 * 杜绝路径穿越。分片先落临时目录，校验通过后原子移动到证据目录。
 */
@Component
public class AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);

    private static final String TEMP_DIR = "temp";
    private static final String EVIDENCE_DIR = "evidence";
    private static final String ASSEMBLED_NAME = "assembled.part";

    private final AttachmentProperties props;
    private Path baseDir;
    private Path tempRoot;
    private Path evidenceRoot;

    public AttachmentStorage(AttachmentProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() throws IOException {
        baseDir = Paths.get(props.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        tempRoot = baseDir.resolve(TEMP_DIR);
        evidenceRoot = baseDir.resolve(EVIDENCE_DIR);
        Files.createDirectories(tempRoot);
        Files.createDirectories(evidenceRoot);
        log.info("附件存储根目录: {}", baseDir);
    }

    public Path getBaseDir() { return baseDir; }
    public Path getEvidenceRoot() { return evidenceRoot; }

    private static String safeKey(String uploadKey) {
        // uploadKey 为服务端生成的十六进制，这里再做一次白名单收敛
        if (uploadKey == null || !uploadKey.matches("[a-f0-9]{8,64}")) {
            throw new IllegalArgumentException("非法的上传标识");
        }
        return uploadKey;
    }

    /** 分片临时目录：temp/{uploadKey}/ */
    public Path createSessionTempDir(String uploadKey) throws IOException {
        Path dir = tempRoot.resolve(safeKey(uploadKey)).normalize();
        if (!dir.startsWith(tempRoot)) {
            throw new IllegalArgumentException("非法的临时路径");
        }
        Files.createDirectories(dir);
        return dir;
    }

    public Path resolveSessionTempDir(String uploadKey) {
        Path dir = tempRoot.resolve(safeKey(uploadKey)).normalize();
        if (!dir.startsWith(tempRoot)) {
            throw new IllegalArgumentException("非法的临时路径");
        }
        return dir;
    }

    private Path chunkFile(Path sessionDir, int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex > 99_999_999) {
            throw new IllegalArgumentException("分片序号越界");
        }
        Path f = sessionDir.resolve("chunk-" + chunkIndex + ".part").normalize();
        if (!f.startsWith(sessionDir)) {
            throw new IllegalArgumentException("非法的分片路径");
        }
        return f;
    }

    /**
     * 写入单个分片：先写同名临时文件再原子移动，避免半成品分片被拼装读到。
     * @return 实际写入字节数
     */
    public long writeChunk(String uploadKey, int chunkIndex, InputStream in) throws IOException {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("分片序号非法");
        }
        Path dir = createSessionTempDir(uploadKey);
        Path target = chunkFile(dir, chunkIndex);
        Path writing = target.resolveSibling(target.getFileName() + ".writing");
        long size;
        try (OutputStream out = Files.newOutputStream(writing,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            size = in.transferTo(out);
        }
        try {
            Files.move(writing, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(writing, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return size;
    }

    public boolean chunkExists(String uploadKey, int chunkIndex) {
        return Files.isRegularFile(chunkFile(resolveSessionTempDir(uploadKey), chunkIndex));
    }

    public long chunkSize(String uploadKey, int chunkIndex) throws IOException {
        return Files.size(chunkFile(resolveSessionTempDir(uploadKey), chunkIndex));
    }

    public void deleteSessionTemp(String uploadKey) throws IOException {
        Path dir = resolveSessionTempDir(uploadKey);
        if (!Files.exists(dir)) return;
        if (!dir.startsWith(tempRoot)) {
            throw new IllegalArgumentException("拒绝删除临时目录之外的路径");
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 按序号顺序拼装分片，流式计算 SHA-256，校验通过后原子移动到证据目录。
     * 任何不匹配都抛出异常，不会产生最终文件。
     *
     * @return 最终文件相对存储根的路径与实际大小、摘要
     */
    public synchronized AssembledFile assemble(String uploadKey, int totalChunks, long expectedSize,
                                               String expectedSha256Hex, String storedFileName)
            throws IOException, ChecksumMismatchException, SizeMismatchException {
        Path sessionDir = createSessionTempDir(uploadKey);
        Path assembled = sessionDir.resolve(ASSEMBLED_NAME).normalize();
        if (!assembled.startsWith(sessionDir)) {
            throw new IllegalArgumentException("非法的拼装路径");
        }

        MessageDigest digest = sha256();
        long actualSize;
        try (OutputStream out = Files.newOutputStream(assembled,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < totalChunks; i++) {
                Path part = chunkFile(sessionDir, i);
                if (!Files.isRegularFile(part)) {
                    Files.deleteIfExists(assembled);
                    throw new IOException("缺少分片：" + i);
                }
                try (InputStream pin = Files.newInputStream(part);
                     DigestInputStream din = new DigestInputStream(pin, digest)) {
                    din.transferTo(out);
                }
            }
            out.flush();
            actualSize = Files.size(assembled);
        }

        if (actualSize != expectedSize) {
            Files.deleteIfExists(assembled);
            throw new SizeMismatchException(expectedSize, actualSize);
        }
        String actualSha = HexFormat.of().formatHex(digest.digest());
        if (!actualSha.equalsIgnoreCase(expectedSha256Hex)) {
            Files.deleteIfExists(assembled);
            throw new ChecksumMismatchException(expectedSha256Hex, actualSha);
        }

        String safeName = safeStoredFileName(storedFileName);
        String monthDir = java.time.LocalDate.now().toString().substring(0, 7).replace("-", "");
        Path finalDir = evidenceRoot.resolve(monthDir).normalize();
        if (!finalDir.startsWith(evidenceRoot)) {
            throw new IllegalArgumentException("非法的证据目录");
        }
        Files.createDirectories(finalDir);
        Path finalFile = uniqueTarget(finalDir.resolve(safeName).normalize());
        if (!finalFile.startsWith(evidenceRoot)) {
            throw new IllegalArgumentException("非法的证据文件路径");
        }
        try {
            Files.move(assembled, finalFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(assembled, finalFile);
        }
        String relative = baseDir.relativize(finalFile).toString().replace('\\', '/');
        return new AssembledFile(relative, actualSize, actualSha);
    }

    private static Path uniqueTarget(Path target) {
        if (!Files.exists(target)) return target;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path candidate = target.resolveSibling(stem + "-" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("无法生成唯一文件名");
    }

    /** 存储文件名只用服务端生成的 key 加收敛后的扩展名，不接受目录分隔。 */
    private static String safeStoredFileName(String proposed) {
        String name = proposed == null ? "" : Paths.get(proposed).getFileName().toString();
        name = name.replaceAll("[^a-zA-Z0-9._-]", "");
        if (name.isBlank() || name.startsWith(".") || name.length() > 96) {
            name = "evidence";
        }
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        if (stem.length() > 64) stem = stem.substring(0, 64);
        if (!ext.matches("(\\.[a-z0-9]{1,8})?")) {
            ext = "";
        }
        return stem + ext;
    }

    /**
     * 相对路径 → 绝对路径，归一化后必须仍位于存储根内，防止路径穿越下载。
     */
    public Path resolveSafely(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("文件路径为空");
        }
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new SecurityException("非法的文件路径：越出存储目录");
        }
        return resolved;
    }

    public boolean fileExists(String relativePath) {
        try {
            return Files.isRegularFile(resolveSafely(relativePath));
        } catch (SecurityException e) {
            return false;
        }
    }

    public long fileSize(String relativePath) throws IOException {
        return Files.size(resolveSafely(relativePath));
    }

    public void deleteFile(String relativePath) throws IOException {
        Path p = resolveSafely(relativePath);
        Files.deleteIfExists(p);
    }

    /** 重新计算最终文件的 SHA-256（完整性巡检用）。 */
    public String sha256OfExistingFile(String relativePath) throws IOException {
        Path p = resolveSafely(relativePath);
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(p);
             DigestInputStream din = new DigestInputStream(in, digest)) {
            din.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 枚举证据目录下全部文件的相对路径（孤儿文件清理用）。 */
    public Map<String, Path> listEvidenceFiles() throws IOException {
        Map<String, Path> result = new TreeMap<>();
        if (!Files.exists(evidenceRoot)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(evidenceRoot)) {
            for (Path monthDir : stream) {
                if (!Files.isDirectory(monthDir)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(monthDir)) {
                    for (Path f : files) {
                        if (Files.isRegularFile(f)) {
                            result.put(baseDir.relativize(f).toString().replace('\\', '/'), f);
                        }
                    }
                }
            }
        }
        return result;
    }

    public List<Path> listSessionDirs() throws IOException {
        if (!Files.exists(tempRoot)) return List.of();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempRoot)) {
            var list = new java.util.ArrayList<Path>();
            stream.forEach(p -> { if (Files.isDirectory(p)) list.add(p); });
            return list;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public record AssembledFile(String relativePath, long size, String sha256) {}

    public static class ChecksumMismatchException extends Exception {
        public ChecksumMismatchException(String expected, String actual) {
            super("校验和不匹配，expected=" + expected + ", actual=" + actual);
        }
    }

    public static class SizeMismatchException extends Exception {
        public SizeMismatchException(long expected, long actual) {
            super("文件大小不匹配，expected=" + expected + ", actual=" + actual);
        }
    }
}
