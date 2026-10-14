package dev.gsr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.gsr.core.GsrConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads / writes config/gsr.json (Gson, provided by Minecraft).
 *
 * Missing fields: default value from {@link GsrConfig}. The file is rewritten after reading so that
 * new fields and values corrected by {@link GsrConfig#validate()} appear in it.
 * An unreadable file is never overwritten: it is reported and the default values are kept.
 * {@code transient} fields (debug) are neither read nor written.
 */
public final class GsrConfigFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private GsrConfigFile() {}

    public static GsrConfig load(Path file) {
        GsrConfig cfg = read(file);
        if (cfg == null) return safeValidate(new GsrConfig());
        write(file, cfg);
        GsrClient.LOG.info("GSR: configuration read from {}", file);
        return cfg;
    }

    /**
     * Saves the settings changed in game (options screen, keys). Only the fields that can change during
     * a session are taken from {@code live}: structural fields edited in the file since startup
     * (levels, pages...) are kept for the next startup. An unreadable file is not overwritten.
     */
    public static void saveRuntime(Path file, GsrConfig live) { saveRuntime(file, live, -1); }

    /**
     * Same, and when {@code nextStartupSplatsPerPage > 0} also writes that structural value (preset chosen in the
     * settings screen: the live value cannot change while the SSBO slabs exist, it applies at the next startup).
     */
    public static void saveRuntime(Path file, GsrConfig live, int nextStartupSplatsPerPage) {
        GsrConfig cfg = read(file);
        if (cfg == null) return;
        cfg.copyRuntimeFrom(live);
        if (nextStartupSplatsPerPage > 0) cfg.maxSplatsPerPage = nextStartupSplatsPerPage;
        write(file, cfg);
    }

    /** Writes every field of {@code cfg} (first launch: the file was just created with the defaults). */
    public static void save(Path file, GsrConfig cfg) { write(file, cfg); }

    /** Validated content of the file, defaults if absent, null if unreadable. */
    private static GsrConfig read(Path file) {
        GsrConfig cfg = new GsrConfig();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg = GSON.fromJson(json, GsrConfig.class);
            } catch (Exception e) {
                GsrClient.LOG.error("GSR: {} unreadable ({}), default values used, file kept", file, e.toString());
                return null;
            }
        }
        return safeValidate(cfg);
    }

    private static void write(Path file, GsrConfig cfg) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (IOException e) {
            GsrClient.LOG.warn("GSR: unable to write {}: {}", file, e.toString());
        }
    }

    private static GsrConfig safeValidate(GsrConfig cfg) {
        try {
            return cfg.validate();
        } catch (IllegalArgumentException e) {
            GsrClient.LOG.error("GSR: invalid configuration ({}), default values used", e.getMessage());
            return new GsrConfig().validate();
        }
    }
}
