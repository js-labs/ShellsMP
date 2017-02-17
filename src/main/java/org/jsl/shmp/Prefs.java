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

import java.util.concurrent.TimeUnit;

public class Prefs
{
    public static final String PLAYER_NAME = "player-name";
    public static final String CHECK_WIFI_STATUS_ON_START = "check-wifi-status-on-start";
    public static final String RENDER_SHADOWS = "render-shadows";
    public static final String PING_INTERVAL = "ping-interval";
    public static final String PING_TIMEOUT = "ping-timeout";
    public static final TimeUnit PING_TIME_UNIT = TimeUnit.SECONDS;
    public static final long DEFAULT_PING_INTERVAL = 0;
    public static final long DEFAULT_PING_TIMEOUT = 10;
    public static final short DEFAULT_GAME_TIME = 20;
    public static final short DEFAULT_CAPS = 3;
    public static final boolean RENDER_DEBUG = true;
}
