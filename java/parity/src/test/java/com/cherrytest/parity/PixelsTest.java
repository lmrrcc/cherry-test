package com.cherrytest.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PixelsTest {
    @TempDir
    Path output;

    @Test
    void identical_png_bytes_pass_without_diagnostic_files() throws IOException {
        byte[] png = png(0xffabcdef);
        assertThat(Pixels.difference(png, png.clone(), output, "same")).isEmpty();
        try (var files = Files.list(output)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void identical_pixels_with_different_bytes_still_pass() throws IOException {
        byte[] png = png(0xffabcdef);
        byte[] padded = Arrays.copyOf(png, png.length + 1);
        assertThat(Pixels.difference(png, padded, output, "encoding")).isEmpty();
    }

    @Test
    void one_channel_difference_is_rejected() throws IOException {
        assertThat(Pixels.difference(png(0xffabcdef), png(0xffabcdee), output, "single-channel"))
                .isEqualTo("1 pixels differ");
        assertThat(output.resolve("single-channel-rust.png")).exists();
        assertThat(output.resolve("single-channel-java.png")).exists();
        assertThat(output.resolve("single-channel-diff.png")).exists();
    }

    private static byte[] png(int color) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
