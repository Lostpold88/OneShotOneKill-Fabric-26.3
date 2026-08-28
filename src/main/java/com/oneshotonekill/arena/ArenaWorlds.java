package com.oneshotonekill.arena;
import com.oneshotonekill.shared.ArenaShape;
import com.oneshotonekill.shared.ArenaDemolition;

import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.match.MatchManager.MatchState;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;


import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.item.box.SpecialItemManager;
import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.arena.ArenaWorlds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Verwaltet die Arena-Dimensionen und ihren Live-Reset.
 *
 * Die drei Arenen sind Datapack-Dimensionen (siehe {@code data/oneshotonekill/dimension/}).
 * Beim Serverstart werden sie frisch aus dem Archiv ausgepackt und als unversehrter Sollzustand
 * in {@link ArenaSnapshot} erfasst.
 *
 * Ein Reset zur Laufzeit läuft unterbrechungsfrei und synchron direkt im Arbeitsspeicher über
 * {@link ArenaDemolition#restoreArena}: Nur abweichende Blöcke werden aktualisiert, ohne dass
 * Spieler evakuiert oder Chunks entladen werden müssen.
 */
public final class ArenaWorlds {
    public enum ResetOutcome {
        STARTED,
        BUSY,
        NOT_OPEN
    }

    private static final float LOBBY_YAW = 0.0f;
    private static final float LOBBY_PITCH = 0.0f;
    private static final String STATE_FILE = "oneshotonekill_active_arena.txt";

    private final MinecraftServer server;
    private final Map<Arena, ServerLevel> levels = new EnumMap<>(Arena.class);
    private final Set<UUID> placedThisSession = new HashSet<>();
    private Arena active;

    public ArenaWorlds(MinecraftServer server) {
        this.server = server;
        this.active = loadActive();
    }

    public Arena getActive() { return active; }

    /**
     * Öffnet alle Arenen – und packt dabei jede einzelne frisch aus.
     *
     * <p>Der Serverstart ist der eine Zeitpunkt, an dem ein voller Reset von der Platte nichts kostet:
     * Es ist niemand verbunden, kein Match läuft, und keine Arena-Dimension hält Chunk-Tickets.</p>
     */
    public void openAll() {
        for (Arena arena : Arena.values()) open(arena);
        List<String> opened = levels.keySet().stream().map(Arena::getId).toList();
        OneShotOneKill.INSTANCE.getLOGGER().info("Arenen frisch ausgepackt und geöffnet: {} (aktiv: {})", opened, active.getId());
    }

    /** Packt das Kartenarchiv aus und nimmt die unversehrte Abschrift auf. */
    private boolean open(Arena arena) {
        ServerLevel level = server.getLevel(arena.getDimension());
        if (level == null) {
            OneShotOneKill.INSTANCE.getLOGGER().error(
                "Dimension {} fehlt – liegt data/oneshotonekill/dimension/{}.json im Jar?", arena.getDimensionId(), arena.getId());
            return false;
        }
        if (ArenaWorlds.MapArchive.INSTANCE.extract(server, arena) == null) {
            return false;
        }
        ArenaWorlds.WorldRulesManager.INSTANCE.applyRules(level);
        levels.put(arena, level);
        // Jetzt und nur jetzt ist die Karte nachweislich unversehrt: gerade ausgepackt, noch
        // ohne Spieler. Die Abschrift von hier ist der Sollzustand, auf den jeder Reset zurueckfuehrt.
        ArenaDemolition.INSTANCE.takeSnapshot(level, arena);
        return true;
    }

    public ServerLevel levelOf(Arena arena) { return levels.get(arena); }

    public boolean isOpen(Arena arena) { return levels.containsKey(arena); }
    public boolean isResetting(Arena arena) { return false; }
    public ServerLevel getActiveLevel() { return levelOf(active); }

    public Arena arenaOf(ServerPlayer player) {
        ResourceKey<Level> current = player.level().dimension();
        for (Arena arena : Arena.values()) if (arena.getDimension().equals(current)) return arena;
        return null;
    }

    public Integer switchTo(Arena arena) {
        ServerLevel level = levelOf(arena);
        if (level == null) {
            OneShotOneKill.INSTANCE.getLOGGER().error("Arena {} ist nicht offen - Wechsel abgebrochen.", arena.getId());
            return null;
        }
        active = arena;
        saveActive(arena);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) sendToLobby(player, arena, level);
        OneShotOneKill.INSTANCE.getLOGGER().info("Arena gewechselt zu {} ({} Spieler versetzt)", arena.getId(), players.size());
        return players.size();
    }

    /**
     * Führt einen synchronen, unterbrechungsfreien Live-Reset der Arena im Speicher aus.
     *
     * <p>Setzt das Block-Delta gegen den beim Start genommenen {@link ArenaSnapshot} zurück,
     * bereinigt Fähigkeiten, Mobs, Drops und verwaiste Displays und setzt anwesende Spieler
     * sicher in die Lobby.</p>
     */
    public ResetOutcome requestReset(Arena arena) {
        ServerLevel level = levels.get(arena);
        if (level == null) return ResetOutcome.NOT_OPEN;

        // 1. Zerstörte Blöcke gegen den In-Memory Sollzustand zurücksetzen
        ArenaDemolition.INSTANCE.restoreArena(level, arena);

        // 2. Weltregeln auffrischen
        ArenaWorlds.WorldRulesManager.INSTANCE.applyRules(level);

        // 3. Boden-Items, Pfeile, Orbs und laufende Fähigkeiten in dieser Arena bereinigen
        clearDroppedEntities(level);
        SpecialItemManager.INSTANCE.clearGroundItems();
        OneShotOneKill.clearAbilities(server);

        // 4. Verwaiste Displays/Hologramme aus der Dimension entfernen
        Hologram.sweepUntracked(server);

        // 5. Spieler behalten ihre Position; Fallschaden wird zurückgesetzt
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (arenaOf(player) == arena) {
                player.fallDistance = 0.0f;
            }
        }

        OneShotOneKill.INSTANCE.getLOGGER().info("Arena {} live im Speicher zurückgesetzt (Spielerpositionen beibehalten).", arena.getId());
        return ResetOutcome.STARTED;
    }

    private void clearDroppedEntities(ServerLevel level) {
        AABB area = new AABB(-5000, level.getMinY(), -5000, 5000, level.getMaxY(), 5000);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) {
            item.discard();
        }
        for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, area)) {
            arrow.discard();
        }
        for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, area)) {
            orb.discard();
        }
    }

    /** Takt-Rückruf – bei synchronem Live-Reset gibt es keine asynchrone Warteschlange. */
    public boolean tick() {
        return false;
    }

    public boolean sendToActiveLobby(ServerPlayer player) {
        ServerLevel level = getActiveLevel();
        if (level == null) return false;
        sendToLobby(player, active, level);
        return true;
    }

    public boolean placeOnJoin(ServerPlayer player) {
        if (!placedThisSession.add(player.getUUID()) && arenaOf(player) != null) return true;
        return sendToActiveLobby(player);
    }

    public void sendToLobby(ServerPlayer player, Arena arena, ServerLevel level) { teleport(player, level, arena.getLobby()); }

    private void sendToOverworldSpawn(ServerPlayer player) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getRespawnData().pos();
        teleport(player, overworld, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
    }

    private void teleport(ServerPlayer player, ServerLevel level, Vec3 target) {
        player.teleportTo(level, target.x, target.y, target.z, Set.<Relative>of(), LOBBY_YAW, LOBBY_PITCH, true);
    }

    private Path getStateFile() { return server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE); }

    private Arena loadActive() {
        Path file = getStateFile();
        if (!Files.exists(file)) return Arena.getDefault();
        try {
            String stored = Files.readString(file).trim();
            Arena storedArena = Arena.byId(stored);
            if (storedArena != null) return storedArena;
            OneShotOneKill.INSTANCE.getLOGGER().warn("Unbekannte gespeicherte Arena '{}', nutze {}", stored, Arena.getDefault().getId());
        } catch (Exception exception) {
            OneShotOneKill.INSTANCE.getLOGGER().warn("Aktive Arena nicht lesbar ({}), nutze {}", exception.getMessage(), Arena.getDefault().getId());
        }
        return Arena.getDefault();
    }

    private void saveActive(Arena arena) {
        try {
            Files.writeString(getStateFile(), arena.getId());
        } catch (Exception exception) {
            OneShotOneKill.INSTANCE.getLOGGER().error("Aktive Arena ließ sich nicht sichern: {}", exception.getMessage());
        }
    }


   public static final class MapArchive {
       public static final MapArchive INSTANCE = new MapArchive();
       private static final String SOURCE_PREFIX = "dimensions/minecraft/overworld/";
       private static final List<String> WANTED_FOLDERS = List.of("region", "entities", "poi");
   
       private MapArchive() { }
   
       public Integer extract(MinecraftServer server, Arena arena) {
           try (InputStream archive = openArchive(arena)) {
               if (archive == null) {
                   OneShotOneKill.INSTANCE.getLOGGER().error("Archiv assets/maps/{} fehlt im Jar – Karte {} bleibt unverändert.", arena.getArchive(), arena.getId());
                   return null;
               }
               Path target = mapPath(server, arena);
               int cleared = clear(target);
               int written = unzip(archive, target);
               OneShotOneKill.INSTANCE.getLOGGER().info("Karte {} ausgepackt: {} Dateien entfernt, {} geschrieben.", arena.getId(), cleared, written);
               return written;
           } catch (IOException exception) {
               throw new IllegalStateException("Kartenarchiv konnte nicht verarbeitet werden: " + arena.getId(), exception);
           }
       }
   
       private Path mapPath(MinecraftServer server, Arena arena) {
           return server.getWorldPath(LevelResource.ROOT).resolve("dimensions")
               .resolve(arena.getDimensionId().getNamespace()).resolve(arena.getDimensionId().getPath());
       }
   
       private int clear(Path target) throws IOException {
           if (!Files.isDirectory(target)) return 0;
           int removed = 0;
           for (String folder : WANTED_FOLDERS) {
               Path path = target.resolve(folder);
               if (!Files.isDirectory(path)) continue;
               try (var files = Files.walk(path)) { removed += (int) files.count(); }
               deleteRecursively(path);
           }
           return removed;
       }
   
       private InputStream openArchive(Arena arena) {
           return MapArchive.class.getResourceAsStream("/assets/maps/" + arena.getArchive());
       }
   
       private int unzip(InputStream source, Path target) throws IOException {
           int written = 0;
           try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
               for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                   String relative = relevantPath(entry.getName());
                   if (relative == null || entry.isDirectory()) continue;
                   Path file = target.resolve(relative).normalize();
                   if (!file.startsWith(target.normalize())) throw new IOException("Ungültiger Pfad im Kartenarchiv: " + entry.getName());
                   Files.createDirectories(file.getParent());
                   Files.copy(zip, file);
                   written++;
               }
           }
           return written;
       }
   
       private String relevantPath(String entryName) {
           if (!entryName.startsWith(SOURCE_PREFIX)) return null;
           String relative = entryName.substring(SOURCE_PREFIX.length());
           if (relative.isEmpty() || relative.split("/").length == 0) return null;
           for (String part : relative.split("/")) if (part.equals("..")) return null;
           int separator = relative.indexOf('/');
           String folder = separator < 0 ? relative : relative.substring(0, separator);
           return WANTED_FOLDERS.contains(folder) ? relative : null;
       }
   
       private static void deleteRecursively(Path path) throws IOException {
           try (var files = Files.walk(path)) {
               files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                   try { Files.deleteIfExists(file); } catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
               });
           } catch (java.io.UncheckedIOException exception) {
               throw exception.getCause();
           }
       }
   }

   public static final class WorldRulesManager {
      public static final WorldRulesManager INSTANCE = new WorldRulesManager();
      private static final long MIDDAY_TICKS = 6_000L;
   
      private WorldRulesManager() {
      }
   
      public void applyRules(ServerLevel level) {
         GameRules rules = level.getGameRules();
         MinecraftServer server = level.getServer();
         rules.set(GameRules.SPAWN_MOBS, false, server);
         rules.set(GameRules.SPAWN_MONSTERS, false, server);
         rules.set(GameRules.SPAWN_PATROLS, false, server);
         rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
         rules.set(GameRules.MOB_GRIEFING, false, server);
         rules.set(GameRules.IMMEDIATE_RESPAWN, true, server);
         rules.set(GameRules.KEEP_INVENTORY, true, server);
         rules.set(GameRules.ADVANCE_TIME, false, server);
         rules.set(GameRules.ADVANCE_WEATHER, false, server);
         rules.set(GameRules.LOCATOR_BAR, false, server);
         rules.set(GameRules.SPAWN_PHANTOMS, false, server);
         rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
   
         if (level.getLevelData() instanceof ServerLevelData levelData) {
            levelData.setGameTime(MIDDAY_TICKS);
         }
   
         WeatherData weather = level.getWeatherData();
         weather.setRaining(false);
         weather.setThundering(false);
         weather.setClearWeatherTime(100_000);
         weather.setRainTime(0);
         weather.setThunderTime(0);
         cleanupMapMobs(level);
      }
   
      public void cleanupMapMobs(ServerLevel level) {
         ResourceKey<?> dimension = level.dimension();
         boolean isArenaLevel = Arrays.stream(Arena.values())
            .anyMatch(arena -> Objects.equals(arena.getDimension(), dimension));
         if (!isArenaLevel) {
            return;
         }
   
         for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob) {
               entity.discard();
            }
         }
      }
   
      /**
       * Meldet, ob diese Entity ein Mob ist und deshalb gar nicht erst in die Welt darf.
       *
       * Vorher wurde hier {@code discard()} gerufen. Das reicht nicht: das Beitrittsereignis feuert,
       * <em>bevor</em> die Entity in der Welt ist, und {@code PersistentEntitySectionManager#addEntity}
       * fügt sie unmittelbar danach trotzdem ein – nur eben als bereits entfernt markiert. Genau
       * daraus wurden die Mobs, die herumstanden und sich nicht bewegten: gezeichnet, aber ohne
       * Tick. Verhindern lässt sich der Beitritt allein durch das Abbrechen des Ereignisses, und
       * das kann nur der Aufrufer.
       */
      public static boolean isUnwantedMob(Entity entity) {
         return entity instanceof Mob;
      }
   }

   public static final class ArenaContainment {
      public static final ArenaContainment INSTANCE = new ArenaContainment();
   
      /** So selten darf die Rückmeldung an der Grenze kommen, in Ticks. */
      private static final int WARN_INTERVAL = 20;
   
      /** Spieler-UUID -> letzte Position innerhalb der Arena. */
      private final Map<UUID, Vec3> safeSpots = new HashMap<>();
      private final Map<UUID, Long> lastWarned = new HashMap<>();
   
      private ArenaContainment() {
      }
   
      public void tick(MinecraftServer server) {
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         if (worlds == null) {
            return;
         }
         // Kein Rauswurf wegen Schwebens: In dieser Mod wird geflogen, geschleudert und gestoßen.
         // Das gilt unabhängig davon, ob gerade ein Match läuft – geflogen wird auch dazwischen.
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (worlds.arenaOf(player) != null) {
               player.connection.resetFlyingTicks();
            }
         }
   
         boolean matchRunning = MatchManager.INSTANCE.getCurrentMatchState() == MatchState.RUNNING
            && !MatchManager.Countdown.INSTANCE.isCountdownRunning();
   
         if (!matchRunning) {
            // Außerhalb eines aktiven Matches oder während des Countdowns:
            // Nur Void-Rettung zur Lobby für jeden, der unter die Karte fällt
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               Arena arena = worlds.arenaOf(player);
               if (arena != null && player.getY() < arena.getVoidRescueY()) {
                  worlds.sendToLobby(player, arena, player.level());
                  player.setDeltaMovement(Vec3.ZERO);
                  player.fallDistance = 0.0f;
                  warn(player, "§c⛔ ABGESTÜRZT §7· zurück in die Lobby");
               }
            }
            reset();
            return;
         }
   
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Arena arena = worlds.arenaOf(player);
            if (arena == null || player.isSpectator() || isNearLobby(arena, player)) {
               forget(player);
               continue;
            }
   
            if (arena.isInArena(player.getX(), player.getY(), player.getZ()) && !belowFloor(arena, player)) {
               safeSpots.put(player.getUUID(), player.position());
               continue;
            }
   
            if (belowFloor(arena, player)) {
               rescue(server, arena, player);
            } else {
               pushBack(player, arena, server);
            }
         }
   
         safeSpots.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
         lastWarned.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
      }
   
      public void forget(ServerPlayer player) {
         safeSpots.remove(player.getUUID());
         lastWarned.remove(player.getUUID());
      }
   
      public void reset() {
         safeSpots.clear();
         lastWarned.clear();
      }
   
      private static boolean isNearLobby(Arena arena, ServerPlayer player) {
         Vec3 lobby = arena.getLobby();
         double dx = player.getX() - lobby.x;
         double dz = player.getZ() - lobby.z;
         double dy = player.getY() - lobby.y;
         return dx * dx + dz * dz < 144.0 && Math.abs(dy) < 10.0;
      }
   
      /**
       * Unterhalb der tiefsten Kampfzone – und zwar unterhalb der Toleranz, die
       * {@link ArenaShape#contains} ohnehin zugesteht.
       */
      private static boolean belowFloor(Arena arena, ServerPlayer player) {
         double floor = arena.getRegions().stream().mapToDouble(ArenaShape::getMinY).min().orElseThrow();
         return player.getY() < floor - ArenaShape.ARENA_FLOOR_TOLERANCE;
      }
   
      /** Zurück auf die zuletzt gemerkte Stelle – ohne Schwung, sonst ginge es sofort wieder hinaus. */
      private void pushBack(ServerPlayer player, Arena arena, MinecraftServer server) {
         Vec3 safe = safeSpots.get(player.getUUID());
         if (safe == null) {
            safe = RandomTpSystem.INSTANCE.getRandomArenaLocation(arena, server);
         }
         player.teleportTo(safe.x, safe.y, safe.z);
         player.setDeltaMovement(Vec3.ZERO);
         player.hurtMarked = true;
         player.fallDistance = 0.0f;
         safeSpots.put(player.getUUID(), safe);
         warn(player, "§c⛔ ARENA-GRENZE §7· hier geht es nicht weiter");
      }
   
      /** Wer unter die Karte gefallen ist, kommt an einer ausgewürfelten Stelle wieder heraus. */
      private void rescue(MinecraftServer server, Arena arena, ServerPlayer player) {
         ServerLevel level = server.getLevel(arena.getDimension());
         if (level == null) {
            level = player.level();
         }
         Vec3 spot = RandomTpSystem.INSTANCE.getRandomArenaLocation(arena, server);
         player.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), player.getYRot(), player.getXRot(), false);
         player.setDeltaMovement(Vec3.ZERO);
         player.fallDistance = 0.0f;
         safeSpots.put(player.getUUID(), spot);
         level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.7F, 1.4F);
         warn(player, "§c⛔ ABGESTÜRZT §7· zurück in die Arena");
      }
   
      /** Eine Meldung je Sekunde: An einer Wand entlangzulaufen soll die Leiste nicht zumüllen. */
      private void warn(ServerPlayer player, String message) {
         MinecraftServer server = player.level().getServer();
         if (server == null) return;
         long now = server.getTickCount();
         if (now - lastWarned.getOrDefault(player.getUUID(), Long.MIN_VALUE) < WARN_INTERVAL) {
            return;
         }
         lastWarned.put(player.getUUID(), now);
         Feedback.actionBar(player, message);
      }
   }
}
