uniform mat4 u_m4Matrix;
attribute vec4 a_v4Position;
attribute vec3 a_v3Normal;
varying vec4 v_v4Position;
varying vec3 v_v3Normal;

void main()
{
    v_v4Position = a_v4Position;
    v_v3Normal = a_v3Normal;
    gl_Position = (u_m4Matrix * a_v4Position);
}
