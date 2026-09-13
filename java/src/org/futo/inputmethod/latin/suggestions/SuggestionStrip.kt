/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.futo.inputmethod.latin.suggestions

import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo

interface SuggestionStripViewListener {
    fun showImportantNoticeContents()
    fun pickSuggestionManually(word: SuggestedWordInfo?)
    fun requestForgetWord(word: SuggestedWordInfo?)
    fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
    fun pickCorrectionCandidate(index: Int) { }
}

/**
 * The Fleksy-style correction row shown below the main suggestion strip.
 * [candidates] are the alternatives for the focused word (the composing word, or the word
 * before the cursor). [index] is the candidate currently in the editor. If the word was
 * autocorrected, index 0 is the text the user actually typed.
 */
data class CorrectionRow(
    val candidates: List<String>,
    val index: Int
)

/**
 * An object that gives basic control of a suggestion strip and some info on it.
 */
interface SuggestionStripViewAccessor {
    fun setNeutralSuggestionStrip()
    fun showSuggestionStrip(suggestedWords: SuggestedWords?)
}
