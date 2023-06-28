/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import android.icu.text.BreakIterator
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import android.util.Size
import android.view.inputmethod.InlineSuggestion
import android.widget.inline.InlineContentView
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dev.patrickgold.florisboard.app.florisPreferenceModel
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.core.ComputedSubtype
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.kotlin.collectLatestIn
import dev.patrickgold.florisboard.lib.util.NetworkUtils
import dev.patrickgold.florisboard.plugin.FlorisPluginIndexer
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates

// TODO: VERY IMPORTANT: This class is the definition of spaghetti code and chaos, clean up or rewrite this class
class NlpManager(context: Context) {
    private val prefs by florisPreferenceModel()
    private val clipboardManager by context.clipboardManager()
    private val editorInstance by context.editorInstance()
    private val extensionManager by context.extensionManager()
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val clipboardSuggestionProvider = ClipboardSuggestionProvider()
    val plugins = FlorisPluginIndexer(context)

    private val internalSuggestionsGuard = Mutex()
    private var internalSuggestions by Delegates.observable(SystemClock.uptimeMillis() to listOf<SuggestionCandidate>()) { _, _, _ ->
        scope.launch { assembleCandidates() }
    }

    private val _activeCandidatesFlow = MutableStateFlow(listOf<SuggestionCandidate>())
    val activeCandidatesFlow = _activeCandidatesFlow.asStateFlow()
    inline var activeCandidates
        get() = activeCandidatesFlow.value
        private set(v) {
            _activeCandidatesFlow.value = v
        }

    private val inlineContentViews = Collections.synchronizedMap<InlineSuggestion, InlineContentView>(hashMapOf())
    private val _inlineSuggestions = MutableLiveData<List<InlineSuggestion>>(emptyList())
    val inlineSuggestions: LiveData<List<InlineSuggestion>> get() = _inlineSuggestions

    val debugOverlaySuggestionsInfos = LruCache<Long, Pair<String, SpellingResult>>(10)
    var debugOverlayVersion = MutableLiveData(0)
    private val debugOverlayVersionSource = AtomicInteger(0)

    init {
        scope.launch {
            plugins.indexBoundServices()
            flogDebug {
                buildString {
                    plugins.pluginIndex.withLock { pluginIndex ->
                        appendLine("Indexed Plugins")
                        for (plugin in pluginIndex) {
                            appendLine(plugin.toString())
                        }
                    }
                }
            }
            plugins.observeServiceChanges()

            clipboardManager.primaryClipFlow.collectLatestIn(scope) {
                assembleCandidates()
            }
            prefs.suggestion.enabled.observeForever {
                assembleCandidates()
            }
            prefs.suggestion.clipboardContentEnabled.observeForever {
                assembleCandidates()
            }
            subtypeManager.activeSubtypeFlow.collectLatestIn(scope) { subtype ->
                preload(subtype)
            }
            extensionManager.dictionaryExtensions.observeForever {
                runBlocking {
                    for (subtype in subtypeManager.subtypes) {
                        preload(subtype)
                    }
                }
            }
        }
    }

    /**
     * Gets the punctuation rule from the currently active subtype and returns it. Falls back to a default one if the
     * subtype does not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getActivePunctuationRule(): PunctuationRule {
        return getPunctuationRule(subtypeManager.activeSubtype)
    }

    /**
     * Gets the punctuation rule from the given subtype and returns it. Falls back to a default one if the subtype does
     * not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getPunctuationRule(subtype: Subtype): PunctuationRule {
        return keyboardManager.resources.punctuationRules.value
            ?.get(subtype.punctuationRule) ?: PunctuationRule.Fallback
    }

    suspend fun preload(subtype: Subtype) {
        subtype.nlpProviders.forEach { providerId ->
            plugins.getOrNull(providerId)?.preload(subtype.compute())
        }
    }

    /**
     * Spell wrapper helper which calls the spelling provider and returns the result. Coroutine management must be done
     * by the source spell checker service.
     */
    suspend fun spell(
        subtype: Subtype,
        word: String,
        prevWords: List<String>,
        maxSuggestionCount: Int,
    ): SpellingResult {
        return plugins.getOrNull(subtype.nlpProviders.spelling)?.spell(
            subtypeId = subtype.id,
            word = word,
            prevWords = prevWords,
            flags = activeSuggestionRequestFlags(maxSuggestionCount),
        ) ?: SpellingResult.unspecified()
    }

    suspend fun determineLocalComposing(
        textBeforeSelection: CharSequence, breakIterators: BreakIteratorGroup, localLastCommitPosition: Int
    ): EditorRange {
        //return nlpProviderRegistry.getSuggestionProvider(subtypeManager.activeSubtype).determineLocalComposing(
        //    subtypeManager.activeSubtype, textBeforeSelection, breakIterators, localLastCommitPosition
        //)
        return breakIterators.word(subtypeManager.activeSubtype.primaryLocale) {
            it.setText(textBeforeSelection.toString())
            val end = it.last()
            val isWord = it.ruleStatus != BreakIterator.WORD_NONE
            if (isWord) {
                val start = it.previous()
                EditorRange(start, end)
            } else {
                EditorRange.Unspecified
            }
        }
    }

