/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of ShellsMP application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.shmp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import android.view.MotionEvent;
import org.jsl.collider.*;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.io.IOException;

public class GameServerView extends GameView
{
    private static final String LOG_TAG = GameServerView.class.getSimpleName();

    private static final int STATE_WAIT_CLIENT = 0;
    private static final int STATE_BALL_SET    = 1;
    private static final int STATE_BALL_DRAG   = 2;
    private static final int STATE_CUP_SET     = 3;
    private static final int STATE_CUP_DRAG    = 4;
    private static final int STATE_GAMBLE      = 5;
    private static final int STATE_GAMBLE_TIMER_TOUCH = 6;
    private static final int STATE_WAIT_REPLY  = 7;
    private static final int STATE_FINISHED    = 8;

    private static final int CUP_TOUCH = 1;
    private static final int CUP_DRAG  = 2;

    private static class SceneObject
    {
        /* Screen coordinates */
        private float m_x;
        private float m_y;

        public boolean contains(float x, float y, float radius)
        {
            final float dx = (x - m_x);
            final float dy = (y - m_y);
            return (Math.sqrt(dx*dx + dy*dy) < radius);
        }

        public float getX() { return m_x; }
        public float getY() { return m_y; }

        public void moveTo(float x, float y)
        {
            m_x = x;
            m_y = y;
        }

        public float moveByX(float dx)
        {
            m_x += dx;
            return m_x;
        }

        public float moveByY(float dy)
        {
            m_y += dy;
            return m_y;
        }
    }

    public static class Ball
    {
        private static final int MODEL = 0;
        private static final int MODEL_INVERTED = 16;
        private static final int SHADOW = 32;

        private final ModelBall m_model;
        private final float [] m_matrix;
        private boolean m_visible;

        private static void orientMatrixTo(float [] m, int offset, float [] to, int toOffset, float [] tmp, int tmpOffset)
        {
            Vector.set(tmp, tmpOffset, 0f, 0f, 1f, 0f);
            Vector.crossProduct(tmp, tmpOffset+4, tmp, tmpOffset, to, toOffset);
            final double angle = (Math.asin(Vector.length(tmp, tmpOffset+4) / Vector.length(to, toOffset)) * 180d / Math.PI);
            Matrix.setRotateM(m, offset, (float)angle, tmp[tmpOffset+4], tmp[tmpOffset+4+1], tmp[tmpOffset+4+2]);
        }

        public Ball(Context context, int color) throws IOException
        {
            m_model = new ModelBall(context, color);
            m_matrix = new float[16*3];
            m_visible = false;
        }

        public void updateMatrix(float x, float y, float z, float radius, Vector light, float [] tmp)
        {
            m_visible = true;

            // model matrix = [translate matrix] x [scale matrix]
            Matrix.setIdentityM(tmp, 0);
            Matrix.scaleM(tmp, 0, radius, radius, radius);
            Matrix.setIdentityM(tmp, 16);
            Matrix.translateM(tmp, 16, x, y, z);
            Matrix.multiplyMM(m_matrix, MODEL, tmp, 16, tmp, 0);
            Matrix.invertM(m_matrix, MODEL_INVERTED, m_matrix, MODEL);

            // shadow model matrix = [translate matrix] x [orient matrix] x [scale matrix]
            Vector.set(tmp, 16, light.getX()-x, light.getY()-y, light.getZ()-z);
            orientMatrixTo(tmp, 0, tmp, 16, tmp, 20);
            Matrix.setIdentityM(tmp, 16);
            Matrix.scaleM(tmp, 16, radius, radius, radius);
            Matrix.multiplyMM(tmp, 32, tmp, 0, tmp, 16);
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(tmp, 0, x, y, z);
            Matrix.multiplyMM(m_matrix, SHADOW, tmp, 0, tmp, 32);
        }

        public void draw(float [] vpMatrix, Vector light, float [] tmp, int tmpOffset)
        {
            if (m_visible)
            {
                Matrix.multiplyMM(tmp, tmpOffset, vpMatrix, 0, m_matrix, MODEL);
                Matrix.multiplyMV(tmp, tmpOffset+16, m_matrix, MODEL_INVERTED, light.v, light.offs);
                tmp[tmpOffset+16+3] = light.v[light.offs+4];
                tmp[tmpOffset+16+4] = light.v[light.offs+5];
                tmp[tmpOffset+16+5] = light.v[light.offs+6];
                m_model.draw(tmp, tmpOffset, tmp, tmpOffset+16);
            }
        }

        public void drawShadow(float [] vpMatrix, int vpMatrixOffset, float [] tmp)
        {
            if (m_visible)
            {
                Matrix.multiplyMM(tmp, 0, vpMatrix, vpMatrixOffset, m_matrix, SHADOW);
                m_model.drawShadow(tmp, 0);
            }
        }

        public void setVisible(boolean visible)
        {
            m_visible = visible;
        }

        public boolean isVisible()
        {
            return m_visible;
        }
    }

    private static class Cup extends SceneObject
    {
        private final int m_id;
        private final ModelCup m_model;
        private final float [] m_matrix;
        private boolean m_visible;

        private float m_eventX;
        private float m_eventY;
        private int m_state;

        public Cup(int id, ModelCup model)
        {
            m_id = id;
            m_model = model;
            m_matrix = new float[16*2];
            Matrix.setIdentityM( m_matrix, 0 );
            m_visible = false;
        }

        public int getID()
        {
            return m_id;
        }

        public void updateMatrix(float x, float y, float radius, float [] tmp)
        {
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(tmp, 0, x, y, 0);
            Matrix.setIdentityM(tmp, 16);
            Matrix.scaleM(tmp, 16, radius, radius, radius);
            Matrix.multiplyMM(m_matrix, 0, tmp, 0, tmp, 16);
            Matrix.invertM(m_matrix, 16, m_matrix, 0);
            m_visible = true;
        }

