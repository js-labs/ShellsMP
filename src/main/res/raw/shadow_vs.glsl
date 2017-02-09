uniform mat4 u_m4Matrix;
attribute vec4 a_v4Position;
varying vec4 v_v4Position;

void main()
{
    vec4 pos = (u_m4Matrix * a_v4Position);
    v_v4Position = pos;
    gl_Position = pos;
}
