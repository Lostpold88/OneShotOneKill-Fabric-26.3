package com.oneshotonekill.event;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.shared.OsokEffects;
import com.oneshotonekill.item.box.SpecialItemManager;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.item.runtime.GrapplingHookSystem;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.item.runtime.ThrownDevices;
import com.oneshotonekill.match.GunGameManager;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.match.MatchManager.GameMode;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.nuke.NukeSequenceManager;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.match.ScoreboardManager;
import com.oneshotonekill.arena.RandomTpSystem.RespawnSystem;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

@SuppressWarnings({"ConstantValue", "RedundantCast", "resource", "unused"})
public final class CombatEvents {
   private CombatEvents() {
   }

   public static void register() {
      // Ersetzt NeoForges LivingIncomingDamageEvent. Die Prüflogik ist unverändert; nur das
      // Abbrechen läuft jetzt über den Rückgabewert statt über setCanceled.
      ServerLivingEntityEvents.ALLOW_DAMAGE.register(DamageListener::allowDamage);
      ServerLivingEntityEvents.ALLOW_DEATH.register(
         (entity, damageSource, damageAmount) -> allowDeath(entity));
   }

   /**
    * Öffnet Vanillas Unverwundbarkeits-Vorprüfung für einen gültigen PvP-Treffer im Match.
    * <p>
    * <p>Vanilla prüft Creative-, Spectator- und Entity-Unverwundbarkeit vor dem eigentlichen
    * Schadensereignis. NeoForge bietet dafür {@code EntityInvulnerabilityCheckEvent}; Fabric API
    * hat kein Gegenstück, deshalb ruft {@code ServerPlayerInvulnerabilityMixin} diese Methode
    * am Ende von {@code ServerPlayer#isInvulnerableTo} auf.</p>
    * <p>
    * @return {@code true}, wenn die gemeldete Unverwundbarkeit für diesen Treffer nicht gelten soll
    */
   public static boolean overridesInvulnerability(ServerPlayer victim, DamageSource source) {
      if (MatchManager.INSTANCE.getCurrentMatchState() != MatchState.RUNNING
         || MatchManager.Countdown.INSTANCE.isCountdownRunning()
         || NukeSequenceManager.INSTANCE.isRunning()) {
         return false;
      }

      ServerPlayer attacker = DamageListener.attackerOf(source);
      return attacker != null && DamageListener.playersAreInActiveArena(attacker, victim);
   }

   /**
    * Ersetzt {@code ServerPlayerDeathMixin}.
    * <p>
    * Ein echter Tod in der Arena wird abgefangen und in einen Sofort-Respawn umgewandelt, damit
    * er über dieselbe Buchführung läuft wie eine reguläre Eliminierung.
    * <p>
    * <p>Genau eine Ausnahme gibt es: die Nuke am Matchende. Dort <em>soll</em> gestorben
    * werden, und zwar endgültig – die Getroffenen werden anschließend zu Zuschauern und sehen
    * dem Pilz zu. Ohne diese Abfrage stünde jeder von ihnen im selben Tick wieder auf, und der
    * Einschlag wäre ein sehr lautes Nichts. Gezählt wird der Tod dabei auch nicht: Die
    * Abschlusstafel zeigt das Ergebnis des Matches, nicht das seiner Beendigung.</p>
    * <p>
    * <p>{@code ALLOW_DEATH} stellt die Gesundheit nicht wieder her – das erledigt der
    * Sofort-Respawn, der den Spieler ohnehin auf volle Gesundheit setzt.</p>
    * <p>
    * @return {@code false}, wenn dieser Tod nicht stattfinden soll
    */
   private static boolean allowDeath(LivingEntity entity) {
      if (MatchManager.INSTANCE.getCurrentMatchState() != MatchState.RUNNING
         || NukeSequenceManager.INSTANCE.isRunning()
         || !(entity instanceof ServerPlayer player)) {
         return true;
      }

      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      if (arena == null || worlds.arenaOf(player) != arena) {
         return true;
      }

      ScoreboardManager.INSTANCE.addDeath(player.getUUID());
      ScoreboardManager.INSTANCE.resetStreak(player.getUUID());
      ScoreboardManager.INSTANCE.updateAllScoreboards();
      KillFeed.death(player);
      com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.clearFor(player);
      RespawnSystem.INSTANCE.respawnInstant(player, arena, player.position(), true);
      return false;
   }


   public static final class DamageListener {
      public static final DamageListener INSTANCE = new DamageListener();

      private DamageListener() {}
   
