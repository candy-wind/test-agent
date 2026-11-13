package vip.mate.agent.config;

/**
 * 图片上传内容校验：只看文件头的魔数，不信任客户端提交的 Content-Type 和扩展名。
 * 这样即便把 .html/.svg/.jsp 伪装成 image/png 也会被拒绝。
 */
public final class ImageUploadGuard {

    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    public record ImageType(String extension, String contentType) {
    }

    private static final ImageType PNG = new ImageType(".png", "image/png");
    private static final ImageType JPEG = new ImageType(".jpg", "image/jpeg");
    private static final ImageType GIF = new ImageType(".gif", "image/gif");
    private static final ImageType WEBP = new ImageType(".webp", "image/webp");
    private static final ImageType BMP = new ImageType(".bmp", "image/bmp");

    private ImageUploadGuard() {
    }

    public static ImageType requireValid(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (data.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片不能超过 10MB");
        }
        ImageType type = detect(data);
        if (type == null) {
            throw new IllegalArgumentException("文件内容不是受支持的图片格式（仅支持 PNG/JPEG/GIF/WEBP/BMP）");
        }
        return type;
    }

    public static ImageType detect(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if (match(data, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (match(data, 0, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (match(data, 0, 'G', 'I', 'F', '8') && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            return GIF;
        }
        if (match(data, 0, 'R', 'I', 'F', 'F') && match(data, 8, 'W', 'E', 'B', 'P')) {
            return WEBP;
        }
        if (match(data, 0, 'B', 'M')) {
            return BMP;
        }
        return null;
    }

    private static boolean match(byte[] data, int offset, int... expected) {
        if (offset + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
