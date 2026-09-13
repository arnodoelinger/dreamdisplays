#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler3;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    float y = (texture(Sampler0, texCoord0).r - 0.0625) * 1.164384;
    float u = texture(Sampler1, texCoord0).r - 0.5;
    float v = texture(Sampler3, texCoord0).r - 0.5;
    vec3 rgb = clamp(vec3(
        y + 1.792741 * v,
        y - 0.213249 * u - 0.532909 * v,
        y + 2.112402 * u
    ), 0.0, 1.0);

    vec4 color = vec4(rgb * vertexColor.rgb, vertexColor.a) * ColorModulator;

    float fogValue = total_fog_value(
        sphericalVertexDistance,
        cylindricalVertexDistance,
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd
    ) * FogColor.a;
    fragColor = vec4(color.rgb, color.a * (1.0 - fogValue));
}
