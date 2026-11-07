package com.photerest.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PhotoRulesTest {
    @Test fun emptyAndSingletonAreValidRounds() {
        assertEquals(emptyList<Int>(), nextPermutation<Int>(emptyList(), emptyList()))
        assertEquals(listOf(7), nextPermutation(listOf(7), listOf(7)))
    }
    @Test fun roundsContainEveryPhotoOnceAndChangeOrder() {
        var previous = (1..80).toList()
        repeat(100) { seed ->
            val next = nextPermutation(previous + previous.take(3), previous, Random(seed))
            assertEquals(80, next.size)
            assertEquals(previous.toSet(), next.toSet())
            assertNotEquals(previous, next)
            previous = next
        }
    }
    @Test fun twoPhotoRoundCannotRepeatEvenWhenRandomDrawMatches() {
        repeat(50) { assertEquals(listOf(2, 1), nextPermutation(listOf(1, 2), listOf(1, 2), Random(it))) }
    }
    private val photo = Photo("content://media/external/images/media/1", "a.jpg", "album", "image/jpeg", 100, 200, 1500, 0, 100)
    @Test fun missingOrReusedSourceIsNotShownAsOldFavorite() {
        assertFalse(sameSource(photo, null))
        assertFalse(sameSource(photo, photo.copy(added = 101)))
        assertFalse(sameSource(photo, photo.copy(name = "b.jpg")))
        assertTrue(sameSource(photo, photo.copy(size = 1600)))
    }
    @Test fun proportionsHandleMissingDimensions() {
        assertEquals(.5f, photo.aspect)
        assertEquals(1f, photo.copy(width = 0).aspect)
        assertEquals(1f, photo.copy(height = 0).aspect)
    }
}
