#version 300 es
precision highp float;
uniform sampler2D uAtlas;
uniform float uCutoff;
uniform bool uEncodeOutput;
in vec2 vUv;
out vec4 result;
vec3 encodeSrgb(vec3 c) {
return mix(1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055,
12.92 * c, lessThanEqual(c, vec3(0.0031308)));
}
void main() {
vec4 color = texture(uAtlas, vUv);
if (color.a < uCutoff) discard;
result = vec4(uEncodeOutput ? encodeSrgb(color.rgb) : color.rgb, 1.0);
}