package com.dummymod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DummyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "dummymod.json");
    private static DummyConfig INSTANCE;

    public enum ProxyDistributionMode { ROUND_ROBIN, PROXIES_ONLY, DIRECT_ONLY }

    public static class SocksProxy {
        public String host = "127.0.0.1";
        public int port = 1080;
        public String username = "";
        public String password = "";
        public boolean enabled = true;

        public SocksProxy() {}
        public SocksProxy(String host, int port, String username, String password, boolean enabled) {
            this.host = host;
            this.port = port;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.enabled = enabled;
        }

        public String label() { return host + ":" + port; }
    }

    private String dummyNick = "DummyPlayer";
    private ProxyDistributionMode proxyDistributionMode = ProxyDistributionMode.ROUND_ROBIN;
    private List<SocksProxy> proxies = new ArrayList<>();

    public static DummyConfig getInstance() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                INSTANCE = GSON.fromJson(reader, DummyConfig.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (INSTANCE == null) INSTANCE = new DummyConfig();
        if (INSTANCE.proxies == null) INSTANCE.proxies = new ArrayList<>();
        if (INSTANCE.proxyDistributionMode == null) INSTANCE.proxyDistributionMode = ProxyDistributionMode.ROUND_ROBIN;
        INSTANCE.save();
    }

    public void save() {
        try {
            if (!CONFIG_FILE.getParentFile().exists()) CONFIG_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) { GSON.toJson(this, writer); }
        } catch (IOException e) { e.printStackTrace(); }
    }

    public String getDummyNick() { return dummyNick != null && !dummyNick.trim().isEmpty() ? dummyNick : "DummyPlayer"; }
    public void setDummyNick(String dummyNick) { this.dummyNick = dummyNick; }
    public ProxyDistributionMode getProxyDistributionMode() { return proxyDistributionMode; }
    public void setProxyDistributionMode(ProxyDistributionMode mode) { this.proxyDistributionMode = mode; }
    public List<SocksProxy> getProxies() { return proxies; }
}
