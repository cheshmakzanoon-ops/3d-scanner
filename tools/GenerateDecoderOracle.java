import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import javax.imageio.ImageIO;

/** Independent HOST oracle only. Never provides pixels to AndroidImageDecoder. */
class GenerateDecoderOracle {
  private static final String[] HASHES = {
    "66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c",
    "c275db33a1d10d84fe5a9bf78c55a7ea31de002ac305e00fd521e54b351b391b",
    "a0d386746c2a8f47840058613bc89aa3c4a8095995288fa936d368ad67fe6c0f"};
  static String sha(Path p) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("fixture directory, NEW output directory, reviewed generator source required");
    Path fixtures = Path.of(args[0]); Path out = Path.of(args[1]);
    Files.createDirectory(out); // Refuse overwrite/reuse of prior evidence.
    StringBuilder manifest = new StringBuilder("generatorSha256=" + sha(Path.of(args[2])) + "\njavaRuntime=" + System.getProperty("java.runtime.version") + "\nlayout=RGB8-row-major\n");
    for (int i = 0; i < 3; i++) {
      String name = String.format("view-%02d.jpg", i + 1);
      Path input = fixtures.resolve(name);
      if (!sha(input).equals(HASHES[i])) throw new AssertionError("Input hash mismatch: " + name);
      BufferedImage source = ImageIO.read(input.toFile());
      if (source == null || source.getWidth() != 800 || source.getHeight() != 600) throw new AssertionError("Invalid fixture");
      try {
        for (int[] size : new int[][] {{800,600}, {256,192}, {512,384}}) {
          int w = size[0], h = size[1];
          BufferedImage expected = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
          try {
            var graphics = expected.createGraphics();
            try {
              graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
              graphics.drawImage(source, 0, 0, w, h, null);
            } finally { graphics.dispose(); }
            String refName = name + "." + w + "x" + h + ".rgb";
            Path ref = out.resolve(refName);
            try (var data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(ref, StandardOpenOption.CREATE_NEW)))) {
              data.writeInt(0x52464f31); data.writeUTF(HASHES[i]); data.writeInt(w); data.writeInt(h);
              for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
                int pixel = expected.getRGB(x,y);
                if ((pixel >>> 24) != 255) throw new AssertionError("Nonopaque reference");
                data.writeByte(pixel >>> 16); data.writeByte(pixel >>> 8); data.writeByte(pixel);
              }
            }
            manifest.append(sha(ref)).append("  ").append(refName).append('\n');
          } finally { expected.flush(); }
        }
      } finally { source.flush(); }
      if (!sha(input).equals(HASHES[i])) throw new AssertionError("Input changed");
    }
    Path manifestPath = out.resolve("SHA256SUMS");
    Files.writeString(manifestPath, manifest.toString(), StandardOpenOption.CREATE_NEW);
    System.out.println("oracleManifestSha256=" + sha(manifestPath));
    System.out.println("javaRuntime=" + System.getProperty("java.runtime.version"));
  }
}
