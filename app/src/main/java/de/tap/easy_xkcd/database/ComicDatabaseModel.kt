package de.tap.easy_xkcd.comicBrowsing

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import de.tap.easy_xkcd.database.DatabaseManager
import de.tap.easy_xkcd.database.RealmComic
import de.tap.easy_xkcd.utils.*
import io.realm.Realm
import io.realm.Sort
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.lang.Exception
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

interface ComicDatabaseModel {
    suspend fun findNewestComic(): Int
    suspend fun updateDatabase(
        newestComic: Int,
        comicSavedCallback: () -> Unit
    )

    fun getAllComics(): List<RealmComic>

    fun isFavorite(number: Int): Boolean

    fun toggleFavorite(number: Int)

    fun isRead(number: Int): Boolean

    fun setRead(number: Int, isRead: Boolean)

    fun getRandomComic(): Int
}

@Singleton
class ComicDatabaseModelImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ComicDatabaseModel {
    private val prefHelper = PrefHelper(context)
    private val databaseManager = DatabaseManager(context)

    private val client = OkHttpClient()

    override fun getRandomComic(): Int {
        return Random.nextInt(returnWithRealm { it.where(RealmComic::class.java).findAll().size })
    }

    override fun getAllComics(): List<RealmComic> = copyResultsFromRealm {
        it.where(RealmComic::class.java).findAllSorted("comicNumber", Sort.ASCENDING)
    }

    private fun getComic(comicNumber: Int, realm: Realm): RealmComic? =
        realm.where(RealmComic::class.java).equalTo("comicNumber", comicNumber).findFirst()

    override fun isFavorite(number: Int) = returnWithRealm {
        getComic(number, it)?.isFavorite == true
    }

    override fun toggleFavorite(number: Int) {
        doWithRealm { realm ->
            getComic(number, realm)?.let { comic ->
                realm.executeTransaction {
                    comic.isFavorite = !comic.isFavorite
                    realm.copyToRealmOrUpdate(comic)
                }
            }
        }
    }

    override fun isRead(number: Int) = returnWithRealm {
        getComic(number, it)?.isRead == true
    }

    override fun setRead(number: Int, isRead: Boolean) {
        doWithRealm { realm ->
            getComic(number, realm)?.let { comic ->
                realm.executeTransaction {
                    comic.isRead = isRead
                    realm.copyToRealmOrUpdate(comic)
                }
            }
        }
    }

    override suspend fun findNewestComic(): Int = withContext(Dispatchers.IO) {
        RealmComic.findNewestComicNumber()
    }

    override suspend fun updateDatabase(
        newestComic: Int,
        comicSavedCallback: () -> Unit
    ) {
        val comicsToDownload = (1..newestComic).map { number ->
            Pair(number, copyFromRealm { realm ->
                getComic(number, realm)
            })
        }.filter {
            // If a comic does not exists in the database at all (i.e. if the realm query returned null)
            // we have to download it in any case.
            // Otherwise, only download it if we're in offline mode and the comic has not been
            // downloaded yet
            val comic = it.second
            comic == null || (prefHelper.fullOfflineEnabled() && !comic.isOffline)
        }.toMap()

        withContext(Dispatchers.IO) {
            comicsToDownload.map {
                async(Dispatchers.IO) {
                    val comic = if (it.value == null) {
                        downloadComic(it.key)
                    } else {
                        it.value
                    }
                    comic?.let {
                        downloadOfflineData(it)
                        copyToRealmOrUpdate(it)
                    }

                    comicSavedCallback()
                }
            }.awaitAll()
        }

        if (!prefHelper.transcriptsFixed()) {
            databaseManager.fixTranscripts()
            prefHelper.setTranscriptsFixed()
            Timber.d("Transcripts fixed!")
        }
    }

    private fun downloadComic(number: Int): RealmComic? {
        val response =
            client.newCall(Request.Builder().url(RealmComic.getJsonUrl(number)).build())
                .execute()

        val body = response.body?.string()
        if (body == null) {
            Timber.e("Got empty body for comic $number")
            return null
        }

        var json: JSONObject? = null
        try {
            json = JSONObject(body)
        } catch (e: JSONException) {
            if (number == 404) {
                Timber.i("Json not found, but that's expected for comic 404")
            } else {
                Timber.e(e, "Occurred at comic $number")
            }
        }

        return RealmComic.buildFromJson(number, json, context)
    }

    private fun downloadOfflineData(comic: RealmComic) {
        if (prefHelper.fullOfflineEnabled() && !comic.isOffline) {
            if (RealmComic.isOfflineComicAlreadyDownloaded(
                    comic.comicNumber,
                    prefHelper,
                    context
                )
            ) {
                Timber.i("Already has offline files for comic ${comic.comicNumber}, skipping download...")
            } else {
                try {
                    val response =
                        client.newCall(Request.Builder().url(comic.url).build()).execute()
                    RealmComic.saveOfflineBitmap(response, prefHelper, comic.comicNumber, context)
                } catch (e: Exception) {
                    Timber.e(e, "Download failed at ${comic.comicNumber} (${comic.url})")
                }
            }

            comic.isOffline = true
        }
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class ComicsModelModule {
    @Binds
    abstract fun bindComicsModel(comicsModelImpl: ComicDatabaseModelImpl): ComicDatabaseModel
}

