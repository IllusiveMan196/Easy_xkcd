package de.tap.easy_xkcd

import android.content.Context
import android.graphics.Bitmap
import android.text.Html
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat.startPostponedEnterTransition
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import de.tap.easy_xkcd.database.Comic
import de.tap.easy_xkcd.database.ComicContainer
import de.tap.easy_xkcd.mainActivity.MainActivity
import de.tap.easy_xkcd.utils.PrefHelper
import de.tap.easy_xkcd.utils.ThemePrefs
import timber.log.Timber
import java.util.*

abstract class ComicViewHolder(view: View): RecyclerView.ViewHolder(view) {
    abstract val title: TextView
    abstract val altText: TextView?
    abstract val number: TextView?
    abstract val image: ImageView
}

abstract class ComicBaseAdapter<ViewHolder: ComicViewHolder>(
    private val fragment: Fragment,
    private val context: Context,
    private var comicNumberOfSharedElementTransition : Int?
) : RecyclerView.Adapter<ViewHolder>() {
    private val prefHelper = PrefHelper(context)
    private val themePrefs = ThemePrefs(context)

    var comics: List<ComicContainer> = emptyList()
    override fun getItemCount() = comics.size

    open fun onComicNull(number: Int) {}

    open fun onDisplayingComic(comic: Comic, holder: ViewHolder) {}

    override fun onViewRecycled(holder: ViewHolder) {
        holder.image.setImageBitmap(null)
        holder.title.text = ""
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val comic = comics[position].comic

        // Transition names used for shared element transitions to the Overview Fragment
        holder.title.transitionName = comic?.number.toString()
        holder.image.transitionName = "im" + comic?.number


        if (comic == null) {
            onComicNull(comics[position].number)

            holder.image.setImageDrawable(makeProgressDrawable())
            startPostponedTransitions(comics[position].number)

            return
        }

        val prefix = if (prefHelper.subtitleEnabled()) "" else "$comic.number: "
        holder.title.text = prefix + Html.fromHtml(Comic.getInteractiveTitle(comic, context))

        if (themePrefs.invertColors(false)) {
            holder.image.colorFilter = themePrefs.negativeColorFilter
        }

        GlideApp.with(fragment)
            .asBitmap()
            .apply(RequestOptions().placeholder(makeProgressDrawable()))
            .apply {
                //TODO enable offline support again
                /*if (comic.isOffline || comic.isFavorite) load(
                    RealmComic.getOfflineBitmap(
                        comic.comicNumber,
                        context,
                        prefHelper
                    )
                ) else load(comic.url)*/
                load(comic.url)
            }
            .listener(object : RequestListener<Bitmap?> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap?>,
                    isFirstResource: Boolean
                ): Boolean {
                    startPostponedTransitions(comic.number)
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap?,
                    model: Any,
                    target: Target<Bitmap?>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    resource?.let {
                        imageLoaded(holder.image, resource, comic)
                        startPostponedTransitions(comic.number)
                    }
                    return false
                }
            }).into(holder.image)

        onDisplayingComic(comic, holder)
    }

    fun startPostponedTransitions(comicNumber: Int) {
        if (comicNumber == comicNumberOfSharedElementTransition) {
            startPostponedTransitions()
            comicNumberOfSharedElementTransition = null
        }
    }

    abstract fun startPostponedTransitions()

    override fun getItemId(position: Int): Long {
        return comics[position].number.toLong()
    }

    fun imageLoaded(image: ImageView, bitmap: Bitmap, comic: Comic) {
        if (themePrefs.invertColors(false) && themePrefs.bitmapContainsColor(
                bitmap,
                comic.number
            )
        ) image.clearColorFilter()

        onImageLoaded(image, bitmap, comic)
    }
    abstract fun onImageLoaded(image: ImageView, bitmap: Bitmap, comic: Comic)

    private fun makeProgressDrawable() = CircularProgressDrawable(context).apply {
        strokeWidth = 5.0f
        centerRadius = 100.0f
        setColorSchemeColors(if (themePrefs.nightThemeEnabled()) themePrefs.accentColorNight else themePrefs.accentColor)
        start()
    }
}