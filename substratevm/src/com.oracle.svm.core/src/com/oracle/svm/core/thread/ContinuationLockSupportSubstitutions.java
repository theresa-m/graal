/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.thread;

import java.util.concurrent.locks.LockSupport;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.ContinuationsSupported;
import com.oracle.svm.core.jdk.NotLoomJDK;

@TargetClass(value = LockSupport.class, onlyWith = {ContinuationsSupported.class, NotLoomJDK.class})
final class Target_java_util_concurrent_locks_LockSupport {

    @Alias static Target_jdk_internal_misc_Unsafe_JavaThreads U;

    @Alias
    static native void setBlocker(Thread thread, Object blocker);

    @Substitute
    static void unpark(Thread thread) {
        if (thread != null) {
            if (thread instanceof VirtualThread) {
                ((VirtualThread) thread).unpark(); // can throw RejectedExecutionException
            } else {
                U.unpark(thread);
            }
        }
    }

    @Substitute
    static void park(Object blocker) {
        Thread t = Thread.currentThread();
        setBlocker(t, blocker);
        try {
            if (t instanceof VirtualThread) {
                ((VirtualThread) t).park();
            } else {
                U.park(false, 0L);
            }
        } finally {
            setBlocker(t, null);
        }
    }

    @Substitute
    static void parkNanos(Object blocker, long nanos) {
        if (nanos > 0) {
            Thread t = Thread.currentThread();
            setBlocker(t, blocker);
            try {
                if (t instanceof VirtualThread) {
                    ((VirtualThread) t).parkNanos(nanos);
                } else {
                    U.park(false, nanos);
                }
            } finally {
                setBlocker(t, null);
            }
        }
    }

    @Substitute
    static void parkUntil(Object blocker, long deadline) {
        Thread t = Thread.currentThread();
        setBlocker(t, blocker);
        try {
            if (t instanceof VirtualThread) {
                ((VirtualThread) t).parkUntil(deadline);
            } else {
                U.park(true, deadline);
            }
        } finally {
            setBlocker(t, null);
        }
    }

    @Substitute
    static void park() {
        if (Thread.currentThread() instanceof VirtualThread) {
            ((VirtualThread) Thread.currentThread()).park();
        } else {
            U.park(false, 0L);
        }
    }

    @Substitute
    public static void parkNanos(long nanos) {
        if (nanos > 0) {
            if (Thread.currentThread() instanceof VirtualThread) {
                ((VirtualThread) Thread.currentThread()).parkNanos(nanos);
            } else {
                U.park(false, nanos);
            }
        }
    }

    @Substitute
    public static void parkUntil(long deadline) {
        if (Thread.currentThread() instanceof VirtualThread) {
            ((VirtualThread) Thread.currentThread()).parkUntil(deadline);
        } else {
            U.park(true, deadline);
        }
    }
}

final class ContinuationLockSupportSubstitutions {
    private ContinuationLockSupportSubstitutions() {
    }
}
