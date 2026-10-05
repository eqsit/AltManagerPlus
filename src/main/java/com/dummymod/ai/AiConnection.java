package com.dummymod.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;

/** Credentials are kept outside the ordinary config and outside the mod JAR. */
public final class AiConnection {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AiConnection instance;
    public String baseUrl = "https://omni.afternet.my/v1";
    public String apiKey = "";
    public String model = "";
    public static synchronized AiConnection get() {
        if (instance == null) {
            try { instance = GSON.fromJson(Files.readString(path()), AiConnection.class); }
            catch (Exception ignored) { }
            if (instance == null) instance = new AiConnection();
        }
        return instance;
    }
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("dummymod-ai.json"); }
    public boolean ready() { return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank(); }
    public synchronized void save() throws Exception {
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("Нужен URL API http(s)://host/v1");
        Files.createDirectories(path().getParent());
        Path temp = Files.createTempFile(path().getParent(), "dummymod-ai", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) {}
            Files.writeString(temp, GSON.toJson(this));
            Files.move(temp, path(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
