precision mediump float;

uniform sampler2D u_sTextureUnit;
varying vec2 v_v2TextureMap;

void main()
{
    gl_FragColor = texture2D(u_sTextureUnit, v_v2TextureMap);
}
