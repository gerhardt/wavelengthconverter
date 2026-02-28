package ch.gerhardt.wavelength

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    
    companion object {
        // Splash duration in milliseconds
        // Set to 0 to disable splash screen
        private const val SPLASH_DURATION = 2000L  // 2 seconds
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set the splash layout
        setContentView(R.layout.activity_splash)
        
        // Navigate to main activity after delay
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_DURATION)
    }
}
