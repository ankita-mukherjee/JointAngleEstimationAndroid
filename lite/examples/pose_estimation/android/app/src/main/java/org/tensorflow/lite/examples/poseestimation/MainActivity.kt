package org.tensorflow.lite.examples.poseestimation

import android.os.Bundle
import android.view.MenuInflater
import android.view.MenuItem
import android.view.SurfaceView
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.tensorflow.lite.examples.poseestimation.camera.CameraSource
import kotlin.properties.Delegates


class MainActivity : AppCompatActivity() {

    private val firstFragment = FirstFragment()
    private lateinit var surfaceView: SurfaceView

    // Elbow_Flexion
    private var selectedMovementId = R.id.elbow_flexion

    private var cameraSource: CameraSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setCurrentFragment(firstFragment)

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        bottomNavigationView.setOnNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.action_home -> setCurrentFragment(firstFragment)
                R.id.action_activities -> showPopupMenu(bottomNavigationView)
            }
            true
        }
    }

    private fun showPopupMenu(view: View) {
        val popupMenu = PopupMenu(this, view)
        val inflater: MenuInflater = popupMenu.menuInflater
        inflater.inflate(R.menu.popup_menu, popupMenu.menu)

        // Set checked state of previously selected menu item
        popupMenu.menu.findItem(selectedMovementId).isChecked = true;

        // Set up menu item click listener
        popupMenu.setOnMenuItemClickListener(object : PopupMenu.OnMenuItemClickListener {
            override fun onMenuItemClick(item: MenuItem): Boolean {
                // Handle menu item clicks here
                when (item.itemId) {
                    R.id.elbow_flexion -> {
                        selectedMovementId = R.id.elbow_flexion
                        return true
                    }

                    R.id.shoulder_abduction -> {
                        selectedMovementId = R.id.shoulder_abduction
                        return true
                    }

                    R.id.shoulder_extension -> {
                        selectedMovementId = R.id.shoulder_extension
                        return true
                    }

                    R.id.shoulder_flexion -> {
                        selectedMovementId = R.id.shoulder_flexion
                        return true
                    }
                }
                return false
            }
        })

        // Show the popup menu
        popupMenu.show()
    }

    private fun setCurrentFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.flFragment, fragment)
            .commit()
    }
}