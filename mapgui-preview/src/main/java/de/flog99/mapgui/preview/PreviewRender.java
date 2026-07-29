package de.flog99.mapgui.preview;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

/** One-shot render to a PNG, for a quick look or for capturing screenshots in CI. */
public final class PreviewRender {

    private PreviewRender() {
    }

    /** args: class name, compiled-output directory, output png, scale, [backdrop png] */
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PreviewRender <class> <classesDir> <out.png> <scale> [backdrop.png]");
            System.exit(2);
        }

        PreviewState state = new PreviewState(
                new ScreenLoader(args[0], Path.of(args[1])),
                Preview.readOrNull(args.length > 4 && !args[4].isBlank() ? Path.of(args[4]) : null),
                Preview.MAP_SIZE, Preview.MAP_SIZE
        );
        state.reload();

        byte[] frame = state.frame();
        if (frame.length == 0) {
            System.err.println("Nothing rendered - see the error above.");
            System.exit(1);
        }

        Path out = Path.of(args[2]);
        Preview.write(Preview.scale(ImageIO.read(new ByteArrayInputStream(frame)), Integer.parseInt(args[3])), out);
        System.out.println("Wrote " + out.toAbsolutePath());
    }
}
