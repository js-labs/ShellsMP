package org.jsl.shmp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class TestActivity extends Activity
{
    private static final String LOG_TAG = TestActivity.class.getSimpleName();

    public static class ShadowDrawer
    {
        private final int m_programId;
        private final int m_matrixLocation;
        private final int m_positionLocation;
        private final int m_textureUnitLocation;
        private final FloatBuffer m_vertexData;

        public ShadowDrawer(Context context) throws IOException
        {
            final int vertexShaderId = Canvas3D.createShader(context, R.raw.shadow_dr_vs, GLES20.GL_VERTEX_SHADER);
            final int fragmentShaderId = Canvas3D.createShader(context, R.raw.shadow_dr_fs, GLES20.GL_FRAGMENT_SHADER);

            m_programId = GLES20.glCreateProgram();
            GLES20.glAttachShader(m_programId, vertexShaderId);
            GLES20.glAttachShader(m_programId, fragmentShaderId);
            GLES20.glLinkProgram(m_programId);

            final int [] linkStatus = new int[1];
            GLES20.glGetProgramiv(m_programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0)
                throw new IOException();

            m_matrixLocation = GLES20.glGetUniformLocation(m_programId, "u_m4Matrix");
            if (m_matrixLocation < 0)
                throw new IOException();
            m_positionLocation = GLES20.glGetAttribLocation(m_programId, "a_v4Position");
            if (m_positionLocation < 0)
                throw new IOException();

            m_textureUnitLocation = GLES20.glGetUniformLocation(m_programId, "u_sTextureUnit");
            if (m_textureUnitLocation < 0)
                throw new IOException();

            final int bufferCapacity = (Float.SIZE/Byte.SIZE) * (2 * 4);
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( bufferCapacity );
            byteBuffer.order( ByteOrder.nativeOrder() );
            m_vertexData = byteBuffer.asFloatBuffer();
            final float [] vertices =
            {
                -1f, -1f,
                -1f,  1f,
                 1f, -1f,
                 1f,  1f
            };
            m_vertexData.put(vertices);
            m_vertexData.position(0);
        }

        public void draw(float [] vpMatrix, int vpMatrixOffset, int shadowMapSize, int textureId)
        {
            final float [] tmp = new float[16*3];

            Matrix.setIdentityM(tmp, 0);
            Matrix.scaleM(tmp, 0, shadowMapSize/2, shadowMapSize/2, shadowMapSize/2);
            Matrix.multiplyMM(tmp, 16, vpMatrix, vpMatrixOffset, tmp, 0);

            GLES20.glUseProgram(m_programId);
            GLES20.glUniformMatrix4fv(m_matrixLocation, 1, false, tmp, 16);
            GLES20.glVertexAttribPointer(m_positionLocation, 2, GLES20.GL_FLOAT, false, 0, m_vertexData);
            GLES20.glEnableVertexAttribArray(m_positionLocation);

            if (m_textureUnitLocation != 0)
            {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
                GLES20.glUniform1i(m_textureUnitLocation, 0);
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glUseProgram(0);
        }
    }

    private class TestView extends GLSurfaceView implements GLSurfaceView.Renderer
    {
        private ShadowObject m_shadowObject;
        private Vector m_light;
        private float [] m_shadowMatrix;
        private int m_viewWidth;
        private int m_viewHeight;
        private Table m_table;
        private GameClientView.Ball m_ball;
        private GameClientView.Cup m_cup;

        public TestView(Context context)
        {
            super(context);
            setEGLContextClientVersion(2);
            setRenderer(this);
            setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        }

        public void onSurfaceCreated(GL10 notUsed, EGLConfig config)
        {
            Log.d( LOG_TAG, "onSurfaceCreated" );
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }

        public void onSurfaceChanged(GL10 gl, int width, int height)
        {
            Log.d(LOG_TAG, "onSurfaceChanged: width=" + width + " height=" + height);
            GLES20.glViewport(0, 0, width, height);

            /********/

            final float [] light = new float [] {
                    /*x*/ -width/2,
                    /*y*/ width/2,
                    /*z*/ width,
                    /*w*/ 0f,
                    /*r*/ 1f,
                    /*g*/ 1f,
                    /*b*/ 1f };

            m_light = new Vector(light, 0);
            m_viewWidth = width;
            m_viewHeight = height;

            final float [] tmp = new float[16*4];
            m_shadowObject = ShadowObject.create(width, m_light, tmp);

            final Context context = getContext();
            try
            {
                m_table = new Table(context);
                m_ball = new GameClientView.Ball(context, Color.GREEN);
                final ModelCup modelCup = new ModelCup(context, 8);
                m_cup = new GameClientView.Cup(modelCup);
            }
            catch (final IOException ex)
            {
                Log.e(LOG_TAG, ex.toString(), ex);
            }
        }

        private void initShadow(float [] tmp)
        {
            Vector.set(tmp, 0, 0f, 0f, 1f, 0f);
            Vector.crossProduct(tmp, 4, m_light.v, 0, tmp, 0);
            Vector.crossProduct(tmp, 0, tmp, 4, m_light.v, 0);

            final float ll = Vector.length(m_light.v, 0);

            final int shadowMapSize = m_shadowObject.mapSize;
            Matrix.frustumM(tmp, 4,
                /*left*/   -shadowMapSize / 2,
                /*right*/   shadowMapSize / 2,
                /*bottom*/ -shadowMapSize / 2,
                /*top*/     shadowMapSize / 2,
                /*near*/    ll - shadowMapSize / 2,
                /*far*/     ll + shadowMapSize / 2);

            Matrix.setLookAtM(tmp, 20,
              /*light X*/  m_light.getX(),
              /*light Y*/  m_light.getY(),
              /*light Z*/  m_light.getZ(),
              /*center X*/ 0.0f,
              /*center Y*/ 0.0f,
              /*center Z*/ 0.0f,
              /*up X*/     tmp[0],
              /*up Y*/     tmp[1],
              /*up Z*/     tmp[2]);

            m_shadowMatrix = new float[16*2];
            Matrix.multiplyMM(m_shadowMatrix, 0, tmp, 4, tmp, 20);
            Matrix.multiplyMM(m_shadowMatrix, 16, Canvas3D.SHADOW_MATRIX_BIAS, 0, m_shadowMatrix, 0);
        }

        private void drawShadow(float [] tmp)
        {
            final int shadowFBO = m_shadowObject.frameBufferId;
            final int shadowMapSize = m_shadowObject.mapSize;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFBO);
            GLES20.glViewport(0, 0, shadowMapSize, shadowMapSize);

            GLES20.glClearColor(1f, 1f, 1f, 1f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT|GLES20.GL_COLOR_BUFFER_BIT);

            m_ball.drawShadow(m_shadowMatrix, 0, tmp);
            m_cup.drawShadow(m_shadowMatrix, 0, tmp);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, m_viewWidth, m_viewHeight);
        }

        private void drawShadow()
        {
            try
            {
                final int shadowMapSize = m_shadowObject.mapSize;
                final int shadowTextureId = m_shadowObject.textureId;
                final ShadowDrawer shadowDrawer = new ShadowDrawer(getContext());
                final float [] matrix = new float[16 * 3];
                Matrix.orthoM(matrix, 0, -m_viewWidth/2, m_viewWidth/2, -m_viewHeight/2, m_viewHeight/2, 0, 50);
                Matrix.setLookAtM(matrix, 16, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
                Matrix.multiplyMM(matrix, 32, matrix, 0, matrix, 16);
                shadowDrawer.draw(matrix, 32, shadowMapSize, shadowTextureId);
            }
            catch (final IOException ex)
            {
                Log.e(LOG_TAG, ex.toString(), ex);
            }
        }

        public void onDrawFrame(GL10 gl)
        {
            Log.d(LOG_TAG, "onDrawFrame");

            GLES20.glEnable(GLES20.GL_CULL_FACE);
            GLES20.glCullFace(GLES20.GL_BACK);
            GLES20.glFrontFace(GLES20.GL_CW);

            final Vector eyePosition = new Vector(0f, -m_viewWidth/2*5, 300f);
            final float [] vpMatrix = new float[16];
            final float [] tmp = new float[16 * 4];
            initShadow(tmp);

            m_table.setSize(m_viewWidth, m_viewHeight);
            m_ball.updateMatrix(-65, -65, 100, 30, eyePosition, m_light, tmp);
            m_cup.updateMatrix(0, 0, 30, 40, 1, tmp);
            drawShadow(tmp);

            GLES20.glClearColor(0, 0, 0.3f, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);

            /*********************/

            /*
            Vector.set(tmp, 32, 0, 0, 1);
            Vector.crossProduct(tmp, 36, eyePosition, tmp, 32);
            Vector.crossProduct(tmp, 40, tmp, 36, eyePosition);

            final float ll = eyePosition.getLength();

            Matrix.frustumM(tmp, 0,
                -m_viewWidth/2,  // left
                 m_viewWidth/2,  // right
                -m_viewHeight/2, // bottom
                 m_viewHeight/2, // top
                 ll/5*4,  // near
                 ll/5*6); // far

            Matrix.setLookAtM(tmp, 16,
                eyePosition.getX(),
                eyePosition.getY(),
                eyePosition.getZ(),
                0f, // center X
                0f, // center Y
                0f, // center Z
                Vector.getX(tmp, 40),  // up X
                Vector.getY(tmp, 40),  // up Y
                Vector.getZ(tmp, 40)); // up Z

            Matrix.multiplyMM(vpMatrix, 0, tmp, 0, tmp, 16);

            final int shadowTextureId = m_shadowObject.textureId;
            m_table.draw(vpMatrix, eyePosition, m_light, m_shadowMatrix, 16, shadowTextureId);
            m_ball.draw(vpMatrix, m_light, tmp);
            m_cup.draw(vpMatrix, eyePosition, m_light, m_shadowMatrix, 16, shadowTextureId, tmp);
            */
            drawShadow();
        }
    }

    private void testFunc()
    {
        final float [] tmp = new float[16*4];
        Matrix.frustumM(tmp, 16, -10, 10, -10, 10, 40, 60);
        Matrix.setLookAtM(tmp, 32, 0, 0, 50, 0, 0, 0, 0, 10, 0);
        Matrix.multiplyMM(tmp, 0, tmp, 16, tmp, 32);
        //Matrix.invertM(tmp, 16, tmp, 0);
        Vector.set(tmp, 32, 0, 0, 30, 1);
        Matrix.multiplyMV(tmp, 36, tmp, 0, tmp, 32);
    }

    private TestView m_view;

    public void onCreate( Bundle savedInstanceState )
    {
        Log.d(LOG_TAG, "onCreate");
        super.onCreate( savedInstanceState );

        //testFunc();
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        final Window window = getWindow();

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        final ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final ConfigurationInfo configurationInfo = activityManager.getDeviceConfigurationInfo();
        Log.d(LOG_TAG, "Supported OpenGL API: " + configurationInfo.reqGlEsVersion);

        m_view = new TestView(this);
        setContentView(m_view);
    }

    public void onResume()
    {
        super.onResume();
        Log.d(LOG_TAG, "onResume");
        m_view.onResume();
    }

    public void onPause()
    {
        Log.d(LOG_TAG, "onPause");
        m_view.onPause();
        super.onPause();
    }

    public void onDestroy()
    {
        Log.d(LOG_TAG, "onDestroy");
        super.onDestroy();
    }
}
