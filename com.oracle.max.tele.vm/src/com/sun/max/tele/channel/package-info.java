/*
 * Copyright (c) 2010, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * The Inspector communicates with the target VM using the method-based protocol defined by
 * {@link com.sun.max.tele.TeleChannelProtocol}. To simplify an architecture where the Inspector runs on one (client)
 * machine and the VM runs on a separate (target) machine a minimal variant of the protocol,
 * {@link com.sun.max.tele.TeleChannelDataIOProtocol}, that is capable of being implemented using
 * {@link java.io.DataInputStream} and {@link java.io.DataOutputStream} is also defined.
 */
package com.sun.max.tele.channel;

