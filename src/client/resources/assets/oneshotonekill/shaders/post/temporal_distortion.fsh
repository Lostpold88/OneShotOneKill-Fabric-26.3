#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;
uniform sampler2D HistorySampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 InDepthSize;
    vec2 HistorySize;
};

layout(std140) uniform TemporalConfig {
    float Strength;
    float Chroma;
    float WarpDirection;
    float Warmth;
    float Atmosphere;
    float HistoryMix;
    float Pulse;
    float Restoring;
    vec2 Origin;
    float OriginFocus;
    float Elapsed;
    vec2 RingRadius;
    vec2 RingEnergy;
    float HistoryAlpha;
    float RadialBlur;
    float Pinch;
    float SweepAngle;
    float SweepEnergy;
    float TrailLength;
    float Grain;
    float RimGain;
};

layout(location = 0) out vec4 fragColor;

const vec2 UV_LO = vec2(0.0015);
const vec2 UV_HI = vec2(0.9985);
const float TAU = 6.28318531;
const float PI = 3.14159265;
// Trennschaerfe der Bewegungsmaske; war als Uniform stets derselbe Wert.
const float MOTION_GAIN = 4.5;

// Ohne pow: pow(x, 2.0) ist laut GLSL-Spezifikation fuer x < 0 undefiniert, und
// radius - RingRadius ist innerhalb jedes Rings negativ. Manche Treiber liefern dort NaN.
float gaussian(float value, float width) {
    float x = value / max(width, 1.0e-4);
    return exp(-x * x);
}

// Ableitung der Glocke: ungerade um den Ringmittelpunkt. Als Verschiebung entlang der Wellen-
// richtung ergibt das eine echte Linse - innen vergroessert, aussen gestaucht.
float gaussianSlope(float value, float width) {
    float x = value / max(width, 1.0e-4);
    return x * exp(-x * x);
}

