#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform TemporalConfig {
    float Strength;
    float ChromaticOffset;
    float WarpDirection;
    float WaveCenter;
    float Warmth;
};

out vec4 fragColor;

void main() {
    vec2 centered = texCoord - vec2(0.5);
    float radius = length(centered);
    vec2 direction = centered / max(radius, 0.001);
    vec2 tangent = vec2(-direction.y, direction.x);
    float envelope = 1.0 - smoothstep(0.05, 0.78, radius);

    // Radialer Sog plus ein kleiner tangentialer Riss: Beim Start zieht WarpDirection das Bild
    // nach innen, beim Wiederanlaufen kehrt die Konfiguration beide Bewegungen um.
    float ripple = sin((radius - WaveCenter) * 48.0) * exp(-pow((radius - WaveCenter) / 0.24, 2.0));
    float twist = sin(radius * 31.0 + WaveCenter * 8.0) * envelope;
    vec2 displacement = direction * ripple * WarpDirection * Strength * 0.026
        + tangent * twist * WarpDirection * Strength * 0.006;
    vec2 warped = clamp(texCoord + displacement, vec2(0.001), vec2(0.999));
    vec2 chroma = direction * ChromaticOffset * WarpDirection * (0.25 + envelope * 0.75);

    float red = texture(InSampler, clamp(warped + chroma, vec2(0.001), vec2(0.999))).r;
    float green = texture(InSampler, warped).g;
    float blue = texture(InSampler, clamp(warped - chroma, vec2(0.001), vec2(0.999))).b;
    vec3 split = vec3(red, green, blue);

    vec2 echoNearUv = clamp((warped - 0.5) * (1.0 - WarpDirection * Strength * 0.024) + 0.5, vec2(0.001), vec2(0.999));
    vec2 echoFarUv = clamp((warped - 0.5) * (1.0 + WarpDirection * Strength * 0.034) + 0.5, vec2(0.001), vec2(0.999));
    vec3 echoes = (texture(InSampler, echoNearUv).rgb + texture(InSampler, echoFarUv).rgb) * 0.5;
    vec3 colour = mix(split, echoes, Strength * 0.20);

    float shockRing = exp(-pow((radius - WaveCenter) / 0.075, 2.0)) * Strength;
    float edge = smoothstep(0.20, 0.78, radius) * Strength;
    float angle = atan(centered.y, centered.x);
    float fractures = pow(abs(sin(angle * 6.0 + radius * 19.0)), 18.0) * envelope * Strength;
    vec3 coldEnergy = vec3(0.18, 0.72, 1.00);
    vec3 warmEnergy = vec3(1.00, 0.72, 0.24);
    vec3 energy = mix(coldEnergy, warmEnergy, Warmth);
    colour += energy * shockRing * 0.24;
    colour += mix(vec3(0.38, 0.12, 0.82), energy, 0.55) * fractures * 0.075;
    vec3 edgeTint = mix(vec3(0.72, 0.55, 1.12), vec3(1.10, 0.80, 0.52), Warmth);
    colour = mix(colour, colour * edgeTint, edge * 0.22);
    fragColor = vec4(colour, 1.0);
}
