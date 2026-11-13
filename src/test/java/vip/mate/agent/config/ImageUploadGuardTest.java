package vip.mate.agent.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageUploadGuardTest {

    @Test
    void detectsRealImageFormats() {
        assertThat(ImageUploadGuard.detect(png()).contentType()).isEqualTo("image/png");
        assertThat(ImageUploadGuard.detect(jpeg()).extension()).isEqualTo(".jpg");
        assertThat(ImageUploadGuard.detect(gif()).contentType()).isEqualTo("image/gif");
    }

    @Test
    void rejectsDisguisedHtml() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ImageUploadGuard.requireValid(html))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是受支持的图片格式");
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> ImageUploadGuard.requireValid(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageUploadGuard.requireValid(new byte[(int) ImageUploadGuard.MAX_IMAGE_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void extensionComesFromMagicNumberNotFilename() {
        ImageUploadGuard.ImageType type = ImageUploadGuard.requireValid(png());
        assertThat(type.extension()).isEqualTo(".png");
        assertThat(type.contentType()).isEqualTo("image/png");
    }

    private byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3, 4};
    }

    private byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 1, 2, 3, 4, 5};
    }

    private byte[] gif() {
        return new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 1, 2, 3, 4};
    }
}