      public void eliminate(ServerPlayer attacker, ServerPlayer victim, Arena arena, KillFeed.Cause cause) {
         // Das Reflektor-Schild sitzt zentral hier und wirkt damit gegen jede Todesursache –
         // Sprengung, Railgun, Geschützturm und Bomber eingeschlossen.
         if (StatusAbilities.INSTANCE.consumeShield(victim, attacker, cause)) {
            return;
         }
         com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.clearFor(victim);
   
         // Beim Tod wird nur beendet, was ohne lebenden Spieler keinen Sinn ergibt. Ein scharf
         // gemachter Schuss, ein Magnetfeld oder ein aufgestellter Turm bleiben bestehen – wer ein
         // Spezial-Item eingesetzt hat, soll es nicht dadurch verlieren, dass er danach stirbt.
         StatusAbilities.INSTANCE.clearOnDeath(victim);
         ThrownDevices.INSTANCE.excludeFromFields(victim);
         Deployables.INSTANCE.clearOnDeath(victim);
   
         recordAttackerKill(attacker, victim, cause, false);

         SpecialItemManager.INSTANCE.tryDropVictimLoot(attacker, victim, cause, victim.position());

         // Sofortige Treffer-Partikel am Sterbeort
         ServerLevel victimLevel = (ServerLevel) victim.level();
         Vec3 hitPos = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
         victimLevel.sendParticles(ParticleTypes.CRIT, hitPos.x, hitPos.y, hitPos.z, 12, 0.35, 0.35, 0.35, 0.2);
         // Erst nach dem Zählen melden, damit die Serie in der Zeile schon stimmt.
         recordVictimDeath(attacker, victim, cause, arena);
      }
   
      public static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
         if (!(entity instanceof ServerPlayer victim)) {
            return true;
         }

         if (source.is(DamageTypeTags.IS_FALL) && GrapplingHookSystem.INSTANCE.isFallImmune(victim)) {
            victim.resetFallDistance();
            return false;
         }

         ServerPlayer attacker = attackerOf(source);
         Entity directEntity = source.getDirectEntity();
         if (MatchManager.INSTANCE.getCurrentMatchState() != MatchState.RUNNING
            || MatchManager.Countdown.INSTANCE.isCountdownRunning()
            || NukeSequenceManager.INSTANCE.isRunning()) {
            return attacker == null;
         }
         if (attacker == null) {
            return true;
         }
   
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         Arena arena = worlds == null ? null : worlds.getActive();
         if (!playersAreInActiveArena(attacker, victim)) {
            return false;
         }
   
         boolean isArrowShot = directEntity != null && !directEntity.equals(attacker) && source.is(DamageTypeTags.IS_PROJECTILE);
         boolean isMinigunShot = directEntity instanceof AbstractArrow arrow
            && arrow.getWeaponItem() != null
            && arrow.getWeaponItem().is(ModItems.MINIGUN);
         boolean isSwordHit = !attacker.equals(victim)
            && directEntity == attacker
            && (attacker.getMainHandItem().is(Items.IRON_SWORD) || attacker.getMainHandItem().is(Items.GOLDEN_SWORD));
         if (isMinigunShot && !MinigunRuntime.INSTANCE.recordHit(attacker, victim)) {
            MinigunRuntime.INSTANCE.sendHitEffect(attacker);
            return false;
         }
         if (!isArrowShot && !isSwordHit) {
            return false;
         }

         KillFeed.Cause hitCause = isMinigunShot ? KillFeed.Cause.MINIGUN : isSwordHit ? KillFeed.Cause.SWORD : KillFeed.Cause.BOW;
   
         // Scharf gemachte Pfeile werden in CombatEvents am Einschlag ausgewertet, nicht hier –
         // sonst wirkten sie nur bei einem Direkttreffer.
         if (StatusAbilities.INSTANCE.consumeShield(victim, attacker, hitCause)) {
            return false;
         }
         // Beim Tod wird nur beendet, was ohne lebenden Spieler keinen Sinn ergibt. Ein scharf
         // gemachter Schuss, ein Magnetfeld oder ein aufgestellter Turm bleiben bestehen – wer ein
         // Spezial-Item eingesetzt hat, soll es nicht dadurch verlieren, dass er danach stirbt.
         StatusAbilities.INSTANCE.clearOnDeath(victim);
         ThrownDevices.INSTANCE.excludeFromFields(victim);
         Deployables.INSTANCE.clearOnDeath(victim);

