package org.jellyfin.androidtv.ui.home

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.ui.shared.BaseActivity
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.sdk.compat.asSdk
import org.jellyfin.apiclient.interaction.EmptyResponse
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.android.ext.android.inject
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber

class HomeFragment : RowsSupportFragment(), AudioEventListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository, userViewsRepository) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(mediaManager) }
	private val liveTVRow by lazy { HomeFragmentLiveTVRow(requireActivity(), userRepository) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		adapter = MutableObjectAdapter<Row>(PositionableListRowPresenter())
		backgroundService.attach(requireActivity())

		val currentUser = userRepository.currentUser.value
		if (currentUser == null) {
			activity?.finish()
			return
		}

		lifecycleScope.launch(Dispatchers.IO) {
			// Start out with default sections
			val homesections = userSettingPreferences.homesections
			var includeLiveTvRows = false

			// Check for live TV support
			if (homesections.contains(HomeSectionType.LIVE_TV) && currentUser.policy?.enableLiveTvAccess == true) {
				// This is kind of ugly, but it mirrors how web handles the live TV rows on the home screen
				// If we can retrieve one live TV recommendation, then we should display the rows
				val recommendedPrograms by api.liveTvApi.getRecommendedPrograms(
					userId = api.userId,
					enableTotalRecordCount = false,
					imageTypeLimit = 1,
					isAiring = true,
					limit = 1,
				)
				includeLiveTvRows = !recommendedPrograms.items.isNullOrEmpty()
			}

			// Make sure the rows are empty
			val rows = mutableListOf<HomeFragmentRow>()

			// Check for coroutine cancellation
			if (!isActive) return@launch

			// Actually add the sections
			for (section in homesections) when (section) {
				HomeSectionType.LATEST_MEDIA -> rows.add(helper.loadRecentlyAdded())
				HomeSectionType.LIBRARY_TILES_SMALL -> rows.add(helper.loadLibraryTiles())
				HomeSectionType.LIBRARY_BUTTONS -> rows.add(helper.loadLibraryTiles())
				HomeSectionType.RESUME -> rows.add(helper.loadResumeVideo())
				HomeSectionType.RESUME_AUDIO -> rows.add(helper.loadResumeAudio())
				HomeSectionType.RESUME_BOOK -> Unit // Books are not (yet) supported
				HomeSectionType.ACTIVE_RECORDINGS -> rows.add(helper.loadLatestLiveTvRecordings())
				HomeSectionType.NEXT_UP -> rows.add(helper.loadNextUp())
				HomeSectionType.LIVE_TV -> if (includeLiveTvRows) {
					rows.add(liveTVRow)
					rows.add(helper.loadOnNow())
				}
				HomeSectionType.NONE -> Unit
			}

			// Add sections to layout
			withContext(Dispatchers.Main) {
				val cardPresenter = CardPresenter()

				// Add rows in order
				notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				for (row in rows) row.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)

				// Manually set focus if focusedByDefault is not available
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) view?.requestFocus()
			}
		}

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(liveTVRow::onItemClicked)
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		(activity as? BaseActivity)?.let { activity ->
			activity.registerKeyListener { key, _ -> KeyProcessor.HandleKey(key, currentItem, activity) }
			activity.registerMessageListener { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}
		}
		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		// Make sure to focus the cards instead of the toolbar
		ViewCompat.setFocusedByDefault(view, true)
	}

	override fun onResume() {
		super.onResume()

		//React to deletion
		val dataRefreshService = get<DataRefreshService>(DataRefreshService::class.java)
		if (activity != null && !requireActivity().isFinishing && currentRow != null && currentItem != null && currentItem!!.getItemId() != null && currentItem!!.getItemId().equals(dataRefreshService.lastDeletedItemId)) {
			(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)
			dataRefreshService.lastDeletedItemId = null
		}

		if (!justLoaded) {
			//Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
			refreshCurrentItem()
			refreshRows()
		} else {
			justLoaded = false
		}

		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	private fun refreshRows() {
		repeat(adapter.size()) { i ->
			val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
			rowAdapter?.ReRetrieveIfNeeded()
		}
	}

	private fun refreshCurrentItem() {
		currentItem?.let { item ->
			if (item.getBaseItemType() == BaseItemKind.USER_VIEW || item.getBaseItemType() == BaseItemKind.COLLECTION_FOLDER) return

			Timber.d("Refresh item ${item.getFullName(requireContext())}")

			item.refresh(object : EmptyResponse() {
				override fun onResponse() {
					val adapter = currentRow?.adapter as? ItemRowAdapter
					adapter?.notifyItemRangeChanged(adapter.indexOf(item), 1)
				}
			})
		}
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder,
			row: Row,
		) {
			if (item !is BaseRowItem) return
			ItemLauncher.launch(item, (row as ListRow).adapter as ItemRowAdapter, item.index, activity)
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder,
			row: Row,
		) {
			if (item !is BaseRowItem) {
				currentItem = null
				//fill in default background
				backgroundService.clearBackgrounds()
			} else {
				currentItem = item
				currentRow = row as ListRow

				(row.adapter as? ItemRowAdapter)?.loadMoreItemsIfNeeded(item.index.toLong())

				backgroundService.setBackground(item.baseItem?.asSdk())
			}
		}
	}
}
