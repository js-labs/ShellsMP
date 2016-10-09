uniform mat4 u_m4Matrix;
uniform vec3 u_v3LightPosition;
uniform vec4 u_v4Color;
attribute vec4 a_v4Position;
attribute vec3 a_v3Normal;
varying vec4 v_v4Color;

void main()
{
    vec3 v3Position = vec3( a_v4Position );
    vec3 lightVector = normalize( u_v3LightPosition - v3Position );
    float diffuse = 0.7 + 0.3 * max( dot(a_v3Normal, lightVector), 0.0 );
    v_v4Color = u_v4Color * diffuse;
    gl_Position = u_m4Matrix * a_v4Position;
}
