uniform mat4 u_m4Matrix;
attribute vec4 a_v4Position;
varying vec2 v2Position;

void main()
{
    v2Position = a_v4Position.xy;
    gl_Position = u_m4Matrix * a_v4Position;
}
