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

import org.jsl.collider.TimerQueue;
import java.util.concurrent.TimeUnit;

public class PingConfig
{
    public final TimerQueue timerQueue;
    public final TimeUnit timeUnit;
    public final long interval;
    public final long timeout;

    public PingConfig( TimerQueue timerQueue, TimeUnit timeUnit, long interval, long timeout )
    {
        this.timerQueue = timerQueue;
        this.timeUnit = timeUnit;
        this.interval = interval;
        this.timeout = timeout;
    }
}
