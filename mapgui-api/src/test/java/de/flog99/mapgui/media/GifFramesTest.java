package de.flog99.mapgui.media;

import de.flog99.mapgui.ui.Palette;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GifFramesTest {

    /**
     * Stands in for the map palette. Never returns nought for an opaque color, which is what lets that
     * index mean "transparent" - the real palette reserves its first four entries the same way.
     */
    private static final Palette PALETTE = new Palette() {
        @Override
        public byte index(Color color) {
            return (byte) (color.getRed() > 127 ? 10 : 20);
        }

        @Override
        public Color color(byte index) {
            return (index & 0xFF) == 10 ? Color.RED : Color.BLUE;
        }
    };

    /**
     * A two-by-one GIF: one red pixel, one fully transparent, written through the indexed color model
     * that is how a GIF carries transparency.
     */
    private static byte[] gifWithATransparentPixel() throws IOException {
        byte[] reds = {(byte) 255, 0};
        byte[] greens = {0, 0};
        byte[] blues = {0, 0};
        // The second entry is the transparent one.
        IndexColorModel colors = new IndexColorModel(2, 2, reds, greens, blues, 1);

        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_INDEXED, colors);
        image.getRaster().setSample(0, 0, 0, 0);
        image.getRaster().setSample(1, 0, 0, 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", out);
        return out.toByteArray();
    }

    /**
     * Two frames - an opaque red pixel, then an entirely transparent one - each with its own disposal.
     *
     * <p>Per frame, deliberately. Giving both the same value hides the bug this is here for: applying
     * disposal a frame too early gives the right answer whenever both frames agree, so a test that only
     * ever sets them together passes either way.
     */
    private static byte[] twoFrames(String first, String second) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);

            for (int i = 0; i < 2; i++) {
                BufferedImage frame = onePixel(i == 0);
                ImageWriteParam params = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(frame), params);
                writer.writeToSequence(new IIOImage(frame, null, withDisposal(metadata, i == 0 ? first : second)), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static BufferedImage onePixel(boolean opaque) {
        byte[] reds = {(byte) 255, 0};
        byte[] zero = {0, 0};
        IndexColorModel colors = new IndexColorModel(2, 2, reds, zero, zero, 1);

        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_INDEXED, colors);
        image.getRaster().setSample(0, 0, 0, opaque ? 0 : 1);
        return image;
    }

    private static IIOMetadata withDisposal(IIOMetadata metadata, String disposal) throws IOException {
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        IIOMetadataNode control = new IIOMetadataNode("GraphicControlExtension");
        control.setAttribute("disposalMethod", disposal);
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "TRUE");
        control.setAttribute("transparentColorIndex", "1");
        control.setAttribute("delayTime", "10");
        root.appendChild(control);

        metadata.setFromTree(format, root);
        return metadata;
    }

    /**
     * The bug this guards: a frame's disposal describes what happens *after* it is shown, so applying it
     * before drawing left one frame of the previous picture showing through the transparent parts.
     *
     * <p>The first frame disposes and the second does not, which is the arrangement the two readings
     * disagree on - the old one asked frame two what to do and frame two said "leave it".
     */
    @Test
    void disposalBelongsToTheFrameThatAsksForIt() throws IOException {
        GifFrames frames = GifFrames.read(new ByteArrayInputStream(twoFrames("restoreToBackgroundColor", "none")), PALETTE);

        assertEquals(2, frames.count());
        assertEquals(10, frames.pixels(0)[0], "the first frame is red");
        assertEquals(Frames.TRANSPARENT, frames.pixels(1)[0],
                "frame one asked to be disposed of, so its red must not survive into frame two"
        );
    }

    /** The opposite rule: without disposal a GIF stores only what moved, so the old pixel must survive. */
    @Test
    void keepingTheCanvasLeavesTheEarlierPixelInPlace() throws IOException {
        GifFrames frames = GifFrames.read(new ByteArrayInputStream(twoFrames("none", "none")), PALETTE);

        assertEquals(10, frames.pixels(0)[0]);
        assertEquals(10, frames.pixels(1)[0], "nothing was disposed, so it still shows");
    }

    /** Undoing a frame has to restore what was under it, not clear to nothing. */
    @Test
    void restoringToPreviousPutsBackWhatWasThere() throws IOException {
        GifFrames frames = GifFrames.read(new ByteArrayInputStream(twoFrames("none", "restoreToPrevious")), PALETTE);

        assertEquals(10, frames.pixels(0)[0]);
        assertEquals(10, frames.pixels(1)[0], "frame two is transparent, so frame one still shows");
    }

    /**
     * The bug this guards: compositing into a surface with no alpha channel turns every see-through pixel
     * black, so a transparent GIF arrived as a picture on a black background.
     */
    @Test
    void aTransparentPixelStaysTransparent() throws IOException {
        GifFrames frames = GifFrames.read(new ByteArrayInputStream(gifWithATransparentPixel()), PALETTE);
        byte[] pixels = frames.pixels(0);

        assertEquals(2, frames.width());
        assertNotEquals(Frames.TRANSPARENT, pixels[0], "the red pixel is opaque");
        assertEquals(10, pixels[0], "and matched to the palette");
        assertEquals(Frames.TRANSPARENT, pixels[1], "the see-through one must not become black");
    }
}
