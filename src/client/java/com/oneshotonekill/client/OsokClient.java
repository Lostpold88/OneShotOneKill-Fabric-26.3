package com.oneshotonekill.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import com.oneshotonekill.client.hud.ChronoHudLayers.TimeDistortionLayer;
import com.oneshotonekill.client.hud.CombatHudLayers;
import com.oneshotonekill.client.hud.CombatHudLayers.*;
import com.oneshotonekill.client.hud.MatchHudLayers.GunGameHudLayer;
import com.oneshotonekill.client.hud.MatchHudLayers.MatchBannerLayer;
import com.oneshotonekill.client.hud.MatchHudLayers.MatchCountdownLayer;
import com.oneshotonekill.client.hud.MatchHudLayers.MatchStartOverlayLayer;
import com.oneshotonekill.client.hud.NukeHudLayers.NukeCountdownLayer;
import com.oneshotonekill.client.hud.NukeHudLayers.NukeFlashLayer;
import com.oneshotonekill.client.hud.NukeHudLayers.NukeVictoryLayer;
import com.oneshotonekill.client.hud.TabScoreboardHudLayer;
import com.oneshotonekill.client.model.OsokClientModels;
import com.oneshotonekill.client.movement.ClientClimbing;
import com.oneshotonekill.client.network.OsokClientHandlers;
import com.oneshotonekill.client.config.MinimapConfig;
import com.oneshotonekill.client.renderer.*;
import com.oneshotonekill.client.screen.AdminItemScreen;
import com.oneshotonekill.client.screen.MinimapConfigScreen;
import com.oneshotonekill.client.sound.MinigunSoundController;
import com.oneshotonekill.client.sound.NukeSoundController;
import com.oneshotonekill.client.sound.TimeDistortionSoundController;
import com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.event.InteractionGates;
import com.oneshotonekill.network.OsokPayloads.DetonateC4Payload;
import com.oneshotonekill.network.OsokPayloads.GlideBoostPayload;
import com.oneshotonekill.network.OsokPayloads.RequestArenaMenuPayload;
import com.oneshotonekill.registry.ModEntities;
import com.oneshotonekill.registry.ModItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Clientseitiger Einstieg: Tastenbelegung, HUD-Ebenen, Modelle und der Client-Takt.
 * <p>
 * <p>Fabric ruft {@link #onInitializeClient()} nur auf dem Client auf. Der Einstiegspunkt steht
 * in {@code fabric.mod.json} unter {@code client}, und der ganze Zweig {@code src/client/java}
 * wird auf einem dedizierten Server gar nicht erst geladen – die Trennung ist damit schon zur
 * Bauzeit dicht und braucht keine Abfrage der Laufzeitumgebung.</p>
 * <p>
 * <p>Alle vierzehn HUD-Ebenen laufen über {@link HudElementRegistry}; gezeichnet wird derselbe
 * Code wie zuvor. Die vier Modellerweiterungen brauchen keine Fabric-API: Vanilla führt seine
 * Modelltypen, Modell-Bedingungen und Sonderrenderer in offenen {@code ID_MAPPER}-Tabellen, in
 * die sich ein eigener Eintrag unmittelbar einhängen lässt.</p>
 */
@SuppressWarnings({"resource", "unused"})
public final class OsokClient implements ClientModInitializer {
    public static final KeyMapping.Category OSOK_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(OneShotOneKill.MOD_ID, "main"));
    private static final int DOUBLE_JUMP_WINDOW = 8;

    private static KeyMapping detonateC4Key;
    private static KeyMapping menuKey;
    private static KeyMapping adminMenuKey;
    private static KeyMapping minimapConfigKey;
    private static boolean jumpWasDown;
    private static int ticksSinceJump = Integer.MAX_VALUE;

    private static void registerKeys() {
        detonateC4Key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oneshotonekill.detonate_c4",
                InputConstants.Type.KEYSYM, InputConstants.KEY_R, OSOK_CATEGORY));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oneshotonekill.open_menu",
                InputConstants.Type.KEYSYM, InputConstants.KEY_G, OSOK_CATEGORY));
        adminMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oneshotonekill.open_admin_menu",
                InputConstants.Type.KEYSYM, InputConstants.KEY_X, OSOK_CATEGORY));
        minimapConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oneshotonekill.minimap_config",
                InputConstants.Type.KEYSYM, InputConstants.KEY_Y, OSOK_CATEGORY));
    }

    /**
     * Alle HUD-Ebenen der Mod.
     */
    private static void registerHudElements() {
        HudElementRegistry.addLast(id("minigun_hud"), new MinigunHudLayer());
        HudElementRegistry.addLast(id("railgun_hud"), new RailgunHudLayer());
        HudElementRegistry.addLast(id("grappling_hook_hud"), new GrapplingHookHudLayer());
        HudElementRegistry.addLast(id("airstrike_alarm"), new AirstrikeAlarmLayer());
        HudElementRegistry.addLast(id("bomber_camera"), new BomberCameraLayer());
        HudElementRegistry.addLast(id("item_box_marker"), new ItemBoxLayer());
        HudElementRegistry.addLast(id("deployable_markers"), new DeployableMarkerLayer());
        HudElementRegistry.addLast(id("ability_status"), new AbilityStatusLayer());
        HudElementRegistry.addLast(id("time_distortion"), new TimeDistortionLayer());
        HudElementRegistry.addLast(id("match_start_overlay"), new MatchStartOverlayLayer());
        HudElementRegistry.addLast(id("match_countdown"), new MatchCountdownLayer());
        HudElementRegistry.addLast(id("match_banner"), new MatchBannerLayer());
        HudElementRegistry.addLast(id("gun_game_hud"), new GunGameHudLayer());
        HudElementRegistry.addLast(id("nuke_countdown"), new NukeCountdownLayer());
        HudElementRegistry.addLast(id("nuke_victory"), new NukeVictoryLayer());
        HudElementRegistry.addLast(id("nuke_flash"), new NukeFlashLayer());
        HudElementRegistry.addLast(id("tilted_minimap"), new TiltedMinimapLayer());
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, id("mantle"), new com.oneshotonekill.client.hud.MantleHudLayers.MantleHudLayer());

        // Das Vanilla-Fadenkreuz wird ausgeblendet, solange Minigun, Railgun oder Grappling Hook in der Hand liegen:
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, deltaTracker) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.options.getCameraType().isFirstPerson()) {
                boolean hasCustomCrosshair = client.player.getMainHandItem().is(ModItems.MINIGUN)
                        || client.player.getMainHandItem().is(ModItems.RAILGUN)
                        || client.player.getOffhandItem().is(ModItems.RAILGUN)
                        || client.player.getMainHandItem().is(ModItems.GRAPPLING_HOOK)
                        || client.player.getOffhandItem().is(ModItems.GRAPPLING_HOOK);
                if (hasCustomCrosshair) {
                    return;
                }
            }
            original.extractRenderState(graphics, deltaTracker);
        });

        // Vanilla-Tab-Liste unterdrücken
        HudElementRegistry.replaceElement(VanillaHudElements.PLAYER_LIST, original -> (graphics, deltaTracker) -> {});

        // CS:GO / Valorant Tactical Match Tab Scoreboard (über jedem anderen HUD-Element gerendert)
        HudElementRegistry.addLast(id("tab_scoreboard"), (graphics, deltaTracker) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.gui.screen() != null) {
                return;
            }
            float progress = TabScoreboardState.INSTANCE.getTabOpenProgress(deltaTracker.getGameTimeDeltaPartialTick(false));
            if (progress > 0.001F) {
                TabScoreboardHudLayer.render(graphics, deltaTracker, progress);
            }
        });
    }

    /**
     * Die eigenen Modellbausteine.
     * {@code ConditionalItemModelProperties} und {@code SpecialModelRenderers} halten je eine
     * öffentliche {@code LateBoundIdMapper}-Tabelle, in die ein eigener Codec unter eigener
     * Kennung eingetragen wird. Genau darauf verweisen die Modell-Definitionen unter
     * {@code assets/oneshotonekill/items/}.</p>
     */
    private static void registerModels() {
        ItemModels.ID_MAPPER.put(id("spinning_rotor"),
                OsokClientModels.SpinningRotorModel.Unbaked.MAP_CODEC);
        ItemModels.ID_MAPPER.put(id("grappling_hook"),
                OsokClientModels.GrapplingHookModel.Unbaked.MAP_CODEC);
        ItemModels.ID_MAPPER.put(id("chrono_distorter"),
                OsokClientModels.ChronoDistorterModel.Unbaked.MAP_CODEC);
        ConditionalItemModelProperties.ID_MAPPER.put(id("has_placed_c4"),
                OsokClientModels.HasPlacedC4Property.MAP_CODEC);
        SpecialModelRenderers.ID_MAPPER.put(id("chain_lightning"),
                ChainLightningItemRenderer.Unbaked.MAP_CODEC);
    }

    private static void registerRenderers() {
        // Das besitzerexklusive Display zeichnet sich wie ein gewöhnliches Item-Display.
        EntityRenderers.register(ModEntities.OWNER_VISIBLE_ITEM_DISPLAY, OwnerVisibleDisplayRenderer::new);

        PictureInPictureRendererRegistry.register(ctx -> new CombatHudLayers.BomberCameraPipRenderer(
                ctx.minecraft().getEntityRenderDispatcher(),
                new BlockModelResolver(ctx.minecraft().getModelManager())));

        ReflectorShieldRenderer.register();
        MagnetShieldRenderer.register();
        GliderWingRenderer.register();
        GrapplingHookRenderer.register();
        BoogieDiscoRenderer.register();
    }

    private static void onClientTick(Minecraft client) {
        if (client.isPaused()) {
            return;
        }
        MinigunHudState.INSTANCE.tick();
        MatchStartState.INSTANCE.tick();
        GrapplePullState.INSTANCE.tick();
        MatchBannerState.INSTANCE.tick();
        GunGameHudState.INSTANCE.tick();
        MinigunSpinState.INSTANCE.tick(client);
        AirstrikeAlarmState.INSTANCE.tick();
        BomberCameraState.INSTANCE.tick();
        AbilityStatusState.INSTANCE.tick();
        ReflectorShieldRenderer.tick();
        MagnetShieldRenderer.tick();
        GliderWingRenderer.tick();
        BoogieDiscoRenderer.tick();
        CameraShakeState.INSTANCE.tick();
        MinigunSoundController.INSTANCE.tickClient(client);
        NukeState.INSTANCE.tick();
        NukeSoundController.INSTANCE.tick(client);
        MinimapState.INSTANCE.tick(client);
        boolean tabDown = client.options.keyPlayerList.isDown() && client.gui.screen() == null;
        TabScoreboardState.INSTANCE.tick(tabDown);
        // Der Zeitverzerrer läuft je Bild: Während der Zeitlupe tickt auch der Client nur achtmal
        // je Sekunde, hier bleibt nur das Ablaufen der Frist übrig.
        TimeDistortionEffects.INSTANCE.clientTick();

        if (detonateC4Key != null && detonateC4Key.consumeClick() && client.player != null && client.gui.screen() == null) {
            ClientPlayNetworking.send(DetonateC4Payload.EMPTY);
        }

        if (menuKey != null && menuKey.consumeClick() && client.player != null) {
            ClientPlayNetworking.send(RequestArenaMenuPayload.EMPTY);
        }

        tickGlideBoost(client);

        if (adminMenuKey != null && adminMenuKey.consumeClick() && client.player != null && OneShotOneKill.isAdmin(client.player)) {
            Screen current = client.gui.screen();
            if (current == null) {
                client.gui.setScreen(new AdminItemScreen());
            } else if (current instanceof AdminItemScreen) {
                client.gui.setScreen(null);
            }
        }

        if (minimapConfigKey != null && minimapConfigKey.consumeClick() && client.player != null) {
            Screen current = client.gui.screen();
            if (current == null) {
                client.gui.setScreen(new MinimapConfigScreen());
            } else if (current instanceof MinimapConfigScreen) {
                client.gui.setScreen(null);
            }
        }
    }

    /**
     * Doppelter Druck auf die Sprungtaste startet einen gelandeten Gleitflug neu.
     * <p>
     * Geschickt wird nur, wenn der eigene Spieler gerade in der Liste der Flieger steht – das
     * ist dieselbe Liste, aus der die Tragflächen gezeichnet werden. Ohne diese Bedingung ginge
     * bei jedem Doppelsprung im Spiel ein Paket zum Server, und das sind viele.
     * <p>
     * Gezählt wird der Tastendruck selbst, nicht der Sprung: Wer landet, drückt zweimal, und
     * beide Male soll zählen – auch der erste, bei dem der Spieler noch am Boden klebt.
     */
    private static void tickGlideBoost(Minecraft client) {
        if (ticksSinceJump < Integer.MAX_VALUE) {
            ticksSinceJump++;
        }
        boolean jumpDown = client.options.keyJump.isDown();
        boolean pressed = jumpDown && !jumpWasDown;
        jumpWasDown = jumpDown;
        if (!pressed) {
            return;
        }

        if (ticksSinceJump <= DOUBLE_JUMP_WINDOW && client.player != null
                && GlideState.INSTANCE.activePlayers().contains(client.player.getUUID())) {
            ClientPlayNetworking.send(GlideBoostPayload.EMPTY);
            ticksSinceJump = Integer.MAX_VALUE;
            return;
        }
        ticksSinceJump = 0;
    }

    /**
     * Beim Verlassen des Servers müssen Kamera, Klang und Zustände zurückgesetzt werden.
     */
    private static void onDisconnect() {
        ClientClimbing.INSTANCE.clear();
        MatchStartState.INSTANCE.clear();
        MinigunHudState.INSTANCE.clear();
        MinigunSpinState.INSTANCE.clear();
        GunGameHudState.INSTANCE.clear();
        AirstrikeAlarmState.INSTANCE.clear();
        BomberCameraState.INSTANCE.clear();
        AbilityStatusState.INSTANCE.clear();
        ReflectorShieldRenderer.clear();
        MagnetFieldState.INSTANCE.clear();
        MagnetShieldRenderer.clear();
        GliderWingRenderer.clear();
        GrapplingHookRenderer.clear();
        BoogieDiscoRenderer.clear();
        GrapplePullState.INSTANCE.clear();
        CameraShakeState.INSTANCE.clear();
        MinigunSoundController.INSTANCE.stopAll();
        NukeState.INSTANCE.clear();
        NukeSoundController.INSTANCE.stopAll();
        TimeDistortionSoundController.INSTANCE.stopAll();
        TimeDistortionEffects.INSTANCE.clear();
        MinimapState.INSTANCE.clear();
        TabScoreboardState.INSTANCE.reset();
    }

    public static boolean isDetonateC4Key(KeyEvent event) {
        return detonateC4Key != null && detonateC4Key.matches(event);
    }

    public static Component detonateC4KeyName() {
        return detonateC4Key == null ? Component.literal("?") : detonateC4Key.getTranslatedKeyMessage().copy();
    }

    public static boolean isMenuKey(KeyEvent event) {
        return menuKey != null && menuKey.matches(event);
    }

    public static Component menuKeyName() {
        return menuKey == null ? Component.literal("?") : menuKey.getTranslatedKeyMessage().copy();
    }

    public static boolean isAdminMenuKey(KeyEvent event) {
        return adminMenuKey != null && adminMenuKey.matches(event);
    }

    public static Component adminMenuKeyName() {
        return adminMenuKey == null ? Component.literal("?") : adminMenuKey.getTranslatedKeyMessage().copy();
    }

    public static boolean isMinimapConfigKey(KeyEvent event) {
        return minimapConfigKey != null && minimapConfigKey.matches(event);
    }

    public static Component minimapConfigKeyName() {
        return minimapConfigKey == null ? Component.literal("?") : minimapConfigKey.getTranslatedKeyMessage().copy();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(OneShotOneKill.MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        MinimapConfig.INSTANCE.load();
        registerKeys();
        registerHudElements();
        registerModels();
        registerRenderers();

        OsokClientHandlers.register();
        com.oneshotonekill.client.effect.BoogieBombClient.register();
        ClientInputEvents.register();
        InteractionGates.registerClientPullGate(player ->
                GrapplePullState.INSTANCE.isPulling(player.getUUID())
        );

        ClientTickEvents.END_CLIENT_TICK.register(OsokClient::onClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
    }

    // --- Owner Visible Display Renderer ---
    public static final class OwnerVisibleDisplayRenderer extends net.minecraft.client.renderer.entity.DisplayRenderer.ItemDisplayRenderer {
        public OwnerVisibleDisplayRenderer(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
            super(context);
        }
    }
}
