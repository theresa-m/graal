/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.graal.pointsto.meta;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.oracle.graal.pointsto.heap.ImageHeapScanner.Reason;

abstract class ElementInfo implements Reason {
    /**
     * Tracks if the element is marked as reachable. The initial value of {@code null} means not
     * reachable, any non-null value means reachable. The value stores the reason, i.e., a reference
     * to any other object that provides debugging information why the element was marked as
     * reachable.
     */
    public final AtomicReference<Reason> reachable = new AtomicReference<>();

    /*
     * Contains all registered reachability handler that are notified when the element is marked as
     * reachable.
     */
    // final Set<ElementReachableNotification> elementReachableNotifications =
    // ConcurrentHashMap.newKeySet();

    /** Atomically mark the element as reachable. */
    public boolean atomicMark(Reason reason) {
        return reachable.compareAndSet(null, Objects.requireNonNull(reason));
    }

}
