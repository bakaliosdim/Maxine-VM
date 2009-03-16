/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
/*
 * @author Bernd Mathiske
 */

#include <string.h>
#include "log.h"
#include "jni.h"

#include "proc.h"

#include "teleProcess.h"
#include "teleNativeThread.h"

void teleProcess_initialize(void) {
}


JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeReadBytes(JNIEnv *env, jclass c, jlong handle, jlong address, jbyteArray byteArray, jint offset, jint length) {
    struct ps_prochandle *ph = (struct ps_prochandle *) handle;

    jbyte* buffer = (jbyte *) malloc(length * sizeof(jbyte));
    if (buffer == 0) {
        log_println("failed to malloc byteArray of %d bytes", length);
        return -1;
    }

    ssize_t bytesRead = proc_Pread(ph, buffer, length, (uintptr_t) address);
    if (bytesRead > 0) {
        (*env)->SetByteArrayRegion(env, byteArray, offset, bytesRead, buffer);
    }
    free(buffer);
    return bytesRead;
}

JNIEXPORT jint JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeWriteBytes(JNIEnv *env, jclass c, jlong handle, jlong address, jbyteArray byteArray, jint offset, jint length) {
    struct ps_prochandle *ph = (struct ps_prochandle *) handle;

    jbyte* buffer = (jbyte *) malloc(length * sizeof(jbyte));
    if (buffer == 0) {
        log_println("failed to malloc byteArray of %d bytes", length);
        return -1;
    }

    (*env)->GetByteArrayRegion(env, byteArray, offset, length, buffer);
    if ((*env)->ExceptionOccurred(env) != NULL) {
        log_println("failed to copy %d bytes from byteArray into buffer", length);
        return -1;
    }

    ssize_t bytesWritten = proc_Pwrite(ph, buffer, length, (uintptr_t) address);
    free(buffer);
    return bytesWritten;
}

JNIEXPORT jlong JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeCreateChild(JNIEnv *env, jclass c, long commandLineArgumentArray, jint vmAgentPort) {
    int error;
    char path[MAX_PATH_LENGTH];
    char **argv = (char**) commandLineArgumentArray;

#if log_TELE
    log_println("argv[0]: %s", argv[0]);
#endif

    int portDefSize = strlen("MAX_AGENT_PORT=") + 11;
    char *portDef = (char *) malloc(portDefSize);
    if (portDef == NULL || snprintf(portDef, portDefSize, "MAX_AGENT_PORT=%u", vmAgentPort) < 0) {
        log_exit(1, "Could not set MAX_AGENT_PORT environment variable");
    }
    putenv(portDef);

    struct ps_prochandle *ph = proc_Pcreate(argv[0], argv, &error, path, sizeof(path));
    if (error != 0) {
        log_println("could not create child process: %s", Pcreate_error(error));
        return NULL;
    }

    return (jlong) ph;
}

