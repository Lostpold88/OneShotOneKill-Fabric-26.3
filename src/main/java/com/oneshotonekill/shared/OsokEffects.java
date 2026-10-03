package com.oneshotonekill.shared;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.oneshotonekill.network.OsokPayloads.MatchNotificationPayload;

/** Bündelt die Ton-, Partikel- und Actionbar-Rückmeldungen des Minigames. */
@SuppressWarnings({"resource", "SameParameterValue", "unused"})
public final class OsokEffects {
   public static final OsokEffects INSTANCE = new OsokEffects();

   private static final int START_RING_POINTS = 16;
   private static final double START_RING_RADIUS = 1.8;

   private OsokEffects() {
   }

   public void playStartMatchEffect(ServerPlayer player) {
      ServerLevel level = player.level();
      Vec3 pos = player.position();
      announce(player, "🎯 ONESHOT ONEKILL — MATCH GESTARTET ⚡", ChatFormatting.GOLD);
      level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.6F, 1.6F);
      level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 0.4F, 1.3F);
      level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y + 1.0, pos.z, 2, 0.2, 0.2, 0.2, 0.0);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 1.0, pos.z, 1, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y + 1.0, pos.z, 60, 0.8, 1.5, 0.8, 0.2);
      level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y + 0.2, pos.z, 45, 0.5, 1.8, 0.5, 0.12);
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 1.0, pos.z, 40, 0.6, 1.2, 0.6, 0.08);

      for (int point = 0; point < START_RING_POINTS; point++) {
         double angle = point * Math.PI / (START_RING_POINTS / 2.0);
         double offsetX = Math.cos(angle) * START_RING_RADIUS;
         double offsetZ = Math.sin(angle) * START_RING_RADIUS;
         level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.x + offsetX, pos.y + 0.1, pos.z + offsetZ, 3, 0.0, 0.2, 0.0, 0.03);
      }

      // Konfetti-Burst: bunte Funken schießen in alle Richtungen, Feuerwerk steigt auf
      int[] confetti = {0xFFD700, 0x00F0FF, 0xFF2244, 0xFFFFFF, 0x00E676, 0xBD00FF};
      for (int i = 0; i < 36; i++) {
         double angle = i * Math.PI * 2.0 / 36.0;
         level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(confetti[i % confetti.length], 1.5F),
            pos.x + Math.cos(angle) * 0.6, pos.y + 1.0, pos.z + Math.sin(angle) * 0.6,
            3, 0.25, 0.25, 0.25, 0.0);
         level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y + 1.0, pos.z,
            0, Math.cos(angle), 0.35, Math.sin(angle), 0.45);
      }
      level.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y + 1.5, pos.z, 60, 0.9, 0.9, 0.9, 0.18);
   }

   public void playResumeMatchEffect(ServerPlayer player) {
      ServerLevel level = player.level();
      Vec3 pos = player.position();
      ServerPlayNetworking.send(player, new MatchNotificationPayload("RESUME", "", "", 42, OsokColors.EMERALD));
      level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.2F);
   }

   public void playPauseMatchEffect(ServerPlayer player) {
      ServerLevel level = player.level();
      Vec3 pos = player.position();
      ServerPlayNetworking.send(player, new MatchNotificationPayload("PAUSE", "", "", 60, OsokColors.GOLD));
      level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.7F, 1.1F);
      level.sendParticles(ParticleTypes.WITCH, pos.x, pos.y + 1.0, pos.z, 20, 0.4, 0.8, 0.4, 0.05);
   }

   public void playStopMatchEffect(ServerPlayer player) {
      ServerLevel level = player.level();
      Vec3 pos = player.position();
      ServerPlayNetworking.send(player, new MatchNotificationPayload("STOP", "", "", 50, OsokColors.CRIMSON));
      level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.8F, 0.9F);
      level.sendParticles(ParticleTypes.WITCH, pos.x, pos.y + 1.0, pos.z, 25, 0.4, 0.8, 0.4, 0.05);
   }

   public void playMapSwitchEffect(ServerPlayer player, String arenaName) {
      ServerLevel level = player.level();
      Vec3 pos = player.position();
      ServerPlayNetworking.send(player, new MatchNotificationPayload("ARENA_SWITCH", "", arenaName, 45, OsokColors.CYAN));
      level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.2F);
      level.sendParticles(ParticleTypes.PORTAL, pos.x, pos.y + 1.0, pos.z, 20, 0.3, 0.8, 0.3, 0.1);
   }

   public void playMapResetEffect(ServerPlayer player, String arenaName) {
      ServerLevel level = player.level();
      Vec3 pos = player.position();
      ServerPlayNetworking.send(player, new MatchNotificationPayload("ARENA_RESET", "", arenaName, 45, OsokColors.GOLD));
      level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.4F, 1.4F);
   }

   public void playFrostTrapTriggeredEffect(ServerPlayer owner, String victimName) {
      ServerPlayNetworking.send(owner, new MatchNotificationPayload("FROST_TRAP", "", victimName, 55, OsokColors.CYAN));
      sendPrivateSound(owner, SoundEvents.ARROW_HIT_PLAYER, 0.9F, 1.6F);
      sendPrivateSound(owner, SoundEvents.GLASS_BREAK, 0.7F, 1.4F);
      Feedback.actionBar(owner, Component.translatable("actionbar.oneshotonekill.frost_trap_triggered", victimName));
   }

   public void playEliminationEffect(ServerLevel level, Vec3 deathPos) {
      level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, deathPos.x, deathPos.y + 1.0, deathPos.z, 12, 0.3, 0.5, 0.3, 0.1);
      level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()),
         deathPos.x, deathPos.y + 1.0, deathPos.z, 35, 0.3, 0.6, 0.3, 0.15);
   }

   /**
    * Großer Text in Bildschirmmitte, wie ihn Vanilla für Titel benutzt.
    * <p>
    * Die Actionbar reicht für Beiläufiges; ein anrollender Bombenangriff soll den Blick
    * unterbrechen. Ohne Untertitel bleibt die zweite Zeile leer.
    */
   public void sendTitle(ServerPlayer player, Component title, Component subtitle,
                         int fadeInTicks, int stayTicks, int fadeOutTicks) {
      player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeInTicks, stayTicks, fadeOutTicks));
      player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
      player.connection.send(new ClientboundSetTitleTextPacket(title));
   }

   /**
    * Spielt einen Ton nur für diesen Spieler ab, ohne ihn an die Umgebung zu senden.
    * <p>
    * Der Ton hängt an einer festen Weltposition. Für alles, was einen Spieler überdauert, der
    * sich gleich bewegt oder teleportiert wird, ist {@link #playOwnSound} die richtige Wahl.
    */
   public void sendPrivateSound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
      Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
      player.connection.send(new ClientboundSoundPacket(holder, SoundSource.PLAYERS,
         player.getX(), player.getY(), player.getZ(), volume, pitch, player.level().getRandom().nextLong()));
   }

    /**
    * Spielt einen Ton am Spieler selbst – er wandert mit ihm mit.
    * <p>
    * Ein an eine Weltposition geheftetes Geräusch bleibt liegen, wo es angestoßen wurde. Beim
    * Tod fällt das sofort auf: der Sofort-Respawn setzt den Spieler bewusst weit vom Sterbeort
    * weg, und der Todes-Ton verhallte dort ungehört. An die Entity gebunden bleibt er dort, wo
    * der Spieler ist, und damit auf voller Lautstärke.
    */
   public void playOwnSound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
      Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
      player.connection.send(new ClientboundSoundEntityPacket(holder, SoundSource.PLAYERS, player,
         volume, pitch, player.level().getRandom().nextLong()));
   }

   public void playOwnSound(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
      playOwnSound(player, sound.value(), volume, pitch);
   }

   /** Der eigene Tod – nur für den Gestorbenen, an ihm selbst. */
   public void playDeathSound(ServerPlayer player) {
      playOwnSound(player, SoundEvents.PLAYER_DEATH, 1.0F, 1.0F);
   }

   private void announce(ServerPlayer player, String text, ChatFormatting color) {
      player.sendSystemMessage(Component.literal(text).withStyle(color, ChatFormatting.BOLD), true);
   }
}
