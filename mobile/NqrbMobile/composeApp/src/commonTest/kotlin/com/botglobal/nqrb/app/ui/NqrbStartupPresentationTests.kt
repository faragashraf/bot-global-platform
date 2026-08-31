package com.botglobal.nqrb.app.ui

import com.botglobal.mobile.platform.calling.CallState
import com.botglobal.nqrb.app.state.NqrbDestination
import com.botglobal.nqrb.app.state.NqrbStartupState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NqrbStartupPresentationTests {
    @Test
    fun google_sign_in_is_hidden_until_session_restoration_finishes() {
        assertTrue(showsRestoringSession(NqrbStartupState.RestoringSession, CallState.Idle))
        assertFalse(showsGoogleSignIn(NqrbStartupState.RestoringSession, NqrbDestination.SignIn))
        assertTrue(showsGoogleSignIn(NqrbStartupState.Ready, NqrbDestination.SignIn))
    }

    @Test
    fun incoming_call_surface_takes_priority_over_compose_session_restoration() {
        assertFalse(showsRestoringSession(NqrbStartupState.RestoringSession, CallState.Ringing))
        assertFalse(showsRestoringSession(NqrbStartupState.RestoringSession, CallState.Answering))
    }
}
