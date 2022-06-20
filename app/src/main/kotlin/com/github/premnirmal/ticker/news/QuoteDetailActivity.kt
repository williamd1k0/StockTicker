package com.github.premnirmal.ticker.news

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog.Builder
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.github.mikephil.charting.charts.LineChart
import com.github.premnirmal.ticker.AppPreferences
import com.github.premnirmal.ticker.CustomTabs
import com.github.premnirmal.ticker.analytics.ClickEvent
import com.github.premnirmal.ticker.analytics.GeneralEvent
import com.github.premnirmal.ticker.base.BaseGraphActivity
import com.github.premnirmal.ticker.components.InAppMessage
import com.github.premnirmal.ticker.components.Injector
import com.github.premnirmal.ticker.formatChange
import com.github.premnirmal.ticker.formatChangePercent
import com.github.premnirmal.ticker.getActionBarHeight
import com.github.premnirmal.ticker.getNavigationBarHeight
import com.github.premnirmal.ticker.getStatusBarHeight
import com.github.premnirmal.ticker.isNetworkOnline
import com.github.premnirmal.ticker.model.IHistoryProvider.Range
import com.github.premnirmal.ticker.network.data.NewsArticle
import com.github.premnirmal.ticker.network.data.Quote
import com.github.premnirmal.ticker.portfolio.AddAlertsActivity
import com.github.premnirmal.ticker.portfolio.AddNotesActivity
import com.github.premnirmal.ticker.portfolio.AddPositionActivity
import com.github.premnirmal.ticker.showDialog
import com.github.premnirmal.ticker.ui.SpacingDecoration
import com.github.premnirmal.ticker.widget.WidgetDataProvider
import com.github.premnirmal.tickerwidget.R
import com.github.premnirmal.tickerwidget.databinding.ActivityQuoteDetailBinding
import com.google.android.material.appbar.AppBarLayout
import com.robinhood.ticker.TickerUtils
import javax.inject.Inject
import kotlin.math.abs

class QuoteDetailActivity : BaseGraphActivity<ActivityQuoteDetailBinding>(), NewsFeedAdapter.NewsClickListener {

  companion object {
    const val TICKER = "TICKER"
    private const val REQ_EDIT_POSITIONS = 10001
    private const val REQ_EDIT_NOTES = 10002
    private const val REQ_EDIT_ALERTS = 10003
    private const val INDEX_PROGRESS = 0
    private const val INDEX_ERROR = 1
    private const val INDEX_EMPTY = 2
    private const val INDEX_DATA = 3
  }

  @Inject lateinit var appPreferences: AppPreferences
  override val simpleName: String = "NewsFeedActivity"
  private lateinit var adapter: NewsFeedAdapter
  private lateinit var quoteDetailsAdapter: QuoteDetailsAdapter
  private lateinit var ticker: String
  private lateinit var quote: Quote
  private val viewModel: QuoteDetailViewModel by viewModels()
  override var range: Range = Range.ONE_MONTH

