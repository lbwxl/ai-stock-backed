package com.ruoyi.webui.file;

import java.io.IOException;
import java.util.Locale;
import org.springframework.web.multipart.MultipartFile;

/** Original bytes + declared metadata, without content parsing. */
public final class FileAnalysisUpload {
    public final String fileName;
    public final String mimeType;
    private final byte[] bytes;

    public FileAnalysisUpload(String fileName, String mimeType, byte[] bytes) {
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.bytes = bytes.clone();
    }
    public byte[] getBytes() { return bytes.clone(); }
    public long getSize() { return bytes.length; }

    public static FileAnalysisUpload read(MultipartFile file, long maxSize) {
        if (file == null || file.isEmpty()) { throw new FileAnalysisException(400, "INVALID_REQUEST", "请选择非空文件"); }
        long limit = Math.max(1, maxSize);
        if (file.getSize() > limit) { throw new FileAnalysisException(413, "FILE_TOO_LARGE", "文件超过服务器允许的大小"); }
        String name = file.getOriginalFilename();
        if (name == null) { throw new FileAnalysisException(400, "INVALID_REQUEST", "文件名不能为空"); }
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.length() > 200 || name.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new FileAnalysisException(400, "INVALID_REQUEST", "文件名不合法");
        }
        String mime = file.getContentType();
        if (mime == null || !mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) { mime = "application/octet-stream"; }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > limit) { throw new FileAnalysisException(413, "FILE_TOO_LARGE", "文件超过服务器允许的大小"); }
            return new FileAnalysisUpload(name, mime.toLowerCase(Locale.ROOT), bytes);
        } catch (IOException e) { throw new FileAnalysisException(400, "INVALID_FILE", "文件读取失败，请重新上传"); }
    }
}
