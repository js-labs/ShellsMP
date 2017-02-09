precision mediump float;
uniform sampler2D u_sTextureUnit;
varying vec2 v_v2TextureMap;

void main()
{
    //gl_FragColor = vec4(v_v2TextureMap.x, v_v2TextureMap.y, 0.0, 1.0);
    if ((abs(v_v2TextureMap.x - 0.5) < 0.002) || (abs(v_v2TextureMap.y - 0.5) < 0.002))
        gl_FragColor = vec4(0.5, 0.0, 0.0, 1.0);
    else
        gl_FragColor = texture2D(u_sTextureUnit, v_v2TextureMap);
}
