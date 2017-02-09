uniform mat4 u_m4Matrix;
attribute vec4 a_v4Position;
varying vec2 v_v2TextureMap;

void main()
{
    v_v2TextureMap = vec2(a_v4Position.x + 1.0, a_v4Position.y + 1.0)/2.0;
    gl_Position = u_m4Matrix * a_v4Position;
}
