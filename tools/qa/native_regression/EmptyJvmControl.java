import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/** Scanner-free startup/ordinary-exit diagnostic, not a zero-allocation control. */
public final class EmptyJvmControl {
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    static TreeSet<String> scannerMappings(Path maps, Path selected) throws Exception {
        String candidate = selected.toRealPath().toString();
        var attributes = Files.readAttributes(selected, "unix:dev,ino");
        long device = (Long)attributes.get("dev"), inode = (Long)attributes.get("ino");
        // Linux dev_t encoding, matching /proc maps' hexadecimal major:minor.
        long major = ((device >>> 8) & 0xfffL) | ((device >>> 32) & 0xfffff000L);
        long minor = (device & 0xffL) | ((device >>> 12) & 0xffffff00L);
        TreeSet<String> scanner = new TreeSet<>();
        // Fail closed if the map/identity is unavailable or malformed. Snapshot only.
        var lines = Files.readAllLines(maps);
        if (lines.isEmpty()) throw new java.io.IOException("Empty Linux mapping snapshot");
        for (String line : lines) {
            String[] fields = line.split("\\s+", 6);
            if (fields.length < 5) throw new java.io.IOException("Malformed Linux mapping");
            String[] mappedDevice = fields[3].split(":", -1);
            if (mappedDevice.length != 2) throw new java.io.IOException("Malformed mapping device");
            long mappedMajor = Long.parseUnsignedLong(mappedDevice[0], 16);
            long mappedMinor = Long.parseUnsignedLong(mappedDevice[1], 16);
            long mappedInode = Long.parseUnsignedLong(fields[4]);
            boolean sameIdentity = mappedInode == inode && mappedMajor == major && mappedMinor == minor;
            String path = fields.length == 6 ? fields[5].replaceFirst(" \\(deleted\\)$", "") : "";
            if (sameIdentity || path.equals(candidate) || path.contains("librealityforge")
                    || path.contains("libagent003_jni")) scanner.add(fields.length == 6 ? fields[5] : line);
        }
        return scanner;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("{\"event\":\"jvm_control_start\",\"java_home\":"
            + quote(System.getProperty("java.home")) + ",\"java_version\":"
            + quote(System.getProperty("java.version")) + ",\"java_vendor\":"
            + quote(System.getProperty("java.vendor")) + "}");
        System.out.flush();
        TreeSet<String> scanner = scannerMappings(Path.of("/proc/self/maps"),
                                                  Path.of(System.getenv("RF_NATIVE_LIBRARY")));
        StringBuilder mappings = new StringBuilder("[");
        for (String path : scanner) {
            if (mappings.length() > 1) mappings.append(',');
            mappings.append(quote(path));
        }
        mappings.append(']');
        System.out.println("{\"event\":\"jvm_control_mapping_snapshot\",\"scanner_mappings\":"
            + mappings + ",\"finalization\":\"ordinary JVM shutdown; final leak check unchanged\"}");
        System.out.flush();
        if (!scanner.isEmpty()) throw new IllegalStateException("JVM control mapped scanner candidate");
        // Return normally: no System.exit/halt, shutdown hooks, GC or sanitizer APIs.
    }
}
