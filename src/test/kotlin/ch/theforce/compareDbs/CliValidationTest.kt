package ch.theforce.compareDbs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CliValidationTest {

    @Test
    fun `mongodb scheme is valid`() {
        assertTrue(isValidMongoUri("mongodb://localhost:27017/db"))
    }

    @Test
    fun `mongodb+srv scheme is valid`() {
        assertTrue(isValidMongoUri("mongodb+srv://cluster.mongodb.net/db"))
    }

    @Test
    fun `https scheme is invalid`() {
        assertFalse(isValidMongoUri("https://localhost:27017/db"))
    }

    @Test
    fun `blank string is invalid`() {
        assertFalse(isValidMongoUri(""))
    }

    @Test
    fun `plain hostname without scheme is invalid`() {
        assertFalse(isValidMongoUri("localhost:27017/db"))
    }
}
