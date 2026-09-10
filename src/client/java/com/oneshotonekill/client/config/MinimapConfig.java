package com.oneshotonekill.client.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persistente Konfiguration für Position und Skalierung der Tilted-Towers-Minimap.
 */
@SuppressWarnings("unused")
public final class MinimapConfig {
    public static final MinimapConfig INSTANCE = new MinimapConfig();

    public static final int DEFAULT_RADIUS = 56;
    public static final int MIN_RADIUS = 36;
    public static final int MAX_RADIUS = 100;
    public static final int DEFAULT_MARGIN = 14;

    private int radius = DEFAULT_RADIUS;
    private float xRatio = 0.08f;
    private float yRatio = 0.12f;

    private MinimapConfig() {
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
    }

    public float getXRatio() {
        return xRatio;
    }

    public float getYRatio() {
        return yRatio;
    }

    public void setRatios(float xRatio, float yRatio) {
        this.xRatio = Math.clamp(xRatio, 0.0f, 1.0f);
        this.yRatio = Math.clamp(yRatio, 0.0f, 1.0f);
    }

    public int getCenterX(int screenWidth, int currentRadius) {
        int minX = currentRadius + 6;
        int maxX = Math.max(minX, screenWidth - currentRadius - 6);
        int raw = Math.round(xRatio * screenWidth);
        return Math.clamp(raw, minX, maxX);
    }

    public int getCenterY(int screenHeight, int currentRadius) {
        int minY = currentRadius + 6;
        int maxY = Math.max(minY, screenHeight - currentRadius - 6);
        int raw = Math.round(yRatio * screenHeight);
        return Math.clamp(raw, minY, maxY);
    }

    public void setCenter(int cx, int cy, int screenWidth, int screenHeight) {
        this.xRatio = Math.clamp((float) cx / (float) Math.max(1, screenWidth), 0.0f, 1.0f);
        this.yRatio = Math.clamp((float) cy / (float) Math.max(1, screenHeight), 0.0f, 1.0f);
    }

    public void setPresetTopLeft(int screenWidth, int screenHeight) {
        setCenter(radius + DEFAULT_MARGIN, radius + DEFAULT_MARGIN, screenWidth, screenHeight);
    }

    public void setPresetTopRight(int screenWidth, int screenHeight) {
        setCenter(screenWidth - radius - DEFAULT_MARGIN, radius + DEFAULT_MARGIN, screenWidth, screenHeight);
    }

    public void setPresetBottomLeft(int screenWidth, int screenHeight) {
        setCenter(radius + DEFAULT_MARGIN, screenHeight - radius - DEFAULT_MARGIN, screenWidth, screenHeight);
    }

    public void setPresetBottomRight(int screenWidth, int screenHeight) {
        setCenter(screenWidth - radius - DEFAULT_MARGIN, screenHeight - radius - DEFAULT_MARGIN, screenWidth, screenHeight);
    }

    public void reset(int screenWidth, int screenHeight) {
        this.radius = DEFAULT_RADIUS;
        setPresetTopLeft(screenWidth, screenHeight);
    }

    private Path getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("oneshotonekill-minimap.properties");
    }

    public synchronized void load() {
        Path path = getConfigFile();
        if (!Files.exists(path)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            Properties props = new Properties();
            props.load(reader);

            String radVal = props.getProperty("radius");
            if (radVal != null) {
                this.radius = Math.clamp(Integer.parseInt(radVal.trim()), MIN_RADIUS, MAX_RADIUS);
            }
            String xrVal = props.getProperty("xRatio");
            if (xrVal != null) {
                this.xRatio = Math.clamp(Float.parseFloat(xrVal.trim()), 0.0f, 1.0f);
            }
            String yrVal = props.getProperty("yRatio");
            if (yrVal != null) {
                this.yRatio = Math.clamp(Float.parseFloat(yrVal.trim()), 0.0f, 1.0f);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        Path path = getConfigFile();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            Properties props = new Properties();
            props.setProperty("radius", String.valueOf(this.radius));
            props.setProperty("xRatio", String.format(java.util.Locale.US, "%.5f", this.xRatio));
            props.setProperty("yRatio", String.format(java.util.Locale.US, "%.5f", this.yRatio));

            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                props.store(writer, "OneShotOneKill Minimap Configuration");
            }
        } catch (Exception ignored) {
        }
    }
}
