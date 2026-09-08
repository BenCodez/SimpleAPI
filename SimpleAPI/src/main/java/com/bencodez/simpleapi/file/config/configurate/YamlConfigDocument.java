package com.bencodez.simpleapi.file.config.configurate;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import com.bencodez.simpleapi.file.config.ConfigDocument;
import com.bencodez.simpleapi.file.config.ConfigEditor;
import com.bencodez.simpleapi.file.config.ConfigSnapshot;

/**
 * YAML document for administrator-owned local configuration. No file is created
 * by open/reload. Updates use private copies, content revisions, a bounded staged
 * write and an atomic replacement; there is no truncate-in-place fallback.
 *
 * <p>The parent directory must exist and be trusted. Target symlinks/non-regular
 * files are rejected. The caller owns exclusive cross-process writes: revision
 * checks detect observed edits but cannot make an atomic filesystem compare/swap.
 * File bytes are forced before rename; power-loss durability of directory entries
 * and preservation of non-POSIX ACLs are not promised.</p>
 *
 * <p>Parsing uses Configurate's YAML loader and its parser policies. This is not
 * a hostile YAML upload endpoint. Formatting/inline comments and native Bukkit
 * serialized objects are not a lossless migration contract. Existing Bukkit and
 * Velocity file implementations are not changed by this class.</p>
 */
public final class YamlConfigDocument implements ConfigDocument {
    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;
    private static final int MAX_NODES = 100_000;
    private static final int MAX_DEPTH = 64;
    private final Path path;
    private final int maxBytes;
    private ConfigurationNode root;
    private String revision;
    private boolean editing;

    private YamlConfigDocument(Path path, int maxBytes) {
        this.path = path;
        this.maxBytes = maxBytes;
    }

    public static YamlConfigDocument open(Path path) throws IOException { return open(path, DEFAULT_MAX_BYTES); }

