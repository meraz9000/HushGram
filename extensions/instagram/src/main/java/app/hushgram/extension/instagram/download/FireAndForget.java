/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import androidx.annotation.NonNull;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

/**
 * The continuation a gallery hand-off gives Instagram's suspending capture call.
 *
 * <p>The call's own state machine wraps this continuation and does the real resuming; this one
 * only receives the final value, or the failure, once the coroutine ends. It reports a failure to
 * the diagnostic log and otherwise does nothing, so the capture work runs exactly as a shutter
 * press's does and nothing waits on the caller.
 */
public final class FireAndForget implements Continuation<Object> {
    @NonNull
    @Override
    public CoroutineContext getContext() {
        return EmptyCoroutineContext.INSTANCE;
    }

    @Override
    public void resumeWith(@NonNull Object result) {
        if (result instanceof kotlin.Result.Failure) {
            Throwable failure = ((kotlin.Result.Failure) result).exception;
            app.hushgram.extension.shared.Logger.printException(
                () -> "The gallery instant's capture failed", failure);
        }
    }
}