    private suspend fun providerRequiresSuggestionAlwaysEnabled(subtype: Subtype): Boolean {
        return plugins.getOrNull(subtype.nlpProviders.suggestion)
            ?.metadata?.suggestionConfig?.requireAlwaysEnabled ?: false
    }

    suspend fun isSuggestionEnabled(): Boolean {
        return keyboardManager.activeState.isComposingEnabled &&
            (prefs.suggestion.enabled.get() || providerRequiresSuggestionAlwaysEnabled(subtypeManager.activeSubtype))
    }

    fun suggest(subtype: Subtype, content: EditorContent) {
        val reqTime = SystemClock.uptimeMillis()
        scope.launch {
            val candidates = plugins.getOrNull(subtype.nlpProviders.spelling)?.suggest(
                subtypeId = subtype.id,
                word = content.composingText,
                prevWords = content.textBeforeSelection.split(" "), // TODO this split is incorrect
                flags = activeSuggestionRequestFlags(),
            ) ?: emptyList()
            flogDebug { "candidates: $candidates" }
            internalSuggestionsGuard.withLock {
                if (internalSuggestions.first < reqTime) {
                    internalSuggestions = reqTime to candidates
                }
            }
        }
    }

    fun suggestDirectly(suggestions: List<SuggestionCandidate>) {
        val reqTime = SystemClock.uptimeMillis()
        runBlocking {
            internalSuggestions = reqTime to suggestions
        }
    }

    fun clearSuggestions() {
        val reqTime = SystemClock.uptimeMillis()
        runBlocking {
            internalSuggestions = reqTime to emptyList()
        }
    }

    fun getAutoCommitCandidate(): SuggestionCandidate? {
        return activeCandidates.firstOrNull { it.isEligibleForAutoCommit }
    }

    fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return runBlocking { candidate.sourceProvider?.removeSuggestion(subtype.id, candidate) == true }.also { result ->
            if (result) {
                scope.launch {
                    // Need to re-trigger the suggestions algorithm
                    if (candidate is ClipboardSuggestionCandidate) {
                        assembleCandidates()
                    } else {
                        suggest(subtypeManager.activeSubtype, editorInstance.activeContent)
                    }
                }
            }
        }
    }

    private fun activeSuggestionRequestFlags(maxSuggestionCount: Int? = null): SuggestionRequestFlags {
        return SuggestionRequestFlags.new(
            maxSuggestionCount = maxSuggestionCount ?: 8, // TODO make dynamic
            issStart = InputShiftState.UNSHIFTED, // TODO evaluate correctly
            issCurrent = InputShiftState.UNSHIFTED, // TODO evaluate correctly
            maxNgramLevel = 3, // TODO make dynamic
            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            overrideHiddenFlag = false, // TODO make dynamic
            isPrivateSession = keyboardManager.activeState.isIncognitoMode,
        )
    }

    private fun assembleCandidates() {
        runBlocking {
            val candidates = when {
                isSuggestionEnabled() -> {
                    clipboardSuggestionProvider.suggest(
                        subtypeId = Subtype.FALLBACK.id,
                        word = editorInstance.activeContent.currentWordText,
                        prevWords = listOf(),
                        flags = activeSuggestionRequestFlags(),
                    ).ifEmpty {
                        buildList {
                            internalSuggestionsGuard.withLock {
                                addAll(internalSuggestions.second)
                            }
                        }
                    }
                }
                else -> emptyList()
            }
            activeCandidates = candidates
            autoExpandCollapseSmartbarActions(candidates, inlineSuggestions.value)
        }
    }

    /**
     * Inflates the given inline suggestions. Once all provided views are ready, the suggestions
     * strip is updated and the Smartbar update cycle is triggered.
     *
     * @param inlineSuggestions A collection of inline suggestions to be inflated and shown.
     */
    fun showInlineSuggestions(inlineSuggestions: List<InlineSuggestion>) {
        inlineContentViews.clear()
        _inlineSuggestions.postValue(inlineSuggestions)
        autoExpandCollapseSmartbarActions(activeCandidates, inlineSuggestions)
    }

    /**
     * Clears the inline suggestions and triggers the Smartbar update cycle.
     */
    fun clearInlineSuggestions() {
        inlineContentViews.clear()
        _inlineSuggestions.postValue(emptyList())
        autoExpandCollapseSmartbarActions(activeCandidates, null)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun inflateOrGet(
        context: Context,
        size: Size,
        inlineSuggestion: InlineSuggestion,
        callback: (InlineContentView) -> Unit,
    ) {
        val view = inlineContentViews[inlineSuggestion]
        if (view != null) {
            callback(view)
        } else {
            try {
                inlineSuggestion.inflate(context, size, context.mainExecutor) { inflatedView ->
                    if (inflatedView != null) {
                        inlineContentViews[inlineSuggestion] = inflatedView
                        callback(inflatedView)
                    }
                }
            } catch (e: Exception) {
                flogError { e.toString() }
            }
        }
    }

    private fun autoExpandCollapseSmartbarActions(list1: List<*>?, list2: List<*>?) {
        if (prefs.smartbar.enabled.get() && prefs.smartbar.sharedActionsAutoExpandCollapse.get()) {
            if (keyboardManager.inputEventDispatcher.isRepeatableCodeLastDown()
                || keyboardManager.activeState.isActionsOverflowVisible
            ) {
                return // We do not auto switch if a repeatable action key was last pressed or if the actions overflow
                       // menu is visible to prevent annoying UI changes
            }
            val isSelection = editorInstance.activeContent.selection.isSelectionMode
            val isExpanded = list1.isNullOrEmpty() && list2.isNullOrEmpty() || isSelection
            prefs.smartbar.sharedActionsExpandWithAnimation.set(false)
            prefs.smartbar.sharedActionsExpanded.set(isExpanded)
        }
    }

    fun addToDebugOverlay(word: String, info: SpellingResult) {
        val version = debugOverlayVersionSource.incrementAndGet()
        debugOverlaySuggestionsInfos.put(System.currentTimeMillis(), word to info)
        debugOverlayVersion.postValue(version)
    }

    fun clearDebugOverlay() {
        val version = debugOverlayVersionSource.incrementAndGet()
        debugOverlaySuggestionsInfos.evictAll()
        debugOverlayVersion.postValue(version)
    }

    inner class ClipboardSuggestionProvider internal constructor() : SuggestionProvider {
        private var lastClipboardItemId: Long = -1

        override suspend fun create() {
            // Do nothing
        }

        override suspend fun evaluateIsSupported(subtype: ComputedSubtype): SubtypeSupportInfo {
            return SubtypeSupportInfo.fullySupported()
        }

        override suspend fun preload(subtype: ComputedSubtype) {
            // Do nothing
        }

        override suspend fun suggest(
            subtypeId: Long,
            word: String,
            prevWords: List<String>,
            flags: SuggestionRequestFlags,
        ): List<SuggestionCandidate> {
            // Check if enabled
            if (!prefs.suggestion.clipboardContentEnabled.get()) return emptyList()

            // Check if already used
            val currentItem = clipboardManager.primaryClip
            val lastItemId = lastClipboardItemId
            if (currentItem == null || currentItem.id == lastItemId || word.isNotBlank()) return emptyList()

            return buildList {
                val now = System.currentTimeMillis()
                if ((now - currentItem.creationTimestampMs) < prefs.suggestion.clipboardContentTimeout.get() * 1000) {
                    add(ClipboardSuggestionCandidate(currentItem, sourceProvider = this@ClipboardSuggestionProvider))
                    if (currentItem.type == ItemType.TEXT) {
                        val text = currentItem.stringRepresentation()
                        val matches = buildList {
                            addAll(NetworkUtils.getEmailAddresses(text))
                            addAll(NetworkUtils.getUrls(text))
                            addAll(NetworkUtils.getPhoneNumbers(text))
                        }
                        matches.forEachIndexed { i, match ->
                            val isUniqueMatch = matches.subList(0, i).all { prevMatch ->
                                prevMatch.value != match.value && prevMatch.range.intersect(match.range).isEmpty()
                            }
                            if (match.value != text && isUniqueMatch) {
                                add(ClipboardSuggestionCandidate(
                                    clipboardItem = currentItem.copy(
                                        // TODO: adjust regex of phone number so we don't need to manually strip the
                                        //  parentheses from the match results
                                        text = if (match.value.startsWith("(") && match.value.endsWith(")")) {
                                            match.value.substring(1, match.value.length - 1)
                                        } else {
                                            match.value
                                        }
                                    ),
                                    sourceProvider = this@ClipboardSuggestionProvider,
                                ))
                            }
                        }
                    }
                }
            }
        }

        override suspend fun notifySuggestionAccepted(subtypeId: Long, candidate: SuggestionCandidate) {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
            }
        }

        override suspend fun notifySuggestionReverted(subtypeId: Long, candidate: SuggestionCandidate) {
            // Do nothing
        }

        override suspend fun removeSuggestion(subtypeId: Long, candidate: SuggestionCandidate): Boolean {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
                return true
            }
            return false
        }

        override suspend fun destroy() {
            // Do nothing
        }
    }
}
