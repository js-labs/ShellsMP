precision mediump float;

uniform sampler2D u_TexUnit;
uniform vec4 u_v4Color;
varying vec2 texCoord;

void main()
{
    gl_FragColor = texture2D(u_TexUnit, texCoord) * u_v4Color;
}
