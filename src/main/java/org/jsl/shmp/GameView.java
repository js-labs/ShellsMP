/*
 * Copyright (C) 2016 Sergey Zubarev, info@js-labs.org
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
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.opengl.GLES20;
import android.util.Log;
import android.view.ViewConfiguration;
import org.jsl.collider.Collider;
import org.jsl.collider.TimerQueue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public abstract class GameView extends GLSurfaceView implements GLSurfaceView.Renderer
{
    protected static final float VIRTUAL_TABLE_WIDTH = 1000.0f;
    protected static final int BALL_COLOR = Color.GREEN;
    protected static final int CUP_STRIPES = 8;

    protected static final int GAMBLE_TIMER_COLOR = Color.GREEN;
    protected static final float GAMBLE_TIMER_FONT_SIZE = 0.8f;

    protected static final int WIN_TEXT_COLOR = Color.GREEN;
    protected static final int LOSE_TEXT_COLOR = Color.RED;

    private static String LOG_TAG = GameView.class.getSimpleName();

    private static AtomicReferenceFieldUpdater<RenderThreadRunnable, RenderThreadRunnable> s_renderThreadRunnableNextUpdater =
            AtomicReferenceFieldUpdater.newUpdater( RenderThreadRunnable.class, RenderThreadRunnable.class, "nextRenderThreadRunnable" );

    private static AtomicReferenceFieldUpdater<GameView, RenderThreadRunnable> s_tailUpdater =
            AtomicReferenceFieldUpdater.newUpdater(GameView.class, RenderThreadRunnable.class, "m_tail");

    public static abstract class RenderThreadRunnable
    {
        private volatile RenderThreadRunnable nextRenderThreadRunnable;
        public abstract boolean runOnRenderThread(int frameId);
    }

    protected static abstract class GambleTimer implements TimerQueue.Task
    {
        private final GameActivity m_activity;
        private final int m_timeout;
        private long m_endTime;
        private long m_lastSwitchTime;
        private long m_value;

        protected abstract void onStop();
        protected abstract void onUpdate( final long value, final float fontSize );

        public GambleTimer( GameActivity activity, int timeout )
        {
            m_activity = activity;
            m_timeout = timeout;
            m_endTime = 0;
            m_lastSwitchTime = System.currentTimeMillis();
            m_value = -1;
        }

        public long run()
        {
            final long currentTime = System.currentTimeMillis();
            if (m_endTime == 0)
            {
                m_lastSwitchTime = currentTime;
                m_endTime = (currentTime + TimeUnit.SECONDS.toMillis(m_timeout) - 1);
                m_value = m_timeout;
            }

            if (currentTime >= m_endTime)
            {
                onStop();
                return 0;
            }
            else
            {
                final long FADE_TIME = 200; /*ms*/
                final long value = ((m_endTime - currentTime) / 1000);
                final long tm = (currentTime - m_lastSwitchTime) % 1000;

                float fontSize = GAMBLE_TIMER_FONT_SIZE;
                long interval;
                if (tm < FADE_TIME)
                {
                    interval = (FADE_TIME - tm);
                    fontSize += fontSize * ((float) interval) / ((float)FADE_TIME) * 0.15f;
                    if (interval > 30)
                        interval = 30;
                }
                else
                    interval = (1000 - tm);

                if (m_value != value)
                {
                    m_lastSwitchTime = (currentTime - tm);
                    m_value = value;
                    m_activity.playSound_Tick();
                }

                onUpdate( value+1, fontSize );
                return interval;
            }
        }
    }

    private final String m_deviceId;
    private final String m_playerName;
    private final String m_strPing;
    private final long m_pingInterval;
    private final long m_pingTimeout;
    private final int m_topReservedHeight;
    private final int m_bottomReservedHeight;
    private final Paint m_paint;
    private final int m_touchSlop;

    private final Runnable m_renderProcessor;
    private final RenderThreadRunnable m_execMarker;
    private RenderThreadRunnable m_head;
    private volatile RenderThreadRunnable m_tail;
    private int m_frameId;

    private int m_viewWidth;
    private int m_viewHeight;
    private float [] m_vpMatrix;
    private Canvas3D m_canvas3D;
    private Collider m_collider;
    private TimerQueue m_timerQueue;
    private Thread m_colliderThread;
    private PingConfig m_pingConfig;

    private Canvas3D.Sprite m_statusLine;
    private FloatBuffer m_statusLineDebug;

    abstract protected Bitmap createStatusLine();
    abstract public void setPing( int ping );

    private void colliderThread()
    {
        Log.d( LOG_TAG, "Collider: run" );
        m_collider.run();
        Log.d( LOG_TAG, "Collider: done" );
    }

    private void processUpdates()
    {
        final int frameId = ++m_frameId;
        RenderThreadRunnable runnable = m_head;
        for (;;)
        {
            RenderThreadRunnable next = s_renderThreadRunnableNextUpdater.get( runnable );
            if (next == null)
            {
                if (BuildConfig.DEBUG && (m_execMarker.nextRenderThreadRunnable != null))
                    throw new AssertionError();

                if (s_tailUpdater.compareAndSet(this, runnable, m_execMarker))
                {
                    final boolean renderFrame = runnable.runOnRenderThread(frameId);

                    m_head = null;
                    if (s_tailUpdater.compareAndSet(this, m_execMarker, null))
                    {
                        if (BuildConfig.DEBUG && (m_execMarker.nextRenderThreadRunnable != null))
                            throw new AssertionError();
                        break;
                    }

                    while ((next = m_execMarker.nextRenderThreadRunnable) == null);
                    s_renderThreadRunnableNextUpdater.lazySet(m_execMarker, null);

                    if (renderFrame)
                    {
                        m_head = next;
                        queueEvent(m_renderProcessor);
                        break;
                    }

                    runnable = next;
                    continue;
                }

                while ((next = s_renderThreadRunnableNextUpdater.get(runnable)) == null) ;
                s_renderThreadRunnableNextUpdater.lazySet( runnable, null );
            }
            else
                s_renderThreadRunnableNextUpdater.lazySet( runnable, null );

            final boolean renderFrame = runnable.runOnRenderThread(frameId);
            if (renderFrame)
            {
                m_head = next;
                queueEvent(m_renderProcessor);
                break;
            }
            runnable = next;
        }

        requestRender();
    }

    protected void executeOnRenderThread( RenderThreadRunnable runnable )
    {
        for (;;)
        {
            final RenderThreadRunnable tail = s_tailUpdater.get( this );
            if (s_tailUpdater.compareAndSet(this, tail, runnable))
            {
                if (tail == null)
                {
                    m_head = runnable;
                    queueEvent( m_renderProcessor );
                }
                else
                    s_renderThreadRunnableNextUpdater.set( tail, runnable );
                break;
            }
        }
    }

    protected void setStatusLine(Bitmap bitmap)
    {
        m_statusLine.setBitmap(bitmap);
        bitmap.recycle();
    }

    protected Bitmap createStatusLine(int ping, String player2Name)
    {
        final Paint paint = m_paint;
        final int width = getWidth();
        final Bitmap bitmap = Bitmap.createBitmap(width, getTopReservedHeight(), Bitmap.Config.RGB_565);
        final Canvas canvas = new Canvas(bitmap);
        final float textY = -paint.ascent();

        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.FILL);

        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(m_playerName, 0, textY, paint);

        if (ping >= 0)
        {
            final String str = m_strPing + Integer.toString(ping);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(str, width / 2, textY, paint);
        }

        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(player2Name, width, textY, paint);

        return bitmap;
    }

    protected int getTopReservedHeight()
    {
        return m_topReservedHeight;
    }

    protected int getBottomReservedHeight()
    {
        return m_bottomReservedHeight;
    }

    protected int getTouchSlop()
    {
        return m_touchSlop;
    }

    private int getReservedHeight()
    {
        return (m_topReservedHeight + m_bottomReservedHeight + 2);
    }

    protected short getDesiredTableHeight(int viewWidth, int viewHeight)
    {
        /* Square table would be the best,
         * but if screen does not feet then wider is better than higher.
         */
        final int reservedHeight = getReservedHeight();
        if (reservedHeight > viewHeight)
            throw new AssertionError();

        final int tableWidth = viewWidth;
        int tableHeight = (viewHeight - getReservedHeight());
        if (tableHeight > tableWidth)
            tableHeight = tableWidth;

        return (short) (VIRTUAL_TABLE_WIDTH / tableWidth * tableHeight);
    }

    protected Collider startCollider() throws IOException
    {
        if (BuildConfig.DEBUG && (m_collider != null))
            throw new AssertionError();

        final Collider.Config colliderConfig = new Collider.Config();
        colliderConfig.byteOrder = Protocol.BYTE_ORDER;
        m_collider = Collider.create( colliderConfig );

        m_timerQueue = new TimerQueue( m_collider.getThreadPool() );
        m_pingConfig = new PingConfig( m_timerQueue, Prefs.PING_TIME_UNIT, m_pingInterval, m_pingTimeout );

        m_colliderThread = new Thread("RenderThread") {
            public void run() {
                colliderThread();
            }
        };
        m_colliderThread.start();
        return m_collider;
    }

    protected void stopCollider() throws InterruptedException
    {
        if (m_collider != null)
        {
            m_collider.stop();
            m_colliderThread.join();
        }
    }

    protected TimerQueue getTimerQueue()
    {
        return m_timerQueue;
    }

    protected String getDeviceId()
    {
        return m_deviceId;
    }

    protected String getPlayerName()
    {
        return m_playerName;
    }

    protected Paint getPaint()
    {
        return m_paint;
    }

    protected PingConfig getPingConfig()
    {
        return m_pingConfig;
    }

    protected int getViewWidth() { return m_viewWidth; }
    protected int getViewHeight() { return m_viewHeight; }

    public GameView(Context context, String deviceId, String playerName)
    {
        super( context );

        m_deviceId = deviceId;
        m_playerName = playerName;
        m_strPing = getResources().getString( R.string.ping );

        final SharedPreferences prefs = context.getSharedPreferences( MainActivity.class.getSimpleName(), Context.MODE_PRIVATE );
        m_pingInterval = prefs.getLong( Prefs.PING_INTERVAL, Prefs.DEFAULT_PING_INTERVAL );
        m_pingTimeout = prefs.getLong( Prefs.PING_TIMEOUT, Prefs.DEFAULT_PING_TIMEOUT );

        final int topReservedHeight = context.getResources().getDimensionPixelSize( R.dimen.topReservedHeight );
        m_topReservedHeight = (topReservedHeight + topReservedHeight/4);
        m_bottomReservedHeight = context.getResources().getDimensionPixelOffset( R.dimen.bottomReservedHeight );

        m_paint = new Paint();
        m_paint.setTextSize( topReservedHeight );

        final ViewConfiguration viewConfig = ViewConfiguration.get( context );
        m_touchSlop = viewConfig.getScaledTouchSlop();

        m_renderProcessor = new Runnable() {
            public void run() {
                processUpdates();
            }
        };

        m_execMarker = new RenderThreadRunnable() {
            public boolean runOnRenderThread(int frameId) {
                /* should never be called */
                throw new AssertionError();
            }
        };

        setEGLContextClientVersion( 2 );
        setRenderer( this );
        setRenderMode( GLSurfaceView.RENDERMODE_WHEN_DIRTY );
    }

    public void onSurfaceCreated(GL10 notUsed, EGLConfig config)
    {
        Log.d(LOG_TAG, "onSurfaceCreated");
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glFrontFace(GLES20.GL_CW);
    }

    public void onSurfaceChanged(GL10 gl, int width, int height)
    {
        Log.d(LOG_TAG, "onSurfaceChanged");

        GLES20.glViewport(0, 0, width, height);

        m_viewWidth = width;
        m_viewHeight = height;
        m_vpMatrix = new float[16];
        final float [] matrix = new float[32];
        Matrix.orthoM(
                matrix, 0,
                /*left  */ -1f,
                /*right */ width,
                /*bottom*/ -1f,
                /*top   */ height,
                /*near  */ 1f,
                /*far   */ 300f );

        Matrix.setLookAtM(
                matrix, 16,
                /*eye X   */ 0f,
                /*eye Y   */ 0f,
                /*eye Z   */ 200f,
                /*center X*/ 0f,
                /*center Y*/ 0f,
                /*center Z*/ 0f,
                /*up X    */ 0f,
                /*up Y    */ 1f,
                /*up Z    */ 0f );

        Matrix.multiplyMM(m_vpMatrix, 0, matrix, 0, matrix, 16);

        try
        {
            m_canvas3D = new Canvas3D(getContext(), /*text size for text renderer*/m_bottomReservedHeight);
            m_statusLine = new Canvas3D.Sprite();

            final Bitmap statusLineBitmap = createStatusLine();
            final float statusLineTop = (height - 1);
            m_statusLine.setBitmap( statusLineBitmap );
            m_statusLine.y = statusLineTop;

            if (Prefs.RENDER_DEBUG)
            {
                final float statusLineLeft = 0;
                final float statusLineRight = (width - 1);
                final float statusLineBottom = (height - 1 - statusLineBitmap.getHeight());

                final float [] statusLineDebugVertices =
                {
                     statusLineLeft,    statusLineTop, 1f,
                    statusLineRight,    statusLineTop, 1f,
                    statusLineRight, statusLineBottom, 1f,
                     statusLineLeft, statusLineBottom, 1f,
                     statusLineLeft,    statusLineTop, 1f
                };

                final int bufferCapacity = (Float.SIZE / Byte.SIZE) * statusLineDebugVertices.length;
                final ByteBuffer byteBuffer = ByteBuffer.allocateDirect( bufferCapacity );
                byteBuffer.order( ByteOrder.nativeOrder() );
                m_statusLineDebug = byteBuffer.asFloatBuffer();
                m_statusLineDebug.put( statusLineDebugVertices );
            }

            statusLineBitmap.recycle();
        }
        catch (final IOException ex)
        {
            Log.d( LOG_TAG, ex.toString() );
        }
    }

    public void onDrawFrame(GL10 gl)
    {
        //Log.d( LOG_TAG, "onDrawFrame" );
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        onDrawFrame(m_vpMatrix, m_canvas3D);
    }

    public void onDrawFrame(float [] vpMatrix, Canvas3D canvas3D)
    {
        canvas3D.draw(vpMatrix, m_statusLine);

        if (m_statusLineDebug != null)
        {
            m_statusLineDebug.position(0);
            canvas3D.drawLines(vpMatrix, 3, 5, m_statusLineDebug, Color.RED);
        }
    }
}
