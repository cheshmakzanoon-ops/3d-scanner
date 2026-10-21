#version 300 es
precision highp float;
uniform sampler2D uAtlas;
uniform float uCutoff;
in vec2 vUv;
out vec4 result;
void main() {
vec4 color = texture(uAtlas, vUv);
if (color.a < uCutoff) discard;
result = vec4(color.rgb, 1.0);
}