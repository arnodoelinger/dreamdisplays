#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:fog.glsl>

uniform sampler2DRect Sampler0;
uniform sampler2DRect Sampler1;

layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 ySize = vec2(textureSize(Sampler0));
    vec2 p = texCoord0 * ySize;

    float y = (texture(Sampler0, p).r - 0.0625) * 1.164384;
    vec2 uv = texture(Sampler1, p * 0.5).rg - vec2(0.5);
    vec3 rgb = clamp(vec3(
        y + 1.792741 * uv.y,
        y - 0.213249 * uv.x - 0.532909 * uv.y,
        y + 2.112402 * uv.x
    ), 0.0, 1.0);

    vec4 color = vec4(rgb * vertexColor.rgb, vertexColor.a) * ColorModulator;
    float fogValue = linear_fog_value(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd);
    fragColor = vec4(color.rgb, color.a * (1.0 - fogValue));
}
