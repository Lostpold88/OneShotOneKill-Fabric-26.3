package com.oneshotonekill.entity;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Ein Item-Display, das der Server vor seiner Enthüllung nur an seinen Besitzer überträgt.
 *
 * <p>Das ist echte serverseitige Sichtbarkeit: Gegner erhalten keine Spawn- oder
 * Aktualisierungspakete für die Entity. Transparenz oder ein unsichtbares Modell würden die
 * Falle weiterhin über Debug-Anzeigen und andere Clientzustände verraten.</p>
 */
public final class OwnerVisibleItemDisplay extends Display.ItemDisplay {
   private UUID owner;

   public OwnerVisibleItemDisplay(EntityType<?> type, Level level) {
      super(type, level);
   }

   public void setOwner(UUID owner) {
      this.owner = owner;
   }

   @Override
   public boolean broadcastToPlayer(ServerPlayer player) {
      return owner != null && owner.equals(player.getUUID());
   }

   /**
    * Als Passagier sitzt ein Display in der Mitte seines Fahrzeugs statt auf dessen Kopf.
    * Die Frost-Falle fährt nie mit; für die Schildkugel ergibt das dagegen eine verzögerungsfreie
    * Bindung an die lokale Spielerbewegung, ohne Positionspaket pro Tick.
    */
   @Override
   public Vec3 getVehicleAttachmentPoint(Entity vehicle) {
      return new Vec3(0.0, vehicle.getBbHeight() * 0.5, 0.0);
   }
}
