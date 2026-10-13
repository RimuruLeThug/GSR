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
        GsrConfig cfg = new GsrConfig();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg = GSON.fromJson(json, GsrConfig.class);
            } catch (Exception e) {
                GsrClient.LOG.error("GSR: {} unreadable ({}), default values used, file kept", file, e.toString());
                return safeValidate(new GsrConfig());
            }
        }
        cfg = safeValidate(cfg);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (IOException e) {
            GsrClient.LOG.warn("GSR: unable to write {}: {}", file, e.toString());
        }
        GsrClient.LOG.info("GSR: configuration read from {}", file);
        return cfg;
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
