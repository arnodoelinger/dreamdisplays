#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// I420 planes: Y at full resolution, U / V at half. Sampler2 is the vanilla lightmap
// (consumed by the vertex stage); the names must match the pipeline's sampler list.
uniform sampler2D Sampler0; // Y
uniform sampler2D Sampler1; // U
uniform sampler2D Sampler3; // V

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    // BT.709 limited range; the FFmpeg filter chain pins the stream to bt709
    float y = (texture(Sampler0, texCoord0).r - 0.0625) * 1.164384;
    float u = texture(Sampler1, texCoord0).r - 0.5;
    float v = texture(Sampler3, texCoord0).r - 0.5;
    vec3 rgb = clamp(vec3(
        y + 1.792741 * v,
        y - 0.213249 * u - 0.532909 * v,
        y + 2.112402 * u
    ), 0.0, 1.0);

    // Brightness (0..2) arrives halved in the vertex color so it fits a normalized byte
    vec4 color = vec4(rgb * vertexColor.rgb * 2.0, vertexColor.a) * ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
