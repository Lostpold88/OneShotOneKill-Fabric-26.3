package com.oneshotonekill.client.effect;

/**
 * Der Herzschlag der Nuke-Sequenz als Uhr.
 * <p>
 * Die Tonspur {@code endgame/nuke_countdown_bed.ogg} enthält einen Herzschlag, der von 60 auf 150 BPM
 * beschleunigt. Dessen Schläge sind deterministisch und werden von {@code tools/audio/generate_nuke_audio.py}
 * mit genau dieser Formel erzeugt. Hier wird sie nachgerechnet, damit Kamera-Puls, Vignette und HUD auf
 * dem Schlag sitzen, ohne dass der Ton ausgewertet werden müsste. <b>Die Konstanten müssen mit dem
 * Python-Skript übereinstimmen.</b>
 */
public final class HeartbeatClock {
   /** Hier bricht das Bett ab - die Stille vor dem Einschlag. */
   public static final float CUT = 11.30F;
   private static final double BPM_START = 60.0;
   private static final double BPM_END = 150.0;
   private static final double FIRST_BEAT = 0.30;
   private static final double LUB_DECAY = 0.10;

   private static final double[] BEATS = computeBeats();

   private HeartbeatClock() {
   }

   private static double phase(double t) {
      double k = (BPM_END - BPM_START) / 60.0;
      return t * BPM_START / 60.0 + k * Math.pow(t, 2.5) / (2.5 * Math.pow(CUT, 1.5));
   }

   private static double[] computeBeats() {
      double[] buffer = new double[64];
      int count = 0;
      double t = 0.0;
      int k = 0;
      double target = k + FIRST_BEAT;
      while (t < CUT) {
         t += 0.001;
         while (phase(t) >= target && t < CUT) {
            if (count == buffer.length) {
               buffer = java.util.Arrays.copyOf(buffer, count * 2);
            }
            buffer[count++] = t;
            k++;
            target = k + FIRST_BEAT;
         }
      }
      return java.util.Arrays.copyOf(buffer, count);
   }

   /** Herzfrequenz in Schlägen pro Minute zum Zeitpunkt {@code seconds}. */
   public static float bpm(float seconds) {
      double p = Math.clamp(seconds / CUT, 0.0, 1.0);
      return (float) (BPM_START + (BPM_END - BPM_START) * Math.pow(p, 1.5));
   }

   /** Wie viele Schläge bis jetzt (ganze Zahl). */
   public static int beatsSoFar(float seconds) {
      int low = 0;
      int high = BEATS.length;
      while (low < high) {
         int mid = (low + high) >>> 1;
         if (BEATS[mid] <= seconds) {
            low = mid + 1;
         } else {
            high = mid;
         }
      }
      return low;
   }

   /**
    * Schlag-Impuls: 1,0 genau auf dem "Lub", danach abklingend; das "Dub" folgt als kleinerer zweiter Impuls.
    * Null, bevor der erste Schlag kam oder nachdem das Bett abgebrochen ist.
    */
   public static float punch(float seconds) {
      if (seconds > CUT + 0.15F) {
         return 0.0F;
      }
      int index = beatsSoFar(seconds) - 1;
      if (index < 0) {
         return 0.0F;
      }
      double beat = BEATS[index];
      double bpm = bpm((float) beat);
      double gap = Math.min(0.17, 60.0 / bpm * 0.34);
      double dt = seconds - beat;
      double lub = Math.exp(-dt / LUB_DECAY);
      double dub = dt >= gap ? 0.7 * Math.exp(-(dt - gap) / LUB_DECAY) : 0.0;
      return (float) Math.max(lub, dub);
   }
}
