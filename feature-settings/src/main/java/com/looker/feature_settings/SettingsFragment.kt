package com.looker.feature_settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.looker.core.common.SdkCheck
import com.looker.core.common.extension.getDrawableFromAttr
import com.looker.core.common.extension.setCollapsable
import com.looker.core.common.extension.systemBarsPadding
import com.looker.core.datastore.UserPreferences
import com.looker.core.datastore.extension.autoSyncName
import com.looker.core.datastore.extension.installerName
import com.looker.core.datastore.extension.proxyName
import com.looker.core.datastore.extension.themeName
import com.looker.core.datastore.model.AutoSync
import com.looker.core.datastore.model.InstallerType
import com.looker.core.datastore.model.ProxyType
import com.looker.core.datastore.model.Theme
import com.looker.feature_settings.databinding.SettingsPageBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import com.looker.core.common.BuildConfig as LocaleConfig
import com.looker.core.common.R.dimen as dimenRes
import com.looker.core.common.R.drawable as drawableRes
import com.looker.core.common.R.plurals as pluralRes
import com.looker.core.common.R.string as stringRes

@AndroidEntryPoint
class SettingsFragment : Fragment() {

	companion object {
		fun newInstance() = SettingsFragment()
	}

	private val viewModel: SettingsViewModel by viewModels()
	private var _binding: SettingsPageBinding? = null
	private val binding get() = _binding!!

	private var restartSnackbar: Snackbar? = null