    public static YamlConfigDocument open(Path path, int maxBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        if (maxBytes < 1 || maxBytes > 16 * DEFAULT_MAX_BYTES)
            throw new IllegalArgumentException("maxBytes must be between 1 byte and 16 MiB");
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getFileName() == null) throw new IOException("A configuration filename is required");
        // Resolve the trusted parent once. Never create parent directories as a side effect of reading.
        Path canonical = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        YamlConfigDocument document = new YamlConfigDocument(canonical, maxBytes);
        document.reload();
        return document;
    }

    @Override public Path path() { return path; }

    @Override public synchronized ConfigSnapshot snapshot() {
        return new ConfigSnapshot(revision, new ConfigurateConfigView(root.copy()));
    }

    @Override public synchronized ConfigSnapshot reload() throws IOException {
        if (editing) throw new IllegalStateException("Cannot reload inside an edit callback");
        byte[] bytes = readCurrent();
        ConfigurationNode candidate = parse(bytes == null ? new byte[0] : bytes);
        ConfigSnapshot result = new ConfigSnapshot(revisionOf(bytes), new ConfigurateConfigView(candidate.copy()));
        root = candidate;
        revision = result.revision();
        return result;
    }

    @Override public synchronized ConfigSnapshot update(String expectedRevision, Consumer<ConfigEditor> edit) throws IOException {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(edit, "edit");
        if (editing) throw new IllegalStateException("Cannot nest document updates");
        if (!revision.equals(expectedRevision)) throw new IOException("Stale in-memory configuration revision");
        requireUnchanged();
        editing = true;
        Editor editor = new Editor(root.copy());
        try {
            try { edit.accept(editor); } finally { editor.active = false; }
            validate(editor.node, 0, new int[1]);
            ConfigurationNode candidate = editor.node.copy();
            byte[] encoded = serialize(candidate);
            String nextRevision = revisionOf(encoded);
            ConfigSnapshot result = new ConfigSnapshot(nextRevision, new ConfigurateConfigView(candidate.copy()));
            replace(encoded);
            root = candidate;
            revision = nextRevision;
            return result;
        } finally {
            editing = false;
        }
    }

    private void requireUnchanged() throws IOException {
        if (!revision.equals(revisionOf(readCurrent())))
            throw new IOException("Configuration changed on disk; reload before applying an edit");
    }

    private byte[] readCurrent() throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return null; }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink())
            throw new IOException("Configuration target must be a regular file, not a symbolic link");
        if (attributes.size() > maxBytes) throw new IOException("Configuration exceeds byte limit");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                int count = buffer.remaining();
                if (count > maxBytes - bytes.size()) throw new IOException("Configuration exceeds byte limit");
                bytes.write(buffer.array(), 0, count);
                buffer.clear();
            }
            return bytes.toByteArray();
        }
    }

    private ConfigurationNode parse(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        ConfigurationNode candidate = YamlConfigurationLoader.builder().buildAndLoadString(text);
        if (candidate.isNull()) candidate.raw(new LinkedHashMap<String, Object>());
        if (!candidate.isMap()) throw new IOException("Configuration root must be a mapping");
        try { validate(candidate, 0, new int[1]); }
        catch (IllegalArgumentException invalid) { throw new IOException("Unsupported configuration structure", invalid); }
        return candidate;
    }

    private byte[] serialize(ConfigurationNode candidate) throws IOException {
        LimitedOutput output = new LimitedOutput(maxBytes);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK).sink(() -> writer).build().save(candidate);
        }
        return output.bytes.toByteArray();
    }

    private void replace(byte[] encoded) throws IOException {
        requireUnchanged();
        Set<PosixFilePermission> permissions = null;
        if (Files.getFileAttributeView(path.getParent(), java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            permissions = revision.equals("missing")
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                    : Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        }
        Path temporary = Files.createTempFile(path.getParent(), ".simpleapi-config-", ".tmp");
        try {
            if (permissions != null) Files.setPosixFilePermissions(temporary, permissions);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            requireUnchanged();
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String revisionOf(byte[] bytes) {
        if (bytes == null) return "missing";
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private static void validate(ConfigurationNode node, int depth, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) throw new IllegalArgumentException("Configuration structure exceeds limits");
        if (node.isMap()) {
            Set<String> keys = new LinkedHashSet<>();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String || key instanceof Number || key instanceof Boolean || key instanceof Character)
                        || !keys.add(key.toString())) throw new IllegalArgumentException("Unsupported or ambiguous configuration key");
                validate(entry.getValue(), depth + 1, count);
            }
        } else if (node.isList()) {
            for (ConfigurationNode child : node.childrenList()) validate(child, depth + 1, count);
        }
    }

    private static Object copyValue(Object value, int depth, int[] count, IdentityHashMap<Object, Boolean> ancestors) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) throw new IllegalArgumentException("Configuration value exceeds structure limits");
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) return value;
        if (value instanceof Character character) return character.toString();
        if (ancestors.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic configuration values are not supported");
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Edited map keys must be strings");
                    copy.put(key, copyValue(entry.getValue(), depth + 1, count, ancestors));
                }
                return copy;
            }
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>();
                for (Object child : list) copy.add(copyValue(child, depth + 1, count, ancestors));
                return copy;
            }
            throw new IllegalArgumentException("Only scalar, list and string-keyed map values are supported");
        } finally { ancestors.remove(value); }
    }

    private static final class Editor extends ConfigurateConfigView implements ConfigEditor {
        private final Thread owner = Thread.currentThread();
        private boolean active = true;
        private Editor(ConfigurationNode node) { super(node); }
        @Override public void set(String path, Object value) { setAt(value, segments(path)); }
        @Override public void setAt(Object value, String... keys) {
            if (!active || Thread.currentThread() != owner) throw new IllegalStateException("Editor is no longer active on this thread");
            Objects.requireNonNull(keys, "keys");
            if (keys.length == 0 || keys.length > MAX_DEPTH) throw new IllegalArgumentException("A nonempty bounded key path is required");
            for (String key : keys) if (key == null || key.isEmpty()) throw new IllegalArgumentException("Empty/null key segment");
            Object copy = copyValue(value, 0, new int[1], new IdentityHashMap<>());
            resolve(keys).raw(copy);
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private LimitedOutput(int limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (bytes.size() >= limit) throw new IOException("Serialized configuration exceeds byte limit");
            bytes.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (length > limit - bytes.size()) throw new IOException("Serialized configuration exceeds byte limit");
            bytes.write(value, offset, length);
        }
    }
}
