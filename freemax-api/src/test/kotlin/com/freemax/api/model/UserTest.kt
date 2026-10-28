package com.freemax.api.model

import org.junit.Assert.*
import org.junit.Test

class UserTest {
    @Test
    fun `full name combines first and last`() {
        val user = User(firstName = "Иван", lastName = "Иванов")
        assertEquals("Иван Иванов", user.fullName)
    }

    @Test
    fun `full name with only first`() {
        val user = User(firstName = "Иван")
        assertEquals("Иван", user.fullName)
    }

    @Test
    fun `full name unknown if empty`() {
        val user = User()
        assertEquals("Unknown", user.fullName)
    }

    @Test
    fun `initials from first and last`() {
        val user = User(firstName = "Иван", lastName = "Иванов")
        assertEquals("ИИ", user.initials)
    }

    @Test
    fun `initials question mark if empty`() {
        val user = User()
        assertEquals("?", user.initials)
    }
}