float hash(vec2 seed) {
    return fract(sin(dot(seed, vec2(12.9898, 78.233))) * 43758.5453);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Die Tiefe des Haupt-Targets traegt an dieser Stelle nur noch die Hand: GameRenderer leert den
// Tiefenpuffer unmittelbar vor dem Handdurchgang auf 0.0 und zeichnet den gehaltenen Gegenstand
// danach mit einer eigenen Projektion hinein. Ein Wert ueber null heisst also "Hand", ganz ohne
// Linearisierung und unabhaengig davon, ob das Geraet Z von 0..1 oder von -1..1 abbildet.
// Fuenf Abtastungen bilden eine um ein Texel geweitete, weiche Maske; so reisst die Verzerrung
// nicht an der Silhouette der Waffe auf.
float handMask() {
    // Sicherung: Der gehaltene Gegenstand kann niemals alle vier Bildecken zugleich bedecken.
    // Sind sie trotzdem alle belegt, traegt die Tiefe nicht das hier Erwartete. Dann bleibt die
    // Maske aus und der Effekt verhaelt sich wie ohne Tiefenpuffer, statt die Verzerrung des
    // ganzen Bildes zu schlucken. Die vier Abtastungen treffen fuer jedes Fragment dieselben
    // Texel und kosten deshalb praktisch nichts.
    float corners = step(1.0e-5, texture(InDepthSampler, vec2(0.002, 0.002)).r)
        + step(1.0e-5, texture(InDepthSampler, vec2(0.998, 0.002)).r)
        + step(1.0e-5, texture(InDepthSampler, vec2(0.002, 0.998)).r)
        + step(1.0e-5, texture(InDepthSampler, vec2(0.998, 0.998)).r);
    if (corners > 3.5) {
        return 0.0;
    }

    vec2 texel = 1.0 / max(InDepthSize, vec2(1.0));
    float covered = step(1.0e-5, texture(InDepthSampler, texCoord).r)
        + step(1.0e-5, texture(InDepthSampler, texCoord + vec2(texel.x, 0.0)).r)
        + step(1.0e-5, texture(InDepthSampler, texCoord - vec2(texel.x, 0.0)).r)
        + step(1.0e-5, texture(InDepthSampler, texCoord + vec2(0.0, texel.y)).r)
        + step(1.0e-5, texture(InDepthSampler, texCoord - vec2(0.0, texel.y)).r);
    return covered * 0.2;
}

void main() {
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 fromOrigin = texCoord - Origin;
    vec2 metric = vec2(fromOrigin.x * aspect, fromOrigin.y);
    float radius = length(metric);
    vec2 metricDirection = metric / max(radius, 1.0e-3);
    vec2 direction = vec2(metricDirection.x / aspect, metricDirection.y);
    vec2 tangent = vec2(-direction.y / aspect, direction.x * aspect);
    float envelope = 1.0 - smoothstep(0.04, 0.94, radius);
    // atan ist im Zentrum instabil; alles Winkelabhaengige muss dort ausblenden.
    float angularFade = smoothstep(0.015, 0.14, radius);
    float angle = atan(metric.y, metric.x);
    float hand = handMask();
    float world = 1.0 - hand;

    // Zwei ueberlappende Druckwellen. Jede wird mit null Energie geboren und stirbt mit null,
    // deshalb gibt es keinen Ruecksprung im Sekundentakt mehr.
    float ringWidth = 0.052 + Atmosphere * 0.015;
    float shockRing = gaussian(radius - RingRadius.x, ringWidth) * RingEnergy.x
        + gaussian(radius - RingRadius.y, ringWidth) * RingEnergy.y;
    float wake = sin((radius - RingRadius.x) * 53.0 - Elapsed * 5.0)
            * gaussian(radius - RingRadius.x, 0.22) * RingEnergy.x
        + sin((radius - RingRadius.y) * 53.0 - Elapsed * 5.0)
            * gaussian(radius - RingRadius.y, 0.22) * RingEnergy.y;

    // Der Zeiger. Eine Umdrehung je Sekunde, scharfe Vorderkante und langer Nachlauf - das ist
    // dieselbe Sekunde, die der Puls hoerbar macht, nur sichtbar ueber das ganze Bild gezogen.
    float sweepDelta = mod(angle - SweepAngle + PI, TAU) - PI;
    float sweepFalloff = mix(3.0, 15.0, step(0.0, sweepDelta));
    float sweep = exp(-abs(sweepDelta) * sweepFalloff) * SweepEnergy * envelope * angularFade;

    // Nur fuer die getragene Phase. Beim Rueckschlag herausgenommen, sonst wirbelt das Bild,
    // statt entlang der Risse auseinanderzufallen.
    float twist = sin(radius * 34.0 - Elapsed * 1.8) * envelope * (1.0 - Restoring);
    float idleRipple = sin(radius * 46.0 - Elapsed * 4.2) * Atmosphere * 0.0012;
    float focus = mix(0.55, 1.0, clamp(OriginFocus, 0.0, 1.0));

    // Linsenzug: Beim Start wird das ganze Bild in den Ursprung gesogen, beim Rueckschlag wieder
    // herausgedrueckt. Erst dadurch wirkt der Ausbruch raeumlich statt wie ein Filter auf Glas.
    float pinchShape = radius * (1.0 - smoothstep(0.0, 1.05, radius));
    vec2 baseUv = texCoord - direction * Pinch * pinchShape * world;

    // Druckwellen als Linse: die Welle bricht das Bild an ihrer Front sichtbar.
    float lens = gaussianSlope(radius - RingRadius.x, ringWidth) * RingEnergy.x
        + gaussianSlope(radius - RingRadius.y, ringWidth) * RingEnergy.y;

    // Zeitstottern: auf jedem Sekundenschlag reissen einzelne Zeilenbaender seitlich weg.
    float glitchBand = floor(texCoord.y * 46.0);
    float glitchStep = floor(Elapsed * 12.0);
    float glitchGate = step(0.80, hash(vec2(glitchBand, glitchStep)));
    float glitch = (hash(vec2(glitchBand + 17.0, glitchStep)) - 0.5) * 0.045
        * Pulse * Pulse * Atmosphere * glitchGate;

    vec2 displacement = direction * wake * WarpDirection * Strength * 0.023 * focus
        + tangent * twist * WarpDirection * Strength * 0.0055
        + tangent * sweep * 0.0045
        + direction * lens * 0.040 * (0.35 + 0.65 * min(Strength, 1.0))
        + vec2(glitch, 0.0)
        + direction * idleRipple;
    vec2 warped = clamp(baseUv + displacement * world, UV_LO, UV_HI);

    // Spektrale Brechung; die Hand bleibt ausgenommen, damit die Waffe lesbar bleibt.
    vec2 chroma = direction * Chroma * WarpDirection * (0.35 + envelope * 0.65) * focus * world;
    vec3 colour = vec3(
        texture(InSampler, clamp(warped + chroma, UV_LO, UV_HI)).r,
        texture(InSampler, warped).g,
        texture(InSampler, clamp(warped - chroma, UV_LO, UV_HI)).b);

    // Radiale Schlieren entlang der Wellenrichtung. Bei RadialBlur = 0 liegen alle Abtastungen
    // auf demselben Punkt und das Gewicht ist null, deshalb ohne Verzweigung.
    float blur = RadialBlur * envelope * world;
    vec3 smear = colour;
    for (int i = 1; i <= 4; i++) {
        vec2 offset = direction * blur * (float(i) * 0.25) * WarpDirection;
        smear += texture(InSampler, clamp(warped - offset, UV_LO, UV_HI)).rgb;
    }
    colour = mix(colour, smear * 0.2, clamp(blur * 26.0, 0.0, 0.85));

    // Kometenschweif aus dem gemittelten Verlaufspuffer: drei Abtastungen entlang der
    // Wellenrichtung, deren Faerbung mit dem Alter von Cyan ueber Blau nach Violett auskuehlt.
    // Eine einzelne Abtastung ergaebe nur einen Farbsaum, kein Nachziehen.
    vec2 historyDrift = direction * (0.0018 + Strength * 0.0032) * WarpDirection * world;
    vec3 echo = vec3(0.0);
    vec3 nearHistory = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        float age = float(i + 1) / 3.0;
        vec2 uv = clamp(warped + historyDrift * age * TrailLength, UV_LO, UV_HI);
        vec3 sampled = texture(HistorySampler, uv).rgb;
        if (i == 0) {
            nearHistory = sampled;
        }
        vec3 tint = mix(vec3(0.72, 0.98, 1.18), vec3(1.12, 0.66, 1.22), age);
        echo += sampled * tint * (1.0 - age * 0.45);
    }
    echo /= 2.10;

    // Der Unterschied zum aktuellen Bild maskiert die Beimischung: ruhende Waende bleiben scharf,
    // nur Bewegtes zieht eine Fahne.
    float motion = clamp(length(colour - nearHistory) * MOTION_GAIN, 0.0, 1.0);
    motion = motion * motion * (3.0 - 2.0 * motion);
    vec3 trail = 1.0 - (1.0 - colour) * (1.0 - clamp(echo, 0.0, 1.0) * 0.55);
    colour = mix(colour, trail, clamp(HistoryMix, 0.0, 1.0) * motion * (1.0 - hand * 0.85));

    vec3 coldEnergy = vec3(0.24, 0.66, 1.00);
    vec3 warmEnergy = vec3(1.00, 0.72, 0.28);
    vec3 energy = mix(coldEnergy, warmEnergy, Warmth);

    // Bewegungssilhouette. Die Ableitung der Bewegungsmaske ueber den Bildschirm ist genau dort
    // gross, wo etwas Bewegtes aufhoert - also am Umriss. Das kostet eine Instruktion und keine
    // einzige zusaetzliche Abtastung und hebt gegnerische Spieler im Zeitbruch klar heraus.
    float motionEdge = clamp(fwidth(motion) * 3.5, 0.0, 1.0);
    colour += energy * motionEdge * RimGain * world;

    // Bloom: helle Bildteile strahlen in zwei Ringen aus je acht Abtastungen aus. Die Schwelle
    // laesst Himmel, Feuer und Magie leuchten, ohne das ganze Bild zu verwaschen.
    vec3 bloom = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * (TAU / 8.0);
        vec2 dir = vec2(cos(a) / aspect, sin(a));
        bloom += max(texture(InSampler, clamp(warped + dir * 0.008, UV_LO, UV_HI)).rgb - 0.58, 0.0);
        bloom += max(texture(InSampler, clamp(warped + dir * 0.021, UV_LO, UV_HI)).rgb - 0.58, 0.0) * 0.7;
    }
    colour += bloom * energy * (0.22 + 0.38 * Pulse) * Atmosphere * world;

    // Daueratmosphaere: kalte Entsaettigung, Randflimmern und ein schwacher Sekundentakt.
    // Bewegtes behaelt seine Farbe und hebt sich so aus der grauen, erstarrten Welt ab.
    float luminance = dot(colour, vec3(0.2126, 0.7152, 0.0722));
    vec3 coldGrade = mix(vec3(luminance), colour, mix(0.46, 1.0, motion)) * vec3(0.88, 0.98, 1.12);
    colour = mix(colour, coldGrade, Atmosphere * (1.0 - Warmth) * 0.62);
    // Leichte S-Kurve: tiefere Schatten, hellere Lichter.
    vec3 clamped = clamp(colour, 0.0, 1.0);
    colour = mix(colour, clamped * clamped * (3.0 - 2.0 * clamped), Atmosphere * 0.28);
    float edge = smoothstep(0.24, 0.76, length(texCoord - vec2(0.5)));
    // Feste Zyklenzahl ueber die Bildhoehe statt einer Kopplung an die Pixelzahl: sonst liegt das
    // Muster nahe der Nyquist-Grenze und wird bei 4K zu Moire, bei 720p zu grobem Banding.
    float edgeFlicker = 0.5 + 0.5 * sin((texCoord.y + Elapsed * 0.19) * 220.0);
    colour *= 1.0 - edge * Atmosphere * (0.11 + edgeFlicker * 0.03);
    colour += vec3(0.08, 0.42, 0.72) * shockRing * (0.16 + Strength * 0.16);
    colour += energy * sweep * 0.055;

    // Lichtstrahlen aus dem Zeitbruch: Rauschen ueber die Richtung (nicht den Winkel), damit an
    // der Naht bei +-PI nichts reisst. Auf dem Sekundenschlag und im Ausbruch gleissend hell.
    float rayField = vnoise(metricDirection * 5.0 + vec2(Elapsed * 0.5, -Elapsed * 0.3)) * 0.6
        + vnoise(metricDirection * 14.0 + vec2(-Elapsed * 0.9, Elapsed * 0.7)) * 0.4;
    float rays = smoothstep(0.50, 0.92, rayField)
        * (1.0 - smoothstep(0.05, 0.80, radius)) * smoothstep(0.03, 0.22, radius);
    colour += energy * rays * Atmosphere * world * (0.05 + 0.13 * Pulse + 0.16 * min(Strength, 1.3));

    // Zifferblatt: ein geisterhafter Ring aus 60 Strichen um die Bildmitte, die Fuenfer kraeftiger.
    // Der Zeiger leuchtet die Striche beim Vorbeilaufen auf. Er liegt auf der Bildmitte und nicht
    // auf dem Ursprung, damit der Ring nach dem Ausbruch ruhig stehen bleibt.
    vec2 centred = (texCoord - vec2(0.5)) * vec2(aspect, 1.0);
    float clockRadius = length(centred);
    float clockAngle = atan(centred.y, centred.x);
    float tickCoord = clockAngle / TAU * 60.0;
    float tickIndex = floor(tickCoord + 0.5);
    float major = 1.0 - step(0.5, mod(tickIndex + 30.0, 5.0));
    float tickLength = mix(0.013, 0.032, major);
    float tickMask = (1.0 - smoothstep(0.035, 0.075, abs(tickCoord - tickIndex)))
        * smoothstep(0.405 - tickLength - 0.002, 0.405 - tickLength, clockRadius)
        * (1.0 - smoothstep(0.405, 0.407, clockRadius));
    float clockDelta = mod(clockAngle - SweepAngle + PI, TAU) - PI;
    float handGlow = exp(-abs(clockDelta) * mix(2.5, 22.0, step(0.0, clockDelta)));
    float clockRing = gaussian(clockRadius - 0.407, 0.0014) * (0.35 + 0.65 * handGlow);
    colour += energy * (tickMask * (0.20 + 0.85 * handGlow) + clockRing * 0.55)
        * SweepEnergy * world * (1.0 - Restoring);

    // Zeitrisse: hinter der Front der Druckwelle bricht das Bild an gluehenden Bruchlinien auf.
    // Zwei Rauschlagen ergeben verzweigte Linien; Staerke und Ringenergie legen fest, wie lange.
    vec2 crackSpace = texCoord * vec2(aspect, 1.0);
    float crackA = abs(vnoise(crackSpace * 6.5 + Origin * 3.0) - 0.5);
    float crackB = abs(vnoise(crackSpace * 14.0 - Origin * 5.0 + 11.0) - 0.5) * 1.9;
    float crackLine = 1.0 - smoothstep(0.0, 0.020, min(crackA, crackB));
    float crackInside = 1.0 - smoothstep(RingRadius.x - 0.12, RingRadius.x, radius);
    float crackEnergy = smoothstep(0.30, 1.0, Strength) * RingEnergy.x * crackInside;
    colour += mix(energy, vec3(1.0), 0.55) * crackLine * crackEnergy * 1.1 * world;
    // Beim Ende wird die kalte Welt in einer warmen Welle zurueck in die Gegenwart gezogen.
    vec3 warmGrade = colour * vec3(1.09, 1.015, 0.91) + warmEnergy * shockRing * 0.20;
    colour = mix(colour, warmGrade, Warmth * (0.18 + Restoring * 0.20));
    float snapFlash = Restoring * Pulse * gaussian(radius - RingRadius.x, 0.09);
    colour += warmEnergy * snapFlash * 0.22;

    // Feines, wanderndes Korn gibt der eingefrorenen Zeit Textur; der statische Dither daneben
    // haelt die flachen Verlaeufe im RGBA8-Ziel frei von sichtbaren Stufen.
    float grain = hash(gl_FragCoord.xy + vec2(Elapsed * 91.0, Elapsed * 57.0));
    colour += (grain - 0.5) * Grain;
    colour += (hash(gl_FragCoord.xy) - 0.5) / 255.0;

    fragColor = vec4(colour, 1.0);
}
