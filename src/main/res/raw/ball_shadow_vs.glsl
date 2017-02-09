uniform mat4 u_m4Matrix;
attribute vec4 a_v4Position;
varying vec2 v_v2ModelPosition;
varying vec4 v_v4Position;

void main()
{
    v_v2ModelPosition = a_v4Position.xy;
    vec4 pos = (u_m4Matrix * a_v4Position);
    v_v4Position = pos;
    gl_Position = pos;
}
