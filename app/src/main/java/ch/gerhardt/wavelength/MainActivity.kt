package ch.gerhardt.wavelength

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ch.gerhardt.wavelength.databinding.ActivityMainBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.pow

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isUpdating = false
    private var wavelengthDatabase: List<WavelengthEntry> = emptyList()
    private var previousWavelengthSet: Set<String> = emptySet()
    private var isFirstWavelengthDisplay = true
    private var lastHapticTime: Long = 0
    private var lastTouchY = 0f
    private var overscrollAccumulator = 0f
    private val vibrator by lazy { 
        getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
    }

    // Custom subscript span with proper size and positioning
    class ProperSubscriptSpan : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(paint: android.text.TextPaint) {
            paint.textSize = paint.textSize * 0.75f  // 75% size
            paint.baselineShift -= (paint.ascent() * 0.2).toInt()  // Shift down slightly
        }
        
        override fun updateMeasureState(paint: android.text.TextPaint) {
            paint.textSize = paint.textSize * 0.75f
            paint.baselineShift -= (paint.ascent() * 0.2).toInt()
        }
    }

    data class WavelengthEntry(
        val minWl: Double,
        val maxWl: Double,
        val description: String,
        val icon: String  // Unicode character
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadDatabase()
        setupInputFields()
        setupAboutButton()
        setupSwipeGestures()
        
        // Restore last wavelength or start at 555nm
        restoreLastWavelength()
    }
    
    override fun onPause() {
        super.onPause()
        // Save current wavelength when app goes to background
        saveLastWavelength()
    }
    
    private fun saveLastWavelength() {
        val currentNm = binding.nmInput.text?.toString()?.toDoubleOrNull()
        if (currentNm != null && currentNm > 0) {
            val prefs = getSharedPreferences("WavelengthConverter", MODE_PRIVATE)
            prefs.edit().putFloat("lastWavelength", currentNm.toFloat()).apply()
        }
    }
    
    private fun restoreLastWavelength() {
        val prefs = getSharedPreferences("WavelengthConverter", MODE_PRIVATE)
        val lastWavelength = prefs.getFloat("lastWavelength", 555f).toDouble()
        binding.nmInput.setText(String.format("%.3f", lastWavelength))
    }
    
    override fun onResume() {
        super.onResume()
        // Reset all EditText fields to allow normal interaction after app resume
        resetEditTextStates()
    }
    
    private fun resetEditTextStates() {
        // Re-enable normal touch mode for all EditText fields
        listOf(binding.nmInput, binding.ghzInput, binding.cm1Input, binding.evInput, binding.kjmolInput).forEach { editText ->
            editText.isFocusable = true
            editText.isFocusableInTouchMode = true
            editText.clearFocus()
        }
    }
    
    // Override touch events to hide keyboard when tapping outside input fields
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    hideKeyboard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        // Clear focus from all input fields
        binding.nmInput.clearFocus()
        binding.ghzInput.clearFocus()
        binding.cm1Input.clearFocus()
        binding.evInput.clearFocus()
        binding.kjmolInput.clearFocus()
    }

    private fun loadDatabase() {
        try {
            // Try new CSV format first
            val inputStream = assets.open("wavelengths.csv")
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            wavelengthDatabase = reader.useLines { lines ->
                lines.mapNotNull { line ->
                    parseCSVLine(line)
                }.toList()
            }
        } catch (e: Exception) {
            // Fallback to old format if CSV doesn't exist
            try {
                val inputStream = assets.open("wllist.txt")
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                wavelengthDatabase = reader.useLines { lines ->
                    lines.mapNotNull { line ->
                        parseOldLine(line)
                    }.toList()
                }
            } catch (e2: Exception) {
                // No database available
            }
        }
    }

    private fun parseCSVLine(line: String): WavelengthEntry? {
        if (line.trim().isEmpty()) return null
        
        val parts = line.split(",")
        if (parts.size < 3) return null
        
        val minWl = parts[0].trim().toDoubleOrNull() ?: return null
        val maxWl = parts[1].trim().toDoubleOrNull() ?: minWl
        val description = parts[2].trim()
        val icon = if (parts.size >= 4) parts[3].trim() else "•"
        
        return WavelengthEntry(minWl, maxWl, description, icon)
    }

    private fun parseOldLine(line: String): WavelengthEntry? {
        val parts = line.split("::")
        if (parts.size < 3) return null
        
        val minWl = parts[0].trim().toDoubleOrNull() ?: return null
        val maxWlStr = parts[1].trim()
        val maxWl = if (maxWlStr.isEmpty() || maxWlStr == " ") minWl 
                   else maxWlStr.toDoubleOrNull() ?: minWl
        val description = parts[2].trim()
        
        return WavelengthEntry(minWl, maxWl, description, "•")
    }

    private fun setupInputFields() {
        // Add text watchers for auto-calculation
        binding.nmInput.addTextChangedListener(createWatcher { updateFromNm() })
        binding.ghzInput.addTextChangedListener(createWatcher { updateFromGHz() })
        binding.cm1Input.addTextChangedListener(createWatcher { updateFromCm1() })
        binding.evInput.addTextChangedListener(createWatcher { updateFromEv() })
        binding.kjmolInput.addTextChangedListener(createWatcher { updateFromKjmol() })
        
        // Auto-hide keyboard when user presses Enter/Done on any input field
        val onEditorActionListener = android.widget.TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        
        binding.nmInput.setOnEditorActionListener(onEditorActionListener)
        binding.ghzInput.setOnEditorActionListener(onEditorActionListener)
        binding.cm1Input.setOnEditorActionListener(onEditorActionListener)
        binding.evInput.setOnEditorActionListener(onEditorActionListener)
        binding.kjmolInput.setOnEditorActionListener(onEditorActionListener)
    }

    private fun setupAboutButton() {
        binding.aboutButton.setOnClickListener {
            val message = getString(R.string.about_text)
            val websiteUrl = getString(R.string.website_url)
            val email = "ilja.gerhardt@physics.uni-hannover.de"
            
            // Make email clickable with custom color
            val spannableMessage = SpannableStringBuilder(message)
            val emailStart = message.indexOf(email)
            if (emailStart >= 0) {
                spannableMessage.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: android.view.View) {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:$email")
                                putExtra(Intent.EXTRA_SUBJECT, "Wavelength Converter - New Wavelength Suggestion")
                            }
                            startActivity(intent)
                        }
                        
                        override fun updateDrawState(ds: android.text.TextPaint) {
                            super.updateDrawState(ds)
                            ds.color = 0xFF00BFFF.toInt() // Bright cyan/light blue for visibility
                            ds.isUnderlineText = true
                        }
                    },
                    emailStart,
                    emailStart + email.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.about))
                .setMessage(spannableMessage)
                .setPositiveButton("Visit Website") { _, _ ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl))
                    startActivity(intent)
                }
                .setNegativeButton("Close", null)
                .create()
            
            dialog.show()
            
            // Make links clickable and set custom link color
            val messageView = dialog.findViewById<android.widget.TextView>(android.R.id.message)
            messageView?.apply {
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                // Set link color to bright cyan for better visibility on gray background
                setLinkTextColor(0xFF00BFFF.toInt())
            }
            
            // Set button text colors to bright cyan for visibility
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF00BFFF.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF00BFFF.toInt())
        }
    }

    private fun setupSwipeGestures() {
        // Setup swipe for nm field: ~20 nm range for full screen swipe
        setupContinuousSwipeGesture(binding.nmInput, 0.05) { delta ->
            val currentValue = binding.nmInput.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (currentValue > 0) {
                val newValue = (currentValue + delta).coerceAtLeast(1.0)
                binding.nmInput.setText(String.format("%.3f", newValue))
            }
        }
        
        // Setup swipe for GHz field: ~200 GHz range for full screen swipe
        setupContinuousSwipeGesture(binding.ghzInput, 0.5) { delta ->
            val currentValue = binding.ghzInput.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (currentValue > 0) {
                val newValue = (currentValue + delta).coerceAtLeast(0.1)
                binding.ghzInput.setText(String.format("%.3f", newValue))
            }
        }
        
        // Setup swipe for cm⁻¹ field: ~20 cm⁻¹ range for full screen swipe
        setupContinuousSwipeGesture(binding.cm1Input, 0.05) { delta ->
            val currentValue = binding.cm1Input.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (currentValue > 0) {
                val newValue = (currentValue + delta).coerceAtLeast(1.0)
                binding.cm1Input.setText(String.format("%.1f", newValue))
            }
        }
        
        // Setup swipe for eV field: ~0.2 eV range for full screen swipe
        setupContinuousSwipeGesture(binding.evInput, 0.0005) { delta ->
            val currentValue = binding.evInput.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (currentValue > 0) {
                val newValue = (currentValue + delta).coerceAtLeast(0.001)
                binding.evInput.setText(String.format("%.3f", newValue))
            }
        }
        
        // Setup swipe for kJ/mol field: ~20 kJ/mol range for full screen swipe (faster, less laggy)
        setupContinuousSwipeGesture(binding.kjmolInput, 0.05) { delta ->
            val currentValue = binding.kjmolInput.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (currentValue > 0) {
                val newValue = (currentValue + delta).coerceAtLeast(0.1)
                binding.kjmolInput.setText(String.format("%.1f", newValue))
            }
        }
        
        // Setup swipe on color bar for fast wavelength scanning
        setupColorBarSwipe()
    }
    
    private fun setupContinuousSwipeGesture(editText: EditText, pixelToValueRatio: Double, updateFunc: (Double) -> Unit) {
        var startX = 0f
        var startY = 0f
        var lastX = 0f
        var hasMoved = false
        var isSwiping = false
        var isLongPressTriggered = false
        var downTime = 0L
        val moveThreshold = 15 // pixels to detect if user moved at all
        val swipeThreshold = 25 // pixels to start swiping
        val longPressTime = 500L // milliseconds for long press
        
        editText.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    lastX = event.x
                    hasMoved = false
                    isSwiping = false
                    isLongPressTriggered = false
                    downTime = System.currentTimeMillis()
                    false // Don't consume initially - let EditText handle it
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // If long press already triggered, ignore all further events
                    if (isLongPressTriggered) {
                        return@setOnTouchListener false
                    }
                    
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY
                    val incrementalDeltaX = event.x - lastX
                    val holdDuration = System.currentTimeMillis() - downTime
                    
                    // Check if user has moved at all
                    if (kotlin.math.abs(deltaX) > moveThreshold || kotlin.math.abs(deltaY) > moveThreshold) {
                        hasMoved = true
                    }
                    
                    // If long press (>500ms) without movement, trigger selection ONCE
                    if (holdDuration > longPressTime && !hasMoved) {
                        isLongPressTriggered = true
                        editText.performLongClick()
                        return@setOnTouchListener false
                    }
                    
                    // If horizontal swipe is detected (more horizontal than vertical)
                    if (kotlin.math.abs(deltaX) > swipeThreshold && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.5) {
                        if (!isSwiping) {
                            isSwiping = true
                            // Clear focus so keyboard doesn't appear
                            editText.clearFocus()
                            hideKeyboard()
                        }
                        
                        // Apply continuous increment based on pixel movement
                        // Positive deltaX = swipe right = increase value
                        val delta = incrementalDeltaX * pixelToValueRatio
                        updateFunc(delta)
                        
                        // Update last position for continuous movement
                        lastX = event.x
                        true // Consume the event
                    } else {
                        false // Let EditText handle if not swiping
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    // If long press was triggered, don't process as tap
                    if (isLongPressTriggered) {
                        isLongPressTriggered = false
                        return@setOnTouchListener false
                    }
                    
                    // Only process as a tap if user didn't move (or moved very little)
                    if (!hasMoved && !isSwiping) {
                        // This is a tap - allow normal EditText behavior
                        editText.isFocusableInTouchMode = true
                        editText.requestFocus()
                        // Show keyboard
                        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        // Move cursor to end
                        editText.setSelection(editText.text?.length ?: 0)
                    }
                    isSwiping = false
                    hasMoved = false
                    isLongPressTriggered = false
                    false // Don't consume UP
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isSwiping = false
                    hasMoved = false
                    isLongPressTriggered = false
                    false
                }
                else -> false
            }
        }
    }
    
    private fun setupColorBarSwipe() {
        var startX = 0f
        var lastX = 0f
        var isSwiping = false
        // Fast scanning: ~400 nm range for full screen swipe (400-800 nm visible spectrum)
        val pixelToNmRatio = 1.0 // 1 nm per pixel for fast spectrum scanning
        
        binding.colorDisplay.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    lastX = event.x
                    isSwiping = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - startX
                    val incrementalDeltaX = event.x - lastX
                    
                    // Start swiping immediately on any horizontal movement
                    if (kotlin.math.abs(deltaX) > 5) {
                        if (!isSwiping) {
                            isSwiping = true
                            // Clear focus from all input fields
                            binding.nmInput.clearFocus()
                            binding.ghzInput.clearFocus()
                            binding.cm1Input.clearFocus()
                            binding.evInput.clearFocus()
                            binding.kjmolInput.clearFocus()
                            hideKeyboard()
                        }
                        
                        // Get current wavelength
                        val currentValue = binding.nmInput.text?.toString()?.toDoubleOrNull() ?: 589.0
                        
                        // Apply fast continuous increment
                        val delta = incrementalDeltaX * pixelToNmRatio
                        val newValue = (currentValue + delta).coerceIn(1.0, 10000.0)
                        
                        // Update nm field (which triggers all other fields)
                        binding.nmInput.setText(String.format("%.3f", newValue))
                        
                        lastX = event.x
                        true
                    } else {
                        true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isSwiping = false
                    true
                }
                else -> false
            }
        }
    }

    private fun createWatcher(updateFunc: () -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdating) {
                    updateFunc()
                }
            }
        }
    }

    private fun updateFromNm() {
        val value = binding.nmInput.text?.toString()?.toDoubleOrNull()
        if (value != null && value > 0) {
            updateAllFields(value, exceptField = binding.nmInput)
            updateDisplay(value)
        } else {
            clearDisplay()
        }
    }

    private fun updateFromGHz() {
        val value = binding.ghzInput.text?.toString()?.toDoubleOrNull()
        if (value != null && value > 0) {
            val wavelengthNm = 299792458.0 / value
            updateAllFields(wavelengthNm, exceptField = binding.ghzInput)
            updateDisplay(wavelengthNm)
        } else {
            clearDisplay()
        }
    }

    private fun updateFromCm1() {
        val value = binding.cm1Input.text?.toString()?.toDoubleOrNull()
        if (value != null && value > 0) {
            val wavelengthNm = 10000000.0 / value
            updateAllFields(wavelengthNm, exceptField = binding.cm1Input)
            updateDisplay(wavelengthNm)
        } else {
            clearDisplay()
        }
    }

    private fun updateFromEv() {
        val value = binding.evInput.text?.toString()?.toDoubleOrNull()
        if (value != null && value > 0) {
            val wavelengthNm = 1239.84193 / value
            updateAllFields(wavelengthNm, exceptField = binding.evInput)
            updateDisplay(wavelengthNm)
        } else {
            clearDisplay()
        }
    }

    private fun updateFromKjmol() {
        val value = binding.kjmolInput.text?.toString()?.toDoubleOrNull()
        if (value != null && value > 0) {
            val wavelengthNm = 119626.565 / value
            updateAllFields(wavelengthNm, exceptField = binding.kjmolInput)
            updateDisplay(wavelengthNm)
        } else {
            clearDisplay()
        }
    }

    private fun updateAllFields(wavelengthNm: Double, exceptField: EditText) {
        isUpdating = true

        if (binding.nmInput != exceptField) {
            binding.nmInput.setText(String.format("%.3f", wavelengthNm))
        }

        if (binding.ghzInput != exceptField) {
            val frequencyGHz = 299792458.0 / wavelengthNm
            binding.ghzInput.setText(String.format("%.3f", frequencyGHz))
        }

        if (binding.cm1Input != exceptField) {
            val wavenumber = 10000000.0 / wavelengthNm
            binding.cm1Input.setText(String.format("%.1f", wavenumber))
        }

        if (binding.evInput != exceptField) {
            val energyEV = 1239.84193 / wavelengthNm
            binding.evInput.setText(String.format("%.3f", energyEV))
        }

        if (binding.kjmolInput != exceptField) {
            val energyEV = 1239.84193 / wavelengthNm
            val energyKJ = energyEV * 96.485
            binding.kjmolInput.setText(String.format("%.1f", energyKJ))
        }

        isUpdating = false
    }

    private fun updateDisplay(wavelengthNm: Double) {
        displayColorBar(wavelengthNm)
        displayRelatedWavelengths(wavelengthNm)
    }

    private fun displayColorBar(wavelengthNm: Double) {
        val color = wavelengthToRGB(wavelengthNm)
        binding.colorDisplay.setBackgroundColor(color)
    }

    private fun wavelengthToRGB(wavelength: Double): Int {
        val wl = wavelength
        
        var r: Double
        var g: Double
        var b: Double
        
        when {
            wl < 380 -> {
                r = 0.5; g = 0.0; b = 1.0
            }
            wl < 440 -> {
                r = (440 - wl) / (440 - 380)
                g = 0.0
                b = 1.0
            }
            wl < 490 -> {
                r = 0.0
                g = (wl - 440) / (490 - 440)
                b = 1.0
            }
            wl < 510 -> {
                r = 0.0
                g = 1.0
                b = (510 - wl) / (510 - 490)
            }
            wl < 580 -> {
                r = (wl - 510) / (580 - 510)
                g = 1.0
                b = 0.0
            }
            wl < 645 -> {
                r = 1.0
                g = (645 - wl) / (645 - 580)
                b = 0.0
            }
            wl <= 780 -> {
                r = 1.0
                g = 0.0
                b = 0.0
            }
            else -> {
                r = 0.5; g = 0.0; b = 0.0
            }
        }
        
        val factor: Double = when {
            wl < 380 -> 0.3
            wl < 420 -> 0.3 + 0.7 * (wl - 380) / (420 - 380)
            wl < 700 -> 1.0
            wl < 780 -> 0.3 + 0.7 * (780 - wl) / (780 - 700)
            else -> 0.3
        }
        
        r *= factor
        g *= factor
        b *= factor
        
        val gamma = 0.8
        r = r.pow(gamma)
        g = g.pow(gamma)
        b = b.pow(gamma)
        
        return Color.rgb(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255)
        )
    }

    private fun displayRelatedWavelengths(wavelengthNm: Double) {
        // Find entries within ±1.1nm range (middle section)
        val inRangeEntries = wavelengthDatabase.filter { entry ->
            wavelengthNm >= entry.minWl - 1.1 && wavelengthNm <= entry.maxWl + 1.1
        }.sortedBy { it.minWl }
        
        // Find previous wavelength (top section - closest BELOW the range)
        val previousEntry = wavelengthDatabase
            .filter { entry -> entry.maxWl < wavelengthNm - 1.1 }
            .maxByOrNull { entry -> entry.maxWl }
        
        // Find next wavelength (bottom section - closest ABOVE the range)
        val nextEntry = wavelengthDatabase
            .filter { entry -> entry.minWl > wavelengthNm + 1.1 }
            .minByOrNull { entry -> entry.minWl }
        
        // Build set of currently highlighted entries for haptic feedback
        val currentWavelengthSet = inRangeEntries.map { 
            "${it.minWl}-${it.maxWl}-${it.description}" 
        }.toSet()
        
        // Trigger haptic if the set changed
        if (currentWavelengthSet != previousWavelengthSet) {
            if (!isFirstWavelengthDisplay) {
                triggerHapticFeedback()
            }
            isFirstWavelengthDisplay = false
        }
        previousWavelengthSet = currentWavelengthSet
        
        // PREVIOUS (top, fixed, one line, dimmed)
        val previousText = SpannableStringBuilder()
        if (previousEntry != null) {
            appendWavelengthEntry(previousText, previousEntry)
            previousText.setSpan(
                android.text.style.ForegroundColorSpan(0x66FFFFFF.toInt()),
                0, previousText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            previousText.setSpan(
                android.text.style.RelativeSizeSpan(0.9f),
                0, previousText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.previousText.text = previousText
        binding.previousText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        
        // CURRENT (middle, scrollable, bright, bold)
        val currentText = SpannableStringBuilder()
        inRangeEntries.forEach { entry ->
            val start = currentText.length
            appendWavelengthEntry(currentText, entry)
            currentText.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, currentText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            currentText.append("\n\n")
        }
        binding.currentText.text = currentText
        // Don't set any touch listener here - let ScrollView handle everything
        
        // NEXT (bottom, fixed, one line, dimmed)
        val nextText = SpannableStringBuilder()
        if (nextEntry != null) {
            appendWavelengthEntry(nextText, nextEntry)
            nextText.setSpan(
                android.text.style.ForegroundColorSpan(0x66FFFFFF.toInt()),
                0, nextText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            nextText.setSpan(
                android.text.style.RelativeSizeSpan(0.9f),
                0, nextText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.nextText.text = nextText
        binding.nextText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        
        // Setup scroll-to-change-wavelength (±1nm when at boundaries)
        setupScrollWavelengthChange()
    }
    
    private fun setupScrollWavelengthChange() {
        var touchDownTime = 0L
        var touchDownX = 0f
        var touchDownY = 0f
        
        binding.wavelengthScrollView.setOnTouchListener { view, event ->
            val scrollView = binding.wavelengthScrollView
            val child = binding.currentText
            
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownTime = System.currentTimeMillis()
                    touchDownX = event.rawY
                    touchDownY = event.rawY
                    lastTouchY = event.rawY
                    overscrollAccumulator = 0f
                }
                android.view.MotionEvent.ACTION_UP -> {
                    // Check if this was a quick tap (not a scroll)
                    val touchDuration = System.currentTimeMillis() - touchDownTime
                    val deltaX = kotlin.math.abs(event.rawY - touchDownX)
                    val deltaY = kotlin.math.abs(event.rawY - touchDownY)
                    
                    if (touchDuration < 200 && deltaX < 10 && deltaY < 10) {
                        // This was a tap - check if it hit a wavelength span
                        val text = child.text as? android.text.Spanned ?: return@setOnTouchListener false
                        
                        // Convert event coordinates to child TextView coordinates
                        val scrollViewLocation = IntArray(2)
                        val childLocation = IntArray(2)
                        scrollView.getLocationOnScreen(scrollViewLocation)
                        child.getLocationOnScreen(childLocation)
                        
                        var x = (event.rawX - childLocation[0]).toInt()
                        var y = (event.rawY - childLocation[1]).toInt()
                        
                        x -= child.totalPaddingLeft
                        y -= child.totalPaddingTop
                        x += child.scrollX
                        y += child.scrollY
                        
                        val layout = child.layout
                        if (layout != null && y >= 0 && y < layout.height) {
                            val line = layout.getLineForVertical(y)
                            val off = layout.getOffsetForHorizontal(line, x.toFloat())
                            
                            val links = text.getSpans(off, off, android.text.style.ClickableSpan::class.java)
                            if (links.isNotEmpty()) {
                                links[0].onClick(child)
                                return@setOnTouchListener true
                            }
                        }
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - lastTouchY
                    
                    // Check if content is scrollable
                    val canScroll = child.measuredHeight > scrollView.measuredHeight
                    
                    if (canScroll) {
                        // Scrollable content - only trigger at boundaries
                        val scrollY = scrollView.scrollY
                        val atTop = scrollY <= 0
                        val atBottom = scrollY + scrollView.height >= child.height - 10
                        
                        if ((atTop && deltaY > 0) || (atBottom && deltaY < 0)) {
                            // At boundary and trying to go beyond
                            overscrollAccumulator += deltaY
                            
                            if (kotlin.math.abs(overscrollAccumulator) > 50f) {
                                val currentNm = binding.nmInput.text?.toString()?.toDoubleOrNull() ?: 0.0
                                if (currentNm > 0) {
                                    val newNm = if (overscrollAccumulator > 0) {
                                        (currentNm - 1.0).coerceAtLeast(1.0)
                                    } else {
                                        currentNm + 1.0
                                    }
                                    binding.nmInput.setText(String.format("%.3f", newNm))
                                }
                                overscrollAccumulator = 0f
                            }
                        } else {
                            overscrollAccumulator = 0f
                        }
                    } else {
                        // Not scrollable (empty or 1-3 entries) - any drag works
                        overscrollAccumulator += deltaY
                        
                        if (kotlin.math.abs(overscrollAccumulator) > 50f) {
                            val currentNm = binding.nmInput.text?.toString()?.toDoubleOrNull() ?: 0.0
                            if (currentNm > 0) {
                                val newNm = if (overscrollAccumulator > 0) {
                                    (currentNm - 1.0).coerceAtLeast(1.0)
                                } else {
                                    currentNm + 1.0
                                }
                                binding.nmInput.setText(String.format("%.3f", newNm))
                            }
                            overscrollAccumulator = 0f
                        }
                    }
                    
                    lastTouchY = event.rawY
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    overscrollAccumulator = 0f
                }
            }
            false // Always let ScrollView handle the events
        }
    }
    
    private fun appendWavelengthEntry(resultText: SpannableStringBuilder, entry: WavelengthEntry) {
        // Add icon
        resultText.append("${entry.icon} ")
        
        // Add wavelength range
        val wavelengthStart = resultText.length
        if (entry.minWl == entry.maxWl) {
            // Single wavelength
            val wlStr = if (entry.minWl == entry.minWl.toInt().toDouble()) {
                "%.0f".format(entry.minWl)
            } else {
                entry.minWl.toString().trimEnd('0').trimEnd('.')
            }
            resultText.append("$wlStr nm:")
            val wavelengthEnd = resultText.length - 4
            
            // Make clickable
            resultText.setSpan(
                object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: android.view.View) {
                        binding.nmInput.setText(entry.minWl.toString())
                        hideKeyboard()
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = android.graphics.Color.WHITE
                        ds.isUnderlineText = false
                    }
                },
                wavelengthStart,
                wavelengthEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            // Range
            val minStr = if (entry.minWl == entry.minWl.toInt().toDouble()) {
                "%.0f".format(entry.minWl)
            } else {
                entry.minWl.toString().trimEnd('0').trimEnd('.')
            }
            val maxStr = if (entry.maxWl == entry.maxWl.toInt().toDouble()) {
                "%.0f".format(entry.maxWl)
            } else {
                entry.maxWl.toString().trimEnd('0').trimEnd('.')
            }
            resultText.append("$minStr-$maxStr nm:")
        }
        
        resultText.append(" ")
        
        // Description with HTML
        var desc = entry.description
        desc = desc.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
        desc = desc.replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
        val htmlSpannable = android.text.Html.fromHtml(desc, android.text.Html.FROM_HTML_MODE_COMPACT)
        
        if (htmlSpannable is android.text.Spanned) {
            val descStart = resultText.length
            resultText.append(htmlSpannable)
            
            val subSpans = htmlSpannable.getSpans(0, htmlSpannable.length, android.text.style.SubscriptSpan::class.java)
            for (span in subSpans) {
                val start = htmlSpannable.getSpanStart(span)
                val end = htmlSpannable.getSpanEnd(span)
                resultText.setSpan(
                    ProperSubscriptSpan(),
                    descStart + start,
                    descStart + end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            resultText.append(htmlSpannable)
        }
    }

    private fun triggerHapticFeedback() {
        val now = System.currentTimeMillis()
        // Debounce: minimum 150ms between haptic feedbacks
        if (now - lastHapticTime < 150) {
            return
        }
        lastHapticTime = now
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }

    private fun clearDisplay() {
        binding.colorDisplay.setBackgroundColor(Color.TRANSPARENT)
        binding.previousText.text = ""
        binding.currentText.text = ""
        binding.nextText.text = ""
    }
}
