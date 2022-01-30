package de.tap.easy_xkcd.search

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.tap.easy_xkcd.database.ComicContainer
import de.tap.easy_xkcd.database.ComicRepository
import de.tap.easy_xkcd.database.ProgressStatus
import de.tap.easy_xkcd.utils.ViewModelWithFlowHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: ComicRepository,
    @ApplicationContext context: Context
) : ViewModelWithFlowHelper() {
    var progress = repository.cacheAllComics

    private var query = MutableStateFlow("")

    private var searchJob: Job? = null
    fun setQuery(newQuery: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val comics = repository.searchComics(newQuery)

                // Needed to allow the coroutine being canceled if there's a new query in the meantime
                delay(1)

                _results.value = comics
            }
        }
    }

    fun getOfflineUri(number: Int) = repository.getOfflineUri(number)

    val _results = MutableStateFlow<List<ComicContainer>>(emptyList())
    val results: StateFlow<List<ComicContainer>> = _results
}