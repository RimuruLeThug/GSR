#version 120
uniform sampler2D gtexture;
uniform sampler2D lightmap;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;

/* DRAWBUFFERS:02 */
void main() {
    vec4 c = texture2D(gtexture, texcoord) * glcolor;
    c.rgb *= texture2D(lightmap, lmcoord).rgb;
    gl_FragData[0] = c;
    gl_FragData[1] = vec4(1.0, lmcoord.y, 0.0, 1.0);   // r = water, g = sky light
}
