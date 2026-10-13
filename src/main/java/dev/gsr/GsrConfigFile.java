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
 * Lecture / écriture de config/gsr.json (Gson, fourni par Minecraft).
 *
 * Champs absents : valeur par défaut de {@link GsrConfig}. Le fichier est réécrit après lecture pour
 * y faire apparaître les nouveaux champs et les valeurs corrigées par {@link GsrConfig#validate()}.
 * Un fichier illisible n'est jamais écrasé : on le signale et on garde les valeurs par défaut.
 * Les champs {@code transient} (debug) ne sont ni lus ni écrits.
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
                GsrClient.LOG.error("GSR : {} illisible ({}), valeurs par défaut utilisées, fichier conservé", file, e.toString());
                return safeValidate(new GsrConfig());
            }
        }
        cfg = safeValidate(cfg);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (IOException e) {
            GsrClient.LOG.warn("GSR : impossible d'écrire {} : {}", file, e.toString());
        }
        GsrClient.LOG.info("GSR : configuration lue depuis {}", file);
        return cfg;
    }

    private static GsrConfig safeValidate(GsrConfig cfg) {
        try {
            return cfg.validate();
        } catch (IllegalArgumentException e) {
            GsrClient.LOG.error("GSR : configuration invalide ({}), valeurs par défaut utilisées", e.getMessage());
            return new GsrConfig().validate();
        }
    }
}
