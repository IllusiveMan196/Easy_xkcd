package de.tap.easy_xkcd.comicBrowsing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.text.Html
import android.transition.TransitionInflater
import android.view.*
import android.widget.*
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.MenuCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.snackbar.Snackbar
import com.tap.xkcd_reader.R
import com.tap.xkcd_reader.databinding.PagerLayoutBinding
import dagger.hilt.android.AndroidEntryPoint
import de.tap.easy_xkcd.ComicBaseAdapter
import de.tap.easy_xkcd.ComicViewHolder
import de.tap.easy_xkcd.CustomTabHelpers.BrowserFallback
import de.tap.easy_xkcd.CustomTabHelpers.CustomTabActivityHelper
import de.tap.easy_xkcd.database.comics.Comic
import de.tap.easy_xkcd.fragments.ImmersiveDialogFragment
import de.tap.easy_xkcd.mainActivity.MainActivity
import de.tap.easy_xkcd.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
abstract class ComicBrowserBaseFragment : Fragment() {
    private var _binding: PagerLayoutBinding? = null
    protected val binding get() = _binding!!

    protected lateinit var pager: ViewPager2
    protected lateinit var adapter: ComicBrowserBaseAdapter

    @Inject
    lateinit var onlineChecker: OnlineChecker

    // TODO Inject these
    protected lateinit var settings: AppSettings
    protected lateinit var sharedPrefs: SharedPrefManager
    protected lateinit var appTheme: AppTheme

    protected abstract val model: ComicBrowserBaseViewModel

    protected var comicNumberOfSharedElementTransition : Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = PagerLayoutBinding.inflate(inflater, container, false)

        setHasOptionsMenu(true)

        settings = AppSettings(requireActivity())
        sharedPrefs = SharedPrefManager(requireActivity())
        appTheme = AppTheme(requireActivity())