JNIEXPORT void JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeKill(JNIEnv *env, jclass c, jlong processHandle) {
    struct ps_prochandle *ph = (struct ps_prochandle *) processHandle;
    int state = proc_Pstate(ph);
    if (state != PS_LOST && state != PS_DEAD && state != PS_UNDEAD) {
        Prelease(ph, PRELEASE_KILL);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeSuspend(JNIEnv *env, jclass c, jlong processHandle) {
    struct ps_prochandle *ph = (struct ps_prochandle *) processHandle;
    if (Pdstop(ph) != 0) {
        log_println("Cannot stop the process");
        return false;
    }
    return true;
}


JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeResume(JNIEnv *env, jclass c, jlong processHandle) {
    struct ps_prochandle *ph = (struct ps_prochandle *) processHandle;

    sysset_t syscalls;
    premptyset(&syscalls);
    proc_Psetsysentry(ph, &syscalls);
    proc_Psetsysexit(ph, &syscalls);

    sigset_t signals;
    premptyset(&signals);
    Psetsignal(ph, &signals);

    fltset_t faults;
    premptyset(&faults);
    praddset(&faults, FLTILL);
    praddset(&faults, FLTPRIV);
    praddset(&faults, FLTBPT);
    praddset(&faults, FLTTRACE);
    //praddset(&faults, FLTACCESS);
    //praddset(&faults, FLTBOUNDS);
    praddset(&faults, FLTIOVF);
    //praddset(&faults, FLTIZDIV);
    praddset(&faults, FLTFPE);
    praddset(&faults, FLTSTACK);
    praddset(&faults, FLTWATCH);
    Psetfault(ph, &faults);

    if (Pclearfault(ph) != 0) {
        log_println("Pclearfault failed");
        return false;
    }

    if (Pclearsig(ph) != 0) {
        log_println("Pclearsig failed");
        return false;
    }

    proc_Psync(ph);

    if (proc_Psetrun(ph, 0, 0) != 0) {
        log_println("Psetrun failed, proc_Pstate %d", proc_Pstate(ph));
        return false;
    }
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeWait(JNIEnv *env, jclass c, jlong processHandle) {
    struct ps_prochandle *ph = (struct ps_prochandle *) processHandle;
    if (proc_Pwait(ph, 0) != 0) {
        int rc = proc_Pstate(ph);
        log_println("nativeResume: Pwait failed, proc_Pstate %d", rc);
        return false;
    }

    if (Pclearfault(ph) != 0) {
        int rc = proc_Pstate(ph);
        log_println("Pclearfault failed, proc_Pstate %d", rc);
        return false;
    }

    if (Pclearsig(ph) != 0) {
        int rc = proc_Pstate(ph);
        log_println("Pclearsig failed, proc_Pstate %d", rc);
        return false;
    }

    proc_Psync(ph);
    return true;
}

ThreadState_t lwpStatusToThreadState(const lwpstatus_t *lwpStatus) {
    short why = lwpStatus->pr_why;
    short what = lwpStatus->pr_what;
    int flags = lwpStatus->pr_flags;

    /* This is only called after a Pwait so all threads should be stopped. */
    c_ASSERT((lwpStatus->pr_flags & PR_STOPPED) != 0);

    ThreadState_t result = TS_SUSPENDED;
    if (why == PR_FAULTED) {
        if (what == FLTBPT){
            result = TS_BREAKPOINT;
        } else if (what == FLTWATCH) {
            result = TS_WATCHPOINT;
        }
    }
    return result;
}

#if 0
WaitResult_t reasonForStopping(lwpstatus_t *lwpStatus) {
    short why = lwpStatus->pr_why;
    short what = lwpStatus->pr_what;


    log_printWhyStopped("LWP stopped: reason = ", lwpStatus, "\n");

    if (why == PR_REQUESTED) {
        /* TODO: need to find out why this is the right answer! */
        return SYSTEM_CALL;
    }

    if (why == PR_SIGNALLED) {
        return SIGNAL;
    }

    if (why == PR_FAULTED && what == FLTBPT){
        return BREAKPOINT;
    }

    if (why == PR_FAULTED && what == FLTWATCH) {
        return WATCHPOINT;
    }

    return DEFAULT;
}
#endif

typedef struct Argument {
    struct ps_prochandle *ph;
    JNIEnv *env;
    jobject process;
    jobject result;
} *Argument;

static jmethodID _methodID = NULL;

static int gatherThread(void *data, const lwpstatus_t *lwpStatus) {
    Argument a = (Argument) data;
    pstatus_t *pStatus = (pstatus_t *) proc_Pstatus(a->ph);
    if (lwpStatus->pr_lwpid == pStatus->pr_agentid) {
        // Ignore the agent thread (i.e. the thread communicating with the inspector)
        return 0;
    }

    jlong lwpId = lwpStatus->pr_lwpid;
    ThreadState_t threadState = lwpStatusToThreadState(lwpStatus);

    ThreadSpecificsStruct tss;
    threadSpecificsList_search(ph, threadSpecificsListAddress, threadState.__rsp, &tss);

    if (_methodID == NULL) {
        jclass c = (*a->env)->GetObjectClass(a->env, a->process);
        c_ASSERT(c != NULL);
        _methodID = (*a->env)->GetMethodID(a->env, c, "jniGatherThread", "(Lcom/sun/max/collect/AppendableSequence;JIJJ)V");
        c_ASSERT(_methodID != NULL);
    }

    tele_log_println("Gathered thread[id=%lu, stackBase=%p, stackEnd=%p, stackSize=%lu, triggeredVmThreadLocals=%p, enabledVmThreadLocals=%p, disabledVmThreadLocals=%p]",
                    lwpId,
                    tss.stackBase,
                    tss.stackBase + tss.stackSize,
                    tss.stackSize,
                    tss.triggeredVmThreadLocals,
                    tss.enabledVmThreadLocals,
                    tss.disabledVmThreadLocals);

    (*a->env)->CallVoidMethod(a->env, a->process, _methodID, a->result, lwpId, threadState,
                    tss.stackBase,
                    tss.stackSize,
                    tss.triggeredVmThreadLocals,
                    tss.enabledVmThreadLocals,
                    tss.disabledVmThreadLocals);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeGatherThreads(JNIEnv *env, jobject process, jlong processHandle, jobject result) {
    struct ps_prochandle *ph = (struct ps_prochandle *) processHandle;

    if (Pcreate_agent(ph) != 0) {
        log_println("could not create agent lwp in tele process");
    }

    struct Argument a;
    a.ph = ph;
    a.env = env;
    a.process = process;
    a.result = result;

    int error = Plwp_iter(ph, gatherThread, &a);
    if (error != 0) {
        log_println("Error iterating over threads of process");
    }

    Pdestroy_agent(ph);
}

JNIEXPORT jboolean JNICALL
Java_com_sun_max_tele_debug_solaris_SolarisTeleProcess_nativeActivateWatchpoint(JNIEnv *env, jclass c, jlong processHandle, jlong address, jlong size) {
    struct ps_prochandle *ph = (struct ps_prochandle *) processHandle;
    prwatch_t w;
    w.pr_vaddr = address;
    w.pr_size = size;
    w.pr_wflags = WA_WRITE | WA_TRAPAFTER;
    w.pr_pad = 0;

    int error = Psetwapt(ph, &w);
    if (error != 0) {
        log_println("could not set watch point - error: %d", error);
        return false;
    }
    proc_Psync(ph);
    return true;
}