  override fun onCreate(savedInstanceState: Bundle?) {
    Injector.appComponent.inject(this)
    super.onCreate(savedInstanceState)
    ticker = checkNotNull(intent.getStringExtra(TICKER))
    binding.toolbar.setNavigationOnClickListener {
      finish()
    }
    if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
      binding.graphContainer.layoutParams.height = (resources.displayMetrics.widthPixels * 0.5625f).toInt()
      binding.graphContainer.requestLayout()
    }
    ViewCompat.setOnApplyWindowInsetsListener(binding.parentView) { _, insets ->
      binding.toolbar.updateLayoutParams<MarginLayoutParams> {
        this.topMargin = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
      }
      binding.headerContainer.updateLayoutParams<MarginLayoutParams> {
        this.topMargin = getActionBarHeight() + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
      }
      insets
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      binding.fakeStatusBar.updateLayoutParams<ViewGroup.LayoutParams> {
        height = getStatusBarHeight()
      }
    }
    quoteDetailsAdapter = QuoteDetailsAdapter()
    binding.listDetails.apply {
      layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
      adapter = quoteDetailsAdapter
    }
    adapter = NewsFeedAdapter(this)
    binding.recyclerView.layoutManager = LinearLayoutManager(this)
    binding.recyclerView.addItemDecoration(
        SpacingDecoration(resources.getDimensionPixelSize(R.dimen.list_spacing_double))
    )
    binding.recyclerView.adapter = adapter
    binding.recyclerView.isNestedScrollingEnabled = false
    binding.equityValue.setCharacterLists(TickerUtils.provideNumberList())
    binding.price.setCharacterLists(TickerUtils.provideNumberList())
    binding.change.setCharacterLists(TickerUtils.provideNumberList())
    binding.changePercent.setCharacterLists(TickerUtils.provideNumberList())
    if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
      binding.appBarLayout.addOnOffsetChangedListener(offsetChangedListener)
    } else if (!resources.getBoolean(R.bool.isTablet) && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
      binding.parentView.updateLayoutParams<MarginLayoutParams> {
        val rotation = if (VERSION.SDK_INT >= VERSION_CODES.R) {
          display?.rotation ?: Surface.ROTATION_90
        } else {
          windowManager.defaultDisplay.rotation
        }
        if (rotation == Surface.ROTATION_90) {
          marginEnd = getNavigationBarHeight()
        } else {
          marginStart = getNavigationBarHeight()
        }
      }
    }
    setupGraphView()
    updateToolbar()
    viewModel.quote.observe(this) { result ->
      if (result?.wasSuccessful == true) {
        quote = result.data
        fetchNewsAndChartData()
        setupQuoteUi()
      } else if (result?.wasSuccessful == false) {
        InAppMessage.showMessage(binding.parentView, R.string.error_fetching_stock, error = true)
        binding.progress.visibility = View.GONE
        binding.graphView.setNoDataText(getString(R.string.error_fetching_stock))
        binding.newsContainer.displayedChild = INDEX_ERROR
      }
    }
    viewModel.details.asLiveData().observe(this) {
      quoteDetailsAdapter.submitList(it)
    }
    viewModel.data.observe(this) { data ->
      dataPoints = data
      loadGraph(ticker)
    }
    viewModel.fetchQuoteInRealTime(ticker)
    viewModel.dataFetchError.observe(this) {
      binding.progress.visibility = View.GONE
      binding.graphView.setNoDataText(getString(R.string.graph_fetch_failed))
      InAppMessage.showMessage(binding.parentView, R.string.graph_fetch_failed, error = true)
    }
    viewModel.newsData.observe(this) { data ->
      analytics.trackGeneralEvent(
          GeneralEvent("FetchNews")
              .addProperty("Success", "True")
      )
      setUpArticles(data)
    }
    viewModel.newsError.observe(this) {
      binding.newsContainer.displayedChild = INDEX_ERROR
      InAppMessage.showMessage(binding.parentView, R.string.news_fetch_failed, error = true)
      analytics.trackGeneralEvent(
          GeneralEvent("FetchNews")
              .addProperty("Success", "False")
      )
    }
    viewModel.fetchQuote(ticker)
    binding.groupPeriod.setOnCheckedChangeListener { _, checkedId ->
      val view = findViewById<View>(checkedId)
      updateRange(view)
    }
    val view = when (range) {
      Range.ONE_DAY -> binding.oneDay
      Range.TWO_WEEKS -> binding.twoWeeks
      Range.ONE_MONTH -> binding.oneMonth
      Range.THREE_MONTH -> binding.threeMonth
      Range.ONE_YEAR -> binding.oneYear
      Range.MAX -> binding.max
      else -> throw UnsupportedOperationException("Range not supported")
    }
    binding.groupPeriod.check(view.id)
  }

  private val offsetChangedListener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
    if (verticalOffset < -20) {
      binding.gradient.alpha = abs(verticalOffset / appBarLayout.height.toFloat())
    } else {
      binding.gradient.alpha = 0f
    }
  }

  @SuppressLint("SetTextI18n")
  private fun setupQuoteUi() {
    binding.toolbar.title = ticker
    binding.tickerName.text = quote.name
    binding.price.text = quote.priceFormat.format(quote.lastTradePrice)
    binding.change.formatChange(quote.change)
    binding.changePercent.formatChangePercent(quote.changeInPercent)
    updatePositionsUi()

    binding.positionsHeader.setOnClickListener {
      positionOnClickListener()
    }

    binding.notesHeader.setOnClickListener {
      notesOnClickListener()
    }
    binding.notesContainer.setOnClickListener {
      notesOnClickListener()
    }

    binding.alertHeader.setOnClickListener {
      alertsOnClickListener()
    }
    binding.alertsContainer.setOnClickListener {
      alertsOnClickListener()
    }
  }

  private fun updateToolbar() {
    binding.toolbar.menu.clear()
    binding.toolbar.inflateMenu(R.menu.menu_news_feed)
    val showRemove = viewModel.showAddOrRemove(ticker)
    val addMenuItem = binding.toolbar.menu.findItem(R.id.action_add)
    val removeMenuItem = binding.toolbar.menu.findItem(R.id.action_remove)
    if (showRemove) {
      addMenuItem.isVisible = false
      removeMenuItem.isVisible = true
    } else {
      removeMenuItem.isVisible = false
      addMenuItem.isVisible = true
    }
    binding.toolbar.setOnMenuItemClickListener { menuItem ->
      when (menuItem.itemId) {
        R.id.action_add -> {
          if (viewModel.hasWidget()) {
            val widgetDatas = viewModel.getWidgetDatas()
            if (widgetDatas.size > 1) {
              val widgetNames = widgetDatas.map { it.widgetName() }
                  .toTypedArray()
              Builder(this)
                  .setTitle(R.string.select_widget)
                  .setItems(widgetNames) { dialog, which ->
                    val id = widgetDatas[which].widgetId
                    addTickerToWidget(ticker, id)
                    dialog.dismiss()
                  }
                  .create()
                  .show()
            } else {
              addTickerToWidget(ticker, widgetDatas.first().widgetId)
            }
          } else {
            addTickerToWidget(ticker, WidgetDataProvider.INVALID_WIDGET_ID)
          }
          updatePositionsUi()
          return@setOnMenuItemClickListener true
        }
        R.id.action_remove -> {
          removeMenuItem.isVisible = false
          addMenuItem.isVisible = true
          viewModel.removeStock(ticker)
          updatePositionsUi()
          return@setOnMenuItemClickListener true
        }
      }
      return@setOnMenuItemClickListener false
    }
  }

  private fun positionOnClickListener() {
    analytics.trackClickEvent(
        ClickEvent("EditPositionClick")
    )
    val intent = Intent(this, AddPositionActivity::class.java)
    intent.putExtra(AddPositionActivity.TICKER, quote.symbol)
    startActivityForResult(intent, REQ_EDIT_POSITIONS)
  }

  private fun notesOnClickListener() {
    analytics.trackClickEvent(
        ClickEvent("EditNotesClick")
    )
    val intent = Intent(this, AddNotesActivity::class.java)
    intent.putExtra(AddNotesActivity.TICKER, quote.symbol)
    startActivityForResult(intent, REQ_EDIT_NOTES)
  }

  private fun alertsOnClickListener() {
    analytics.trackClickEvent(
        ClickEvent("EditAlertsClick")
    )
    val intent = Intent(this, AddAlertsActivity::class.java)
    intent.putExtra(AddAlertsActivity.TICKER, quote.symbol)
    startActivityForResult(intent, REQ_EDIT_ALERTS)
  }

  private fun fetchData() {
    if (!::quote.isInitialized) return

    if (isNetworkOnline()) {
      binding.progress.visibility = View.VISIBLE
      viewModel.fetchChartData(quote.symbol, range)
    } else {
      binding.progress.visibility = View.GONE
      binding.graphView.setNoDataText(getString(R.string.no_network_message))
    }
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
  ) {
    if (requestCode == REQ_EDIT_POSITIONS) {
      if (resultCode == Activity.RESULT_OK) {
        quote = checkNotNull(data?.getParcelableExtra(AddPositionActivity.QUOTE))
      }
    }
    if (requestCode == REQ_EDIT_NOTES) {
      if (resultCode == Activity.RESULT_OK) {
        quote = checkNotNull(data?.getParcelableExtra(AddNotesActivity.QUOTE))
      }
    }
    if (requestCode == REQ_EDIT_ALERTS) {
      if (resultCode == Activity.RESULT_OK) {
        quote = checkNotNull(data?.getParcelableExtra(AddAlertsActivity.QUOTE))
      }
    }
    super.onActivityResult(requestCode, resultCode, data)
  }

  private fun setUpArticles(articles: List<NewsArticle>) {
    if (articles.isEmpty()) {
      binding.newsContainer.displayedChild = INDEX_EMPTY
    } else {
      adapter.setData(articles)
      binding.newsContainer.displayedChild = INDEX_DATA
    }
  }

  override fun onResume() {
    super.onResume()
    if (this::quote.isInitialized) {
      updatePositionsUi()
    }
  }

  @SuppressLint("SetTextI18n")
  private fun updatePositionsUi() {
    if (!this::quote.isInitialized) {
      return
    }
    val isInPortfolio = viewModel.isInPortfolio(ticker)
    if (isInPortfolio) {
      binding.positionsContainer.visibility = View.VISIBLE
      binding.positionsHeader.visibility = View.VISIBLE
      binding.notesHeader.visibility = View.VISIBLE
      binding.alertHeader.visibility = View.VISIBLE
      binding.numShares.text = quote.numSharesString()
      binding.equityValue.text = quote.priceFormat.format(quote.holdings())
      val notesText = quote.properties?.notes
      if (notesText.isNullOrEmpty()) {
        binding.notesContainer.visibility = View.GONE
      } else {
        binding.notesContainer.visibility = View.VISIBLE
        binding.notesDisplay.text = notesText
      }

      val alertAbove = quote.getAlertAbove()
      val alertBelow = quote.getAlertBelow()
      if (alertAbove > 0.0f || alertBelow > 0.0f) {
        binding.alertsContainer.visibility = View.VISIBLE
      } else {
        binding.alertsContainer.visibility = View.GONE
      }
      if (alertAbove > 0.0f) {
        binding.alertAbove.visibility = View.VISIBLE
        binding.alertAbove.setText(appPreferences.selectedDecimalFormat.format(alertAbove))
      } else {
        binding.alertAbove.visibility = View.GONE
      }
      if (alertBelow > 0.0f) {
        binding.alertBelow.visibility = View.VISIBLE
        binding.alertBelow.setText(appPreferences.selectedDecimalFormat.format(alertBelow))
      } else {
        binding.alertBelow.visibility = View.GONE
      }

      if (quote.hasPositions()) {
        binding.totalGainLoss.visibility = View.VISIBLE
        binding.totalGainLoss.setText("${quote.gainLossString()} (${quote.gainLossPercentString()})")
        if (quote.gainLoss() >= 0) {
          binding.totalGainLoss.setTextColor(ContextCompat.getColor(this, R.color.positive_green))
        } else {
          binding.totalGainLoss.setTextColor(ContextCompat.getColor(this, R.color.negative_red))
        }
        binding.averagePrice.visibility = View.VISIBLE
        binding.averagePrice.setText(quote.averagePositionPrice())
        binding.dayChange.visibility = View.VISIBLE
        binding.dayChange.setText(quote.dayChangeString())
        if (quote.change > 0 || quote.changeInPercent >= 0) {
          binding.dayChange.setTextColor(ContextCompat.getColor(this, R.color.positive_green))
        } else {
          binding.dayChange.setTextColor(ContextCompat.getColor(this, R.color.negative_red))
        }
      } else {
        binding.totalGainLoss.visibility = View.GONE
        binding.dayChange.visibility = View.GONE
        binding.averagePrice.visibility = View.GONE
      }
    } else {
      binding.positionsHeader.visibility = View.GONE
      binding.positionsContainer.visibility = View.GONE
      binding.notesHeader.visibility = View.GONE
      binding.notesContainer.visibility = View.GONE
      binding.alertHeader.visibility = View.GONE
      binding.alertsContainer.visibility = View.GONE
    }
  }

  private fun fetchNewsAndChartData() {
    if (!isNetworkOnline()) {
      InAppMessage.showMessage(binding.parentView, R.string.no_network_message, error = true)
    }
    if (adapter.itemCount == 0) {
      binding.newsContainer.displayedChild = INDEX_PROGRESS
      fetchNews()
    }
    if (dataPoints == null) {
      fetchData()
    } else {
      loadGraph(ticker)
    }
  }

  private fun fetchNews() {
    if (isNetworkOnline()) {
      viewModel.fetchNews(quote)
    } else {
      binding.newsContainer.displayedChild = INDEX_ERROR
    }
  }

  override fun fetchGraphData() {
    fetchData()
  }

  override fun onGraphDataAdded(graphView: LineChart) {
    binding.progress.visibility = View.GONE
    analytics.trackGeneralEvent(GeneralEvent("GraphLoaded"))
  }

  override fun onNoGraphData(graphView: LineChart) {
    binding.progress.visibility = View.GONE
    analytics.trackGeneralEvent(GeneralEvent("NoGraphData"))
  }

  /**
   * Called via xml
   */
  fun openGraph(v: View) {
    analytics.trackClickEvent(
        ClickEvent("GraphClick")
    )
    val intent = Intent(this, GraphActivity::class.java)
    intent.putExtra(GraphActivity.TICKER, ticker)
    startActivity(intent)
  }

  private fun addTickerToWidget(
    ticker: String,
    widgetId: Int
  ) {
    if (viewModel.addTickerToWidget(ticker, widgetId)) {
      InAppMessage.showToast(this, getString(R.string.added_to_list, ticker))
      val showRemove = viewModel.showAddOrRemove(ticker)
      val addMenuItem = binding.toolbar.menu.findItem(R.id.action_add)
      val removeMenuItem = binding.toolbar.menu.findItem(R.id.action_remove)
      if (showRemove) {
        addMenuItem.isVisible = false
        removeMenuItem.isVisible = true
      } else {
        removeMenuItem.isVisible = false
        addMenuItem.isVisible = true
      }
    } else {
      showDialog(getString(R.string.already_in_portfolio, ticker))
    }
  }

  // NewsFeedAdapter.NewsClickListener

  override fun onClickNewsArticle(article: NewsArticle) {
    CustomTabs.openTab(this, article.url)
  }
}