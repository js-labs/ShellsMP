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

import android.util.Log;
import org.jsl.collider.TimerQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class TimerManager
{
    private static final String LOG_TAG = TimerManager.class.getSimpleName();

    private final ReentrantLock m_lock;
    private int m_schedule;
    private int m_cancel;
    private boolean m_reset;
    private Condition m_cond;
    private TimerQueue.Task m_timerTask;

    public TimerManager()
    {
        m_lock = new ReentrantLock();
    }

    public boolean scheduleTimer( TimerQueue timerQueue, TimerQueue.Task timerTask )
    {
        m_lock.lock();
        try
        {
            Log.d( LOG_TAG, "scheduleTimer: schedule=" + m_schedule + " cancel=" + m_cancel );

            if (BuildConfig.DEBUG)
            {
                if (m_reset)
                    throw new AssertionError();
                if (m_cancel == 1)
                    throw new AssertionError();
            }

            if (m_cancel == 2)
                return false;

            m_schedule = 1;
        }
        finally
        {
            m_lock.unlock();
        }

        timerQueue.schedule( timerTask, timerTask.run(), TimeUnit.MILLISECONDS );

        m_lock.lock();
        try
        {
            Log.d( LOG_TAG, "scheduleTimer: schedule=" + m_schedule + " cancel=" + m_cancel );

            m_schedule = 2;
            if (m_cancel == 0)
            {
                m_timerTask = timerTask;
            }
            else if (m_cancel == 1)
            {
                m_timerTask = timerTask;
                m_cond.signal();
            }
            else if (m_cancel == 2)
            {
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
        }
        finally
        {
            m_lock.unlock();
        }

        return true;
    }

    public boolean resetTimer( TimerQueue.Task timerTask )
    {
        m_lock.lock();
        try
        {
            Log.d( LOG_TAG, "resetTimer: schedule=" + m_schedule + " cancel=" + m_cancel );

            if (BuildConfig.DEBUG && m_reset)
                throw new AssertionError();

            if (m_schedule == 0)
            {
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
            else if (m_schedule == 1)
            {
                if (BuildConfig.DEBUG && (m_timerTask != null))
                    throw new AssertionError();
                m_reset = true;
            }
            else if (m_schedule == 2)
            {
                if (BuildConfig.DEBUG && (m_timerTask != null) && (m_timerTask != timerTask))
                    throw new AssertionError();
                m_reset = true;
            }
            else if (BuildConfig.DEBUG)
                throw new AssertionError();

            return ((m_schedule == 2) && (m_cancel == 0));
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public boolean cancelTimer( TimerQueue timerQueue ) throws InterruptedException
    {
        TimerQueue.Task timerTask;

        m_lock.lock();
        try
        {
            Log.d( LOG_TAG, "cancelTimer: schedule=" + m_schedule + " cancel=" + m_cancel + " reset=" + m_reset );

            if (m_reset)
            {
                /* Timer already fired and canceled itself, do nothing. */
                return false;
            }

            if (m_schedule == 0)
            {
                m_cancel = 2;
                return false;
            }
            else if (m_schedule == 1)
            {
                if (m_cond == null)
                    m_cond = m_lock.newCondition();

                m_cond.await();

                if (BuildConfig.DEBUG && (m_schedule != 2))
                    throw new AssertionError();

                if (m_reset)
                    return false;

                if (m_cancel == 0)
                {
                    m_cancel = 1;
                    timerTask = m_timerTask;
                    m_timerTask = null;
                }
                else if (m_cancel == 1)
                {
                    /* Not expected */
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                    return false;
                }
                else if (m_cancel == 2)
                    return false;
                else
                {
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                    return false;
                }
            }
            else if (m_schedule == 2)
            {
                if (m_cancel == 0)
                {
                    m_cancel = 1;
                    timerTask = m_timerTask;
                    m_timerTask = null;
                }
                else if (m_cancel == 1)
                {
                    if (m_cond == null)
                        m_cond = m_lock.newCondition();
                    m_cond.await();
                    return false;
                }
                else if (m_cancel == 2)
                {
                    return false;
                }
                else
                {
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                    return false;
                }
            }
            else
            {
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
                return false;
            }
        }
        finally
        {
            m_lock.unlock();
        }

        final int rc = timerQueue.cancel( timerTask );

        m_lock.lock();
        try
        {
            m_cancel = 2;
            if (m_cond != null)
                m_cond.signalAll();
        }
        finally
        {
            m_lock.unlock();
        }

        return (rc == 0);
    }
}