        public void draw(float [] vpMatrix, Vector eyePosition, Vector light, ShadowObject shadowObject, float [] tmp, int tmpOffset)
        {
            if (m_visible)
            {
                Matrix.multiplyMM(tmp, tmpOffset, vpMatrix, 0, m_matrix, 0);
                Matrix.multiplyMV(tmp, tmpOffset+16, m_matrix, 16, eyePosition.v, eyePosition.offs);
                Matrix.multiplyMV(tmp, tmpOffset+20, m_matrix, 16, light.v, light.offs);
                tmp[tmpOffset+20+3] = light.get(4);
                tmp[tmpOffset+20+4] = light.get(5);
                tmp[tmpOffset+20+5] = light.get(6);
                if (shadowObject == null)
                    m_model.draw(tmp, tmpOffset, tmp, tmpOffset + 16, tmp, tmpOffset + 20, null, 0, 0);
                else
                {
                    Matrix.multiplyMM(tmp, tmpOffset+32, shadowObject.matrix, 16, m_matrix, 0);
                    m_model.draw(tmp, tmpOffset, tmp, tmpOffset + 16, tmp, tmpOffset + 20,
                            tmp, tmpOffset + 32, shadowObject.textureId);
                }
            }
        }

        public void drawShadow(float [] vpMatrix, int vpMatrixOffset, float [] tmp)
        {
            if (m_visible)
            {
                Matrix.multiplyMM(tmp, 0, vpMatrix, vpMatrixOffset, m_matrix, 0);
                m_model.drawShadow(tmp, 0);
            }
        }

        void setEventPosition(float x, float y)
        {
            m_eventX = x;
            m_eventY = y;
        }

        float getEventX()
        {
            return m_eventX;
        }

        float getEventY()
        {
            return m_eventY;
        }

        void setState(int state)
        {
            m_state = state;
        }

        int getState()
        {
            return m_state;
        }

        void setVisible(boolean visible)
        {
            m_visible = visible;
        }
    }

