package com.oneshotonekill.shared;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Die blinkenden Anzeigen der Handgeräte.
 *
 * Funkgerät, Peilsender und Gleitflug tragen alle dieselbe Bauart: ein fast weißes
 * Anzeigefeld im Modell, das über {@code tintindex} eingefärbt wird, und ein Muster, das die
 * Farbe Tick für Tick setzt. Drei Kopien derselben acht Zeilen wären der sichere Weg dazu, dass
 * sie irgendwann auseinanderlaufen.
 *
 * <p>Zwei Dinge sind dabei nicht offensichtlich und stehen deshalb hier:</p>
 *
 * <ul>
 *   <li><b>Nur bei echter Änderung setzen.</b> Jeder Wechsel geht als Inventarpaket zum Client.
 *       Die Muster haben lange Ruhephasen, und {@link #set} vergleicht davor.</li>
 *   <li><b>Die Nachgreif-Animation muss abgeschaltet werden.</b> Vanilla vergleicht die beiden
 *       Stapel über die Objektgleichheit. Jede Farbänderung erzeugt clientseitig einen neuen
 *       Stapel und gälte damit als Waffenwechsel – das Gerät würde bei jedem Blinken
 *       heruntergenommen und wieder hochgerissen. Wer ein Gerät hier anschließt, überschreibt
 *       dafür {@code FabricItem#allowComponentsUpdateAnimation} und gibt
 *       {@link #allowsReequipAnimation()} zurück.</li>
 * </ul>
 */
public final class DeviceLights {
   /** Länge des Ruhemusters: zweimal kurz, dann lange Pause. */
   private static final int BEACON_CYCLE = 40;

   private DeviceLights() {
   }

   /**
    * Ruhemuster: zwei kurze Blitze, dann eine lange Pause.
    *
    * Für Geräte, die nur bereitstehen. Ein Dauerblinken zöge über eine ganze Runde mehr
    * Aufmerksamkeit auf sich als das Gerät wert ist.
    */
   public static void beacon(ItemStack device, long gameTime, int lit, int dim) {
      int phase = (int) Math.floorMod(gameTime, BEACON_CYCLE);
      set(device, phase < 2 || (phase >= 5 && phase < 7) ? lit : dim);
   }

   /**
    * Wechselblinken mit fester Taktlänge – für Geräte, die scharf sind.
    *
    * Je kürzer die Taktlänge, desto dringlicher wirkt es; darüber steuern die Aufrufer ihre
    * Dringlichkeit, ohne ein eigenes Muster zu schreiben.
    */
   public static void strobe(ItemStack device, long gameTime, int period, int lit, int dim) {
      int safePeriod = Math.max(2, period);
      set(device, Math.floorMod(gameTime, safePeriod) < Math.max(1, safePeriod / 2) ? lit : dim);
   }

   /** Setzt die Anzeigefarbe, aber nur wenn sie sich wirklich ändert. */
   public static void set(ItemStack device, int colour) {
      int stepped = colour & 0xF8F8F8;
      DyedItemColor current = device.get(DataComponents.DYED_COLOR);
      if (current != null && current.rgb() == stepped) {
         return;
      }
      device.set(DataComponents.DYED_COLOR, new DyedItemColor(stepped));
   }

   /**
    * Antwort für {@code FabricItem#allowComponentsUpdateAnimation}: nie nachgreifen.
    *
    * <p>Siehe den Klassenkommentar – ohne das ruckelt jedes blinkende Gerät in der Hand. Unter
    * NeoForge musste dieselbe Antwort noch den Slot- und den Itemwechsel von der reinen
    * Komponentenänderung unterscheiden. Fabric fragt gar nicht erst danach: Der Rückruf läuft
    * nur, wenn Hand und Gegenstand gleich geblieben sind, es also wirklich nur die Farbe war.</p>
    */
   public static boolean allowsReequipAnimation() {
      return false;
   }
}