         // Sofortiges Treffer-Feedback am Zielort (Partikel & Sound vor Teleport)
         ServerLevel victimLevel = (ServerLevel) victim.level();
         Vec3 hitPos = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
         if (isSwordHit) {
            victimLevel.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.2F, 1.1F);
            victimLevel.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 1.2F);
            victimLevel.sendParticles(ParticleTypes.CRIT, hitPos.x, hitPos.y, hitPos.z, 15, 0.35, 0.35, 0.35, 0.2);
            victimLevel.sendParticles(ParticleTypes.SWEEP_ATTACK, hitPos.x, hitPos.y, hitPos.z, 1, 0.0, 0.0, 0.0, 0.0);
         } else if (isArrowShot) {
            OsokEffects.INSTANCE.sendPrivateSound(attacker, SoundEvents.ARROW_HIT_PLAYER, 1.2F, 1.2F);
            victimLevel.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 1.0F, 1.2F);
            victimLevel.sendParticles(ParticleTypes.CRIT, hitPos.x, hitPos.y, hitPos.z, 12, 0.3, 0.3, 0.3, 0.15);
            if (directEntity != null) {
               directEntity.discard();
            }
         }

         recordAttackerKill(attacker, victim, hitCause, isMinigunShot);
         recordVictimDeath(attacker, victim, hitCause, arena);
         return false;
      }

      private static void recordAttackerKill(ServerPlayer attacker, ServerPlayer victim, KillFeed.Cause cause, boolean isMinigunShot) {
         if (attacker.equals(victim)) {
            return;
         }
         if (MatchManager.INSTANCE.getCurrentGameMode() == GameMode.GUN_GAME) {
            GunGameManager.INSTANCE.recordKill(attacker, victim, cause);
            ScoreboardManager.INSTANCE.addKill(attacker.getUUID());
         } else {
            int newKills = ScoreboardManager.INSTANCE.addKill(attacker.getUUID());
            if (ScoreboardManager.INSTANCE.claimBounty(victim.getUUID())) {
               SpecialItemManager.INSTANCE.grantBounty(attacker, victim);
            }
            SpecialItemManager.INSTANCE.grantStreakReward(attacker, ScoreboardManager.INSTANCE.addStreak(attacker.getUUID()));
            OsokEffects.INSTANCE.sendPrivateSound(attacker, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.8F);
            if (isMinigunShot) {
               MinigunRuntime.INSTANCE.sendKillEffect(attacker);
            }
            MatchManager.INSTANCE.checkKillLimit(attacker, newKills);
         }
      }

      private static void recordVictimDeath(ServerPlayer attacker, ServerPlayer victim, KillFeed.Cause cause, Arena arena) {
         ScoreboardManager.INSTANCE.addDeath(victim.getUUID());
         ScoreboardManager.INSTANCE.resetStreak(victim.getUUID());
         ScoreboardManager.INSTANCE.updateAllScoreboards();
         KillFeed.kill(attacker, victim, cause);
         RespawnSystem.INSTANCE.respawnInstant(victim, arena, victim.position(), true);
      }

      static ServerPlayer attackerOf(DamageSource source) {
         if (source.getEntity() instanceof ServerPlayer attacker) {
            return attacker;
         }
         if (source.getDirectEntity() instanceof Projectile projectile
            && projectile.getOwner() instanceof ServerPlayer attacker) {
            return attacker;
         }
         return null;
      }

      static boolean playersAreInActiveArena(ServerPlayer attacker, ServerPlayer victim) {
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         Arena arena = worlds == null ? null : worlds.getActive();
         // Die Arena-Dimension bestimmt die Matchteilnahme. Eine zusätzliche Polygonprüfung
         // würde Dächer, Treppen und kurzen Rückstoß über den Rand erneut unverwundbar machen.
         return arena != null && worlds.arenaOf(attacker) == arena && worlds.arenaOf(victim) == arena;
      }
   }

   /**
    * Die Aktionssperre für Spieler im Eiskäfig der Frost-Falle.
    * <p>
    * <p>Gesperrt wird an zwei Stellen, und beide sind Mixins:</p>
    * <p>
    * <ul>
    *   <li><b>Auf dem Client</b> sind Bewegung und Interaktion tot, bevor überhaupt etwas
    *       passiert – {@code KeyboardInputMixin} verwirft die Bewegungseingabe,
    *       {@code MinecraftInteractionMixin} Angriff, Benutzen und Pick-Block. Das ist derselbe
    *       Weg wie beim Countdown zum Match-Start, und er ist der Grund, warum ein
    *       Eingefrorener nicht mehr gegen den Server anläuft.</li>
    *   <li><b>Auf dem Server</b> verwirft {@code ServerGamePacketListenerMixin} die
    *       Interaktionspakete und ruft dafür {@link #blocksAction(ServerPlayer)}. Ein
    *       verworfenes Paket stößt gar nichts erst an; die früheren Fabric-Callbacks griffen
    *       dagegen erst mitten in Vanillas Auswertung.</li>
    * </ul>
    */
   public static final class FrozenPlayerEvents {
      private FrozenPlayerEvents() {
      }

      /**
       * @return {@code true}, wenn dieser Spieler eingefroren ist und die Aktion fallen soll
       */
      public static boolean blocksAction(ServerPlayer player) {
         if (!Deployables.INSTANCE.isFrozen(player)) {
            return false;
         }
         Feedback.actionBar(player, Component.translatable("actionbar.oneshotonekill.frozen_combat"));
         return true;
      }
   }
}