        pager = binding.pager
        pager.offscreenPageLimit = 2
        pager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                model.comicSelected(position)
            }
        })

        model.selectedComicNumber.observe(viewLifecycleOwner) {
            (activity as AppCompatActivity).supportActionBar?.subtitle = it?.toString()
        }

        arguments?.let { args ->
            if (args.containsKey(MainActivity.ARG_COMIC_OR_ARTICLE_TO_SHOW)) {
                model.selectComic(args.getInt(MainActivity.ARG_COMIC_OR_ARTICLE_TO_SHOW))
            }

            // Prepare for shared element transition
            if (savedInstanceState == null && args.getBoolean(
                    MainActivity.ARG_TRANSITION_PENDING,
                    false
                )
            ) {
                comicNumberOfSharedElementTransition = model.selectedComicNumber.value
                postponeEnterTransition()
                sharedElementEnterTransition = TransitionInflater.from(context)
                    .inflateTransition(R.transition.image_shared_element_transition)
            }

        }

        activity?.onBackPressedDispatcher
            ?.addCallback {
                if (settings.altBackButton) {
                    showAltText()
                } else {
                    if (isEnabled) {
                        isEnabled = false
                        activity?.onBackPressed()
                    }
                }
            }

        return binding.root
    }

    // Used by the MainActivity for passing the view to the OverviewFragment
    fun getSharedElementsForTransitionToOverview() : List<View?> {
        val underlyingRecyclerView = pager.getChildAt(0) as? RecyclerView?
        val holder = underlyingRecyclerView?.findViewHolderForAdapterPosition(pager.currentItem) as? ComicViewHolder?
        return listOf(
            holder?.title,
            holder?.image
        )
    }

    open inner class ComicBrowserBaseAdapter : ComicBaseAdapter<ComicBrowserBaseAdapter.ComicBrowserViewHolder>(
        requireActivity(),
        comicNumberOfSharedElementTransition
    ) {
        override fun startPostponedTransitions() {
            startPostponedEnterTransition()
            activity?.startPostponedEnterTransition()
        }

        override fun getOfflineUri(number: Int) = model.getOfflineUri(number)

        override fun onImageLoaded(image: ImageView?, bitmap: Bitmap, comic: Comic) {
            if (comicNumberOfSharedElementTransition == null) {
                image?.alpha = 0f
                image?.animate()?.alpha(1f)?.duration = 300
            }
        }

        inner class LongAndDoubleTapListener(
            private val image: PhotoView,
            private val comic: Comic
        ) : GestureDetector.OnDoubleTapListener, View.OnLongClickListener {
            private var fingerLifted = true

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                if (e.action == MotionEvent.ACTION_UP) fingerLifted = true
                if (e.action == MotionEvent.ACTION_DOWN) fingerLifted = false
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (settings.doubleTapToFavorite) {
                    toggleFavorite()
                    (activity?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator?)?.vibrate(100)
                } else {
                    // Implementation adapted from the PhotoView's default double tap listener
                    try {
                        image.setScale(when {
                            image.scale < image.mediumScale -> image.mediumScale
                            else -> image.minimumScale
                        }, e.x, e.y, true)
                    } catch (e: ArrayIndexOutOfBoundsException) {
                        // Can sometimes happen when getX() and getY() is called
                    }
                }
                return true
            }

            fun altOrFullscreen(singleTap: Boolean) {
                if ((singleTap xor settings.altLongTap) && !settings.alwaysShowAltText) {
                    if (settings.altVibration) {
                        (activity?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator?)?.vibrate(10)
                    }
                    showAltText()
                } else {
                    (activity as? MainActivity?)?.toggleFullscreen()
                }
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (Comic.isInteractiveComic(comic.number)) {
                    openInBrowser(comic)
                } else {
                    altOrFullscreen(singleTap = true)
                }
                return false
            }

            override fun onLongClick(p0: View?): Boolean {
                if (fingerLifted) {
                    altOrFullscreen(singleTap = false)
                }
                return true
            }
        }

        override fun onBindViewHolder(holder: ComicBrowserViewHolder, position: Int) {
            holder.image.mediumScale = 0.7f * holder.image.maximumScale
            if (Comic.isLargeComic(position + 1)) {
                holder.image.maximumScale = 15.0f
            }

            if (settings.scrollDisabledWhileZoom && settings.defaultZoom) {
                holder.image.setOnMatrixChangeListener {
                    pager.isUserInputEnabled = holder.image.scale <= 1.4
                }
            }

            comics[position].comic?.let { comic ->
                LongAndDoubleTapListener(holder.image, comic).let {
                    holder.image.setOnDoubleTapListener(it)
                    holder.image.setOnLongClickListener(it)
                }

                if (settings.alwaysShowAltText) {
                    holder.altText.text = comic.altText
                    holder.altText.visibility = View.VISIBLE
                }
            }

            super.onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ComicBrowserViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.pager_item, parent, false)
        )

        inner class ComicBrowserViewHolder(view: View) : ComicViewHolder(view) {
            override val title: TextView = itemView.findViewById(R.id.tvTitle)
            override val altText: TextView = itemView.findViewById(R.id.tvAlt)
            override val info: TextView? = null
            override val image: PhotoView = itemView.findViewById(R.id.ivComic)

            init {
                if (appTheme.nightThemeEnabled) {
                    title.setTextColor(Color.WHITE)
                }

                if (!settings.defaultZoom) {
                    image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    image.maximumScale = 10f
                }
            }
        }
    }

    fun getDisplayedComic(): Comic? = model.selectedComic.value

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        MenuCompat.setGroupDividerEnabled(menu, true)

        menu.findItem(R.id.action_alt)?.isVisible = sharedPrefs.showAltTip && !settings.alwaysShowAltText

        super.onCreateOptionsMenu(menu, inflater)
    }

    fun toggleFavorite() {
        if (onlineChecker.isOnline() || settings.fullOfflineEnabled) {
            model.toggleFavorite()
        } else {
            Toast.makeText(activity, R.string.no_connection, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAltText(fromMenu: Boolean = false): Boolean {
        //Show alt text
        getDisplayedComic()?.let { comic ->
            ImmersiveDialogFragment.getInstance(Html.fromHtml(Html.escapeHtml(comic.altText)).toString()).apply {
                if (fromMenu) {
                    dismissListener = ImmersiveDialogFragment.DismissListener {
                        //If the user selected the menu item for the first time, show the toast
                        if (sharedPrefs.showAltTip) {
                            Snackbar.make(
                                requireActivity().findViewById(android.R.id.content),
                                R.string.action_alt_tip,
                                Snackbar.LENGTH_LONG
                            ).setAction(R.string.got_it) { sharedPrefs.showAltTip = false }
                                .show()
                        }
                    }
                }
            }.showImmersive(requireActivity() as AppCompatActivity)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        when (item.itemId) {
            R.id.action_share -> {
                AlertDialog.Builder(requireContext()).setItems(R.array.share_dialog) { _, which ->
                    getDisplayedComic()?.let {
                        when (which) {
                            0 -> lifecycleScope.launch { shareComicImage(it) }
                            1 -> shareComicUrl(it)
                        }
                    }
                }.create().show()
                true
            }
            R.id.action_alt -> {
                showAltText(fromMenu = true)
                true
            }
            R.id.action_favorite -> {
                toggleFavorite()
                true
            }
            R.id.action_trans -> {
                getDisplayedComic()?.let { showTranscript(it) }
                true
            }
            R.id.action_explain -> {
                getDisplayedComic()?.let { explainComic(it) }
                true
            }
            R.id.action_browser -> {
                getDisplayedComic()?.let { openInBrowser(it) }
                true
            }
            R.id.action_thread -> {
                openRedditThread()
                true
            }
            R.id.action_boomark -> {
                lifecycleScope.launch {
                    model.setBookmark()

                    Toast.makeText(
                        activity,
                        if (sharedPrefs.bookmark == 0) R.string.bookmark_toast else R.string.bookmark_toast_2,
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun openRedditThread() {
        lifecycleScope.launch {
            //TODO model should show the progress here

            model.getRedditThread()?.let { url ->
                withContext(Dispatchers.Main) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } ?: run {
                Toast.makeText(
                    activity,
                    resources.getString(R.string.thread_not_found),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openInBrowser(comic: Comic) {
        // We open the mobile site (m.xkcd.com) by default
        // For interactive comics we use the desktop since it has better support for some interactive comics

        activity?.let { activity ->
            val intent = Intent(
                Intent.ACTION_VIEW, Uri.parse(
                    "https://"
                            + (if (Comic.isInteractiveComic(
                            comic.number,
                        )
                    ) "" else "m.")
                            + "xkcd.com/" + comic.number
                )
            )

            // Since the app also handles xkcd intents, we need to exxlude it from the intent chooser
            // Code adapted from https://codedogg.wordpress.com/2018/11/09/how-to-exclude-your-own-activity-from-activity-startactivityintent-chooser/
            val packageManager = activity.packageManager
            val possibleIntents: MutableList<Intent> = ArrayList()

            val possiblePackageNames: MutableSet<String> = HashSet()
            for (resolveInfo in packageManager.queryIntentActivities(intent, 0)) {
                val packageName = resolveInfo.activityInfo.packageName
                if (packageName != activity.packageName) {
                    val possibleIntent = Intent(intent)
                    possibleIntent.setPackage(resolveInfo.activityInfo.packageName)
                    possiblePackageNames.add(resolveInfo.activityInfo.packageName)
                    possibleIntents.add(possibleIntent)
                }
            }

            val defaultResolveInfo = packageManager.resolveActivity(intent, 0)

            if (defaultResolveInfo == null || possiblePackageNames.isEmpty()) {
                return
            }

            // If there is a default app to handle the intent (which is not this app), use it.
            if (possiblePackageNames.contains(defaultResolveInfo.activityInfo.packageName)) {
                activity.startActivity(intent)
            } else { // Otherwise, let the user choose.
                val intentChooser = Intent.createChooser(
                    possibleIntents.removeAt(0),
                    activity.resources.getString(R.string.chooser_title)
                )
                intentChooser.putExtra(
                    Intent.EXTRA_INITIAL_INTENTS,
                    possibleIntents.toTypedArray()
                )
                activity.startActivity(intentChooser)
            }
        }
    }

    private fun explainComic(comic: Comic) {
        val url = "https://explainxkcd.com/${comic.number}"
        if (settings.useCustomTabs) {
            CustomTabActivityHelper.openCustomTab(
                activity,
                CustomTabsIntent.Builder()
                    .setToolbarColor(appTheme.getPrimaryColor(false))
                    .build(),
                Uri.parse(url),
                BrowserFallback()
            )
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun shareComicImage(comic: Comic) {
        lifecycleScope.launch {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, model.getUriForSharing(comic.number))
                putExtra(Intent.EXTRA_SUBJECT, comic.title)
            }

            var extraText = comic.title
            if (settings.shareAlt) {
                extraText += "\n" + comic.altText
            }
            if (settings.includeLinkWhenSharing) {
                extraText += "\n" + "https://${if (settings.shareMobile) "m." else ""}xkcd.com/${comic.number}/"
            }
            share.putExtra(Intent.EXTRA_TEXT, extraText)

            startActivity(Intent.createChooser(share, resources.getString(R.string.share_image)))
        }
    }

    private fun shareComicUrl(comic: Comic) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, comic.title)
            putExtra(
                Intent.EXTRA_TEXT,
                " https://" + (if (settings.shareMobile) "m." else "") + "xkcd.com/" + comic.number + "/"
            )
        }, resources.getString(R.string.share_url)))
    }

    protected fun showTranscript(comic: Comic) {
        lifecycleScope.launch {
            //TODO Show a progress bar while transcript is being downloaded
            AlertDialog.Builder(requireContext())
                .setTitle(resources.getString(R.string.transcript))
                .setMessage(model.getTranscript(comic))
                .show()
        }
    }
}