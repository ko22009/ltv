package ru.lazytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The season and episode numbers are what the two devices agree on: a wrong reading opens the wrong episode. */
class TitleProgressTest {
    @Test
    fun `season and episode out of the names the sources give`() {
        assertEquals(2 to 5, TitleProgress.episodeOf("Сезон 2 • Серия 5"))
        assertEquals(1 to 7, TitleProgress.episodeOf("7 серия"))
        assertEquals(3 to 12, TitleProgress.episodeOf("3 сезон 12 серия"))
        assertEquals(2 to 5, TitleProgress.episodeOf("Show.S02E05.1080p"))
    }

    @Test
    fun `a name without an episode number is not guessed`() {
        assertNull(TitleProgress.episodeOf("Трейлер"))
    }
}