    private class GambleTimerImpl extends GambleTimer
    {
        protected void onStop()
        {
            if (m_timerManager.resetTimer(this))
            {
                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId)
                    {
                        /* Change status line,
                         * then change state then send message to the client,
                         * to be sure m_state will be changed before we will receive reply.
                         */
                        setBottomLineText(R.string.waiting, GAMBLE_TIMER_COLOR, 0.5f);

                        post( new Runnable() {
                            public void run() {
                                m_state = STATE_WAIT_REPLY;
                                final RetainableByteBuffer msg = Protocol.Guess.create(m_byteBufferPool, (short) m_cupWithBall);
                                m_session.sendMessage(msg);
                                msg.release();
                            }
                        } );

                        return false;
                    }
                } );
            }
        }

        protected void onUpdate(final long value, final float fontSize)
        {
            executeOnRenderThread( new RenderThreadRunnable() {
                public boolean runOnRenderThread(int frameId) {
                    setBottomLineText(Long.toString(value), GAMBLE_TIMER_COLOR, fontSize);
                    return false;
                }
            } );
        }

        public GambleTimerImpl(int timeout)
        {
            super(m_activity, timeout);
        }
    }

    private void setBottomLineText(int resId, int color, float fontSize)
    {
        setBottomLineText(getContext().getString(resId), color, fontSize);
    }

    private void setBottomLineText(String str, int color, float fontSize)
    {
        m_bottomLineText = str;
        m_bottomLineTextFontSize = (getBottomReservedHeight() * fontSize);
        m_bottomLineTextColor = color;
    }

    private float getVirtualX(float x) { return (x * m_scale); }
    private float getVirtualY(float y) { return (y * m_scale); }

    private float getBallStartX() { return 0; }
    private float getBallStartY() { return -m_tableHeight/2 - m_ballRadius*2; }

    private final GameServerActivity m_activity;
    private final NsdManager m_nsdManager;
    private final boolean m_renderShadows;
    private final short m_gameTime;
    private final Cup [] m_cup;
    private final String m_strPort;
    private final int m_ballRadius;
    private final TimerManager m_timerManager;

    private final float [] m_tmpMatrix;
    private final HashMap<Integer, Cup> m_cupByPointer;

    private Vector m_light;
    private Vector m_eyePosition;
    private ShadowObject m_shadowObject;
    private float [] m_tableMatrix;
    private float [] m_screen2TableMatrix;
    private Table m_table;
    private int m_tableWidth;
    private int m_tableHeight;
    private Ball m_ball;
    private float m_ballX;
    private float m_ballY;
    private int m_cupIdx;
    private int m_cupWithBall;

    private int m_state;
    private float m_eventX;
    private float m_eventY;

    private float m_bottomLineX;
    private float m_bottomLineY;
    private String m_bottomLineText;
    private float m_bottomLineTextFontSize;
    private int m_bottomLineTextColor;

    private RetainableByteBufferPool m_byteBufferPool;

    private final ReentrantLock m_lock;
    private final Condition m_cond;
    private RegistrationListener m_registrationListener;
    private boolean m_registrationListenerStop;
    private boolean m_pause;
    private GameServerSession m_session;

    private float m_scale;

    private String m_clientDeviceId;
    private String m_clientPlayerName;
    private boolean m_clientDisconnected;
    private int m_win;

    private class GameAcceptor extends Acceptor
    {
        private final short m_desiredTableHeight;
        private final short m_ballRadius;

        public GameAcceptor(short desiredTableHeight, short ballRadius)
        {
            m_desiredTableHeight = desiredTableHeight;
            m_ballRadius = ballRadius;
        }

        public void onAcceptorStarted(Collider collider, int localPortNumber)
        {
            Log.d(LOG_TAG, "Acceptor started, port=" + localPortNumber);
            m_lock.lock();
            try
            {
                final String deviceId = getDeviceId();
                final String playerName = getPlayerName();
                final NsdServiceInfo serviceInfo = MainActivity.createServiceInfo(deviceId, playerName, localPortNumber);
                m_registrationListener = new RegistrationListener(localPortNumber);
                m_registrationListenerStop = false;
                m_nsdManager.registerService( serviceInfo, NsdManager.PROTOCOL_DNS_SD, m_registrationListener );
                final Bitmap statusLine = createStatusLine( localPortNumber, Color.WHITE );
                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId) {
                        setStatusLine(statusLine);
                        return false;
                    }
                } );
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public Session.Listener createSessionListener(Session session)
        {
            boolean interrupted = false;
            try
            {
                session.getCollider().removeAcceptor(this);
            }
            catch (final InterruptedException ex)
            {
                interrupted = true;
                Log.e(LOG_TAG, ex.toString(), ex);
            }
            finally
            {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }

            m_lock.lock();
            try
            {
                m_nsdManager.unregisterService(m_registrationListener);
                m_registrationListenerStop = true;
            }
            finally
            {
                m_lock.unlock();
            }

            /* Session will register in the activity after handshake */
            return new HandshakeServerSession(
                    GameServerView.this,
                    session,
                    getPingConfig(),
                    m_desiredTableHeight,
                    m_ballRadius,
                    (short) m_cup.length );
        }
    }

    private class RegistrationListener implements NsdManager.RegistrationListener
    {
        /* Port number in NsdServiceInfo in onServiceRegistered() is 0,
         * so we have to keep a port number to show it later.
          */
        private final int m_portNumber;

        public RegistrationListener(int portNumber)
        {
            m_portNumber = portNumber;
        }

        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode)
        {
            Log.d(LOG_TAG, "onRegistrationFailed: " + errorCode);
            m_lock.lock();
            try
            {
                m_registrationListener = null;
            }
            finally
            {
                m_lock.unlock();
            }
            m_activity.onGameRegistrationFailed();
        }

        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode)
        {
            Log.d( LOG_TAG, "onUnregistrationFailed: " + errorCode );
            if (BuildConfig.DEBUG)
                throw new AssertionError();
            /* Nothing critical probably... */
        }

        public void onServiceRegistered(NsdServiceInfo serviceInfo)
        {
            m_lock.lock();
            try
            {
                if (BuildConfig.DEBUG && (m_registrationListener != this))
                    throw new AssertionError();

                final String serviceName = serviceInfo.getServiceName();
                Log.d( LOG_TAG, "onServiceRegistered: " + serviceName );

                final Bitmap statusLine = createStatusLine(m_portNumber, Color.GREEN);
                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId) {
                        setStatusLine(statusLine);
                        return false;
                    }
                } );
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onServiceUnregistered( NsdServiceInfo serviceInfo )
        {
            Log.d( LOG_TAG, "onServiceUnregistered: " + serviceInfo.getServiceName() );
            m_lock.lock();
            try
            {
                m_registrationListener = null;
                m_cond.signal();
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    protected Bitmap createStatusLine()
    {
        /* Initial status line */
        final int width = getViewWidth();
        final Bitmap bitmap = Bitmap.createBitmap( width, getTopReservedHeight(), Bitmap.Config.RGB_565 );
        final Canvas canvas = new Canvas( bitmap );
        final Paint paint = getPaint();
        final float textY = -paint.ascent();

        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(m_strPort, 0, textY, paint);

        return bitmap;
    }

    private Bitmap createStatusLine( int portNumber, int color )
    {
        final int width = getWidth();
        final Bitmap bitmap = Bitmap.createBitmap( width, getTopReservedHeight(), Bitmap.Config.RGB_565 );
        final Canvas canvas = new Canvas( bitmap );
        final Paint paint = getPaint();
        final float textY = -paint.ascent();

        paint.setColor( color );
        final String str = m_strPort + Integer.toString(portNumber);
        paint.setTextAlign( Paint.Align.LEFT );
        canvas.drawText( str, 0, textY, paint );

        return bitmap;
    }

    public GameServerView(
            GameServerActivity activity,
            String deviceId,
            String playerName,
            NsdManager nsdManager,
            boolean renderShadows,
            short gameTime,
            short caps)
    {
        super(activity, deviceId, playerName);

        m_activity = activity;
        m_nsdManager = nsdManager;
        m_renderShadows = renderShadows;
        m_gameTime = gameTime;
        m_cup = new Cup[caps];
        m_strPort = getResources().getString(R.string.port);
        m_ballRadius = (getBottomReservedHeight() / 3);
        m_timerManager = new TimerManager();

        m_tmpMatrix = new float[64];
        m_cupByPointer = new HashMap<Integer, Cup>();

        m_byteBufferPool = new RetainableByteBufferPool(1024, true, Protocol.BYTE_ORDER);

        m_lock = new ReentrantLock();
        m_cond = m_lock.newCondition();
        m_win = -1;
    }

    public void onSurfaceCreated(GL10 gl, EGLConfig config)
    {
        super.onSurfaceCreated(gl, config);
    }

    public void onSurfaceChanged(GL10 gl, int width, int height)
    {
        /* Looks like a better place to start all than surfaceCreated() */
        super.onSurfaceChanged(gl, width, height);

        m_scale = (VIRTUAL_TABLE_WIDTH / width);
        m_state = STATE_WAIT_CLIENT;

        try
        {
            final float [] light =
            {
                -(width / 2f), // x
                (height / 2f), // y
                 (width * 2f), // z
                           0f, // w
                           1f, // r
                           1f, // g
                           1f  // b
            };

            m_light = new Vector(light, 0);
            m_eyePosition = new Vector(0f, 0f, 100f);

            final HashSet<String> macro = new HashSet<String>();
            if (m_renderShadows)
            {
                m_shadowObject = ShadowObject.create(width, m_light, m_tmpMatrix);
                if (m_shadowObject != null)
                    macro.add("RENDER_SHADOWS");
            }

            /***********************/

            final Context context = getContext();
            m_table = new Table(context, macro);
            m_ball = new Ball(context, BALL_COLOR);

            final ModelCup modelCup = new ModelCup(context, CUP_STRIPES, macro);
            for (int idx = 0; idx< m_cup.length; idx++)
                m_cup[idx] = new Cup(idx, modelCup);

            final Collider collider = startCollider();
            final GameAcceptor acceptor = new GameAcceptor(
                    getDesiredTableHeight(width, height),
                    (short) (m_ballRadius * VIRTUAL_TABLE_WIDTH / width) );
            collider.addAcceptor( acceptor );

            /* Bottom line will be in the middle of the screen,
             * position will be adjusted after we will find the table height.
             */
            m_bottomLineX = (width / 2f);
            m_bottomLineY = (height / 2f);

            m_bottomLineText = getContext().getString(R.string.waiting_second_player);
            m_bottomLineTextColor = Color.GREEN;
            m_bottomLineTextFontSize = (getBottomReservedHeight() * 0.3f);
        }
        catch (final IOException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
        }
    }

    public void onDrawFrame(float [] vpMatrix, Canvas3D canvas3D)
    {
        super.onDrawFrame(vpMatrix, canvas3D);

        final float [] tmp = m_tmpMatrix;

        if (m_tableWidth > 0)
        {
            if (m_shadowObject != null)
            {
                final int frameBufferId = m_shadowObject.frameBufferId;
                final int mapSize = m_shadowObject.mapSize;
                final int viewWidth = getWidth();
                final int viewHeight = getHeight();

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId);
                GLES20.glViewport(0, 0, mapSize, mapSize);

                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT|GLES20.GL_COLOR_BUFFER_BIT);

                m_ball.drawShadow(m_shadowObject.matrix, 0, tmp);
                for (Cup cup : m_cup)
                    cup.drawShadow(m_shadowObject.matrix, 0, tmp);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glViewport(0, 0, viewWidth, viewHeight);

                /*
                try
                {
                    final TestActivity.ShadowDrawer shadowDrawer = new TestActivity.ShadowDrawer(getContext());
                    final float[] matrix = new float[16 * 3];
                    Matrix.orthoM(matrix, 0, -viewWidth/2, viewWidth/2, -viewHeight/2, viewHeight/2, 0, 50);
                    Matrix.setLookAtM(matrix, 16, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
                    Matrix.multiplyMM(matrix, 32, matrix, 0, matrix, 16);
                    shadowDrawer.draw(matrix, 32, mapSize, m_shadowObject.textureId);
                }
                catch (final IOException ex)
                {
                    Log.e(LOG_TAG, ex.toString(), ex);
                }
                */
            }

            Matrix.multiplyMM(tmp, 0, vpMatrix, 0, m_tableMatrix, 0);

            m_table.draw(tmp, m_eyePosition, m_light, m_shadowObject, tmp, 16);
            m_ball.draw(tmp, m_light, tmp, 16);

            for (Cup cup : m_cup)
                cup.draw(tmp, m_eyePosition, m_light, m_shadowObject, tmp, 16);
        }

        if (m_bottomLineText != null)
        {
            canvas3D.drawText(
                    vpMatrix,
                    m_bottomLineText,
                    m_bottomLineX,
                    (getHeight() - m_bottomLineY),
                    1f,
                    m_bottomLineTextFontSize,
                    m_bottomLineTextColor,
                    Canvas3D.Align.CENTER,
                    Canvas3D.VerticalAlign.CENTER,
                    tmp, 0);
        }

        /*
        canvas3D.drawText( vpMatrix, "12wait",
                getWidth()/2, getHeight()/2, 1f, 70, Color.GREEN, Paint.Align.CENTER );

        canvas3D.drawText( vpMatrix, "12wait",
                getWidth()/2, getHeight()/2-100, 1f, 50, Color.GREEN, Paint.Align.CENTER );
        */

        /*
        Matrix.setIdentityM( m_tmpMatrix, 16 );
        //Matrix.scaleM( m_tmpMatrix, 16, 2f, 2f, 2f );
        //Matrix.setIdentityM( m_tmpMatrix, 32 );
        //Matrix.rotateM( m_tmpMatrix, 32, 45, 1, 0, 0 );
        //Matrix.multiplyMM( m_tmpMatrix, 48, m_tmpMatrix, 16, m_tmpMatrix, 32 );
        Matrix.setIdentityM( m_tmpMatrix, 32 );
        Matrix.translateM( m_tmpMatrix, 32, getWidth()/2, getHeight()/2, 0f );
        //Matrix.multiplyMM( m_tmpMatrix, 48, m_tmpMatrix, 32, m_tmpMatrix, 16 );
        Matrix.multiplyMM( m_tmpMatrix, 0, vpMatrix, 0, m_tmpMatrix, 32 );
        m_cup.draw( m_tmpMatrix );
        */
    }

    public void onClientConnected(GameServerSession session, short virtualTableHeight, String clientDeviceId, String clientPlayerName)
    {
        m_session = session;
        m_clientDeviceId = clientDeviceId;
        m_clientPlayerName = clientPlayerName;

        final int topReservedHeight = getTopReservedHeight();
        final int viewWidth = getWidth();
        final int viewHeight = getHeight();

        m_tableWidth = viewWidth;
        m_tableHeight = (int) (viewWidth * virtualTableHeight / VIRTUAL_TABLE_WIDTH);
        m_tableMatrix = new float[16];
        Matrix.setIdentityM(m_tableMatrix, 0);
        Matrix.translateM(m_tableMatrix, 0, m_tableWidth/2-1, viewHeight-getTopReservedHeight()-1-m_tableHeight/2, 0f);

        m_screen2TableMatrix = new float[16];
        m_screen2TableMatrix[0]  = 1;  // 0 4  8 12
        m_screen2TableMatrix[5]  = -1; // 1 5  9 13
        m_screen2TableMatrix[10] = 1;  // 2 6 10 14
        m_screen2TableMatrix[15] = 1;  // 3 7 11 15
        m_screen2TableMatrix[12] = -m_tableMatrix[12];
        m_screen2TableMatrix[13] = (viewHeight - m_tableMatrix[13]);

        m_table.setSize(m_tableWidth, m_tableHeight);

        m_bottomLineText = null;
        m_bottomLineY = (topReservedHeight + m_tableHeight + getBottomReservedHeight() / 2);

        m_state = STATE_BALL_SET;
        
        final Bitmap statusLine = createStatusLine(/*ping*/-1, clientPlayerName);

        final float ballX = getBallStartX();
        final float ballY = getBallStartY();

        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                setStatusLine(statusLine);
                m_ball.updateMatrix(
                        ballX,
                        ballY,
                        m_ballRadius,
                        m_ballRadius,
                        m_light,
                        m_tmpMatrix);
                return false;
            }
        } );

        m_ballX = ballX;
        m_ballY = ballY;
    }

    public void onClientDisconnected()
    {
        m_activity.runOnUiThread( new Runnable() {
            public void run()
            {
                if (!m_pause && (m_state != STATE_FINISHED))
                {
                    m_clientDisconnected = true;
                    boolean interrupted = false;

                    try
                    {
                        m_timerManager.cancelTimer(getTimerQueue());
                    }
                    catch (final InterruptedException ex)
                    {
                        Log.w(LOG_TAG, ex.toString(), ex);
                        interrupted = true;
                    }

                    final int state = m_state;
                    m_state = STATE_FINISHED;

                    executeOnRenderThread(new RenderThreadRunnable() {
                        public boolean runOnRenderThread(int frameId)
                        {
                            if ((state == STATE_BALL_SET) || (state == STATE_BALL_DRAG))
                                m_ball.setVisible(false);

                            if ((state == STATE_CUP_SET) || (state == STATE_CUP_DRAG))
                                m_cup[m_cupIdx].setVisible(false);

                            setBottomLineText(R.string.player_left_game, Color.GREEN, 0.4f);
                            return false;
                        }
                    });

                    if (interrupted)
                        Thread.currentThread().interrupt();
                }
            }
        });
    }

    public void setPing(int ping)
    {
        final Bitmap statusLine = createStatusLine(ping, m_clientPlayerName);
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                setStatusLine(statusLine);
                return false;
            }
        } );
    }

    public void showGuessReplyCT(boolean found)
    {
        m_state = STATE_FINISHED;
        m_win = (found ? 0 : 1);
        executeOnRenderThread( new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                if (m_win > 0)
                    setBottomLineText(R.string.you_win, WIN_TEXT_COLOR, GAMBLE_TIMER_FONT_SIZE);
                else
                    setBottomLineText(R.string.you_lose, LOSE_TEXT_COLOR, GAMBLE_TIMER_FONT_SIZE);
                return false;
            }
        } );
    }

    public boolean onTouchEvent(MotionEvent event)
    {
        final int action = event.getActionMasked();
        //final long tsc = System.nanoTime();

        if (action == MotionEvent.ACTION_DOWN)
        {
            if (m_state == STATE_BALL_SET)
            {
                final float eventX = Vector.getMVX(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                final float eventY = Vector.getMVY(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                if (Vector.length(eventX-m_ballX, eventY-m_ballY) <= m_ballRadius)
                {
                    m_eventX = eventX;
                    m_eventY = eventY;
                    m_state = STATE_BALL_DRAG;
                    Log.d(LOG_TAG, "STATE_BALL_SET -> STATE_BALL_DRAG");
                }
            }
            else if (m_state == STATE_CUP_SET)
            {
                final float eventX = Vector.getMVX(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                final float eventY = Vector.getMVY(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                if (m_cup[m_cupIdx].contains(eventX, eventY, m_ballRadius))
                {
                    m_eventX = eventX;
                    m_eventY = eventY;
                    m_state = STATE_CUP_DRAG;
                    Log.d( LOG_TAG, "STATE_CUP_SET -> STATE_CUP_DRAG" );
                }
            }
            else if (m_state == STATE_GAMBLE)
            {
                if (!m_cupByPointer.isEmpty())
                    throw new AssertionError();

                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId( pointerIndex );
                final float eventX = Vector.getMVX(m_screen2TableMatrix, 0, event.getX(pointerIndex), event.getY(pointerIndex), 0f, 1f);
                final float eventY = Vector.getMVY(m_screen2TableMatrix, 0, event.getX(pointerIndex), event.getY(pointerIndex), 0f, 1f);
                int idx = 0;
                for (; idx< m_cup.length; idx++)
                {
                    final Cup cup = m_cup[idx];
                    if (cup.contains(eventX, eventY, m_ballRadius))
                    {
                        cup.setEventPosition(eventX, eventY);
                        cup.setState(CUP_TOUCH);
                        m_cupByPointer.put(pointerId, cup);
                        break;
                    }
                }

                if (idx < m_cup.length)
                {
                    Log.d(LOG_TAG, "ACTION_DOWN: pointerIndex=" + pointerIndex +
                            " pointerId=" + pointerId + ", capIdx=" + idx);
                }
                else if ((Math.abs(event.getX(pointerIndex) - m_bottomLineX) < getBottomReservedHeight()/2f) &&
                         (Math.abs(event.getY(pointerIndex) - m_bottomLineY) < getBottomReservedHeight()/2f))
                {
                    m_state = STATE_GAMBLE_TIMER_TOUCH;
                    Log.d( LOG_TAG, "ACTION_DOWN: STATE_GAMBLE -> STATE_GAMBLE_TIMER_TOUCH" );
                }
                else
                {
                    Log.d( LOG_TAG, "ACTION_DOWN: pointerIndex=" + pointerIndex +
                            " pointerId=" + pointerId + ", no cap" );
                }
            }
        }
        else if (action == MotionEvent.ACTION_POINTER_DOWN)
        {
            if (m_state == STATE_GAMBLE)
            {
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId( pointerIndex );
                Log.d(LOG_TAG, "STATE_GAMBLE: POINTER_DOWN: pointerIndex=" + pointerIndex + " pointerId=" + pointerId);
                final float eventX = Vector.getMVX(m_screen2TableMatrix, 0, event.getX(pointerIndex), event.getY(pointerIndex), 0f, 1f);
                final float eventY = Vector.getMVY(m_screen2TableMatrix, 0, event.getX(pointerIndex), event.getY(pointerIndex), 0f, 1f);
                for (Cup cup : m_cup)
                {
                    if (cup.contains(eventX, eventY, m_ballRadius))
                    {
                        cup.setEventPosition(eventX, eventY);
                        cup.setState(CUP_TOUCH);
                        m_cupByPointer.put(pointerId, cup);
                        break;
                    }
                }
            }
        }
        else if (action == MotionEvent.ACTION_POINTER_UP)
        {
            if (m_state == STATE_GAMBLE)
            {
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId(pointerIndex);
                Log.d(LOG_TAG, "STATE_GAMBLE: POINTER_UP: pointerIndex=" + pointerIndex + " pointerId=" + pointerId);
                m_cupByPointer.remove(pointerId);
            }
        }
        else if (action == MotionEvent.ACTION_MOVE)
        {
            if (m_state == STATE_BALL_DRAG)
            {
                final float eventX = Vector.getMVX(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                final float eventY = Vector.getMVY(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                final float dx = (eventX - m_eventX);
                final float dy = (eventY - m_eventY);
                final float ballX = (m_ballX += dx);
                final float ballY = (m_ballY += dy);

                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId) {
                        m_ball.updateMatrix(
                                ballX,        // x
                                ballY,        // y
                                m_ballRadius, // z
                                m_ballRadius,
                                m_light,
                                m_tmpMatrix);
                        return false;
                    }
                } );

                final RetainableByteBuffer msg = Protocol.DragBall.create(
                        m_byteBufferPool, getVirtualX(ballX), getVirtualY(ballY));
                m_session.sendMessage(msg);
                msg.release();

                m_eventX = eventX;
                m_eventY = eventY;
            }
            else if (m_state == STATE_CUP_DRAG)
            {
                final int capIdx = m_cupIdx;
                final float eventX = Vector.getMVX(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                final float eventY = Vector.getMVY(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                final float dx = (eventX - m_eventX);
                final float dy = (eventY - m_eventY);
                final float cx = m_cup[capIdx].moveByX(dx);
                final float cy = m_cup[capIdx].moveByY(dy);

                executeOnRenderThread( new RenderThreadRunnable() {
                    public boolean runOnRenderThread(int frameId) {
                        m_cup[capIdx].updateMatrix(cx, cy, m_ballRadius, m_tmpMatrix);
                        return false;
                    }
                } );

                final RetainableByteBuffer msg = Protocol.DragCup.create(m_byteBufferPool, (short)capIdx,
                        getVirtualX(cx), getVirtualY(cy), (m_ballRadius * 2f * m_scale));
                m_session.sendMessage( msg );
                msg.release();

                m_eventX = eventX;
                m_eventY = eventY;
            }
            else if (m_state == STATE_GAMBLE)
            {
                final int pointerCount = event.getPointerCount();
                for (int pointerIndex=0; pointerIndex<pointerCount; pointerIndex++)
                {
                    final int pointerId = event.getPointerId(pointerIndex);
                    final Cup cup = m_cupByPointer.get(pointerId);
                    if (cup != null)
                    {
                        /* Properly detect cup collision would be quite difficult now,
                         * let's just stop drugging if cup meet another or leave the table.
                         */
                        final float eventX = Vector.getMVX(m_screen2TableMatrix, 0, event.getX(pointerIndex), event.getY(pointerIndex), 0f, 1f);
                        final float eventY = Vector.getMVY(m_screen2TableMatrix, 0, event.getX(pointerIndex), event.getY(pointerIndex), 0f, 1f);
                        float dx = (eventX - cup.getEventX());
                        float dy = (eventY - cup.getEventY());

                        if (cup.getState() == CUP_TOUCH)
                        {
                            final int touchSlop = getTouchSlop();
                            if (Math.sqrt(dx*dx + dy*dy) < touchSlop)
                            {
                                /* Let's wait more significant movement */
                                break;
                            }
                            cup.setState(CUP_DRAG);
                        }

                        float cx = cup.moveByX(dx);
                        float cy = cup.moveByY(dy);
                        final float tableWidth2 = (m_tableWidth/2 - m_ballRadius);
                        final float tableHeight2 = (m_tableHeight/2 - m_ballRadius);

                        if ((Math.abs(cx) <= tableWidth2) && (Math.abs(cy) <= tableHeight2))
                        {
                            final float minDistance = (m_ballRadius * 2f);
                            int idx = 0;
                            for (; idx< m_cup.length; idx++)
                            {
                                if (m_cup[idx] != cup)
                                {
                                    if (m_cup[idx].contains(cx, cy, minDistance))
                                        break;
                                }
                            }

                            if (idx == m_cup.length)
                            {
                                final float fcx = cx;
                                final float fcy = cy;

                                executeOnRenderThread( new RenderThreadRunnable() {
                                    public boolean runOnRenderThread(int frameId) {
                                        cup.updateMatrix(fcx, fcy, m_ballRadius, m_tmpMatrix);
                                        return false;
                                    }
                                } );

                                final RetainableByteBuffer msg = Protocol.DragCup.create(m_byteBufferPool,
                                        (short) cup.getID(), getVirtualX(cx), getVirtualY(cy), 0);
                                m_session.sendMessage(msg);
                                msg.release();

                                cup.setEventPosition(eventX, eventY);
                            }
                            else
                            {
                                /* Collision with another cup, caps now can significantly intersect,
                                 * have to put them properly.
                                 */
                                dx = (cx - m_cup[idx].getX());
                                dy = (cy - m_cup[idx].getY());
                                final float dist = (float) Math.sqrt( dx*dx + dy*dy );
                                final float fcx = m_cup[idx].getX() + minDistance*dx/dist;
                                final float fcy = m_cup[idx].getY() + minDistance*dy/dist;
                                executeOnRenderThread( new RenderThreadRunnable() {
                                    public boolean runOnRenderThread(int frameId) {
                                        cup.updateMatrix(fcx, fcy, m_ballRadius, m_tmpMatrix);
                                        return false;
                                    }
                                } );
                                cup.moveTo(fcx, fcy);
                                m_cupByPointer.remove(pointerId);
                            }
                        }
                        else
                        {
                            /* Out of the table */
                            if (cx < -tableWidth2)
                                cx = -tableWidth2;
                            else if (cx > tableWidth2)
                                cx = tableWidth2;

                            if (cy < -tableHeight2)
                                cy = -tableHeight2;
                            else if (cy > tableHeight2)
                                cy = tableHeight2;

                            cup.moveTo(cx, cy);

                            final float fcx = cx;
                            final float fcy = cy;

                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    cup.updateMatrix(fcx, fcy, m_ballRadius, m_tmpMatrix);
                                    return false;
                                }
                            } );

                            m_cupByPointer.remove( pointerId );
                        }
                    }
                    /* else pointer missed the cup when was down */
                }
            }
        }
        else if ((action == MotionEvent.ACTION_UP) ||
                 (action == MotionEvent.ACTION_CANCEL))
        {
            if (m_state == STATE_BALL_DRAG)
            {
                final float eventX = Vector.getMVX(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                final float eventY = Vector.getMVY(m_screen2TableMatrix, 0, event.getX(), event.getY(), 0f, 1f);
                final float dx = (eventX - m_eventX);
                final float dy = (eventY - m_eventY);
                final float ballX = (m_ballX += dx);
                final float ballY = (m_ballY += dy);

                if ((Math.abs(ballX) <= (m_tableWidth/2 - m_ballRadius)) &&
                    (Math.abs(ballY) <= (m_tableHeight/2 - m_ballRadius)))
                {
                    /* Ball in the valid range, proceed with caps. */
                    final int capIdx = 0;
                    m_cupIdx = capIdx;

                    executeOnRenderThread( new RenderThreadRunnable() {
                        public boolean runOnRenderThread(int frameId) {
                            m_ball.updateMatrix(
                                    ballX,        // x
                                    ballY,        // y
                                    m_ballRadius, // z
                                    m_ballRadius,
                                    m_light,
                                    m_tmpMatrix);
                            m_cup[capIdx].updateMatrix(getBallStartX(), getBallStartY(), m_ballRadius, m_tmpMatrix);
                            return false;
                        }
                    } );

                    final RetainableByteBuffer msg = Protocol.PutBall.create(
                            m_byteBufferPool, getVirtualX(ballX), getVirtualY(ballY));
                    m_session.sendMessage(msg);
                    msg.release();

                    m_cup[capIdx].moveTo(getBallStartX(), getBallStartY());
                    m_state = STATE_CUP_SET;
                    m_activity.playSound_BallPut();
                }
                else
                {
                    executeOnRenderThread( new RenderThreadRunnable() {
                        public boolean runOnRenderThread(int frameId) {
                            m_ball.updateMatrix(
                                    getBallStartX(), // x
                                    getBallStartY(), // y
                                    m_ballRadius,    // z
                                    m_ballRadius,
                                    m_light,
                                    m_tmpMatrix);
                            return false;
                        }
                    } );

                    final RetainableByteBuffer msg = Protocol.RemoveBall.create(m_byteBufferPool);
                    m_session.sendMessage(msg);
                    msg.release();

                    m_ballX = getBallStartX();
                    m_ballY = getBallStartY();
                    m_state = STATE_BALL_SET;
                }
            }
            else if (m_state == STATE_CUP_DRAG)
            {
                final int capIdx = m_cupIdx;
                final float cx = m_cup[capIdx].getX();
                final float cy = m_cup[capIdx].getY();

                if ((Math.abs(cx) <= (m_tableWidth/2 - m_ballRadius)) &&
                    (Math.abs(cy) <= (m_tableHeight/2 - m_ballRadius)))
                {
                    if (m_ball.isVisible() && (Vector.length(m_ballX-cx, m_ballY-cy) <= m_ballRadius))
                    {
                        /* Cup is almost over the ball, let' put it exactly on the ball. */
                        final float ballX = m_ballX;
                        final float ballY = m_ballY;
                        m_ball.setVisible(false);
                        m_cupWithBall = m_cupIdx;

                        short gambleTime;
                        final int capIdxx = ++m_cupIdx;
                        if (capIdxx == m_cup.length)
                        {
                            /* Last cap */
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    m_cup[capIdx].updateMatrix(ballX, ballY, m_ballRadius, m_tmpMatrix);
                                    return false;
                                }
                            } );

                            m_cup[capIdx].moveTo(ballX, ballY);
                            m_state = STATE_GAMBLE;

                            final GambleTimer gambleTimer = new GambleTimerImpl(m_gameTime);
                            m_timerManager.scheduleTimer(getTimerQueue(), gambleTimer);

                            gambleTime = m_gameTime;
                        }
                        else
                        {
                            /* Set next cap */
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    m_cup[capIdx].updateMatrix(ballX, ballY, m_ballRadius, m_tmpMatrix);
                                    m_cup[capIdxx].updateMatrix(getBallStartX(), getBallStartY(), m_ballRadius, m_tmpMatrix);
                                    return false;
                                }
                            } );
                            m_cup[capIdx].moveTo(ballX, ballY);
                            m_cup[capIdxx].moveTo(getBallStartX(), getBallStartY());
                            m_state = STATE_CUP_SET;
                            gambleTime = 0;
                        }

                        RetainableByteBuffer msg = Protocol.RemoveBall.create(m_byteBufferPool);
                        m_session.sendMessage(msg);
                        msg.release();

                        msg = Protocol.PutCup.create(m_byteBufferPool, (short)capIdx,
                                getVirtualX(ballX), getVirtualY(ballY), /*gambleTime*/ gambleTime);
                        m_session.sendMessage(msg);
                        msg.release();

                        m_activity.playSound_CupPut();
                    }
                    else if (m_ball.isVisible() && (Vector.length(m_ballX-cx, m_ballY-cy) <= m_ballRadius*2))
                    {
                        /* Intersect with ball,
                         * but does not cover even half of the ball,
                         * let's move cap to the start point.
                         */
                        executeOnRenderThread( new RenderThreadRunnable() {
                            public boolean runOnRenderThread(int frameId) {
                                m_cup[capIdx].updateMatrix(getBallStartX(), getBallStartY(), m_ballRadius, m_tmpMatrix);
                                return false;
                            }
                        } );

                        final RetainableByteBuffer msg = Protocol.RemoveCup.create(m_byteBufferPool, (short)capIdx);
                        m_session.sendMessage(msg);
                        msg.release();

                        m_cup[capIdx].moveTo(getBallStartX(), getBallStartY());
                        m_state = STATE_CUP_SET;
                    }
                    else
                    {
                        /* Check that new cap does not intersect with all previously set */
                        int idx = 0;
                        for (; idx<m_cupIdx; idx++)
                        {
                            if (m_cup[idx].contains(cx, cy, m_ballRadius*2))
                                break;
                        }

                        if (idx == m_cupIdx)
                        {
                            final int capIdxx = ++m_cupIdx;
                            if (capIdxx == m_cup.length)
                            {
                                if (m_ball.isVisible())
                                {
                                    /* Last cap should cover ball if it is still visible */
                                    executeOnRenderThread( new RenderThreadRunnable() {
                                        public boolean runOnRenderThread(int frameId) {
                                            m_cup[capIdx].updateMatrix(getBallStartX(), getBallStartY(), m_ballRadius, m_tmpMatrix);
                                            return false;
                                        }
                                    } );

                                    final RetainableByteBuffer msg = Protocol.RemoveCup.create(m_byteBufferPool, (short)capIdx);
                                    m_session.sendMessage(msg);
                                    msg.release();

                                    m_cupIdx = capIdx;
                                    m_cup[capIdx].moveTo(getBallStartX(), getBallStartY());
                                    m_state = STATE_CUP_SET;
                                }
                                else
                                {
                                    final Cup cup = m_cup[capIdx];
                                    final RetainableByteBuffer msg = Protocol.PutCup.create(m_byteBufferPool,
                                            (short)capIdx, getVirtualX(cup.getX()), getVirtualY(cup.getY()), m_gameTime);

                                    m_session.sendMessage( msg );
                                    msg.release();

                                    m_state = STATE_GAMBLE;
                                    m_activity.playSound_CupPut();

                                    final GambleTimer gambleTimer = new GambleTimerImpl(m_gameTime);
                                    m_timerManager.scheduleTimer( getTimerQueue(), gambleTimer );
                                }
                            }
                            else
                            {
                                executeOnRenderThread( new RenderThreadRunnable() {
                                    public boolean runOnRenderThread(int frameId) {
                                        m_cup[capIdxx].updateMatrix(getBallStartX(), getBallStartY(), m_ballRadius, m_tmpMatrix);
                                        return false;
                                    }
                                } );

                                final Cup cup = m_cup[capIdx];
                                final RetainableByteBuffer msg = Protocol.PutCup.create(m_byteBufferPool, (short)capIdx,
                                        getVirtualX(cup.getX()), getVirtualY(cup.getY()), /*gambleTime*/(short)0);
                                m_session.sendMessage( msg );
                                msg.release();

                                m_cup[capIdxx].moveTo(getBallStartX(), getBallStartY());
                                m_state = STATE_CUP_SET;
                                m_activity.playSound_CupPut();
                            }
                        }
                        else
                        {
                            /* Cup intersects with a cap set before, remove it. */
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    m_cup[capIdx].updateMatrix(getBallStartX(), getBallStartY(), m_ballRadius, m_tmpMatrix);
                                    return false;
                                }
                            } );

                            final RetainableByteBuffer msg = Protocol.RemoveCup.create(m_byteBufferPool, (short)capIdx);
                            m_session.sendMessage(msg);
                            msg.release();

                            m_cup[capIdx].moveTo(getBallStartX(), getBallStartY());
                            m_state = STATE_CUP_SET;
                        }
                    }
                }
                else
                {
                    /* Cup is not in the valid range */
                    executeOnRenderThread( new RenderThreadRunnable() {
                        public boolean runOnRenderThread(int frameId) {
                            m_cup[capIdx].updateMatrix(getBallStartX(), getBallStartY(), m_ballRadius, m_tmpMatrix);
                            return false;
                        }
                    } );

                    final RetainableByteBuffer msg = Protocol.RemoveCup.create(m_byteBufferPool, (short)capIdx);
                    m_session.sendMessage(msg);
                    msg.release();

                    m_cup[capIdx].moveTo(getBallStartX(), getBallStartY());
                    m_state = STATE_CUP_SET;
                }
            }
            else if (m_state == STATE_GAMBLE)
            {
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId(pointerIndex);
                Log.d(LOG_TAG, "STATE_GAMBLE: ACTION_UP/ACTION_CANCEL: pointerIndex=" + pointerIndex + " pointerId=" + pointerId);
                m_cupByPointer.remove(pointerId);
            }
            else if (m_state == STATE_GAMBLE_TIMER_TOUCH)
            {
                if ((Math.abs(event.getX() - m_bottomLineX) < getBottomReservedHeight()/2) &&
                    (Math.abs(event.getY() - m_bottomLineY) < getBottomReservedHeight()/2))
                {
                    try
                    {
                        if (m_timerManager.cancelTimer(getTimerQueue()))
                        {
                            m_state = STATE_WAIT_REPLY;
                            executeOnRenderThread( new RenderThreadRunnable() {
                                public boolean runOnRenderThread(int frameId) {
                                    setBottomLineText(R.string.waiting, GAMBLE_TIMER_COLOR, 0.5f);
                                    return false;
                                }
                            } );

                            final RetainableByteBuffer msg = Protocol.Guess.create(m_byteBufferPool, (short) m_cupWithBall);
                            m_session.sendMessage(msg);
                            msg.release();
                        }
                    }
                    catch (final InterruptedException ex)
                    {
                        Log.w(LOG_TAG, "Exception:", ex);
                    }
                }
                else
                    m_state = STATE_GAMBLE;
            }
        }
        return true;
    }

    public Intent onPauseEx()
    {
        if (BuildConfig.DEBUG && m_pause)
            throw new AssertionError();

        m_pause = true;
        boolean interrupted = false;

        try
        {
            m_timerManager.cancelTimer( getTimerQueue() );
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, "Exception:", ex );
            interrupted = true;
        }

        try
        {
            stopCollider();
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, ex.toString() );
            interrupted = true;
        }

        /* Game acceptor is not working now, no new session can appear.
         * The only thing remaining is a service registration.
         */

        m_lock.lock();
        try
        {
            if (m_registrationListener != null)
            {
                if (!m_registrationListenerStop)
                {
                    m_registrationListenerStop = true;
                    m_nsdManager.unregisterService( m_registrationListener );
                }

                do
                {
                    m_cond.await();
                }
                while (m_registrationListener != null);
            }
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, ex.toString() );
            interrupted = true;
        }
        finally
        {
            m_lock.unlock();
        }

        if (interrupted)
            Thread.currentThread().interrupt();

        super.onPause();

        if (m_state == STATE_WAIT_CLIENT)
            return null;
        else if (m_clientDisconnected)
            return null; /* Activity result already set */
        else if (m_state == STATE_FINISHED)
        {
            final Intent result = new Intent();
            result.putExtra(MainActivity.EXTRA_DEVICE_ID, m_clientDeviceId);
            result.putExtra(MainActivity.EXTRA_WIN, (m_win > 0));
            return result;
        }
        else
        {
            final Intent result = new Intent();
            result.putExtra(MainActivity.EXTRA_TITLE_ID, R.string.info);
            result.putExtra(MainActivity.EXTRA_MESSAGE_ID, R.string.quit_game_before_end);
            result.putExtra(MainActivity.EXTRA_DEVICE_ID, m_clientDeviceId);
            result.putExtra(MainActivity.EXTRA_WIN, false);
            return result;
        }
    }
}
