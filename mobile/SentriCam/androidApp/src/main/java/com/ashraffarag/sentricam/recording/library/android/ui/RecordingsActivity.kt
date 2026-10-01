package com.ashraffarag.sentricam.recording.library.android.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import com.ashraffarag.sentricam.R
import com.ashraffarag.sentricam.databinding.ActivityRecordingsBinding
import com.ashraffarag.sentricam.recording.library.android.RecordingLibraryEngineFactory
import com.ashraffarag.sentricam.recording.library.android.thumbnail.RecordingThumbnailLoader
import com.ashraffarag.sentricam.recording.library.capability.RecordingLibraryEngine
import com.ashraffarag.sentricam.recording.library.capability.RecordingRefreshResult
import com.ashraffarag.sentricam.recording.library.domain.RecordingDateFilter
import com.ashraffarag.sentricam.recording.library.domain.RecordingLibraryQuery
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RecordingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var engine: RecordingLibraryEngine
    private lateinit var adapter: RecordingsAdapter
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestGeneration = AtomicInteger()
    private val thumbnailLoader = RecordingThumbnailLoader()
    private var loadedRecordingCount = 0
    private var hasLoaded = false
    private var currentQuery = RecordingLibraryQuery()

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) refreshLibrary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.recordingsRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        engine = RecordingLibraryEngineFactory.create(applicationContext)
        adapter = RecordingsAdapter(thumbnailLoader) { recording ->
            playerLauncher.launch(VideoPlayerActivity.createIntent(this, recording.id))
        }
        val spanCount = resources.getInteger(R.integer.recordings_grid_span_count)
        val layoutManager = GridLayoutManager(this, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isHeader(position)) spanCount else 1
            }
        }
        binding.recordingsList.layoutManager = layoutManager
        binding.recordingsList.adapter = adapter
        binding.recordingsList.setHasFixedSize(false)

        binding.recordingsToolbar.setNavigationOnClickListener { finish() }
        binding.retryButton.setOnClickListener { refreshLibrary() }
        binding.searchInput.addTextChangedListener { text ->
            currentQuery = currentQuery.copy(searchText = text?.toString().orEmpty())
            scheduleQuery()
        }
        binding.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentQuery = currentQuery.copy(filter = checkedIds.firstOrNull().toFilter())
            scheduleQuery()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasLoaded) refreshLibrary()
    }

    override fun onDestroy() {
        requestGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        thumbnailLoader.close()
        binding.recordingsList.adapter = null
        super.onDestroy()
    }

    private fun refreshLibrary() {
        val generation = requestGeneration.incrementAndGet()
        showLoading()
        executor.execute {
            when (val result = engine.refresh()) {
                is RecordingRefreshResult.Loaded -> {
                    loadedRecordingCount = result.recordings.size
                    val sections = engine.query(currentQuery)
                    mainHandler.post {
                        if (generation != requestGeneration.get()) return@post
                        hasLoaded = true
                        renderSections(sections)
                    }
                }

                is RecordingRefreshResult.Failed -> mainHandler.post {
                    if (generation != requestGeneration.get()) return@post
                    showError()
                }
            }
        }
    }

    private fun scheduleQuery() {
        if (!hasLoaded) return
        mainHandler.removeCallbacks(queryRunnable)
        mainHandler.postDelayed(queryRunnable, SEARCH_DEBOUNCE_MILLIS)
    }

    private val queryRunnable = Runnable {
        val generation = requestGeneration.incrementAndGet()
        val query = currentQuery
        executor.execute {
            val sections = engine.query(query)
            mainHandler.post {
                if (generation == requestGeneration.get()) renderSections(sections)
            }
        }
    }

    private fun renderSections(sections: List<com.ashraffarag.sentricam.recording.library.domain.RecordingSection>) {
        binding.loadingIndicator.visibility = View.GONE
        binding.errorGroup.visibility = View.GONE
        val isEmpty = sections.isEmpty()
        binding.emptyGroup.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recordingsList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (isEmpty) {
            val filtered = loadedRecordingCount > 0
            binding.emptyTitle.setText(
                if (filtered) R.string.library_no_matching_recordings else R.string.library_empty_title,
            )
            binding.emptySubtitle.setText(
                if (filtered) R.string.library_adjust_search else R.string.library_empty_subtitle,
            )
        }
        adapter.submitSections(sections)
    }

    private fun showLoading() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.emptyGroup.visibility = View.GONE
        binding.errorGroup.visibility = View.GONE
        binding.recordingsList.visibility = View.GONE
    }

    private fun showError() {
        binding.loadingIndicator.visibility = View.GONE
        binding.emptyGroup.visibility = View.GONE
        binding.recordingsList.visibility = View.GONE
        binding.errorGroup.visibility = View.VISIBLE
    }

    private fun Int?.toFilter(): RecordingDateFilter = when (this) {
        R.id.filter_today -> RecordingDateFilter.TODAY
        R.id.filter_yesterday -> RecordingDateFilter.YESTERDAY
        R.id.filter_this_week -> RecordingDateFilter.THIS_WEEK
        else -> RecordingDateFilter.ALL
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
