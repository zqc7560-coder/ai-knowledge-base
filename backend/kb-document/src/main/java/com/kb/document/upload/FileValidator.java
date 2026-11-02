package com.kb.document.upload;

import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** 扩展名白名单 + Magic Number 双校验（docs/02 2.1）。 */
public final class FileValidator {
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "docx", "txt", "md", "markdown");
    private static final Map<String, byte[][]> MAGIC = Map.of(
            "pdf", new byte[][]{ new byte[]{'%','P','D','F','-'} },
            // docx 是 zip：PK\x03\x04
            "docx", new byte[][]{ new byte[]{'P','K',3,4} },
            // 文本类没有固定魔数，仅防二进制：首 512 字节里 0x00 视为可疑
            "txt", new byte[][]{ new byte[]{} },
            "md", new byte[][]{ new byte[]{} },
            "markdown", new byte[][]{ new byte[]{} });

    private FileValidator() {}

    public static String extOf(String filename) {
        int i = filename.lastIndexOf('.');
        if (i < 0 || i == filename.length() - 1)
            throw BizException.of(BizCode.FILE_TYPE_NOT_ALLOWED, "文件缺少扩展名");
        return filename.substring(i + 1).toLowerCase();
    }

    /** @return 规范化扩展名 */
    public static String validate(String filename, long size, Path stored) {
        String ext = extOf(filename);
        if (!ALLOWED_EXT.contains(ext))
            throw BizException.of(BizCode.FILE_TYPE_NOT_ALLOWED, "仅支持 PDF/DOCX/TXT/MD");
        if (size > 20L * 1024 * 1024)
            throw BizException.of(BizCode.FILE_TOO_LARGE, "文件超过 20MB");
        if (size == 0) throw BizException.of(BizCode.FILE_TYPE_NOT_ALLOWED, "空文件");
        byte[] head = readHead(stored, 8);
        byte[][] sigs = MAGIC.get(ext);
        outer:
        for (byte[] sig : sigs) {
            if (sig.length == 0) {
                // 文本类：前 512 字节出现 0x00 则拒绝（伪装扩展名的二进制）
                byte[] deep = readHead(stored, 512);
                for (byte b : deep) if (b == 0)
                    throw BizException.of(BizCode.FILE_TYPE_NOT_ALLOWED, "内容疑似二进制，与扩展名不符");
                break outer;
            }
            if (startsWith(head, sig)) break outer;
            throw BizException.of(BizCode.FILE_TYPE_NOT_ALLOWED, "文件内容与扩展名不符");
        }
        return ext;
    }

    private static byte[] readHead(Path p, int n) {
        try (InputStream in = java.nio.file.Files.newInputStream(p)) {
            byte[] b = new byte[n];
            int r = in.read(b);
            return r < n && r > 0 ? java.util.Arrays.copyOf(b, r) : b;
        } catch (Exception e) {
            throw BizException.of(BizCode.FILE_TYPE_NOT_ALLOWED, "文件不可读");
        }
    }

    private static boolean startsWith(byte[] data, byte[] sig) {
        if (data.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if (data[i] != sig[i]) return false;
        return true;
    }
}
