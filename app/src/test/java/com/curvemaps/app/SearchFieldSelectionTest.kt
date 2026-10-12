package com.curvemaps.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchFieldSelectionTest {

    @Test
    fun focusingSelectsTheWholeEntryWhateverTheCaret() {
        val text = "Brisbane, Brisbane · City"
        for (caret in 0..text.length) {
            val focused = selectAllForReplacement(TextFieldValue(text, TextRange(caret)))
            assertEquals(text, focused.text)
            assertEquals(TextRange(0, text.length), focused.selection)
        }
        assertEquals(TextRange(0), selectAllForReplacement(TextFieldValue("")).selection)
    }

    @Test
    fun theRidersSelectionSurvivesWhileTheTextIsUnchanged() {
        val selected = TextFieldValue("Samford", TextRange(0, 7))
        assertSame(selected, syncedFieldValue(selected, "Samford"))
    }

    @Test
    fun anExternalChangePutsTheCaretAtTheEndOfTheNewText() {
        val selected = TextFieldValue("Samford", TextRange(0, 7))
        val picked = syncedFieldValue(selected, "Samford Valley, Moreton Bay")
        assertEquals("Samford Valley, Moreton Bay", picked.text)
        assertEquals(TextRange(picked.text.length), picked.selection)
        assertEquals(TextRange(0), syncedFieldValue(selected, "").selection)
    }
}