	@SuppressLint("SetTextI18n")
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		_binding = SettingsPageBinding.inflate(inflater, container, false)
		binding.nestedScrollView.systemBarsPadding()
		restartSnackbar = Snackbar.make(binding.root, stringRes.restart_app, Snackbar.LENGTH_LONG)
		val toolbar = binding.toolbar
		toolbar.navigationIcon =
			toolbar.context.getDrawableFromAttr(android.R.attr.homeAsUpIndicator)
		toolbar.setNavigationOnClickListener { activity?.onBackPressed() }
		toolbar.title = getString(stringRes.settings)
		with(binding) {
			language.title.text = getString(stringRes.prefs_language_title)
			theme.title.text = getString(stringRes.theme)
			dynamicTheme.title.text = getString(stringRes.material_you)
			dynamicTheme.content.text = getString(stringRes.material_you_desc)
			allowCollapsingToolbar.title.text = getString(stringRes.allow_collapsing_toolbar)
			allowCollapsingToolbar.content.text = getString(stringRes.allow_collapsing_toolbar_DESC)
			cleanUp.title.text = getString(stringRes.cleanup_title)
			forceCleanUp.title.text = getString(stringRes.force_clean_up)
			forceCleanUp.content.text = getString(stringRes.force_clean_up_DESC)
			autoSync.title.text = getString(stringRes.sync_repositories_automatically)
			notifyUpdates.title.text = getString(stringRes.notify_about_updates)
			notifyUpdates.content.text = getString(stringRes.notify_about_updates_summary)
			autoUpdate.title.text = getString(stringRes.auto_update)
			autoUpdate.content.text = getString(stringRes.auto_update_apps)
			unstableUpdates.title.text = getString(stringRes.unstable_updates)
			unstableUpdates.content.text = getString(stringRes.unstable_updates_summary)
			incompatibleUpdates.title.text = getString(stringRes.incompatible_versions)
			incompatibleUpdates.content.text = getString(stringRes.incompatible_versions_summary)
			proxyType.title.text = getString(stringRes.proxy_type)
			proxyHost.title.text = getString(stringRes.proxy_host)
			proxyPort.title.text = getString(stringRes.proxy_port)
			installer.title.text = getString(stringRes.installer)
			creditFoxy.title.text = "Based on Foxy Droid"
			creditFoxy.content.text = "FoxyDroid"
			droidify.title.text = "Droid-ify"
			droidify.content.text = BuildConfig.VERSION_NAME
		}
		viewLifecycleOwner.lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.RESUMED) {
				setChangeListener()
				viewModel.userPreferencesFlow.collect {
					binding.appbarLayout.setCollapsable(it.allowCollapsingToolbar)
					updateUserPreference(it)
				}
			}
		}
		return binding.root
	}

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
		restartSnackbar = null
	}

	private fun <T> View.addSingleCorrectDialog(
		initialValue: T,
		values: List<T>,
		@StringRes title: Int,
		@DrawableRes iconRes: Int,
		onClick: (T) -> Unit,
		valueToString: (T) -> String
	) = MaterialAlertDialogBuilder(context)
		.setTitle(title)
		.setIcon(iconRes)
		.setSingleChoiceItems(
			values.map(valueToString).toTypedArray(),
			values.indexOf(initialValue)
		) { dialog, newValue ->
			dialog.dismiss()
			post {
				onClick(values.elementAt(newValue))
			}
		}
		.setNegativeButton(stringRes.cancel, null)
		.create()

	private fun View.addIntEditText(
		initialValue: Int,
		@StringRes title: Int,
		onFinish: (Int) -> Unit
	): AlertDialog {
		val scroll = NestedScrollView(context)
		val customEditText = TextInputEditText(context)
		customEditText.id = android.R.id.edit
		val paddingValue = context.resources.getDimension(dimenRes.shape_margin_large).toInt()
		scroll.setPadding(paddingValue, 0, paddingValue, 0)
		customEditText.setText(initialValue.toString())
		customEditText.hint = customEditText.text.toString()
		customEditText.text?.let { editable -> customEditText.setSelection(editable.length) }
		customEditText.requestFocus()
		scroll.addView(
			customEditText,
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT
		)
		return MaterialAlertDialogBuilder(context)
			.setTitle(title)
			.setView(scroll)
			.setPositiveButton(stringRes.ok) { _, _ ->
				post {
					val output = try {
						customEditText.text.toString().toInt()
					} catch (e: NumberFormatException) {
						Toast.makeText(context, "PORT can only be a Integer", Toast.LENGTH_SHORT)
							.show()
						initialValue
					}
					onFinish(output)
				}
			}
			.setNegativeButton(stringRes.cancel, null)
			.create()
			.apply {
				window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
			}
	}

	private fun View.addStringEditText(
		initialValue: String,
		@StringRes title: Int,
		onFinish: (String) -> Unit
	): AlertDialog {
		val scroll = NestedScrollView(context)
		val customEditText = TextInputEditText(context)
		customEditText.id = android.R.id.edit
		val paddingValue = context.resources.getDimension(dimenRes.shape_margin_large).toInt()
		scroll.setPadding(paddingValue, 0, paddingValue, 0)
		customEditText.setText(initialValue)
		customEditText.hint = customEditText.text.toString()
		customEditText.text?.let { editable -> customEditText.setSelection(editable.length) }
		customEditText.requestFocus()
		scroll.addView(
			customEditText,
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT
		)
		return MaterialAlertDialogBuilder(context)
			.setTitle(title)
			.setView(scroll)
			.setPositiveButton(stringRes.ok) { _, _ ->
				post { onFinish(customEditText.text.toString()) }
			}
			.setNegativeButton(stringRes.cancel, null)
			.create()
			.apply {
				window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
			}
	}

	private fun setChangeListener() {
		with(binding) {
			with(viewModel) {
				dynamicTheme.checked.setOnCheckedChangeListener { _, checked ->
					setDynamicTheme(checked)
				}
				allowCollapsingToolbar.checked.setOnCheckedChangeListener { _, checked ->
					setToolbarState(checked)
				}
				notifyUpdates.checked.setOnCheckedChangeListener { _, checked ->
					setNotifyUpdates(checked)
				}
				autoUpdate.checked.setOnCheckedChangeListener { _, checked ->
					setAutoUpdate(checked)
				}
				unstableUpdates.checked.setOnCheckedChangeListener { _, checked ->
					setUnstableUpdates(checked)
				}
				incompatibleUpdates.checked.setOnCheckedChangeListener { _, checked ->
					setIncompatibleUpdates(checked)
				}
			}
			creditFoxy.root.setOnClickListener {
				"https://github.com/kitsunyan/foxy-droid".openLink(context)
			}
			droidify.root.setOnClickListener {
				"https://github.com/Iamlooker/Droid-ify".openLink(context)
			}
		}
	}

	private val languageList: List<String>
		get() {
			val list = LocaleConfig.DETECTED_LOCALES.toMutableList()
			list.add(0, "system")
			return list
		}

	private val languageListCompat = AppCompatDelegate.getApplicationLocales()

	private fun updateUserPreference(userPreferences: UserPreferences) {
		val systemSetLanguage = languageListCompat.toLanguageTags()
		if (userPreferences.language != systemSetLanguage) {
			viewModel.setLanguage(systemSetLanguage)
		}
		with(binding) {
			language.content.text =
				translateLocale(context?.getLocaleOfCode(userPreferences.language))
			language.root.setOnClickListener { view ->
				view.addSingleCorrectDialog(
					initialValue = userPreferences.language,
					values = languageList,
					title = stringRes.prefs_language_title,
					iconRes = drawableRes.ic_language,
					onClick = { viewModel.setLanguage(it) },
					valueToString = { translateLocale(context?.getLocaleOfCode(it)) }
				).show()
			}
			theme.content.text = context.themeName(userPreferences.theme)
			theme.root.setOnClickListener { view ->
				view.addSingleCorrectDialog(
					initialValue = userPreferences.theme,
					values = Theme.values().toList(),
					title = stringRes.theme,
					iconRes = drawableRes.ic_themes,
					onClick = { viewModel.setTheme(it) },
					valueToString = { view.context.themeName(it) }
				).show()
			}
			dynamicTheme.checked.isChecked = userPreferences.dynamicTheme
			dynamicTheme.root.isVisible = SdkCheck.isSnowCake
			allowCollapsingToolbar.checked.isChecked = userPreferences.allowCollapsingToolbar
			cleanUp.content.text = userPreferences.cleanUpDuration.toTime(context)
			cleanUp.root.setOnClickListener { view ->
				view.addSingleCorrectDialog(
					initialValue = userPreferences.cleanUpDuration,
					values = cleanUpIntervals,
					valueToString = { it.toTime(context) },
					title = stringRes.cleanup_title,
					iconRes = drawableRes.ic_time,
					onClick = { viewModel.setCleanUpDuration(it) }
				).show()
			}
			forceCleanUp.root.isVisible = userPreferences.cleanUpDuration == Duration.INFINITE
					|| userPreferences.cleanUpDuration == Duration.ZERO
			forceCleanUp.root.setOnClickListener { viewModel.setCleanUpDuration(Duration.ZERO) }
			autoSync.content.text = context.autoSyncName(userPreferences.autoSync)
			autoSync.root.setOnClickListener { view ->
				view.addSingleCorrectDialog(
					initialValue = userPreferences.autoSync,
					values = AutoSync.values().toList(),
					title = stringRes.sync_repositories_automatically,
					iconRes = drawableRes.ic_sync,
					onClick = { viewModel.setAutoSync(it) },
					valueToString = { view.context.autoSyncName(it) }
				).show()
			}
			notifyUpdates.checked.isChecked = userPreferences.notifyUpdate
			autoUpdate.checked.isChecked = userPreferences.autoUpdate
			unstableUpdates.checked.isChecked = userPreferences.unstableUpdate
			incompatibleUpdates.checked.isChecked = userPreferences.incompatibleVersions
			proxyType.content.text = context.proxyName(userPreferences.proxyType)
			proxyType.root.setOnClickListener { view ->
				view.addSingleCorrectDialog(
					initialValue = userPreferences.proxyType,
					values = ProxyType.values().toList(),
					title = stringRes.proxy_type,
					iconRes = drawableRes.ic_proxy,
					onClick = { viewModel.setProxyType(it) },
					valueToString = { view.context.proxyName(it) }
				).show()
			}
			val allowProxies = userPreferences.proxyType != ProxyType.DIRECT
			proxyHost.root.isVisible = allowProxies
			proxyHost.content.text = userPreferences.proxyHost
			proxyHost.root.setOnClickListener { view ->
				view.addStringEditText(
					initialValue = userPreferences.proxyHost,
					title = stringRes.proxy_host,
					onFinish = { viewModel.setProxyHost(it) }
				).show()
			}
			proxyPort.root.isVisible = allowProxies
			proxyPort.content.text = userPreferences.proxyPort.toString()
			proxyPort.root.setOnClickListener { view ->
				view.addIntEditText(
					initialValue = userPreferences.proxyPort,
					title = stringRes.proxy_host,
					onFinish = { viewModel.setProxyPort(it) }
				).show()
			}
			installer.content.text = context.installerName(userPreferences.installerType)
			installer.root.setOnClickListener { view ->
				view.addSingleCorrectDialog(
					initialValue = userPreferences.installerType,
					values = InstallerType.values().toList(),
					title = stringRes.installer,
					iconRes = drawableRes.ic_download,
					onClick = {
						viewModel.setInstaller(it)
						restartSnackbar?.show()
					},
					valueToString = { view.context.installerName(it) }
				).show()
			}
		}
	}

	private val cleanUpIntervals = listOf(
		6.hours,
		12.hours,
		18.hours,
		1.days,
		2.days,
		Duration.INFINITE
	)

	private fun Duration.toTime(context: Context?): String {
		val time = inWholeHours.toInt()
		val days = inWholeDays.toInt()
		if (this == Duration.INFINITE || this == Duration.ZERO) return getString(stringRes.never)
		return if (time >= 24) "$days " + context?.resources?.getQuantityString(
			pluralRes.days,
			days
		)
		else "$time " + context?.resources?.getQuantityString(pluralRes.hours, time)
	}

	private fun translateLocale(locale: Locale?): String {
		val country = locale?.getDisplayCountry(locale)
		val language = locale?.getDisplayLanguage(locale)
		val languageDisplay = if (locale != null) {
			(language?.replaceFirstChar { it.uppercase(Locale.getDefault()) }
					+ (if (country?.isNotEmpty() == true && country.compareTo(
					language.toString(),
					true
				) != 0
			)
				"($country)" else ""))
		} else getString(stringRes.system)
		return languageDisplay
	}

	private fun String.openLink(context: Context?) {
		try {
			context?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(this)))
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	@Suppress("DEPRECATION")
	private fun Context.getLocaleOfCode(localeCode: String): Locale? = when {
		localeCode.isEmpty() -> if (SdkCheck.isNougat) resources.configuration.locales[0]
		else resources.configuration.locale
		localeCode.contains("-r") -> Locale(
			localeCode.substring(0, 2),
			localeCode.substring(4)
		)
		localeCode.contains("_") -> Locale(
			localeCode.substring(0, 2),
			localeCode.substring(3)
		)
		localeCode == "system" -> null
		else -> Locale(localeCode)
	}
}