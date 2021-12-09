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

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.monitor.MonitorSupport;
import com.oracle.svm.core.stack.JavaFrameAnchor;
import com.oracle.svm.core.stack.JavaFrameAnchors;

public final class LoomSupport {

    @Fold
    public static boolean isEnabled() {
        return JavaContinuations.isSupported() && SubstrateOptions.UseLoom.getValue();
    }

    public static Integer yield(Target_java_lang_Continuation cont) {
        return cont.internal.yield();
    }

    public static int isPinned(Target_java_lang_Thread thread, Target_java_lang_ContinuationScope scope, boolean isCurrentThread) {
        Target_java_lang_Continuation cont = thread.getContinuation();

        IsolateThread vmThread = isCurrentThread ? CurrentIsolate.getCurrentThread() : JavaThreads.getIsolateThread(SubstrateUtil.cast(thread, Thread.class));

        if (cont != null) {
            int threadMonitorCount = MonitorSupport.singleton().countThreadLock(vmThread);

            while (true) {
                if (cont.cs > 0) {
                    return JavaContinuations.PINNED_CRITICAL_SECTION;
                } else if (threadMonitorCount > cont.monitorBefore) {
                    return JavaContinuations.PINNED_MONITOR;
                }

                if (cont.getParent() != null && cont.getScope() != scope) {
                    cont = cont.getParent();
                } else {
                    break;
                }
            }

            JavaFrameAnchor anchor = JavaFrameAnchors.getFrameAnchor(vmThread);
            if (anchor.isNonNull() && cont.internal.sp.aboveThan(anchor.getLastJavaSP())) {
                return JavaContinuations.PINNED_NATIVE;
            }
        }
        return JavaContinuations.YIELD_SUCCESS;
    }

    public static boolean isStarted(Target_java_lang_Continuation cont) {
        return cont.isStarted();
    }

    public static Pointer getSP(Target_java_lang_Continuation cont) {
        return cont.internal.sp;
    }

    public static CodePointer getIP(Target_java_lang_Continuation cont) {
        return cont.internal.ip;
    }

    /**
     * Note this is different than `Thread.getContinuation`. `Thread.getContinuation` is orthogonal
     * with `VirtualThread.cont`.
     */
    public static Target_java_lang_Continuation getContinuation(Target_java_lang_Thread thread) {
        if (thread.isVirtual()) {
            Target_java_lang_VirtualThread vthread = SubstrateUtil.cast(thread, Target_java_lang_VirtualThread.class);
            return vthread.cont;
        }
        return thread.cont;
    }

    public static class CompatibilityUtil {
        static long getStackSize(Target_java_lang_Thread tjlt) {
            return isEnabled() ? tjlt.holder.stackSize : tjlt.stackSize;
        }

        static int getThreadStatus(Target_java_lang_Thread tjlt) {
            return isEnabled() ? tjlt.holder.threadStatus : tjlt.threadStatus;
        }

        static void setThreadStatus(Target_java_lang_Thread tjlt, int threadStatus) {
            if (isEnabled()) {
                tjlt.holder.threadStatus = threadStatus;
            } else {
                tjlt.threadStatus = threadStatus;
            }
        }

        static int getPriority(Target_java_lang_Thread tjlt) {
            if (isEnabled()) {
                return tjlt.holder.priority;
            } else {
                return tjlt.priority;
            }
        }

        static void setPriority(Target_java_lang_Thread tjlt, int priority) {
            if (isEnabled()) {
                tjlt.holder.priority = priority;
            } else {
                tjlt.priority = priority;
            }
        }

        static void setStackSize(Target_java_lang_Thread tjlt, long stackSize) {
            if (isEnabled()) {
                tjlt.holder.stackSize = stackSize;
            } else {
                tjlt.stackSize = stackSize;
            }
        }

        static void setDaemon(Target_java_lang_Thread tjlt, boolean isDaemon) {
            if (isEnabled()) {
                tjlt.holder.daemon = isDaemon;
            } else {
                tjlt.daemon = isDaemon;
            }
        }

        static void setGroup(Target_java_lang_Thread tjlt, ThreadGroup group) {
            if (isEnabled()) {
                tjlt.holder.group = group;
            } else {
                tjlt.group = group;
            }
        }

        static void setTarget(Target_java_lang_Thread tjlt, Runnable target) {
            if (isEnabled()) {
                tjlt.holder.task = target;
            } else {
                tjlt.target = target;
            }
        }

        static void initThreadFields(Target_java_lang_Thread tjlt, ThreadGroup group, Runnable target, long stackSize, int priority, boolean daemon, int threadStatus) {
            if (isEnabled()) {
                tjlt.holder = new Target_java_lang_Thread_FieldHolder(null, null, 0, 0, false);
            }
            setGroup(tjlt, group);

            setPriority(tjlt, priority);
            setDaemon(tjlt, daemon);

            CompatibilityUtil.setTarget(tjlt, target);
            tjlt.setPriority(priority);

            /* Stash the specified stack size in case the VM cares */
            CompatibilityUtil.setStackSize(tjlt, stackSize);

            CompatibilityUtil.setThreadStatus(tjlt, threadStatus);
        }
    }

    private LoomSupport() {
    }
}
