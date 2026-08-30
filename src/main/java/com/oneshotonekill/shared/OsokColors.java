package com.oneshotonekill.shared;

/**
 * Die Akzentfarben der Mod.
 * <p>
 * <p>Sie standen zusammen mit dem restlichen Farbsatz in {@code client/screen/OsokWidgets}.
 * Auf Fabric geht das nicht mehr: {@code src/client/java} wird auf einem dedizierten Server
 * nicht geladen, und {@link com.oneshotonekill.shared.OsokEffects} – Serverseite – braucht
 * dieselben Werte, weil es die Akzentfarbe einer Einblendung im Paket mitschickt.</p>
 * <p>
 * <p>Deshalb steht hier nur, was beide Seiten gemeinsam kennen müssen. Alles Übrige – Karten,
 * Ränder, Textstufen – bleibt in {@code OsokWidgets}, weil es nur gezeichnet und nie
 * verschickt wird.</p>
 */
public final class OsokColors {
   public static final int GOLD = 0xFFFFD700;
   public static final int CYAN = 0xFF00F0FF;
   public static final int EMERALD = 0xFF10B981;
   public static final int CRIMSON = 0xFFFF3366;
   public static final int AMBER = 0xFFF59E0B;
   public static final int PURPLE = 0xFFA855F7;

   private OsokColors() {
   }
}
