package com.privee.app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthProblemTest {
    @Test
    fun `a refused log in to a session used on this device means it was deleted`() {
        assertEquals(AuthProblem.SessionGone, failedAuthProblem(AuthMode.LogIn, 401, validation = false, knownSession = true))
        assertEquals(AuthProblem.SessionGone, failedAuthProblem(AuthMode.LogIn, 404, validation = false, knownSession = true))
    }

    @Test
    fun `other refusals stay generic`() {
        assertEquals(AuthProblem.InvalidCredentials, failedAuthProblem(AuthMode.LogIn, 401, validation = false, knownSession = false))
        assertEquals(AuthProblem.InvalidCredentials, failedAuthProblem(AuthMode.Register, 401, validation = false, knownSession = true))
        assertEquals(AuthProblem.Validation, failedAuthProblem(AuthMode.LogIn, 401, validation = true, knownSession = true))
        assertEquals(AuthProblem.Http, failedAuthProblem(AuthMode.LogIn, 500, validation = false, knownSession = true))
    }
}
